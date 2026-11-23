package app.androplay.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.androplay.AndroPlayApp
import app.androplay.core.Backend
import app.androplay.core.BoardInfo
import app.androplay.core.BoardSyncState
import app.androplay.core.Content
import app.androplay.core.RedisSubscriber
import app.androplay.core.S3
import app.androplay.core.Slideshow
import app.androplay.core.flatten
import app.androplay.core.playAllSlides
import app.androplay.core.slideshowCommand
import app.androplay.core.unb64
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.Fingerprint
import app.androplay.protocol.MimeTypes
import app.androplay.protocol.Net
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
enum class UploadState { QUEUED, UPLOADING, DONE, FAILED }

@Serializable
data class PartTag(val n: Int, val etag: String)

/** A file this phone is sending. Persisted, so the queue survives restarts and bad networks. */
@Serializable
data class Upload(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
    val type: ContentType,
    val addedAt: Long,
    val state: UploadState = UploadState.QUEUED,
    val uploaded: Long = 0,
    val error: String? = null,
    val staged: Boolean = false,
    val uploadId: String? = null,
    val parts: List<PartTag> = emptyList(),
)

enum class EntryStatus { READY, DOWNLOADING, WAITING, UPLOADING, QUEUED, FAILED }

/** One tile: backend content and/or a local upload, with its status on the selected board. */
data class Entry(
    val id: String,
    val name: String,
    val type: ContentType,
    val size: Long,
    val addedAt: Long,
    val content: Content?,
    val upload: Upload?,
    val status: EntryStatus,
    val progress: Float,
)

/** Aggregate of everything currently uploading or queued (this phone or other admins). */
data class UploadSummary(val active: Int, val uploadedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (uploadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

class AdminRepository(private val app: AndroPlayApp) {
    private val ctx: Context = app
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = ctx.getSharedPreferences("admin", Context.MODE_PRIVATE)
    private val uploadsFile = File(ctx.filesDir, "uploads.json")
    private val stagingDir = File(ctx.filesDir, "staged").apply { mkdirs() }
    private val mutex = Mutex()

    private var backend: Backend? = null
    private var s3: S3? = null
    private var loops: Job? = null
    private var stateSub: RedisSubscriber? = null

    val ble = BleLink(ctx, main)
    val lan = LanBrowser(ctx)
    val lanProgress = MutableStateFlow(LanSendProgress())
    val connected = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val contents = MutableStateFlow<List<Content>>(emptyList())
    val boards = MutableStateFlow<List<BoardInfo>>(emptyList())
    val selectedId = MutableStateFlow(prefs.getString("board", null))
    val boardSync = MutableStateFlow(BoardSyncState())
    private val remoteState = MutableStateFlow<PlaybackState?>(null)
    private val _uploads = MutableStateFlow(load())
    val uploads: StateFlow<Map<String, Upload>> = _uploads

    val selected: StateFlow<BoardInfo?> = combine(boards, selectedId) { b, id -> b.firstOrNull { it.id == id } }
        .stateIn(main, SharingStarted.Eagerly, null)

    /** Live state: BLE when connected in the room, otherwise Redis pub/sub. */
    val state: StateFlow<PlaybackState?> = combine(ble.status, ble.state, remoteState) { s, viaBle, viaRedis ->
        if (s == BleLink.Status.READY) viaBle ?: viaRedis else viaRedis
    }.stateIn(main, SharingStarted.Eagerly, null)

    val entries: StateFlow<List<Entry>> = combine(contents, _uploads, boardSync, selectedId) { c, u, sync, board ->
        merge(c, u, sync, board)
    }.stateIn(main, SharingStarted.Eagerly, emptyList())

    /** Live totals for an "uploading N files" banner. */
    val uploadSummary: StateFlow<UploadSummary> = entries.map { list ->
        val active = list.filter { it.status == EntryStatus.UPLOADING || it.status == EntryStatus.QUEUED }
        UploadSummary(
            active = active.size,
            uploadedBytes = active.sumOf { (it.progress.coerceIn(0f, 1f) * it.size).toLong() },
            totalBytes = active.sumOf { it.size },
        )
    }.distinctUntilChanged().stateIn(main, SharingStarted.Eagerly, UploadSummary(0, 0, 0))

    val thumbsRev = MutableStateFlow(0)
    private val thumbDir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    @Volatile private var s3ThumbsDead = false
    private var thumbJob: Job? = null

    fun thumbFile(id: String) = File(thumbDir, "$id.jpg")

    /** Local cache, else S3, else null while a Wi-Fi fetch from the board is in flight. */
    fun thumbModel(c: Content): Any? {
        val local = thumbFile(c.id)
        if (local.exists() && local.length() > 32) return local
        if (!s3ThumbsDead && c.hasThumb) {
            return runCatching { s3?.presignGet("thumbs/${c.id}.jpg", 6 * 3600) }.getOrNull()
        }
        return null
    }

    @Deprecated("Use thumbModel", ReplaceWith("thumbModel(c)?.toString()"))
    fun thumbUrl(c: Content): String? = when (val m = thumbModel(c)) {
        is File -> m.toURI().toString()
        is String -> m
        else -> null
    }

    fun saveThumb(id: String, bytes: ByteArray) {
        if (bytes.size < 32) return
        val dest = thumbFile(id)
        val tmp = File(thumbDir, "$id.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(dest)
        thumbsRev.value++
    }

    /** Pull any missing thumbs: S3 first, then the board over Wi-Fi when storage refuses. */
    fun prefetchThumbs(list: List<Entry> = entries.value) {
        thumbJob?.cancel()
        thumbJob = io.launch {
            delay(250)
            for (e in list) {
                if (!isActive) return@launch
                val c = e.content ?: continue
                if (thumbFile(c.id).exists()) continue
                if (!s3ThumbsDead && c.hasThumb && pullThumbS3(c.id)) continue
                pullThumbLan(c.id)
            }
        }
    }

    private fun pullThumbS3(id: String): Boolean {
        val client = s3 ?: return false
        return try {
            client.get("thumbs/$id.jpg").use { r ->
                val bytes = r.body?.bytes() ?: return false
                saveThumb(id, bytes)
                true
            }
        } catch (e: S3.S3Error) {
            if (e.code == 403 || e.code == 401) s3ThumbsDead = true
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun pullThumbLan(id: String): Boolean {
        val host = suggestedLanHost().ifBlank { return false }
        val token = selected.value?.bleToken?.ifBlank { null }
            ?: prefs.getString("lanToken", null)
            ?: return false
        val port = prefs.getInt("lanPort", Net.PORT).let { if (it > 0) it else Net.PORT }
        return try {
            val http = app.http.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder()
                .url("http://$host:$port/lan/thumb/$id")
                .header(Net.TOKEN_HEADER, token)
                .get()
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return false
                val bytes = r.body?.bytes() ?: return false
                saveThumb(id, bytes)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setActive(active: Boolean) {
        if (!active) {
            loops?.cancel()
            loops = null
            stateSub?.stop()
            stateSub = null
            ble.stop()
            backend?.close()
            backend = null
            lan.stop()
            return
        }
        if (loops != null || !app.settings.config.isComplete) return
        val b = try {
            app.newBackend()
        } catch (e: Exception) {
            error.value = "Bad backend config: ${e.message}"
            return
        }
        backend = b
        s3 = app.newS3()
        loops = io.launch {
            launch { pollLoop(b) }
            launch { eventLoop(b) }
            launch { entries.collect { prefetchThumbs(it) } }
            launch {
                selected.map { it?.id to it?.bleToken }.distinctUntilChanged().collect { (id, token) ->
                    watchBoard(b, id)
                    withContext(Dispatchers.Main) {
                        if (id != null && !token.isNullOrEmpty()) ble.start(token, id) else ble.stop()
                    }
                }
            }
        }
        if (hasPending()) UploadWorker.enqueue(ctx)
        io.launch { app.updater.check() }
    }

    val updater get() = app.updater

    fun restartBle() {
        val b = selected.value ?: return
        if (loops != null && b.bleToken.isNotEmpty()) ble.start(b.bleToken, b.id)
    }

    fun select(boardId: String) {
        selectedId.value = boardId
        prefs.edit().putString("board", boardId).apply()
    }

    /** BLE first (≈10–30 ms in the room), Redis pub/sub otherwise (works from anywhere). */
    suspend fun send(cmd: Command): Boolean {
        if (ble.send(cmd)) return true
        val b = backend ?: return false
        val board = selectedId.value ?: return false
        return withContext(Dispatchers.IO) { runCatching { b.publishCommand(board, cmd) }.isSuccess }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        backend?.let { b -> runCatching { contents.value = b.allContent() } }
    }

    // --- Slideshows --------------------------------------------------------------------------

    val shows = MutableStateFlow<List<Slideshow>>(emptyList())

    suspend fun saveShow(show: Slideshow): Boolean = withContext(Dispatchers.IO) {
        val b = backend ?: return@withContext false
        runCatching {
            val isNew = shows.value.none { it.id == show.id }
            val position = if (isNew) (shows.value.maxOfOrNull { it.position } ?: -1) + 1 else show.position
            val saved = show.copy(updatedAt = System.currentTimeMillis(), position = position)
            b.putSlideshow(saved)
            shows.value = (shows.value.filterNot { it.id == saved.id } + saved).sortedBy { it.position }
        }.isSuccess
    }

    /** Persists a new slideshow order (after drag and drop). Updates the UI immediately. */
    suspend fun reorderShows(ordered: List<Slideshow>): Boolean {
        val updated = ordered.mapIndexed { i, s -> s.copy(position = i) }
        shows.value = updated
        val changed = updated.filter { u -> ordered.firstOrNull { it.id == u.id }?.position != u.position }
        return withContext(Dispatchers.IO) {
            val b = backend ?: return@withContext false
            runCatching { b.putSlideshows(changed) }.isSuccess
        }
    }

    suspend fun deleteShow(id: String): Boolean = withContext(Dispatchers.IO) {
        val b = backend ?: return@withContext false
        runCatching {
            b.deleteSlideshow(id)
            shows.value = shows.value.filterNot { it.id == id }
        }.isSuccess
    }

    private fun readyHandle(contentId: String): Int? =
        entries.value.firstOrNull { it.id == contentId && it.status == EntryStatus.READY }?.content?.handle

    /** Slides of [show] (nested slideshows expanded) that the selected board can play now. */
    fun playableCount(show: Slideshow): Int =
        show.flatten(shows.value.associateBy { it.id }).count { readyHandle(it.contentId) != null }

    /** Starts [show] on the board. Returns how many slides will play (0 = nothing ready). */
    suspend fun playShow(show: Slideshow): Int {
        val cmd = slideshowCommand(show.flatten(shows.value.associateBy { it.id }), show.loop, ::readyHandle) ?: return 0
        send(cmd)
        return cmd.handles.size
    }

    /** Plays every slideshow back to back, in list order. */
    suspend fun playAll(loop: Boolean): Int {
        val cmd = slideshowCommand(playAllSlides(shows.value), loop, ::readyHandle) ?: return 0
        send(cmd)
        return cmd.handles.size
    }

    suspend fun delete(c: Content) = withContext(Dispatchers.IO) {
        val b = backend ?: return@withContext false
        runCatching {
            b.deleteContent(c.id)
            if (c.objectKey.isNotEmpty()) s3?.delete(c.objectKey)
            if (c.hasThumb) s3?.delete("thumbs/${c.id}.jpg")
            contents.value = contents.value.filterNot { it.id == c.id }
        }.isSuccess
    }

    /**
     * Renames [c] everywhere. Keeps the original extension when the user leaves it out
     * ("Intro" for talk.mp4 becomes Intro.mp4). The backend emits an `update` event, so boards
     * and other phones pick up the new name on their own; the local list updates right away.
     */
    suspend fun rename(c: Content, newName: String): Boolean {
        val name = withExtension(newName.trim().replace('/', '_'), c.name)
        if (name.isBlank()) return false
        if (name == c.name) return true
        val ok = withContext(Dispatchers.IO) {
            val b = backend ?: return@withContext false
            runCatching { b.updateContent(c.id, "name" to name) }.isSuccess
        }
        if (ok) contents.value = contents.value.map { if (it.id == c.id) it.copy(name = name) else it }
        return ok
    }

    // --- Uploads -----------------------------------------------------------------------------

    fun hasPending() = _uploads.value.values.any { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }

    fun nextPending(): Upload? = _uploads.value.values
        .filter { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }
        .minByOrNull { it.addedAt }

    data class AddResult(val added: Int, val rejected: Int)

    /** Adds files from the picker/share sheet. Rejects anything that isn't a video or image. */
    suspend fun add(uris: List<Uri>): AddResult = withContext(Dispatchers.IO) {
        var added = 0
        var rejected = 0
        for (uri in uris) {
            val u = runCatching { describe(uri) }.getOrNull()
            if (u == null) {
                rejected++
                continue
            }
            val alreadyThere = contents.value.any { it.id == u.id && it.ready }
            val isNew = mutex.withLock {
                if (u.id in _uploads.value) return@withLock false
                save(_uploads.value + (u.id to if (alreadyThere) u.copy(state = UploadState.DONE, uploaded = u.size) else u))
                true
            }
            if (isNew) added++
        }
        if (added > 0) UploadWorker.enqueue(ctx)
        AddResult(added, rejected)
    }

    data class LanSendResult(val sent: Int, val rejected: Int, val error: String? = null)

    /** Pushes files straight to the board over the local network. Does not use Redis or S3. */
    suspend fun sendLan(uris: List<Uri>, host: String, port: Int = Net.PORT): LanSendResult = withContext(Dispatchers.IO) {
        val token = selected.value?.bleToken?.ifBlank { null }
            ?: prefs.getString("lanToken", null)
        if (token.isNullOrBlank()) {
            return@withContext LanSendResult(0, 0, "Select a board first (needed once, so this phone has the share key).")
        }
        val target = host.trim().trimStart('/').substringAfter("://").substringBefore('/')
        if (target.isBlank()) return@withContext LanSendResult(0, 0, "Enter the board's Wi-Fi address.")
        prefs.edit().putString("lanHost", target).putInt("lanPort", port).putString("lanToken", token).apply()

        val http = app.http.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var sent = 0
        var rejected = 0
        var lastError: String? = null
        val usable = ArrayList<Upload>()
        for (uri in uris) {
            val u = runCatching { describe(uri) }.getOrNull()
            if (u == null) rejected++ else usable += u
        }
        usable.forEachIndexed { i, u ->
            lanProgress.value = LanSendProgress(true, u.name, 0, u.size, i + 1, usable.size)
            try {
                putLan(http, target, port, token, u)
                sent++
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                lanProgress.value = LanSendProgress(message = lastError)
                return@withContext LanSendResult(sent, rejected, friendlyLanError(e))
            }
        }
        lanProgress.value = LanSendProgress()
        LanSendResult(sent, rejected, lastError)
    }

    fun lastLanHost(): String = prefs.getString("lanHost", "")
        ?: selected.value?.ip.orEmpty().ifBlank { ble.info.value?.host.orEmpty() }

    fun suggestedLanHost(): String {
        val selectedId = selectedId.value
        val discovered = lan.boards.value
        discovered.firstOrNull { it.id == selectedId }?.let { return it.host }
        selected.value?.ip?.takeIf { it.isNotBlank() }?.let { return it }
        ble.info.value?.host?.takeIf { it.isNotBlank() }?.let { return it }
        discovered.firstOrNull()?.let { return it.host }
        return prefs.getString("lanHost", "").orEmpty()
    }

    private fun putLan(http: okhttp3.OkHttpClient, host: String, port: Int, token: String, u: Upload) {
        val encoded = java.net.URLEncoder.encode(u.name, Charsets.UTF_8.name())
        val url = "http://$host:$port/lan?name=$encoded"
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = u.mime.toMediaTypeOrNull()
            override fun contentLength() = u.size
            override fun writeTo(sink: okio.BufferedSink) {
                ctx.openOriginal(Uri.parse(u.uri)).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 200) {
                                last = now
                                lanProgress.value = lanProgress.value.copy(sent = done, total = u.size)
                            }
                        }
                    }
                }
            }
        }
        val req = okhttp3.Request.Builder()
            .url(url)
            .header(Net.TOKEN_HEADER, token)
            .put(body)
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                val err = r.body?.string().orEmpty().ifBlank { "HTTP ${r.code}" }
                throw java.io.IOException(err)
            }
        }
    }

    private fun friendlyLanError(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is java.net.ConnectException || e is java.net.SocketTimeoutException || "failed to connect" in m || "timeout" in m ->
                "Can't reach the board on this Wi-Fi. Join the same network or turn on the phone's hotspot and connect the board to it. The board shows its address on the home screen."
            "unauthorized" in m -> "This phone doesn't have the board's share key. Open the app while the cloud is up once, or pick the board again."
            else -> e.message ?: "Send failed"
        }
    }

    suspend fun retry(id: String) {
        update(id) { it.copy(state = UploadState.QUEUED, error = null) }
        UploadWorker.enqueue(ctx)
    }

    suspend fun removeUpload(id: String) {
        val removed = mutex.withLock {
            val u = _uploads.value[id] ?: return@withLock null
            save(_uploads.value - id)
            u
        } ?: return
        if (removed.staged) Uri.parse(removed.uri).path?.let { File(it).delete() }
        else runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(removed.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    suspend fun update(id: String, f: (Upload) -> Upload) = mutex.withLock {
        val cur = _uploads.value[id] ?: return@withLock
        save(_uploads.value + (id to f(cur)))
    }

    suspend fun finished(id: String) {
        val u = _uploads.value[id]
        update(id) { it.copy(state = UploadState.DONE, uploaded = it.size, error = null, uploadId = null, parts = emptyList()) }
        if (u?.staged == true) Uri.parse(u.uri).path?.let { File(it).delete() }
        refresh()
    }

    // --- Loops -------------------------------------------------------------------------------

    private suspend fun pollLoop(b: Backend) {
        while (io.isActive) {
            try {
                val list = b.boards()
                boards.value = list
                if (selectedId.value == null || list.none { it.id == selectedId.value }) {
                    list.firstOrNull { it.online }?.let { select(it.id) }
                }
                list.firstOrNull { it.id == selectedId.value }?.let { b ->
                    val edit = prefs.edit()
                    if (b.bleToken.isNotBlank()) edit.putString("lanToken", b.bleToken)
                    if (b.ip.isNotBlank()) edit.putString("lanHost", b.ip)
                    edit.apply()
                }
                selectedId.value?.let { boardSync.value = b.syncState(it) }
                connected.value = true
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connected.value = false
                error.value = e.message ?: e.javaClass.simpleName
            }
            delay(10_000) // each pass is ~2 commands per board; live changes arrive as events
        }
    }

    /** Real-time library: any admin's upload or delete shows up here within ~a second. */
    private suspend fun eventLoop(b: Backend) {
        while (io.isActive) {
            val reader = b.eventReader()
            try {
                var cursor = b.lastEventId()
                contents.value = b.allContent()
                shows.value = b.slideshows()
                var lastRefresh = System.currentTimeMillis()
                while (io.isActive) {
                    val events = reader.read(cursor)
                    if (events.isNotEmpty()) {
                        cursor = events.last().streamId
                        // Progress events arrive per uploaded part (possibly from several admins):
                        // coalesce them so the library reloads at most every PROGRESS_THROTTLE_MS.
                        if (events.all { it.op == "progress" }) {
                            val wait = PROGRESS_THROTTLE_MS - (System.currentTimeMillis() - lastRefresh)
                            if (wait > 0) delay(wait)
                        }
                        if (events.any { it.op == "show" }) shows.value = b.slideshows()
                        if (events.any { it.op == "release" }) io.launch { app.updater.check() }
                        if (events.any { it.op != "show" }) contents.value = b.allContent()
                        lastRefresh = System.currentTimeMillis()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(3_000)
            } finally {
                reader.close()
            }
        }
    }

    private fun watchBoard(b: Backend, boardId: String?) {
        stateSub?.stop()
        stateSub = null
        remoteState.value = null
        if (boardId == null) return
        runCatching { remoteState.value = b.state(boardId) }
        stateSub = RedisSubscriber(b.route, listOf(b.stateChannel(boardId))) { _, payload ->
            PlaybackState.decode(payload.unb64())?.let { remoteState.value = it }
        }.also { it.start() }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun merge(contents: List<Content>, uploads: Map<String, Upload>, sync: BoardSyncState, board: String?): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (c in contents) {
            if (board != null && !c.isFor(board)) continue
            val u = uploads[c.id]
            val (status, progress) = when {
                !c.ready && u != null -> uploadStatus(u)
                // Another admin is uploading it: show their live progress from Redis.
                !c.ready -> EntryStatus.UPLOADING to (c.uploaded / c.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                c.id in sync.have -> EntryStatus.READY to 1f
                c.id in sync.progress -> EntryStatus.DOWNLOADING to (sync.progress[c.id]!! / c.size.coerceAtLeast(1).toFloat())
                else -> EntryStatus.WAITING to 0f
            }
            out[c.id] = Entry(c.id, c.name, c.type, c.size, c.addedAt, c, u, status, progress)
        }
        for (u in uploads.values) {
            if (u.id in out || u.state == UploadState.DONE) continue
            val (status, progress) = uploadStatus(u)
            out[u.id] = Entry(u.id, u.name, u.type, u.size, u.addedAt, null, u, status, progress)
        }
        return out.values.sortedByDescending { it.addedAt }
    }

    private fun uploadStatus(u: Upload): Pair<EntryStatus, Float> = when (u.state) {
        UploadState.FAILED -> EntryStatus.FAILED to 0f
        UploadState.QUEUED -> EntryStatus.QUEUED to u.uploaded / u.size.coerceAtLeast(1).toFloat()
        else -> EntryStatus.UPLOADING to u.uploaded / u.size.coerceAtLeast(1).toFloat()
    }

    private fun describe(source: Uri): Upload? {
        val cr = ctx.contentResolver
        var name = "file"
        cr.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val mime = cr.getType(source)?.takeUnless { it == MimeTypes.BINARY } ?: MimeTypes.of(name)
        val type = ContentType.of(name, mime)
        if (type != ContentType.IMAGE && type != ContentType.VIDEO) return null
        val persistent = runCatching { cr.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess ||
            source.scheme == "file"
        // Share-sheet grants are temporary; keep a private copy so the upload can finish later.
        val uri = if (persistent) source else stage(source, name)
        val (size, id) = ctx.openOriginal(uri).let { pfd ->
            val size = pfd.statSize
            require(size > 0) { "Unknown file size" }
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { size to Fingerprint.of(it.channel, size) }
        }
        return Upload(id, uri.toString(), name, size, mime, type, System.currentTimeMillis(), staged = !persistent)
    }

    private fun stage(source: Uri, name: String): Uri {
        val dest = File(stagingDir, "${System.nanoTime()}-${name.replace('/', '_')}")
        ParcelFileDescriptor.AutoCloseInputStream(ctx.openOriginal(source)).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(dest)
    }

    private fun load(): Map<String, Upload> = runCatching {
        ProtocolJson.decodeFromString<List<Upload>>(uploadsFile.readText())
    }.getOrDefault(emptyList()).associateBy { it.id }

    private fun save(items: Map<String, Upload>) {
        _uploads.value = items
        val tmp = File(uploadsFile.path + ".tmp")
        tmp.writeText(ProtocolJson.encodeToString(items.values.toList()))
        tmp.renameTo(uploadsFile)
    }

    companion object {
        private const val PROGRESS_THROTTLE_MS = 700L

        /** Extension of [name] including the dot (".mp4"), or "" if it has none. */
        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0 && dot < name.length - 1 && name.length - dot <= 6) name.substring(dot) else ""
        }

        /** [input] with [original]'s extension appended if the user omitted it. */
        fun withExtension(input: String, original: String): String {
            val ext = extensionOf(original)
            if (input.isBlank() || ext.isEmpty() || input.endsWith(ext, ignoreCase = true)) return input
            return input + ext
        }
    }
}
