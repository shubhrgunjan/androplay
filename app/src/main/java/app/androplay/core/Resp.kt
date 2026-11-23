package app.androplay.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.math.min

/*
 * Minimal RESP2 client over TCP/TLS. AndroPlay only needs a handful of commands, and a ~200
 * line client keeps the APK light and avoids JVM-server dependencies (pools, JMX) that
 * don't belong on Android.
 */

class RedisError(message: String) : IOException(message)

/** A transport change never changes the Redis host or the logical database. */
class RedisRoute(val primary: RedisUrl, restToken: String) {
    init {
        require(primary.tls) { "Redis requires TLS" }
        require(restToken.isBlank() || RedisRest.supports(primary)) { "Redis HTTPS fallback requires an Upstash endpoint" }
    }

    val rest = restToken.takeIf { it.isNotBlank() }?.let { RedisRest(primary.host, it) }
    @Volatile private var fallbackUntil = 0L
    private val _httpsFallback = MutableStateFlow(false)
    val httpsFallback = _httpsFallback.asStateFlow()

    val useHttps: Boolean
        get() = rest != null && System.nanoTime() < fallbackUntil

    @Synchronized
    fun nativeUnavailable(error: IOException): Boolean {
        if (rest == null || error is SSLException) return false
        if (error is RedisError && !error.message.orEmpty().contains("max concurrent connections", ignoreCase = true)) {
            return false
        }
        fallbackUntil = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2)
        _httpsFallback.value = true
        return true
    }

    @Synchronized
    fun nativeReady() {
        if (!useHttps) _httpsFallback.value = false
    }
}

data class RedisUrl(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val user: String?,
    val password: String?,
    val db: Int,
) {
    companion object {
        /**
         * Pulls a Redis URL out of whatever was pasted (e.g. a whole `redis-cli --tls -u …` line)
         * and upgrades it to TLS when the source asked for it or the host requires it (Upstash).
         */
        fun normalize(raw: String): String {
            val url = Regex("rediss?://\\S+").find(raw)?.value ?: raw.trim()
            val wantsTls = "--tls" in raw || ".upstash.io" in url
            return if (wantsTls && url.startsWith("redis://")) "rediss://" + url.removePrefix("redis://") else url
        }

        /** `redis://` or `rediss://[user[:password]@]host[:port][/db]` */
        fun parse(url: String): RedisUrl {
            val u = URI(normalize(url))
            val tls = u.scheme == "rediss"
            require(tls || u.scheme == "redis") { "Redis URL must start with redis:// or rediss://" }
            val info = u.rawUserInfo?.split(':', limit = 2)?.map { URLDecoder.decode(it, "UTF-8") }
            val (user, password) = when {
                info == null -> null to null
                info.size == 2 -> info[0].ifEmpty { null } to info[1]
                else -> null to info[0]
            }
            return RedisUrl(
                host = requireNotNull(u.host) { "Redis URL has no host" },
                port = if (u.port > 0) u.port else 6379,
                tls = tls,
                user = user,
                password = password,
                db = u.path?.trim('/')?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** One socket. [call] is thread-safe; [send]/[read] are for the subscriber's own loop. */
class RedisConnection(private val url: RedisUrl, readTimeoutMs: Int = 15_000) : Closeable {
    private val socket: Socket = open()
    private val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
    private val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)

    init {
        socket.soTimeout = readTimeoutMs
        try {
            url.password?.let { if (url.user != null) call("AUTH", url.user, it) else call("AUTH", it) }
            if (url.db != 0) call("SELECT", url.db.toString())
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun open(): Socket {
        val plain = Socket()
        plain.connect(InetSocketAddress(url.host, url.port), 8_000)
        plain.tcpNoDelay = true
        plain.keepAlive = true
        if (!url.tls) return plain
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, url.host, url.port, true) as SSLSocket
        try {
            ssl.soTimeout = 8_000
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, ssl.session)) {
                throw SSLException("Redis TLS certificate does not match ${url.host}")
            }
            return ssl
        } catch (e: Exception) {
            ssl.close()
            throw e
        }
    }

    @Synchronized
    fun call(vararg args: String): Any? {
        send(args)
        return read()
    }

    /** Sends all commands before reading any reply: one round trip for many commands. */
    @Synchronized
    fun pipeline(commands: List<Array<String>>): List<Any?> {
        if (commands.isEmpty()) return emptyList()
        commands.forEach(::write)
        output.flush()
        return commands.map {
            try {
                read()
            } catch (e: RedisError) {
                e
            }
        }
    }

    fun send(args: Array<out String>) {
        synchronized(output) {
            write(args)
            output.flush()
        }
    }

    fun read(): Any? {
        val type = input.read()
        if (type == -1) throw EOFException("Redis closed the connection")
        val line = readLine()
        return when (type.toChar()) {
            '+' -> line
            '-' -> throw RedisError(line)
            ':' -> line.toLong()
            '$' -> {
                val n = line.toInt()
                if (n < 0) {
                    null
                } else {
                    val bytes = ByteArray(n)
                    var off = 0
                    while (off < n) {
                        val r = input.read(bytes, off, n - off)
                        if (r < 0) throw EOFException()
                        off += r
                    }
                    input.read()
                    input.read() // CRLF
                    bytes.decodeToString()
                }
            }
            '*' -> {
                val n = line.toInt()
                if (n < 0) null else List(n) {
                    try {
                        read()
                    } catch (e: RedisError) {
                        e
                    }
                }
            }
            else -> throw IOException("Unexpected RESP type '${type.toChar()}'")
        }
    }

    private fun write(args: Array<out String>) {
        output.write("*${args.size}\r\n".toByteArray())
        for (a in args) {
            val b = a.toByteArray()
            output.write("\$${b.size}\r\n".toByteArray())
            output.write(b)
            output.write(CRLF)
        }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) throw EOFException()
            if (c == '\r'.code) {
                input.read() // \n
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

/** Native Redis first, with optional HTTPS transport to the same database. */
class Redis(private val route: RedisRoute, private val readTimeoutMs: Int = 15_000) : Closeable {
    private var conn: RedisConnection? = null
    val usingHttps: Boolean get() = route.useHttps

    fun call(vararg args: String): Any? = execute(
        replayable = args.firstOrNull() in READ_ONLY,
        tcp = { it.call(*args) },
        https = { it.call(args) },
    )

    fun callOnce(vararg args: String): Any? = execute(
        replayable = false,
        tcp = { it.call(*args) },
        https = { it.call(args) },
    )

    fun readStream(key: String, after: String, blockMs: Long): Any? = execute(
        replayable = true,
        tcp = { it.call("XREAD", "COUNT", "200", "BLOCK", blockMs.toString(), "STREAMS", key, after) },
        https = { it.call(arrayOf("XREAD", "COUNT", "200", "STREAMS", key, after)) },
    )

    fun transaction(commands: List<Array<String>>) {
        execute(replayable = false, tcp = { connection ->
            val replies = connection.pipeline(listOf(arrayOf("MULTI")) + commands + listOf(arrayOf("EXEC")))
            replies.filterIsInstance<RedisError>().firstOrNull()?.let { throw it }
            val results = replies.lastOrNull() as? List<*> ?: throw IOException("Redis transaction was not committed")
            if (results.size != commands.size) throw IOException("Redis returned incomplete transaction results")
            results.filterIsInstance<RedisError>().firstOrNull()?.let { throw it }
        }, https = { it.batch(commands, atomic = true); Unit })
    }

    fun pipeline(commands: List<Array<String>>): List<Any?> = execute(
        replayable = commands.all { it.firstOrNull() in READ_ONLY },
        tcp = { connection ->
            connection.pipeline(commands).also { replies ->
                replies.filterIsInstance<RedisError>().firstOrNull()?.let { throw it }
            }
        },
        https = { it.batch(commands, atomic = false) },
    )

    @Synchronized
    private fun <T> execute(
        replayable: Boolean,
        tcp: (RedisConnection) -> T,
        https: (RedisRest) -> T,
    ): T {
        if (route.useHttps) {
            close()
            return https(checkNotNull(route.rest))
        }
        val connection = try {
            conn ?: RedisConnection(route.primary, readTimeoutMs).also { conn = it }
        } catch (e: IOException) {
            if (route.nativeUnavailable(e)) return https(checkNotNull(route.rest))
            throw e
        }
        try {
            return tcp(connection).also { route.nativeReady() }
        } catch (e: IOException) {
            close()
            val fallback = route.nativeUnavailable(e)
            // A missing response cannot distinguish an unsent mutation from an applied one.
            if (fallback && replayable) return https(checkNotNull(route.rest))
            throw e
        }
    }

    @Synchronized
    override fun close() {
        conn?.close()
        conn = null
    }

    private companion object {
        val READ_ONLY = setOf("PING", "GET", "HGETALL", "EXISTS", "SMEMBERS", "ZREVRANGE", "XREVRANGE")
    }
}

/** Long-lived SUBSCRIBE on its own socket and thread, reconnecting with backoff. */
class RedisSubscriber(
    private val route: RedisRoute,
    private val channels: List<String>,
    private val onMessage: (channel: String, payload: String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var conn: RedisConnection? = null
    private var worker: Thread? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "redis-sub", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        conn?.close()
        restCall?.cancel()
        worker?.interrupt()
    }

    @Volatile private var restCall: okhttp3.Call? = null

    private fun loop() {
        var backoff = 500L
        while (running) {
            val useHttps = route.useHttps
            try {
                if (useHttps) subscribeHttps(checkNotNull(route.rest))
                else subscribeNative()
                backoff = 500L
            } catch (e: IOException) {
                if (!useHttps) route.nativeUnavailable(e)
                if (running && useHttps == route.useHttps) {
                    try {
                        Thread.sleep(backoff)
                    } catch (_: InterruptedException) {
                    }
                    backoff = min(backoff * 2, 10_000)
                }
            } finally {
                _connected.value = false
                conn?.close()
                conn = null
                restCall?.cancel()
                restCall = null
            }
        }
    }

    private fun subscribeNative() {
        val c = RedisConnection(route.primary, readTimeoutMs = 45_000)
        conn = c
        if (!running) return
        c.send(arrayOf("SUBSCRIBE", *channels.toTypedArray()))
        repeat(channels.size) {
            val ack = c.read() as? List<*>
            if (ack?.firstOrNull() != "subscribe") throw IOException("Redis subscription was not acknowledged")
        }
        _connected.value = true
        route.nativeReady()
        val ping = thread(name = "redis-sub-ping", isDaemon = true) {
            try {
                while (running && conn === c) {
                    Thread.sleep(20_000)
                    c.send(arrayOf("PING"))
                }
            } catch (_: Exception) {
            }
        }
        try {
            while (running && !route.useHttps) {
                val message = c.read() as? List<*> ?: continue
                if (message.firstOrNull() == "message") {
                    deliver(message.getOrNull(1) as? String, message.getOrNull(2) as? String)
                }
            }
        } finally {
            ping.interrupt()
        }
    }

    private fun subscribeHttps(rest: RedisRest) {
        val call = rest.subscription(channels)
        restCall = call
        if (!running) return
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Redis HTTPS subscription failed (HTTP ${response.code})")
            val source = response.body?.source() ?: throw IOException("Empty Redis HTTPS subscription")
            while (running && route.useHttps) {
                val line = source.readUtf8Line() ?: throw EOFException("Redis HTTPS subscription closed")
                if (!line.startsWith("data:")) continue
                val fields = line.removePrefix("data:").trimStart().split(',', limit = 3)
                when (fields.firstOrNull()) {
                    "subscribe" -> _connected.value = true
                    "message" -> deliver(fields.getOrNull(1), fields.getOrNull(2))
                }
            }
        }
    }

    private fun deliver(channel: String?, payload: String?) {
        if (channel in channels && channel != null && payload != null) runCatching { onMessage(channel, payload) }
    }
}
