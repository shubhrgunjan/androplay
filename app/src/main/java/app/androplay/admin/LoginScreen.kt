package app.androplay.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import app.androplay.ui.AndroPlayTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.androplay.core.AdminAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen admin login shown before the dashboard. Calls [onLoggedIn] after valid credentials. */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showPass by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    fun submit() {
        if (checking) return
        if (user.isBlank() || pass.isEmpty()) {
            error = "Enter username and password"
            return
        }
        checking = true
        val username = user
        val password = pass
        scope.launch {
            val valid = withContext(Dispatchers.Default) { AdminAuth.check(ctx, username, password) }
            checking = false
            if (valid) {
                focus.clearFocus()
                AdminAuth.setLoggedIn(ctx, true)
                onLoggedIn()
            } else {
                error = "Wrong username or password"
                pass = ""
            }
        }
    }

    AndroPlayTheme {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.background, scheme.background))),
        ) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Brand header
                Box(
                    Modifier.size(76.dp).shadow(12.dp, RoundedCornerShape(24.dp)).background(scheme.primary, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = scheme.onPrimary, modifier = Modifier.size(46.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("AndroPlay", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Control every board from your phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Admin login", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it; error = null },
                            label = { Text("Username") },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it; error = null },
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            trailingIcon = {
                                IconButton(onClick = { showPass = !showPass }) {
                                    Icon(
                                        if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        if (showPass) "Hide password" else "Show password",
                                    )
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            isError = error != null,
                            supportingText = error?.let { msg -> { Text(msg) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { submit() },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) { Text("Log in", style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        }
    }
}
