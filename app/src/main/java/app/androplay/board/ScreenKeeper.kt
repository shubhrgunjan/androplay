package app.androplay.board

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import app.androplay.AndroPlayApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The board never sleeps or shows a screen saver while it is locked or showing content.
 *
 * Layers (each works on its own):
 *  1. A screen-bright wake lock: keeps the panel on even when a document is open in another
 *     app, and wakes the screen if a Play arrives while it is off.
 *  2. With `WRITE_SECURE_SETTINGS` (adb grant): turns Android's screen saver (Daydream) off.
 *  3. With `WRITE_SETTINGS` (adb appops): stretches the screen-off timeout.
 * Original settings are saved and restored when the board goes idle again, including after a crash.
 */
class ScreenKeeper(private val app: AndroPlayApp) {
    private val prefs = app.getSharedPreferences("screen-keeper", Context.MODE_PRIVATE)
    private val power = app.getSystemService(PowerManager::class.java)

    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK is deprecated but still the only way from a service
    private val wakeLock = power.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "androplay:presenting",
    ).apply { setReferenceCounted(false) }

    private var started = false

    fun start() {
        if (started) return
        started = true
        restoreSettings() // undo anything left over from a crash
        app.scope.launch(Dispatchers.Main) {
            combine(app.engine.locked, app.engine.display) { locked, display -> locked || display !is PlayerEngine.Display.Idle }
                .distinctUntilChanged()
                .collect { keepAwake -> if (keepAwake) hold() else release() }
        }
    }

    private fun hold() {
        if (!wakeLock.isHeld) wakeLock.acquire()
        overrideSettings()
    }

    private fun release() {
        if (wakeLock.isHeld) wakeLock.release()
        restoreSettings()
    }

    private fun overrideSettings() {
        val cr = app.contentResolver
        if (canWriteSecure() && !prefs.contains(KEY_SCREENSAVER)) {
            runCatching {
                prefs.edit().putInt(KEY_SCREENSAVER, Settings.Secure.getInt(cr, SCREENSAVER_ENABLED, 1)).commit()
                Settings.Secure.putInt(cr, SCREENSAVER_ENABLED, 0)
            }.onFailure { Log.w(TAG, "Could not disable screen saver", it) }
        }
        if (Settings.System.canWrite(app) && !prefs.contains(KEY_TIMEOUT)) {
            runCatching {
                prefs.edit().putInt(KEY_TIMEOUT, Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 600_000)).commit()
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
            }.onFailure { Log.w(TAG, "Could not extend screen timeout", it) }
        }
    }

    private fun restoreSettings() {
        val cr = app.contentResolver
        if (prefs.contains(KEY_SCREENSAVER) && canWriteSecure()) {
            runCatching { Settings.Secure.putInt(cr, SCREENSAVER_ENABLED, prefs.getInt(KEY_SCREENSAVER, 1)) }
            prefs.edit().remove(KEY_SCREENSAVER).apply()
        }
        if (prefs.contains(KEY_TIMEOUT) && Settings.System.canWrite(app)) {
            runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, prefs.getInt(KEY_TIMEOUT, 600_000)) }
            prefs.edit().remove(KEY_TIMEOUT).apply()
        }
    }

    private fun canWriteSecure() =
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "ScreenKeeper"
        const val SCREENSAVER_ENABLED = "screensaver_enabled" // Settings.Secure.SCREENSAVER_ENABLED (hidden)
        const val KEY_SCREENSAVER = "screensaver"
        const val KEY_TIMEOUT = "timeout"
    }
}
