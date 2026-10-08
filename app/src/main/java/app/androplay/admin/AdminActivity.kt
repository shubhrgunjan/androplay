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
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.androplay.AndroPlayApp
import app.androplay.MainActivity
import kotlinx.coroutines.launch

class AdminActivity : ComponentActivity() {
    private val app get() = application as AndroPlayApp

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.admin.restartBle()
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) add(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        handleShare(intent)
        setContent {
            AdminScreen(
                repo = app.admin,
                onAdd = { picker.launch(arrayOf("*/*")) },
                onSettings = { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true)) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        app.admin.setActive(true)
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
        if (uris.isNotEmpty()) add(uris)
    }

    private fun add(uris: List<Uri>) {
        lifecycleScope.launch {
            val n = app.admin.add(uris)
            val msg = if (n == 0) "Already in the library" else "Uploading $n file${if (n > 1) "s" else ""}"
            Toast.makeText(this@AdminActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
