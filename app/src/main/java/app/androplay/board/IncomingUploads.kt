package app.androplay.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androplay.AndroPlayApp

/**
 * Compact overlay listing content an admin is uploading to this board right now, plus the
 * current download. Shown over both the idle screen and playing content; hidden when idle.
 */
@Composable
fun IncomingUploadsCard(app: AndroPlayApp, modifier: Modifier = Modifier) {
    val sync by app.syncStatus.collectAsState()
    val downloading = sync.downloading
    if (sync.incoming.isEmpty() && downloading == null) return
    val dim = Color.White.copy(alpha = 0.6f)

    Surface(
        modifier.width(360.dp),
        color = Color(0xCC0B0F14),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sync.incoming.take(MAX_SHOWN).forEach { u ->
                Text(
                    "Receiving ${u.name} — ${(u.fraction * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth())
            }
            if (sync.incoming.size > MAX_SHOWN) {
                Text("${sync.incoming.size - MAX_SHOWN} more uploading", color = dim, fontSize = 14.sp)
            }
            if (downloading != null) {
                Text(
                    "Downloading $downloading — ${(sync.downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(progress = { sync.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (sync.pending > 1) Text("${sync.pending - 1} more queued", color = dim, fontSize = 14.sp)
            }
        }
    }
}

private const val MAX_SHOWN = 4
