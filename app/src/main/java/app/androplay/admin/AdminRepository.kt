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
import java.io.File

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

    fun thumbUrl(c: Content): String? = if (c.hasThumb) runCatching { s3?.presignGet("thumbs/${c.id}.jpg", 6 * 3600) }.getOrNull() else null

    fun setActive(active: Boolean) {
        if (!active) {
            loops?.cancel()
            loops = null
            stateSub?.stop()
            stateSub = null
            ble.stop()
            backend?.close()
            backend = null
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

    // --- Uploads -----------------------------------------------------------------------------

    fun hasPending() = _uploads.value.values.any { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }

    fun nextPending(): Upload? = _uploads.value.values
        .filter { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }
        .minByOrNull { it.addedAt }

    /** Adds files from the picker/share sheet. Returns how many were new. */
    suspend fun add(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uri in uris) {
            val u = runCatching { describe(uri) }.getOrNull() ?: continue
            val alreadyThere = contents.value.any { it.id == u.id && it.ready }
            val isNew = mutex.withLock {
                if (u.id in _uploads.value) return@withLock false
                save(_uploads.value + (u.id to if (alreadyThere) u.copy(state = UploadState.DONE, uploaded = u.size) else u))
                true
            }
            if (isNew) added++
        }
        if (added > 0) UploadWorker.enqueue(ctx)
        added
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
                selectedId.value?.let { boardSync.value = b.syncState(it) }
                connected.value = true
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connected.value = false
                error.value = e.message ?: e.javaClass.simpleName
            }
            delay(2_000)
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
        stateSub = RedisSubscriber(b.url, listOf(b.stateChannel(boardId))) { _, payload ->
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

    private fun describe(source: Uri): Upload {
        val cr = ctx.contentResolver
        val persistent = runCatching { cr.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess ||
            source.scheme == "file"
        var name = "file"
        cr.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        // Share-sheet grants are temporary; keep a private copy so the upload can finish later.
        val uri = if (persistent) source else stage(source, name)
        val mime = cr.getType(uri)?.takeUnless { it == MimeTypes.BINARY } ?: MimeTypes.of(name)
        val (size, id) = ctx.openOriginal(uri).let { pfd ->
            val size = pfd.statSize
            require(size > 0) { "Unknown file size" }
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { size to Fingerprint.of(it.channel, size) }
        }
        return Upload(id, uri.toString(), name, size, mime, ContentType.of(name, mime), System.currentTimeMillis(), staged = !persistent)
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

    private companion object {
        const val PROGRESS_THROTTLE_MS = 700L
    }
}
