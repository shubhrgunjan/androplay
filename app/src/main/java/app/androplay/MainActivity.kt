package app.androplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.androplay.admin.AdminActivity
import app.androplay.board.BoardActivity
import app.androplay.board.BoardService
import app.androplay.core.AdminAuth
import app.androplay.core.BackendConfig
import app.androplay.core.Role
import app.androplay.ui.PasswordDialog

/**
 * Entry point: routes to the Board or Admin UI, or shows first-run setup (role).
 * Provision headlessly with: adb shell am start -n app.androplay/.MainActivity --es config <setup code> --es role BOARD
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EDIT = "edit"
    }

    private val app get() = application as AndroPlayApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.settings
        intent.getStringExtra("config")?.let { BackendConfig.fromCode(it) }?.let { s.config = it }
        intent.getStringExtra("role")?.let { r -> runCatching { Role.valueOf(r.uppercase()) }.getOrNull() }?.let { s.role = it }

        val role = s.role
        if (!intent.getBooleanExtra(EXTRA_EDIT, false) && role != null && s.config.isComplete) {
            route(role)
            return
        }
        // Boards never see a login. Only an admin phone that isn't logged in must log in to change settings.
        val needsAuth = role == Role.ADMIN && !AdminAuth.isLoggedIn(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var unlocked by remember { mutableStateOf(!needsAuth) }
                    if (unlocked) {
                        SetupScreen(::save)
                    } else {
                        PasswordDialog(
                            title = "Admin login",
                            message = "Enter the admin username and password to change settings.",
                            onDismiss = { finish() },
                            onSuccess = { unlocked = true },
                        )
                    }
                }
            }
        }
    }

    private fun route(role: Role) {
        val target = if (role == Role.BOARD) BoardActivity::class.java else AdminActivity::class.java
        if (role == Role.BOARD) BoardService.start(this)
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun save(role: Role, name: String, config: BackendConfig) {
        val s = app.settings
        s.role = role
        s.deviceName = name
        s.config = config
        if (role == Role.BOARD) BoardService.restartSync(this)
        if (role == Role.ADMIN) stopService(Intent(this, BoardService::class.java))
        if (role == Role.BOARD) AdminAuth.setLoggedIn(this, false) // don't leave a board logged in as admin
        route(role)
    }

    private fun logOut() {
        AdminAuth.setLoggedIn(this, false)
        route(Role.ADMIN) // AdminActivity comes back on its login screen
    }

    @Composable
    private fun SetupScreen(onSave: (Role, String, BackendConfig) -> Unit) {
        val s = app.settings
        var name by remember { mutableStateOf(s.deviceName) }
        val cfg = if (s.config.isComplete) s.config.normalized() else BackendConfig.buildDefault.normalized()
        val detectedBoard = looksLikeBoard()

        fun choose(r: Role) {
            onSave(r, name.trim().ifEmpty { s.deviceName }, cfg)
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("AndroPlay", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                // One obvious choice, detected from the hardware; the other role stays one tap away.
                val primary = if (detectedBoard) Role.BOARD else Role.ADMIN
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (detectedBoard) "This device is a board" else "This device is an admin phone",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (detectedBoard) "Detected ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}. It will play content full-screen and sync automatically."
                            else "Upload videos and images, and control the board.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { choose(primary) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(if (detectedBoard) "Continue as board" else "Continue as admin", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                TextButton(onClick = { choose(if (detectedBoard) Role.ADMIN else Role.BOARD) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (detectedBoard) "Use this device as an admin phone instead" else "This device is a board")
                }

                OutlinedTextField(name, { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                if (s.role == Role.ADMIN && AdminAuth.isLoggedIn(this@MainActivity)) {
                    OutlinedButton(onClick = ::logOut, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
                }
            }
        }
    }

    /** LG CreateBoard, or any large screen without phone hardware. */
    private fun looksLikeBoard(): Boolean {
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        if (model.contains("TR3D", ignoreCase = true) || model.contains("CreateBoard", ignoreCase = true)) return true
        val large = resources.configuration.smallestScreenWidthDp >= 600
        return large && !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    }
}
