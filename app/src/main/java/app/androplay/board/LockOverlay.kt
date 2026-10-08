package app.androplay.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.androplay.AndroPlayApp
import app.androplay.protocol.Command
import app.androplay.ui.PasswordDialog

/**
 * Unlock prompt (the lock itself is invisible). Touches on the activity window are swallowed by BoardActivity
 * while locked; the password dialog is its own window, so it still receives touches.
 */
@Composable
fun LockLayer(app: AndroPlayApp, showUnlock: Boolean, onDismissUnlock: () -> Unit) {
    val locked by app.engine.locked.collectAsState()

    // Unlocked from a phone while the prompt was open: close it.
    LaunchedEffect(locked, showUnlock) { if (!locked && showUnlock) onDismissUnlock() }

    // No on-screen badge: the audience never sees that the board is locked.
    if (locked) {
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
