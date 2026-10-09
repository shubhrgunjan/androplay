package app.androplay.admin

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.androplay.AndroPlayApp
import app.androplay.MainActivity
import app.androplay.core.AdminAuth
import kotlinx.coroutines.launch

class AdminActivity : ComponentActivity() {
    private val app get() = application as AndroPlayApp

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.admin.restartBle()
    }

    /** Admin login gate; the dashboard and its background work start only once logged in. */
    private var loggedIn by mutableStateOf(false)
    /** Shares that arrived before login; added right after a successful login. */
    private val pendingShares = mutableListOf<Uri>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) add(uris)
    }

    private var lanHost: String? = null
    private val lanPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val host = lanHost
        if (uris.isNotEmpty() && !host.isNullOrBlank()) sendLan(uris, host)
    }

    /** MIME types we accept: images and videos only. */
    private val acceptedMimes = arrayOf("image/*", "video/*")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        loggedIn = AdminAuth.isLoggedIn(this)
        handleShare(intent)
        setContent {
            if (!loggedIn) {
                LoginScreen(onLoggedIn = ::onLoggedIn)
            } else {
                AdminScreen(
                    repo = app.admin,
                    onAdd = { picker.launch(acceptedMimes) },
                    onSendLan = { host ->
                        lanHost = host
                        lanPicker.launch(acceptedMimes)
                    },
                    onSettings = { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        loggedIn = AdminAuth.isLoggedIn(this) // may have changed (Settings → Log out)
        if (loggedIn) app.admin.setActive(true)
    }

    private fun onLoggedIn() {
        loggedIn = true
        app.admin.setActive(true)
        if (pendingShares.isNotEmpty()) {
            val uris = pendingShares.toList()
            pendingShares.clear()
            add(uris)
        }
    }

    override fun onStop() {
        app.admin.setActive(false)
        super.onStop()
    }

    private fun requestPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // Keep EXIF/location in uploaded photos & videos (MediaStore.setRequireOriginal).
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        if (perms.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            permissions.launch(perms.toTypedArray())
        }
    }

    /** "Share → AndroPlay" from Gallery, Files, Drive… */
    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        if (loggedIn) add(uris) else pendingShares += uris
    }

    private fun add(uris: List<Uri>) {
        lifecycleScope.launch {
            val result = app.admin.add(uris)
            val msg = when {
                result.added > 0 && result.rejected > 0 ->
                    "Uploading ${result.added} · skipped ${result.rejected} (videos and images only)"
                result.added > 0 ->
                    "Uploading ${result.added} file${if (result.added > 1) "s" else ""}"
                result.rejected > 0 ->
                    "Only videos and images can be uploaded"
                else -> "Already in the library"
            }
            Toast.makeText(this@AdminActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendLan(uris: List<Uri>, host: String) {
        lifecycleScope.launch {
            val result = app.admin.sendLan(uris, host)
            val msg = when {
                result.error != null && result.sent == 0 -> result.error
                result.sent > 0 && result.error != null -> "Sent ${result.sent}, then failed: ${result.error}"
                result.sent > 0 && result.rejected > 0 -> "Sent ${result.sent} · skipped ${result.rejected}"
                result.sent > 0 -> "Sent ${result.sent} file${if (result.sent > 1) "s" else ""} to the board"
                result.rejected > 0 -> "Only videos and images can be sent"
                else -> "Nothing to send"
            }
            Toast.makeText(this@AdminActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
