package app.androplay.board

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import app.androplay.AndroPlayApp
import app.androplay.core.Content
import app.androplay.protocol.ContentType
import app.androplay.protocol.Fingerprint
import app.androplay.protocol.Item
import app.androplay.protocol.MimeTypes
import app.androplay.protocol.Net
import app.androplay.protocol.Origin
import app.androplay.protocol.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local Wi-Fi fallback: the board listens on [Net.PORT] so a phone on the same network
 * (campus Wi-Fi or the phone's hotspot) can push videos/images when cloud storage is down.
 */
class LanShareServer(private val app: AndroPlayApp) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var accept: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    private val _endpoint = MutableStateFlow<String?>(null)
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Net.PORT))
            server = ss
            accept = Thread({
                while (running.get()) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: continue
                    pool.execute { handle(socket) }
                }
            }, "androplay-lan").also { it.isDaemon = true; it.start() }
            pool.execute {
                while (running.get()) {
                    refreshEndpoint()
                    Thread.sleep(5_000)
                }
            }
            advertise()
            refreshEndpoint()
        } catch (e: Exception) {
            Log.w(TAG, "LAN share listen failed", e)
            running.set(false)
            _endpoint.value = null
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        accept = null
        unadvertise()
        _endpoint.value = null
    }

    fun refreshEndpoint() {
        val ip = NetUtil.localIp(app)
        _endpoint.value = ip?.let { "$it:${Net.PORT}" }
    }

    private fun advertise() {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicast = wifi?.createMulticastLock("androplay-lan")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val manager = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "ap-${app.settings.deviceId}"
            serviceType = Net.NSD_TYPE
            port = Net.PORT
            setAttribute("id", app.settings.deviceId)
            setAttribute("name", app.settings.deviceName.take(40))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        nsdListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unadvertise() {
        val manager = nsd
        val listener = nsdListener
        if (manager != null && listener != null) runCatching { manager.unregisterService(listener) }
        nsd = null
        nsdListener = null
        multicast?.release()
        multicast = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val headers = readHeaders(input) ?: return reply(output, 400, "Bad request")
            val token = headers.fields[Net.TOKEN_HEADER.lowercase()]
            if (token.isNullOrBlank() || token != app.settings.bleToken) {
                return reply(output, 401, "Unauthorized")
            }
            when {
                headers.method == "GET" && headers.path.substringBefore('?') == "/lan" -> {
                    refreshEndpoint()
                    val body = """{"ok":true,"id":"${app.settings.deviceId}","name":${jsonStr(app.settings.deviceName)},"ip":${jsonStr(_endpoint.value.orEmpty())}}"""
                    reply(output, 200, body, "application/json")
                }
                headers.method == "GET" && headers.path.substringBefore('?').startsWith("/lan/thumb/") -> {
                    val id = headers.path.substringBefore('?').removePrefix("/lan/thumb/").substringBefore('/')
                    val item = app.store.item(id) ?: return reply(output, 404, "Unknown item")
                    val file = app.thumbnails.file(item) ?: return reply(output, 404, "No thumbnail")
                    replyFile(output, file, "image/jpeg")
                }
                headers.method == "PUT" && headers.path.substringBefore('?') == "/lan" -> {
                    val name = query(headers.path)["name"]?.trim().orEmpty().ifEmpty { "file" }
                    val type = ContentType.of(name, headers.fields["content-type"])
                    if (type != ContentType.IMAGE && type != ContentType.VIDEO) {
                        return reply(output, 415, "Only videos and images")
                    }
                    val length = headers.fields["content-length"]?.toLongOrNull()
                        ?: return reply(output, 411, "Content-Length required")
                    if (length <= 0L) return reply(output, 400, "Empty file")
                    val tmp = File(app.store.incoming, "lan-${System.nanoTime()}")
                    try {
                        tmp.outputStream().use { out ->
                            var left = length
                            val buf = ByteArray(256 * 1024)
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw IOException("Connection closed early")
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        val id = ingest(tmp, name)
                        reply(output, 200, """{"ok":true,"id":"$id"}""", "application/json")
                    } catch (e: Exception) {
                        tmp.delete()
                        Log.w(TAG, "LAN receive failed", e)
                        reply(output, 500, e.message ?: "Receive failed")
                    }
                }
                else -> reply(output, 404, "Not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LAN client failed", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun ingest(tmp: File, rawName: String): String {
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "file" }
        val dest = app.store.destinationFor(name)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val id = Fingerprint.of(dest)
        app.store.item(id)?.let {
            if (dest.absolutePath != app.store.fileOf(id)?.absolutePath) dest.delete()
            return it.id
        }
        val type = ContentType.of(name)
        val (duration, pages) = app.store.probe(dest, type)
        val handle = app.sync?.newLocalHandle() ?: handleFor(id)
        val item = Item(
            id = id,
            handle = handle,
            name = name,
            type = type,
            mime = MimeTypes.of(name),
            size = dest.length(),
            durationMs = duration,
            pageCount = pages,
            addedAt = System.currentTimeMillis(),
            origin = Origin.BOARD,
        )
        app.store.put(item, dest)
        app.sync?.announceLocal(item)
        return id
    }

    private data class Headers(val method: String, val path: String, val fields: Map<String, String>)

    private fun readHeaders(input: BufferedInputStream): Headers? {
        val raw = ByteArray(16 * 1024)
        var n = 0
        while (n < raw.size) {
            val b = input.read()
            if (b < 0) return null
            raw[n++] = b.toByte()
            if (n >= 4 && raw[n - 4] == CR && raw[n - 3] == LF && raw[n - 2] == CR && raw[n - 1] == LF) break
        }
        val text = raw.copyOf(n).decodeToString()
        val lines = text.split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val fields = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) fields[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return Headers(first[0].uppercase(), first[1], fields)
    }

    private fun query(path: String): Map<String, String> {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(part.substring(0, i), Charsets.UTF_8.name()) to
                URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8.name())
        }.toMap()
    }

    private fun reply(out: BufferedOutputStream, code: Int, body: String, type: String = "text/plain") {
        val bytes = body.encodeToByteArray()
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            411 -> "Length Required"
            415 -> "Unsupported Media Type"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.encodeToByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun replyFile(out: BufferedOutputStream, file: File, type: String) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n"
        out.write(head.encodeToByteArray())
        file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun jsonStr(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
        append('"')
    }

    companion object {
        private const val TAG = "LanShare"
        private const val CR: Byte = 13
        private const val LF: Byte = 10

        fun handleFor(id: String): Int {
            var h = 0
            for (c in id) h = 31 * h + c.code
            val n = (h and 0x7fff)
            return if (n == 0 || n == PlaybackState.NO_HANDLE) 1 else n
        }
    }
}
