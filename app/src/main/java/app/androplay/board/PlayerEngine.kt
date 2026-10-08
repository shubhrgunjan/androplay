package app.androplay.board

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.AudioManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.Item
import app.androplay.protocol.MimeTypes
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns everything shown on the board. Lives in the Application so players stay warm across
 * activity restarts. All methods must be called on the main thread.
 *
 * Instant switching: the next queue item is prepared in a standby ExoPlayer (or decoded into the
 * still cache for images/PDF pages); playing it just swaps which player owns the surface.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(
    private val ctx: Context,
    private val library: LocalStore,
    private val scope: CoroutineScope,
) {
    sealed interface Display {
        val item: Item?

        data object Idle : Display {
            override val item: Item? = null
        }
        data class Video(override val item: Item) : Display
        data class Audio(override val item: Item) : Display
        data class Still(override val item: Item, val bitmap: Bitmap, val page: Int) : Display
        data class External(override val item: Item) : Display
        data class Failed(override val item: Item?, val message: String) : Display
    }

    private val _display = MutableStateFlow<Display>(Display.Idle)
    val display: StateFlow<Display> = _display.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Board UI shows the content grid while true. */
    val libraryVisible = MutableStateFlow(true)

    private val audio = ctx.getSystemService(AudioManager::class.java)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === active) publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (_display.value is Display.Video || _display.value is Display.Audio) {
                _display.value = Display.Failed(current, "Can't play ${current?.name}: ${error.errorCodeName}")
                publish()
            }
        }
    }

    private var active: ExoPlayer = newPlayer()
    private var standby: ExoPlayer = newPlayer()
    private var standbyHandle = PlaybackState.NO_HANDLE

    /** Set by the activity; the engine moves it between players on swap. */
    var view: PlayerView? = null
        set(value) {
            if (field !== value) field?.player = null
            field = value
            value?.player = active
        }

    /** Pixel size used to decode images and render PDF pages. */
    var screen: Size = ctx.resources.displayMetrics.let { Size(it.widthPixels, it.heightPixels) }

    private var current: Item? = null
    private var queue: List<Int> = emptyList()
    private var page = 0
    private var loadJob: Job? = null

    // PdfRenderer is single-threaded: every PDF/image decode runs on this one thread.
    private val stillDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var pdfSession: Pair<String, PdfRenderer>? = null
    private val stills = object : LruCache<String, Bitmap>(192 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        scope.launch(Dispatchers.Main) { library.manifest.collect { publish() } }
        scope.launch(Dispatchers.Main) {
            while (true) {
                publish()
                delay(if (active.isPlaying) 500 else 1500)
            }
        }
    }

    fun needsScreen(): Boolean =
        _display.value !is Display.External && (current != null || libraryVisible.value)

    fun handle(cmd: Command) {
        when (cmd) {
            is Command.Play -> library.byHandle(cmd.handle)?.let { open(it, cmd.start) }
            Command.Pause -> active.pause()
            Command.Resume -> resume()
            Command.Toggle -> if (active.isPlaying) active.pause() else resume()
            is Command.Seek -> seekTo(cmd.positionMs.toLong())
            is Command.SeekBy -> seekTo(active.currentPosition + cmd.deltaMs)
            is Command.Volume -> setVolume(cmd.percent)
            Command.ToggleMute -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            Command.Next -> step(+1)
            Command.Prev -> step(-1)
            is Command.GoToPage -> current?.takeIf { it.type == ContentType.PDF }?.let { showPage(it, cmd.page) }
            Command.Stop -> stop()
            Command.ToggleLibrary -> libraryVisible.value = !libraryVisible.value || current == null
            is Command.SetQueue -> {
                queue = cmd.handles
                preloadNext()
            }
            is Command.Auth -> Unit
        }
        publish()
    }

    private fun open(item: Item, start: Int) {
        val file = library.fileOf(item.id) ?: return fail(item, "${item.name} is missing on the board")
        loadJob?.cancel()
        libraryVisible.value = false
        val same = current?.id == item.id
        current = item
        when (item.type) {
            ContentType.VIDEO, ContentType.AUDIO -> playMedia(item, file, start.toLong(), restart = same)
            ContentType.IMAGE -> {
                haltMedia()
                showStill(item, 0) { decodeImage(file) }
            }
            ContentType.PDF -> {
                haltMedia()
                showPage(item, start)
            }
            ContentType.DOCUMENT, ContentType.OTHER -> {
                haltMedia()
                openExternal(item, file)
            }
        }
        preloadNext()
    }

    private fun playMedia(item: Item, file: File, startMs: Long, restart: Boolean) {
        when {
            restart && active.currentMediaItem != null -> active.seekTo(startMs)
            standbyHandle == item.handle -> {
                val old = active
                active = standby
                standby = old
                standbyHandle = PlaybackState.NO_HANDLE
                old.stop()
                old.clearMediaItems()
                view?.player = active
                if (startMs > 0) active.seekTo(startMs)
            }
            else -> {
                active.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), startMs)
                active.prepare()
            }
        }
        active.playWhenReady = true
        _display.value = if (item.type == ContentType.VIDEO) Display.Video(item) else Display.Audio(item)
    }

    private fun haltMedia() {
        active.stop()
        active.clearMediaItems()
    }

    private fun resume() {
        if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
        active.play()
    }

    private fun seekTo(ms: Long) {
        if (_display.value !is Display.Video && _display.value !is Display.Audio) return
        val duration = active.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        active.seekTo(ms.coerceIn(0, duration))
    }

    private fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max / 100f).roundToInt(), 0)
    }

    private fun stop() {
        loadJob?.cancel()
        haltMedia()
        current = null
        _display.value = Display.Idle
        libraryVisible.value = true
    }

    private fun step(dir: Int) {
        val cur = current
        if (cur?.type == ContentType.PDF) {
            val target = page + dir
            if (target >= 0 && (cur.pageCount == 0 || target < cur.pageCount)) {
                showPage(cur, target)
                return
            }
        }
        val handle = neighbour(dir) ?: return
        library.byHandle(handle)?.let { open(it, if (dir < 0 && it.type == ContentType.PDF) it.pageCount - 1 else 0) }
    }

    private fun order(): List<Int> = queue.ifEmpty { library.items().map { it.handle } }

    private fun neighbour(dir: Int): Int? {
        val list = order()
        if (list.isEmpty()) return null
        val i = list.indexOf(current?.handle ?: -1)
        return if (i < 0) (if (dir > 0) list.first() else list.last()) else list.getOrNull(i + dir)
    }

    private fun preloadNext() {
        val handle = neighbour(+1) ?: return
        if (handle == current?.handle) return
        val item = library.byHandle(handle) ?: return
        val file = library.fileOf(item.id) ?: return
        when (item.type) {
            ContentType.VIDEO, ContentType.AUDIO -> if (standbyHandle != handle) {
                standby.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                standby.playWhenReady = false
                standby.prepare()
                standbyHandle = handle
            }
            ContentType.IMAGE -> preloadStill(item, 0) { decodeImage(file) }
            ContentType.PDF -> preloadStill(item, 0) { renderPdfPage(file, item.id, 0, keepOpen = false) }
            else -> Unit
        }
    }

    // --- Images & PDF -------------------------------------------------------------------------

    private fun showPage(item: Item, requested: Int) {
        val last = if (item.pageCount > 0) item.pageCount - 1 else Int.MAX_VALUE
        val p = requested.coerceIn(0, last)
        val file = library.fileOf(item.id) ?: return fail(item, "${item.name} is missing on the board")
        page = p
        showStill(item, p) { renderPdfPage(file, item.id, p, keepOpen = true) }
        if (p + 1 <= last) preloadStill(item, p + 1) { renderPdfPage(file, item.id, p + 1, keepOpen = true) }
    }

    private fun showStill(item: Item, page: Int, load: () -> Bitmap?) {
        val key = "${item.id}:$page"
        stills.get(key)?.let {
            _display.value = Display.Still(item, it, page)
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Main.immediate) {
            val bitmap = withContext(stillDispatcher) { runCatching { load() }.getOrNull() }
            if (current?.id != item.id) return@launch
            if (bitmap == null) {
                fail(item, "Can't open ${item.name}")
            } else {
                stills.put(key, bitmap)
                _display.value = Display.Still(item, bitmap, page)
            }
            publish()
        }
    }

    private fun preloadStill(item: Item, page: Int, load: () -> Bitmap?) {
        val key = "${item.id}:$page"
        if (stills.get(key) != null) return
        scope.launch(stillDispatcher) { runCatching { load() }.getOrNull()?.let { stills.put(key, it) } }
    }

    private fun decodeImage(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, min(screen.width / w.toFloat(), screen.height / h.toFloat()))
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }

    /** Runs on [stillDispatcher] only. */
    private fun renderPdfPage(file: File, id: String, index: Int, keepOpen: Boolean): Bitmap? {
        val renderer = if (keepOpen) {
            pdfSession?.takeIf { it.first == id }?.second ?: run {
                pdfSession?.second?.close()
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                    .also { pdfSession = id to it }
            }
        } else {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        try {
            if (index !in 0 until renderer.pageCount) return null
            renderer.openPage(index).use { pg ->
                val scale = min(screen.width / pg.width.toFloat(), screen.height / pg.height.toFloat())
                val bitmap = Bitmap.createBitmap(
                    (pg.width * scale).roundToInt().coerceAtLeast(1),
                    (pg.height * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                pg.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        } finally {
            if (!keepOpen) renderer.close()
        }
    }

    // --- Other formats ------------------------------------------------------------------------

    private fun openExternal(item: Item, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(intent)
            _display.value = Display.External(item)
        } catch (e: ActivityNotFoundException) {
            fail(item, "No app on the board can open .${MimeTypes.extension(item.name)} files")
        }
    }

    private fun fail(item: Item?, message: String) {
        _display.value = Display.Failed(item, message)
        publish()
    }

    // --- State --------------------------------------------------------------------------------

    private fun publish() {
        val item = current
        val d = _display.value
        val media = d is Display.Video || d is Display.Audio
        val status = when {
            item == null -> Status.IDLE
            d is Display.Failed -> Status.ERROR
            d.item?.id != item.id -> Status.LOADING
            d is Display.External -> Status.EXTERNAL
            d is Display.Still -> Status.PLAYING
            else -> when (active.playbackState) {
                Player.STATE_ENDED -> Status.ENDED
                Player.STATE_BUFFERING -> if (active.playWhenReady) Status.LOADING else Status.PAUSED
                else -> if (active.playWhenReady) Status.PLAYING else Status.PAUSED
            }
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        _state.value = PlaybackState(
            status = status,
            handle = item?.handle ?: PlaybackState.NO_HANDLE,
            positionMs = if (media) active.currentPosition.coerceAtLeast(0) else 0,
            durationMs = if (media) active.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: item?.durationMs ?: 0 else 0,
            volume = if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0,
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC),
            page = if (item?.type == ContentType.PDF) page else 0,
            pageCount = if (item?.type == ContentType.PDF) item.pageCount else 0,
            manifestVersion = library.manifest.value.version,
        )
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
        // Local files: start as soon as 250 ms is buffered.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 15_000, 250, 500).build())
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            false,
        )
        .build()
        .also { it.addListener(listener) }
}
