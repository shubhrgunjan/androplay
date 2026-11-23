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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.androplay.ui.LogsActivity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point: routes to the Board or Admin UI, or shows first-run setup (role).
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EDIT = "edit"
    }

    private val app get() = application as AndroPlayApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.settings
        val role = s.role
        if (!intent.getBooleanExtra(EXTRA_EDIT, false) && role != null) {
            route(role)
            return
        }
        val needsAuth = role != null && !AdminAuth.isLoggedIn(this)
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
        var setupCode by remember { mutableStateOf("") }
        var setupError by remember { mutableStateOf(false) }
        var editRestToken by remember { mutableStateOf(false) }
        var restToken by remember { mutableStateOf("") }
        val detectedBoard = looksLikeBoard()
        val scope = rememberCoroutineScope()
        var changeCredentials by remember { mutableStateOf(s.role == null) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var credentialError by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }

        fun choose(r: Role) {
            val chosen = if (setupCode.isBlank()) s.config else BackendConfig.fromCode(setupCode)
            val cfg = chosen?.let { if (editRestToken) it.copy(redisRestToken = restToken) else it }?.normalized()
            if (cfg == null || ((setupCode.isNotBlank() || editRestToken) && !cfg.isSecure)) {
                setupError = true
                return
            }
            if (!changeCredentials) {
                onSave(r, name.trim().ifEmpty { s.deviceName }, cfg)
                return
            }
            if (username.isBlank() || ':' in username || password.length < AdminAuth.MIN_PASSWORD_LENGTH) {
                credentialError = "Enter a username and a password with at least ${AdminAuth.MIN_PASSWORD_LENGTH} characters."
                return
            }
            if (saving) return
            saving = true
            val newUsername = username
            val newPassword = password
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { AdminAuth.setCredentials(this@MainActivity, newUsername, newPassword) }.getOrDefault(false)
                }
                saving = false
                if (saved) onSave(r, name.trim().ifEmpty { s.deviceName }, cfg)
                else credentialError = "Could not save credentials."
            }
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

                OutlinedTextField(
                    setupCode, { setupCode = it; setupError = false },
                    label = { Text("Setup code") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = setupError,
                    supportingText = { if (setupError) Text("Use a valid TLS setup code. An HTTPS fallback token requires an Upstash Redis endpoint.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editRestToken) {
                    OutlinedTextField(
                        restToken, { restToken = it; setupError = false },
                        label = { Text("Upstash REST token") },
                        supportingText = { Text("Use a token for the same database. Leave blank to disable HTTPS fallback.") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { editRestToken = true }) { Text("Configure Redis HTTPS fallback") }
                }
                if (changeCredentials) {
                    OutlinedTextField(
                        username, { username = it; credentialError = null },
                        label = { Text("Admin username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        password, { password = it; credentialError = null },
                        label = { Text("New admin password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    credentialError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else if (s.role != null) {
                    TextButton(onClick = { changeCredentials = true }) { Text("Change admin credentials") }
                }
                if (s.role != null) {
                    OutlinedButton(
                        onClick = { startActivity(Intent(this@MainActivity, LogsActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Activity logs") }
                }
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
