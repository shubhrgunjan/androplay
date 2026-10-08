package app.androplay.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androplay.AndroPlayApp
import app.androplay.protocol.Command
import app.androplay.ui.PasswordDialog

/**
 * Lock indicator + unlock prompt. Touches on the activity window are swallowed by BoardActivity
 * while locked; the password dialog is its own window, so it still receives touches.
 */
@Composable
fun LockLayer(app: AndroPlayApp, showUnlock: Boolean, onDismissUnlock: () -> Unit) {
    val locked by app.engine.locked.collectAsState()

    // Unlocked from a phone while the prompt was open: close it.
    LaunchedEffect(locked, showUnlock) { if (!locked && showUnlock) onDismissUnlock() }

    if (locked) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "🔒 Locked",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (showUnlock) {
            PasswordDialog(
                title = "Unlock board",
                message = "Enter the admin login to unlock the touchscreen.",
                onDismiss = onDismissUnlock,
                onSuccess = {
                    app.dispatch(Command.Lock(false))
                    onDismissUnlock()
                },
            )
        }
    }
}
