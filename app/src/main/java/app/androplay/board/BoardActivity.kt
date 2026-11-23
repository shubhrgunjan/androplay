package app.androplay.board

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.androplay.AndroPlayApp
import app.androplay.MainActivity
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen player for the Board role. Never exits on Back; remotes and phones drive it. */
@OptIn(UnstableApi::class)
class BoardActivity : ComponentActivity() {
    companion object {
        @Volatile
        var visible = false
    }

    private val app get() = application as AndroPlayApp
    private lateinit var playerView: PlayerView
    private val setup = MutableStateFlow<List<SetupStep>>(emptyList())

    /** Unlock password prompt is visible (opened by the unlock gesture while locked). */
    private val showUnlock = MutableStateFlow(false)
    private val handler = Handler(Looper.getMainLooper())
    private val cornerTaps = ArrayDeque<Long>()
    private var multiTouchArmed = false
    private val requestUnlock = Runnable {
        multiTouchArmed = false
        if (app.engine.locked.value) showUnlock.value = true
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        BoardService.restartBle(this)
        refreshSetup()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) import(uris)
    }

    /** MIME types we accept: images and videos only. */
    private val acceptedMimes = arrayOf("image/*", "video/*")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Stable clocks instead of bursts that throttle mid-presentation (if the SoC supports it).
        if (getSystemService(android.os.PowerManager::class.java)?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        BoardService.start(this)

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            setBackgroundColor(Color.BLACK)
        }
        app.engine.view = playerView

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!app.engine.locked.value) app.dispatch(Command.ToggleLibrary)
            }
        })

        setContent {
            BoardScreen(
                app = app,
                playerView = playerView,
                setupFlow = setup,
                onSetup = ::fixSetup,
                showUnlockFlow = showUnlock,
                onDismissUnlock = { showUnlock.value = false },
                onImport = { importLauncher.launch(acceptedMimes) },
                onSettings = {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true))
                },
            )
        }
        observeLock()
    }

    /** Pins the task (if allowed by device policy) and keeps system bars hidden while locked. */
    private fun observeLock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.engine.locked.collect { locked -> applyLock(locked) }
            }
        }
    }

    private fun applyLock(locked: Boolean) {
        val am = getSystemService(ActivityManager::class.java)
        val pinned = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (locked) {
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            val permitted = runCatching {
                getSystemService(DevicePolicyManager::class.java).isLockTaskPermitted(packageName)
            }.getOrDefault(false)
            if (permitted && !pinned) runCatching { startLockTask() }
        } else {
            showUnlock.value = false
            handler.removeCallbacks(requestUnlock)
            multiTouchArmed = false
            if (pinned) runCatching { stopLockTask() }
        }
    }

    /**
     * While locked every touch on the board window is swallowed, except the unlock gestures:
     * hold 3+ fingers for 2 s anywhere, or tap the top-left corner 5 times within 3 s.
     * The password dialog is a separate window, so its touches never pass through here.
     * Key events (Bluetooth remotes) go through [dispatchKeyEvent] and are unaffected.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!app.engine.locked.value) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corner = 120 * resources.displayMetrics.density
                if (ev.x <= corner && ev.y <= corner) {
                    val now = SystemClock.uptimeMillis()
                    cornerTaps.addLast(now)
                    while (cornerTaps.isNotEmpty() && now - cornerTaps.first() > 3_000) cornerTaps.removeFirst()
                    if (cornerTaps.size >= 5) {
                        cornerTaps.clear()
                        showUnlock.value = true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 3 && !multiTouchArmed) {
                multiTouchArmed = true
                handler.postDelayed(requestUnlock, 2_000)
            }
            MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount - 1 < 3) cancelMultiTouch()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelMultiTouch()
        }
        return true
    }

    private fun cancelMultiTouch() {
        multiTouchArmed = false
        handler.removeCallbacks(requestUnlock)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onResume() {
        super.onResume()
        // Full physical panel (incl. system-bar areas) so stills decode at native 4K resolution.
        app.engine.screen = if (android.os.Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.let { Size(it.width(), it.height()) }
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { Size(it.widthPixels, it.heightPixels) }
        }
        val hadStorage = SetupStep.STORAGE !in setup.value
        refreshSetup()
        if (!hadStorage && SetupStep.STORAGE !in setup.value) app.sync?.requestRescan()
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(requestUnlock)
        if (app.engine.view === playerView) app.engine.view = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cmd = keyCommand(event.keyCode)
        if (cmd != null) {
            // A held button should only repeat seeking, so it can't skip items or toggle playback.
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || cmd is Command.SeekBy)) {
                app.dispatch(cmd)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Bluetooth remotes, presenter clickers and keyboards. */
    private fun keyCommand(code: Int): Command? {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> return Command.Toggle
            KeyEvent.KEYCODE_MEDIA_PLAY -> return Command.Resume
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return Command.Pause
            KeyEvent.KEYCODE_MEDIA_STOP -> return Command.Stop
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> return Command.Next
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> return Command.Prev
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return Command.SeekBy(10_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> return Command.SeekBy(-10_000)
            KeyEvent.KEYCODE_MENU -> return Command.ToggleLibrary
        }
        if (app.engine.libraryVisible.value) return null // D-pad navigates the grid
        return when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> Command.Next
            KeyEvent.KEYCODE_DPAD_LEFT -> Command.Prev
            KeyEvent.KEYCODE_DPAD_UP -> Command.SeekBy(10_000)
            KeyEvent.KEYCODE_DPAD_DOWN -> Command.SeekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> Command.Toggle
            else -> null
        }
    }

    private fun refreshSetup() {
        setup.value = buildList {
            val ble = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            if (ble.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) add(SetupStep.BLUETOOTH)
            if (!Environment.isExternalStorageManager()) add(SetupStep.STORAGE)
            if (!Settings.canDrawOverlays(this@BoardActivity)) add(SetupStep.OVERLAY)
            if (!packageManager.canRequestPackageInstalls()) add(SetupStep.INSTALL)
        }
    }

    private fun fixSetup(step: SetupStep) {
        val pkg = Uri.parse("package:$packageName")
        when (step) {
            SetupStep.BLUETOOTH -> permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
            SetupStep.STORAGE -> openSettings(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
            SetupStep.OVERLAY -> openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            SetupStep.INSTALL -> openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
        }
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Open Settings > Apps > AndroPlay to grant this", Toast.LENGTH_LONG).show()
        }
    }

    /** Copies files picked on the board (USB stick, Drive…) into AndroPlay; sync registers them. */
    private fun import(uris: List<Uri>) {
        lifecycleScope.launch {
            val (count, skipped) = withContext(Dispatchers.IO) {
                var imported = 0
                var rejected = 0
                for (uri in uris) {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "import"
                    val mime = contentResolver.getType(uri)
                    if (ContentType.of(name, mime) !in setOf(ContentType.IMAGE, ContentType.VIDEO)) {
                        rejected++
                        continue
                    }
                    val ok = runCatching {
                        val dest = app.store.destinationFor(name)
                        contentResolver.openInputStream(uri)!!.use { input ->
                            dest.outputStream().use { input.copyTo(it, 256 * 1024) }
                        }
                    }.isSuccess
                    if (ok) imported++
                }
                imported to rejected
            }
            app.sync?.requestRescan()
            val msg = when {
                skipped > 0 && count == 0 -> "Only videos and images can be imported"
                skipped > 0 -> "Imported $count · skipped $skipped (videos and images only)"
                else -> "Imported $count of ${uris.size}"
            }
            Toast.makeText(this@BoardActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
