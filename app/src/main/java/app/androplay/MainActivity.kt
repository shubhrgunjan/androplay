package app.androplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.androplay.admin.AdminActivity
import app.androplay.board.BoardActivity
import app.androplay.board.BoardService
import app.androplay.core.AdminAuth
import app.androplay.core.Backend
import app.androplay.core.BackendConfig
import app.androplay.core.Role
import app.androplay.ui.PasswordDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point: routes to the Board or Admin UI, or shows first-run setup (role + backend).
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
        val scope = rememberCoroutineScope()
        var role by remember { mutableStateOf(s.role) }
        var name by remember { mutableStateOf(s.deviceName) }
        var cfg by remember { mutableStateOf(s.config) }
        var code by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }
        // Builds ship with the lab backend, so the only real question is "board or phone?".
        var showBackend by remember { mutableStateOf(!cfg.isComplete) }
        val detectedBoard = looksLikeBoard()

        fun choose(r: Role) {
            role = r
            if (cfg.isComplete) onSave(r, name.trim().ifEmpty { s.deviceName }, cfg.normalized()) else showBackend = true
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
                            else "Upload videos, images and PDFs, and control the board.",
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

                if (s.role == Role.ADMIN && AdminAuth.isLoggedIn(this@MainActivity)) {
                    OutlinedButton(onClick = ::logOut, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
                }

                TextButton(onClick = { showBackend = !showBackend }) {
                    Text(if (showBackend) "Hide backend settings" else "Backend settings")
                }
                if (showBackend) {
                    OutlinedTextField(name, { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(code, { code = it }, label = { Text("Setup code (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            BackendConfig.fromCode(code)?.let { cfg = it; status = "Setup code applied" } ?: run { status = "Invalid setup code" }
                        }) { Text("Apply") }
                    }
                    Field("Redis URL (rediss://default:password@host:port)", cfg.redisUrl) { cfg = cfg.copy(redisUrl = it) }
                    Field("Key prefix", cfg.prefix) { cfg = cfg.copy(prefix = it) }
                    Field("S3 endpoint", cfg.s3Endpoint) { cfg = cfg.copy(s3Endpoint = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Bucket", cfg.bucket, Modifier.weight(1f)) { cfg = cfg.copy(bucket = it) }
                        Field("Region", cfg.s3Region, Modifier.weight(1f)) { cfg = cfg.copy(s3Region = it) }
                    }
                    Field("Access key", cfg.accessKey) { cfg = cfg.copy(accessKey = it) }
                    Field("Secret key", cfg.secretKey, secret = true) { cfg = cfg.copy(secretKey = it) }
                    OutlinedButton(onClick = { cfg = BackendConfig.buildDefault; status = "Restored built-in backend" }) {
                        Text("Use built-in backend")
                    }

                    status?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = cfg.isComplete && !testing,
                            onClick = {
                                testing = true
                                status = "Testing…"
                                scope.launch {
                                    cfg = cfg.normalized()
                                    status = withContext(Dispatchers.IO) { test(cfg) }
                                    testing = false
                                }
                            },
                        ) { Text("Test") }
                        OutlinedButton(enabled = cfg.isComplete, onClick = {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AndroPlay setup", cfg.toCode()))
                            Toast.makeText(this@MainActivity, "Setup code copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy code") }
                        Button(
                            enabled = role != null && cfg.isComplete && name.isNotBlank(),
                            onClick = { onSave(role!!, name.trim(), cfg.normalized()) },
                        ) { Text("Save") }
                    }
                }
            }
        }
    }

    private fun test(cfg: BackendConfig): String {
        val redis = runCatching { Backend(cfg).use { it.ping() } }.exceptionOrNull()
        if (redis != null) return "Redis failed: ${redis.message}"
        val s3 = runCatching {
            app.newS3For(cfg).putObject(".androplay-check", "ok".encodeToByteArray(), "text/plain")
        }.exceptionOrNull()
        return if (s3 != null) "Redis OK · Storage failed: ${s3.message}" else "Redis OK · Storage OK"
    }

    /** LG CreateBoard, or any large screen without phone hardware. */
    private fun looksLikeBoard(): Boolean {
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        if (model.contains("TR3D", ignoreCase = true) || model.contains("CreateBoard", ignoreCase = true)) return true
        val large = resources.configuration.smallestScreenWidthDp >= 600
        return large && !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    }

    @Composable
    private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), secret: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
            modifier = modifier,
        )
    }
}
