package app.androplay.board

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import app.androplay.AndroPlayApp
import app.androplay.protocol.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GuardStatus(
    val presenting: Boolean = false,
    val quiet: Boolean = false,
    val lastBoostAt: Long = 0,
    val lastReason: String? = null,
    val appsCleared: Int = 0,
    val boosts: Int = 0,
)

/**
 * Keeps presentations smooth on the board.
 *
 * Detects lag (dropped video frames, playback stalls, UI jank, memory pressure) and reacts:
 * clears other apps' background processes, frees our caches, and silences our own background
 * work (downloads) for a while. Before every Play it pre-emptively clears background apps.
 *
 * Limits (Android, no root): foreground services, system apps and system processes can't be
 * killed by an app; use the adb cleanup in the README for vendor bloat.
 */
class PerformanceGuard(private val app: AndroPlayApp) {
    private val am = app.getSystemService(ActivityManager::class.java)
    private val _status = MutableStateFlow(GuardStatus())
    val status: StateFlow<GuardStatus> = _status.asStateFlow()

    @Volatile private var quietUntil = 0L
    @Volatile private var lastBoost = 0L
    @Volatile private var lastPrepare = 0L
    private var started = false
    private var memoryJob: Job? = null

    /** Our own background work must stop entirely (lag was detected recently). */
    val quiet: Boolean get() = SystemClock.elapsedRealtime() < quietUntil

    /** Something is on screen and moving: background work should be gentle. */
    val presenting: Boolean get() = _status.value.presenting

    fun start() {
        if (started) return
        started = true
        app.engine.onDroppedFrames = ::onDroppedFrames
        app.engine.onStall = ::onStall
        app.scope.launch(Dispatchers.Main) {
            app.engine.state.map { it.status == Status.PLAYING || it.status == Status.LOADING }
                .distinctUntilChanged()
                .collect { p ->
                    _status.value = _status.value.copy(presenting = p)
                    if (p) startMonitors() else stopMonitors()
                }
        }
    }

    /** Called right before content starts: clear the deck without delaying playback. */
    fun prepareForPresentation() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrepare < PREPARE_INTERVAL_MS) return
        lastPrepare = now
        app.scope.launch(Dispatchers.IO) { clearBackgroundApps() }
    }

    fun onDroppedFrames(count: Int, elapsedMs: Long) {
        // ExoPlayer reports in batches; >10 drops in a batch is visible stutter.
        if (count >= 10) boost("dropped $count video frames")
    }

    fun onStall() = boost("playback stalled")

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) boost("memory low (level $level)")
    }

    /** React to lag. Rate-limited so we never thrash. */
    fun boost(reason: String) {
        val now = SystemClock.elapsedRealtime()
        quietUntil = now + QUIET_MS
        _status.value = _status.value.copy(quiet = true)
        if (now - lastBoost < BOOST_INTERVAL_MS) return
        lastBoost = now
        Log.i(TAG, "Boost: $reason")
        app.scope.launch(Dispatchers.Main) { app.engine.trimCaches() }
        app.scope.launch(Dispatchers.IO) {
            val cleared = clearBackgroundApps()
            _status.value = _status.value.copy(
                lastBoostAt = System.currentTimeMillis(),
                lastReason = reason,
                appsCleared = cleared,
                boosts = _status.value.boosts + 1,
            )
            delay(QUIET_MS)
            if (!quiet) _status.value = _status.value.copy(quiet = false)
        }
    }

    /** Kills cached/background processes of every other non-critical app. Returns how many apps. */
    private suspend fun clearBackgroundApps(): Int = withContext(Dispatchers.IO) {
        val protected = protectedPackages()
        val pm = app.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        var n = 0
        for (info in apps) {
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (info.packageName in protected || (system && !updatedSystem)) continue
            runCatching { am.killBackgroundProcesses(info.packageName) }.onSuccess { n++ }
        }
        n
    }

    private fun protectedPackages(): Set<String> {
        val pm = app.packageManager
        val home = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        val ime = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        return setOfNotNull(app.packageName, home, ime, "com.google.android.gms", "com.android.vending")
    }

    // --- Monitors (only while presenting) -----------------------------------------------------

    private var frameCallback: Choreographer.FrameCallback? = null

    private fun startMonitors() {
        // UI jank: count frames that took more than 2.5 vsyncs within a 2 s window.
        var last = 0L
        var windowStart = 0L
        var janky = 0
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (last != 0L && frameTimeNanos - last > JANK_NANOS) janky++
                if (windowStart == 0L) windowStart = frameTimeNanos
                if (frameTimeNanos - windowStart > 2_000_000_000L) {
                    if (janky >= 8) boost("UI janky ($janky slow frames in 2 s)")
                    janky = 0
                    windowStart = frameTimeNanos
                }
                last = frameTimeNanos
                if (frameCallback === this) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)

        memoryJob?.cancel()
        memoryJob = app.scope.launch(Dispatchers.Default) {
            val info = ActivityManager.MemoryInfo()
            while (true) {
                am.getMemoryInfo(info)
                if (info.lowMemory || info.availMem < info.threshold + LOW_MEMORY_MARGIN) boost("memory low")
                delay(3_000)
            }
        }
    }

    private fun stopMonitors() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        memoryJob?.cancel()
        memoryJob = null
    }

    private companion object {
        const val TAG = "PerformanceGuard"
        const val QUIET_MS = 90_000L
        const val BOOST_INTERVAL_MS = 20_000L
        const val PREPARE_INTERVAL_MS = 120_000L
        const val JANK_NANOS = 42_000_000L // ~2.5 frames at 60 Hz
        const val LOW_MEMORY_MARGIN = 150L * 1024 * 1024
    }
}
