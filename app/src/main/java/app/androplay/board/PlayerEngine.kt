package app.androplay.board

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
 * still cache for images); playing it just swaps which player owns the surface.
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
        data class Still(override val item: Item, val bitmap: Bitmap) : Display
        data class External(override val item: Item) : Display
        data class Failed(override val item: Item?, val message: String) : Display
    }

    private val _display = MutableStateFlow<Display>(Display.Idle)
    val display: StateFlow<Display> = _display.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Board UI shows the content grid while true. */
    val libraryVisible = MutableStateFlow(true)

    private val prefs = ctx.getSharedPreferences("board", Context.MODE_PRIVATE)

    /** Touchscreen lock (persisted). Touches are swallowed by BoardActivity; remotes/phones still work. */
    val locked = MutableStateFlow(prefs.getBoolean("locked", false))

    private val audio = ctx.getSystemService(AudioManager::class.java)

    /** Lag signals for [PerformanceGuard]. */
    var onDroppedFrames: ((count: Int, elapsedMs: Long) -> Unit)? = null
    var onStall: (() -> Unit)? = null

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            onDroppedFrames?.invoke(droppedFrames, elapsedMs)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player !== active) return
            // Slideshow: a finished video/audio moves on to the next slide.
            if (show != null && events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                advance()
            }
            // Buffering mid-playback of a *local* file means the device is struggling.
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_BUFFERING &&
                player.playWhenReady && player.currentPosition > 1_000
            ) {
                onStall?.invoke()
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (_display.value is Display.Video) {
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

    /** Pixel size used to decode images. */
    var screen: Size = ctx.resources.displayMetrics.let { Size(it.widthPixels, it.heightPixels) }

    private var current: Item? = null
    private var queue: List<Int> = emptyList()
    private var loadJob: Job? = null

    // Image decode runs on this single thread.
    private val stillDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val stills = object : LruCache<String, Bitmap>(192 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        scope.launch(Dispatchers.Main) { library.manifest.collect { publish() } }
        scope.launch(Dispatchers.Main) {
            while (true) {
                publish()
                // Fast ticks while anything moves, so phones' sliders advance smoothly.
                delay(if (active.isPlaying || stillRunning()) 500 else 1500)
            }
        }
    }

    /** Frees decoded images (the one on screen stays referenced by [display]). */
    fun trimCaches() {
        stills.evictAll()
    }

    fun needsScreen(): Boolean =
        _display.value !is Display.External && (current != null || libraryVisible.value)

    fun handle(cmd: Command) {
        when (cmd) {
            is Command.Play -> {
                endShow()
                library.byHandle(cmd.handle)?.let { open(it, cmd.start) }
            }
            // A lone still has no timeline: play/pause are no-ops (the phone disables them).
            Command.Pause -> when {
                showingStill() -> pauseStill()
                !loneStill() -> active.pause()
            }
            Command.Resume -> when {
                showingStill() -> resumeStill()
                !loneStill() -> resume()
            }
            Command.Toggle -> when {
                showingStill() -> if (stillPaused) resumeStill() else pauseStill()
                loneStill() -> Unit
                active.isPlaying -> active.pause()
                else -> resume()
            }
            is Command.Seek -> if (showingStill()) seekStill(cmd.positionMs.toLong()) else seekTo(cmd.positionMs.toLong())
            is Command.SeekBy -> if (showingStill()) seekStill(stillPosition() + cmd.deltaMs) else seekTo(active.currentPosition + cmd.deltaMs)
            is Command.Volume -> setVolume(cmd.percent)
            Command.ToggleMute -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            Command.Next -> if (show != null) showStep(+1, auto = false) else step(+1)
            Command.Prev -> if (show != null) showStep(-1, auto = false) else step(-1)
            Command.Stop -> {
                endShow()
                stop()
            }
            Command.ToggleLibrary -> if (!locked.value) libraryVisible.value = !libraryVisible.value || current == null
            is Command.Lock -> {
                locked.value = cmd.locked
                prefs.edit().putBoolean("locked", cmd.locked).apply()
            }
            is Command.SetQueue -> if (show == null) {
                queue = cmd.handles
                preloadNext()
            }
            is Command.Slideshow -> startShow(cmd)
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
            ContentType.VIDEO -> playMedia(item, file, start.toLong(), restart = same)
            ContentType.IMAGE -> {
                haltMedia()
                showStill(item) { decodeImage(file) }
            }
            ContentType.OTHER -> {
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
        _display.value = Display.Video(item)
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
        if (_display.value !is Display.Video) return
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

    // --- Slideshow ----------------------------------------------------------------------------

    /** A running slideshow. Index-based, so the same item may appear several times. */
    private class Show(val handles: List<Int>, val seconds: List<Int>, val loop: Boolean) {
        var index = 0
    }

    private var show: Show? = null
    private var showTimer: Job? = null
    private var stillPaused = false

    // Slideshow still timeline: time shown so far = stillElapsed + (now - stillStartedAt) while running.
    // stillStartedAt is 0 whenever the timer isn't running (paused, media slide, no show).
    private var stillStartedAt = 0L
    private var stillElapsed = 0L

    private fun startShow(cmd: Command.Slideshow) {
        // OTHER content opens in other apps and can't be timed, so they're skipped.
        val keep = cmd.handles.indices.filter { i ->
            library.byHandle(cmd.handles[i])?.type?.let { it != ContentType.OTHER } == true
        }
        if (keep.isEmpty()) return
        val s = Show(keep.map { cmd.handles[it] }, keep.map { cmd.secondsAt(it).coerceAtLeast(1) }, cmd.loop)
        s.index = keep.indexOfFirst { it >= cmd.start }.takeIf { it >= 0 } ?: 0
        show = s
        queue = s.handles
        showSlide(s, s.index, lastPage = false)
    }

    private fun endShow() {
        show = null
        showTimer?.cancel()
        stillPaused = false
        stillStartedAt = 0
        stillElapsed = 0
    }

    private fun showingStill() = show != null && current?.type == ContentType.IMAGE

    private fun loneStill() = show == null && current?.type == ContentType.IMAGE

    private fun stillRunning() = show != null && stillStartedAt > 0

    private fun slideMs(): Long = show?.let { it.seconds.getOrElse(it.index) { 8 } * 1000L } ?: 0

    private fun stillPosition(): Long {
        val running = if (stillStartedAt > 0) SystemClock.elapsedRealtime() - stillStartedAt else 0
        return (stillElapsed + running).coerceIn(0, slideMs())
    }

    private fun pauseStill() {
        if (stillPaused) return
        stillElapsed = stillPosition()
        stillStartedAt = 0
        showTimer?.cancel()
        stillPaused = true
    }

    private fun resumeStill() {
        if (stillPaused) armShow(stillElapsed)
    }

    private fun seekStill(ms: Long) {
        val target = ms.coerceIn(0, slideMs())
        if (stillPaused) stillElapsed = target else armShow(target)
    }

    private fun showSlide(s: Show, index: Int, lastPage: Boolean) {
        s.index = index
        library.byHandle(s.handles[index])?.let { open(it, 0) }
            ?: fail(null, "A slide is missing on the board")
        preloadShowNext(s)
        armShow()
    }

    /**
     * Images advance on their own timer; media advances when it ends (see listener).
     * [elapsed] > 0 resumes mid-slide, so only the remaining time is waited.
     */
    private fun armShow(elapsed: Long = 0) {
        showTimer?.cancel()
        stillPaused = false
        stillStartedAt = 0
        stillElapsed = elapsed
        if (show == null || !showingStill()) return
        val remaining = (slideMs() - elapsed).coerceAtLeast(0)
        stillStartedAt = SystemClock.elapsedRealtime()
        showTimer = scope.launch(Dispatchers.Main) {
            delay(remaining)
            advance()
        }
    }

    /** Moves within the slideshow; wraps when looping. */
    private fun showStep(dir: Int, auto: Boolean) {
        val s = show ?: return
        val next = s.index + dir
        val index = when {
            next in s.handles.indices -> next
            s.loop -> if (dir > 0) 0 else s.handles.lastIndex
            auto -> {
                endShow() // finished: stay on the last slide
                publish()
                return
            }
            else -> return
        }
        showSlide(s, index, lastPage = dir < 0)
    }

    private fun advance() {
        showStep(+1, auto = true)
        publish()
    }

    private fun preloadShowNext(s: Show) {
        val next = (s.index + 1).let { if (it > s.handles.lastIndex) (if (s.loop) 0 else return) else it }
        preloadHandle(s.handles[next])
    }

    private fun step(dir: Int) {
        val handle = neighbour(dir) ?: return
        library.byHandle(handle)?.let { open(it, 0) }
    }

    private fun order(): List<Int> = queue.ifEmpty { library.items().map { it.handle } }

    private fun neighbour(dir: Int): Int? {
        val list = order()
        if (list.isEmpty()) return null
        val i = list.indexOf(current?.handle ?: -1)
        if (i < 0) return if (dir > 0) list.first() else list.last()
        return list.getOrNull(i + dir) ?: if (show?.loop == true) (if (dir > 0) list.first() else list.last()) else null
    }

    private fun preloadNext() {
        show?.let { return preloadShowNext(it) }
        preloadHandle(neighbour(+1) ?: return)
    }

    private fun preloadHandle(handle: Int) {
        if (handle == current?.handle) return
        val item = library.byHandle(handle) ?: return
        val file = library.fileOf(item.id) ?: return
        when (item.type) {
            ContentType.VIDEO -> if (standbyHandle != handle) {
                standby.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                standby.playWhenReady = false
                standby.prepare()
                standbyHandle = handle
            }
            ContentType.IMAGE -> preloadStill(item) { decodeImage(file) }
            else -> Unit
        }
    }

    private fun showStill(item: Item, load: () -> Bitmap?) {
        val key = "${item.id}"
        stills.get(key)?.let {
            _display.value = Display.Still(item, it)
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
                _display.value = Display.Still(item, bitmap)
            }
            publish()
        }
    }

    private fun preloadStill(item: Item, load: () -> Bitmap?) {
        val key = "${item.id}"
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
        // Never let one broken slide stop the show.
        if (show != null) {
            showTimer?.cancel()
            stillStartedAt = 0
            showTimer = scope.launch(Dispatchers.Main) {
                delay(1_500)
                advance()
            }
        }
    }

    // --- State --------------------------------------------------------------------------------

    private fun publish() {
        val item = current
        val d = _display.value
        val media = d is Display.Video
        val timedStill = d is Display.Still && showingStill()
        val status = when {
            item == null -> Status.IDLE
            d is Display.Failed -> Status.ERROR
            d.item?.id != item.id -> Status.LOADING
            d is Display.External -> Status.EXTERNAL
            d is Display.Still -> if (stillPaused) Status.PAUSED else Status.PLAYING
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
            positionMs = when {
                media -> active.currentPosition.coerceAtLeast(0)
                timedStill -> stillPosition()
                else -> 0
            },
            durationMs = when {
                media -> active.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: item?.durationMs ?: 0
                timedStill -> slideMs()
                else -> 0
            },
            volume = if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0,
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC),
            page = 0,
            pageCount = 0,
            manifestVersion = library.manifest.value.version,
            locked = locked.value,
            slideshow = show != null,
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
        .also {
            it.addListener(listener)
            it.addAnalyticsListener(analytics)
        }
}
