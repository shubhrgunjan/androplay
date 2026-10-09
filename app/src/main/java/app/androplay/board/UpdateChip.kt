package app.androplay.board

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androplay.AndroPlayApp
import app.androplay.core.UpdateState

/** Small update status pill on the idle board ("Downloading v0.8.0 40%", "Update failed: …"). */
@Composable
fun UpdateChip(app: AndroPlayApp, modifier: Modifier = Modifier) {
    val state by app.updater.state.collectAsState()
    if (state is UpdateState.UpToDate) return
    val text = app.updater.statusText() ?: return
    val failed = state is UpdateState.Failed
    Surface(modifier, color = Color(0xCC12161F), contentColor = Color.White, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (failed) Icons.Default.ErrorOutline else Icons.Default.SystemUpdate,
                null,
                tint = if (failed) Color(0xFFE5484D) else Color(0xFF8AB0FF),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 16.sp)
        }
    }
}
