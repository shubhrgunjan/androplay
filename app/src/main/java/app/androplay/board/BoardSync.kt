package app.androplay.board

import android.util.Log
import app.androplay.AndroPlayApp
import app.androplay.BuildConfig
import app.androplay.core.Backend
import app.androplay.core.BoardInfo
import app.androplay.core.Content
import app.androplay.core.RedisSubscriber
import app.androplay.core.S3
import app.androplay.core.unb64
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.Fingerprint
import app.androplay.protocol.MimeTypes
import app.androplay.protocol.Origin
import app.androplay.protocol.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import kotlin.math.abs
import kotlin.math.min

data class SyncStatus(
    val connected: Boolean = false,
    val error: String? = null,
    val downloading: String? = null,
    val downloadProgress: Float = 0f,
    val pending: Int = 0,
)

/**
 * Board-side sync engine:
 *  - heartbeat so admins see the board online,
 *  - Redis Stream consumer that turns "content added/removed" events into downloads/deletes,
 *  - full reconcile on start and every few minutes (covers missed events),
 *  - resumable, verified downloads from object storage,
 *  - pub/sub command intake and state publishing.
 */
class BoardSync(
    private val app: AndroPlayApp,
    private val backend: Backend,
    private val s3: S3,
    private val store: LocalStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val me = app.settings.deviceId
    private val downloads = Channel<Content>(Channel.UNLIMITED)
    private val queued = Collections.synchronizedSet(HashSet<String>())
    private var subscriber: RedisSubscriber? = null

    private val status = app.syncStatus

    fun start() {
        subscriber = RedisSubscriber(backend.url, listOf(backend.commandChannel(me))) { _, payload ->
            Command.decode(payload.unb64())?.let(app::dispatch)
        }.also { it.start() }
        scope.launch { heartbeatLoop() }
        scope.launch { eventLoop() }
        scope.launch { downloadLoop() }
        scope.launch { publishState() }
    }

    fun stop() {
        subscriber?.stop()
        scope.cancel()
        backend.close()
    }

    fun requestRescan() {
        scope.launch { runCatching { reconcile() } }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            try {
                backend.heartbeat(
                    BoardInfo(
                        id = me,
                        name = app.settings.deviceName,
                        model = android.os.Build.MODEL,
                        ip = NetUtil.localIp(app) ?: "",
                        freeBytes = store.freeBytes(),
                        appVersion = BuildConfig.VERSION_NAME,
                        bleToken = app.settings.bleToken,
                        lastSeen = System.currentTimeMillis(),
                    ),
                )
                status.value = status.value.copy(connected = true, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = status.value.copy(connected = false, error = e.message ?: e.javaClass.simpleName)
            }
            delay(5_000)
        }
    }

    private suspend fun eventLoop() {
        var backoff = 1_000L
        while (scope.isActive) {
            val reader = backend.eventReader()
            try {
                // Remember where the stream is *before* reconciling so no event slips through.
                var cursor = backend.lastEventId()
                reconcile()
                var lastFull = System.currentTimeMillis()
                backoff = 1_000L
                while (scope.isActive) {
                    for (e in reader.read(cursor)) {
                        handleEvent(e)
                        cursor = e.streamId
                    }
                    backend.setCursor(me, cursor)
                    if (System.currentTimeMillis() - lastFull > 5 * 60_000) {
                        reconcile()
                        lastFull = System.currentTimeMillis()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync loop failed", e)
                status.value = status.value.copy(error = e.message ?: e.javaClass.simpleName)
                delay(backoff)
                backoff = min(backoff * 2, 30_000)
            } finally {
                reader.close()
            }
        }
    }

    private fun handleEvent(e: Backend.Event) {
        when (e.op) {
            "add", "update" -> backend.content(e.contentId)?.let(::apply)
            "remove" -> {
                if (store.remove(e.contentId, deleteFile = true)) app.thumbnails.evict(e.contentId)
                backend.removeHave(me, e.contentId)
            }
        }
    }

    /** Brings local storage and the backend into agreement. Safe to run any time. */
    @Synchronized
    private fun reconcile() {
        val contents = backend.allContent().associateBy { it.id }.toMutableMap()

        // 1. Files dropped onto the board (USB, Downloads…) become content admins can play.
        val (changed, vanished) = store.scanChanges()
        vanished.forEach { store.remove(it, deleteFile = false) }
        for (file in changed) {
            runCatching { registerLocal(file, contents) }.onFailure { Log.w(TAG, "Skip $file", it) }
        }

        // 2. Local entries the backend no longer knows: cached downloads are deleted; board-owned
        //    files are re-registered (never delete a user's own files because of a backend wipe).
        for (id in store.ids() - contents.keys) {
            val item = store.item(id) ?: continue
            if (item.origin == Origin.UPLOAD) {
                store.remove(id, deleteFile = true)
            } else {
                val file = store.fileOf(id) ?: continue
                store.remove(id, deleteFile = false)
                runCatching { registerLocal(file, contents) }
            }
        }

        // 3. Everything meant for this board: refresh metadata or download it.
        for (c in contents.values) {
            if (!c.ready || !c.isFor(me)) continue
            val local = store.item(c.id)
            if (local != null) store.update(c.toItem().copy(durationMs = local.durationMs, pageCount = local.pageCount))
            else if (c.objectKey.isNotEmpty()) enqueue(c)
        }
        backend.setHave(me, store.ids())
    }

    private fun apply(c: Content) {
        if (!c.ready || !c.isFor(me)) return
        val local = store.item(c.id)
        if (local != null) {
            store.update(c.toItem().copy(durationMs = local.durationMs, pageCount = local.pageCount))
            backend.addHave(me, c.id)
        } else if (c.objectKey.isNotEmpty()) {
            enqueue(c)
        }
    }

    private fun registerLocal(file: File, contents: MutableMap<String, Content>) {
        val id = Fingerprint.of(file)
        val existing = contents[id]
        val content = existing ?: run {
            val mime = MimeTypes.of(file.name)
            val type = ContentType.of(file.name, mime)
            val (duration, pages) = store.probe(file, type)
            val thumb = uploadThumb(id, file, type)
            Content(
                id = id, handle = backend.newHandle(), name = file.name, type = type, mime = mime, size = file.length(),
                ready = true, origin = Origin.BOARD, onlyOn = me, addedBy = app.settings.deviceName,
                addedAt = file.lastModified(), durationMs = duration, pageCount = pages, hasThumb = thumb,
            ).also { backend.putContent(it); contents[id] = it }
        }
        val (duration, pages) = store.probe(file, content.type)
        store.put(content.toItem().copy(durationMs = duration, pageCount = pages), file)
    }

    private fun uploadThumb(id: String, file: File, type: ContentType): Boolean = runCatching {
        val jpeg = app.thumbnails.render(file, type) ?: return false
        s3.putObject("thumbs/$id.jpg", jpeg, "image/jpeg")
        true
    }.getOrDefault(false)

    private fun enqueue(c: Content) {
        if (queued.add(c.id)) {
            downloads.trySend(c)
            status.value = status.value.copy(pending = queued.size)
        }
    }

    private suspend fun downloadLoop() {
        for (c in downloads) {
            var attempt = 0
            while (scope.isActive) {
                try {
                    download(c)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Download ${c.name} failed", e)
                    if (e is S3.S3Error && e.code == 404) break // object gone; nothing to fetch
                    delay(min(2_000L shl min(attempt++, 4), 30_000))
                }
            }
            queued -= c.id
            status.value = status.value.copy(downloading = null, downloadProgress = 0f, pending = queued.size)
        }
    }

    private fun download(c: Content) {
        if (store.item(c.id) != null) return
        val part = File(store.incoming, "${c.id}.part")
        if (part.length() > c.size) part.delete()
        var offset = part.length()
        status.value = status.value.copy(downloading = c.name, downloadProgress = offset / c.size.coerceAtLeast(1).toFloat())

        if (offset < c.size) {
            s3.get(c.objectKey, offset).use { response ->
                if (offset > 0 && response.code != 206) {
                    part.delete() // server ignored the Range header; start over
                    offset = 0
                }
                val body = response.body ?: throw IOException("Empty body")
                FileOutputStream(part, offset > 0).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        offset += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 700) {
                            lastReport = now
                            status.value = status.value.copy(downloadProgress = offset / c.size.toFloat())
                            runCatching { backend.setProgress(me, c.id, offset) }
                        }
                    }
                }
            }
        }
        if (part.length() != c.size) throw IOException("Incomplete download (${part.length()}/${c.size})")
        if (Fingerprint.of(part) != c.id) {
            part.delete()
            throw IOException("Checksum mismatch for ${c.name}")
        }
        val dest = store.destinationFor(c.name)
        if (!part.renameTo(dest)) {
            part.copyTo(dest)
            part.delete()
        }
        val (duration, pages) = store.probe(dest, c.type)
        store.put(c.toItem().copy(durationMs = duration, pageCount = pages), dest)
        backend.addHave(me, c.id)
        if ((c.durationMs == 0L && duration > 0) || (c.pageCount == 0 && pages > 0)) {
            runCatching {
                backend.updateContent(c.id, "durationMs" to duration.toString(), "pageCount" to pages.toString(), emit = false)
            }
        }
    }

    /** Mirrors playback state to Redis so admins anywhere see it live. */
    private suspend fun publishState() {
        var last: PlaybackState? = null
        app.engine.state.collect { s ->
            val l = last
            val significant = l == null || l.copy(positionMs = s.positionMs) != s || abs(l.positionMs - s.positionMs) >= 1000
            if (significant) {
                runCatching { backend.publishState(me, s) }.onSuccess { last = s }
            }
        }
    }

    private companion object {
        const val TAG = "BoardSync"
    }
}
