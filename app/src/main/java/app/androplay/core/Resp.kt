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

class RedisError(message: String) : IOException(message) {
    /** The database refuses all work (quota used up, bad credentials): another one may not. */
    val unusable: Boolean
        get() = message.orEmpty().let { m ->
            listOf("max requests limit", "max daily request limit", "NOAUTH", "WRONGPASS", "invalid password")
                .any { m.contains(it, ignoreCase = true) }
        }
}

/**
 * The primary Redis plus fallbacks, shared by every connection in the process so they all
 * agree on one database. [failover] moves to the next one; the primary is retried after
 * [RETRY_PRIMARY_MS].
 */
class RedisRoute private constructor(val urls: List<RedisUrl>) {
    @Volatile private var index = 0
    @Volatile private var failedAt = 0L

    val current: RedisUrl
        get() {
            if (index != 0 && System.currentTimeMillis() - failedAt > RETRY_PRIMARY_MS) index = 0
            return urls[index]
        }

    /** Moves on from [from] if it is still the active database. */
    @Synchronized
    fun failover(from: RedisUrl) {
        if (urls.size < 2 || urls[index] != from) return
        index = (index + 1) % urls.size
        failedAt = System.currentTimeMillis()
    }

    companion object {
        private const val RETRY_PRIMARY_MS = 10 * 60_000L
        private val shared = HashMap<List<RedisUrl>, RedisRoute>()

        fun of(urls: List<String>): RedisRoute {
            val parsed = urls.filter { it.isNotBlank() }.map(RedisUrl::parse).distinct()
            require(parsed.isNotEmpty()) { "No Redis URL configured" }
            return synchronized(shared) { shared.getOrPut(parsed) { RedisRoute(parsed) } }
        }
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
        url.password?.let { if (url.user != null) call("AUTH", url.user, it) else call("AUTH", it) }
        if (url.db != 0) call("SELECT", url.db.toString())
    }

    private fun open(): Socket {
        val plain = Socket()
        plain.connect(InetSocketAddress(url.host, url.port), 8_000)
        plain.tcpNoDelay = true
        plain.keepAlive = true
        if (!url.tls) return plain
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, url.host, url.port, true) as SSLSocket
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, ssl.session)) {
            ssl.close()
            throw SSLException("Redis TLS certificate does not match ${url.host}")
        }
        return ssl
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
                if (n < 0) null else List(n) { read() }
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

/**
 * A lazily (re)connecting client. Network errors are retried (moving to a fallback database
 * after the first), as are errors meaning the database refuses everything. Other Redis errors
 * are thrown as is.
 */
class Redis(private val route: RedisRoute, private val readTimeoutMs: Int = 15_000) : Closeable {
    private var conn: RedisConnection? = null
    private var connUrl: RedisUrl? = null

    @Synchronized
    fun <T> use(block: (RedisConnection) -> T): T {
        var last: IOException? = null
        repeat(route.urls.size + 1) { attempt ->
            val url = route.current
            if (connUrl != url) close()
            try {
                val c = conn ?: RedisConnection(url, readTimeoutMs).also { conn = it; connUrl = url }
                return block(c)
            } catch (e: RedisError) {
                if (!e.unusable) throw e
                close()
                route.failover(url)
                last = e
            } catch (e: IOException) {
                close()
                if (attempt > 0) route.failover(url)
                last = e
            }
        }
        throw last!!
    }

    fun call(vararg args: String): Any? = use { it.call(*args) }

    fun pipeline(commands: List<Array<String>>): List<Any?> = use { c ->
        c.pipeline(commands).also { replies ->
            replies.firstOrNull { it is RedisError && it.unusable }?.let { throw it as RedisError }
        }
    }

    @Synchronized
    override fun close() {
        conn?.close()
        conn = null
        connUrl = null
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
        worker?.interrupt()
    }

    private fun loop() {
        var backoff = 500L
        while (running) {
            val url = route.current
            try {
                val c = RedisConnection(url, readTimeoutMs = 45_000)
                conn = c
                c.send(arrayOf("SUBSCRIBE", *channels.toTypedArray()))
                _connected.value = true
                backoff = 500L
                // Keep NATs and cloud proxies from dropping an idle socket.
                thread(name = "redis-sub-ping", isDaemon = true) {
                    try {
                        while (running && conn === c) {
                            Thread.sleep(20_000)
                            c.send(arrayOf("PING"))
                        }
                    } catch (_: Exception) {
                    }
                }
                while (running && route.current == url) {
                    val msg = c.read() as? List<*> ?: continue
                    if (msg.getOrNull(0) == "message") {
                        runCatching { onMessage(msg[1] as String, msg[2] as? String ?: "") }
                    }
                }
            } catch (e: Exception) {
                _connected.value = false
                conn?.close()
                if (e is RedisError && e.unusable) route.failover(url)
                if (running) {
                    try {
                        Thread.sleep(backoff)
                    } catch (_: InterruptedException) {
                    }
                    backoff = min(backoff * 2, 10_000)
                }
            }
        }
        _connected.value = false
    }
}
