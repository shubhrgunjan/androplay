package app.androplay.board

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Size
import android.view.KeyEvent
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.androplay.AndroPlayApp
import app.androplay.MainActivity
import app.androplay.protocol.Command
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        BoardService.restartBle(this)
        refreshSetup()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) import(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            override fun handleOnBackPressed() = app.dispatch(Command.ToggleLibrary)
        })

        setContent {
            BoardScreen(
                app = app,
                playerView = playerView,
                setupFlow = setup,
                onSetup = ::fixSetup,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onSettings = {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true))
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onResume() {
        super.onResume()
        val metrics = resources.displayMetrics
        app.engine.screen = Size(metrics.widthPixels, metrics.heightPixels)
        val hadStorage = SetupStep.STORAGE !in setup.value
        refreshSetup()
        if (!hadStorage && SetupStep.STORAGE !in setup.value) app.sync?.requestRescan()
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (app.engine.view === playerView) app.engine.view = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cmd = keyCommand(event.keyCode)
        if (cmd != null) {
            if (event.action == KeyEvent.ACTION_DOWN) app.dispatch(cmd)
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
            val count = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    runCatching {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "import"
                        val dest = app.store.destinationFor(name)
                        contentResolver.openInputStream(uri)!!.use { input ->
                            dest.outputStream().use { input.copyTo(it, 256 * 1024) }
                        }
                    }.isSuccess
                }
            }
            app.sync?.requestRescan()
            Toast.makeText(this@BoardActivity, "Imported $count of ${uris.size}", Toast.LENGTH_SHORT).show()
        }
    }
}
