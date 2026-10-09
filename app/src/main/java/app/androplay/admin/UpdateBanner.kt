package app.androplay.admin

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androplay.BuildConfig
import app.androplay.core.UpdateState
import kotlinx.coroutines.launch

/** "Update available" card at the top of the admin dashboard. Hidden when up to date. */
@Composable
fun UpdateBanner(repo: AdminRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repo.updater.state.collectAsState()
    if (state is UpdateState.UpToDate) return

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val s = state) {
                is UpdateState.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Update available: v${s.release.versionName}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "You have v${BuildConfig.VERSION_NAME}" + if (s.release.notes.isNotBlank()) " · ${s.release.notes}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = {
                        if (!repo.updater.canInstall()) {
                            // One-time: Android must allow AndroPlay to install updates.
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
                            )
                        } else {
                            scope.launch { repo.updater.install(s.release) }
                        }
                    }) { Text("Update") }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading v${s.release.versionName}… ${(s.progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> Text("Installing v${s.release.versionName}… the app will restart")
                is UpdateState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Update failed: ${s.message}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch { repo.updater.check() } }) { Text("Retry") }
                }
                UpdateState.UpToDate -> Unit
            }
        }
    }
}
