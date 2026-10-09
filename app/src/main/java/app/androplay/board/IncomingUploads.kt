package app.androplay.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androplay.AndroPlayApp
import app.androplay.ui.Brand

/**
 * Compact overlay listing content an admin is uploading to this board right now, plus the
 * current download. Shown over both the idle screen and playing content; hidden when idle.
 */
@Composable
fun IncomingUploadsCard(app: AndroPlayApp, modifier: Modifier = Modifier) {
    val sync by app.syncStatus.collectAsState()
    val downloading = sync.downloading
    if (sync.incoming.isEmpty() && downloading == null) return
    val dim = Color.White.copy(alpha = 0.55f)

    Surface(
        modifier.width(380.dp),
        color = Color(0xE6121722),
        contentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            sync.incoming.take(MAX_SHOWN).forEach { u ->
                ProgressRow(Icons.Filled.CloudUpload, Brand.Accent, "Receiving", u.name, u.fraction)
            }
            if (sync.incoming.size > MAX_SHOWN) {
                Text("${sync.incoming.size - MAX_SHOWN} more uploading", color = dim, fontSize = 14.sp)
            }
            if (downloading != null) {
                ProgressRow(Icons.Filled.CloudDownload, Brand.BlueDark, "Downloading", downloading, sync.downloadProgress.coerceIn(0f, 1f))
                if (sync.pending > 1) Text("${sync.pending - 1} more queued", color = dim, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ProgressRow(icon: ImageVector, tint: Color, verb: String, name: String, fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("${(fraction * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
            Text(verb, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = tint,
                trackColor = Color.White.copy(alpha = 0.10f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

private const val MAX_SHOWN = 4
