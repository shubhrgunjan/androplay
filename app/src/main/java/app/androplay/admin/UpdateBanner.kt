package app.androplay.admin

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val failed = state is UpdateState.Failed
            Box(
                Modifier.size(36.dp).background(
                    if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (failed) Icons.Default.ErrorOutline else Icons.Default.SystemUpdate,
                    null,
                    tint = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val s = state) {
                    is UpdateState.Available -> {
                        Text("Update available · v${s.release.versionName}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "You have v${BuildConfig.VERSION_NAME}" + if (s.release.notes.isNotBlank()) " · ${s.release.notes}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    is UpdateState.Downloading -> {
                        Text(
                            "Downloading v${s.release.versionName}… ${(s.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Confirming -> {
                        Text("Confirm v${s.release.versionName}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("Tap Update in Android's prompt to finish", style = MaterialTheme.typography.labelSmall)
                    }
                    is UpdateState.Installing -> {
                        Text("Installing v${s.release.versionName}…", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("The app will restart", style = MaterialTheme.typography.labelSmall)
                    }
                    is UpdateState.Failed -> {
                        Text("Update failed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(s.message, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    UpdateState.UpToDate -> Unit
                }
            }
            when (val s = state) {
                is UpdateState.Available -> {
                    Spacer(Modifier.width(8.dp))
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
                is UpdateState.Failed -> {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        scope.launch {
                            if (s.release != null) repo.updater.install(s.release)
                            else repo.updater.check()
                        }
                    }) { Text("Retry") }
                }
                else -> Unit
            }
        }
    }
}
