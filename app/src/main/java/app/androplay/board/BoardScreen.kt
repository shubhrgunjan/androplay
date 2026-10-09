package app.androplay.board

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import app.androplay.AndroPlayApp
import app.androplay.board.PlayerEngine.Display
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.Item
import app.androplay.protocol.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

enum class SetupStep(val title: String, val why: String) {
    BLUETOOTH("Allow Bluetooth", "so phones in the room can control the board"),
    STORAGE("Allow file access", "to store synced content and play files already on the board"),
    OVERLAY("Allow display over other apps", "so remote commands can bring the player to the front"),
}

@Composable
fun BoardScreen(
    app: AndroPlayApp,
    playerView: PlayerView,
    setupFlow: StateFlow<List<SetupStep>>,
    onSetup: (SetupStep) -> Unit,
    showUnlockFlow: StateFlow<Boolean>,
    onDismissUnlock: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val display by app.engine.display.collectAsState()
    val libraryOpen by app.engine.libraryVisible.collectAsState()
    val setup by setupFlow.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

            when (val d = display) {
                is Display.Still -> Image(
                    bitmap = d.bitmap.asImageBitmap(),
                    contentDescription = d.item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                )
                is Display.Audio -> Message(d.item.name, "Playing audio")
                is Display.External -> Message(d.item.name, "Opened in another app. Press Stop on the remote to return.")
                is Display.Failed -> Message(d.item?.name ?: "Error", d.message)
                Display.Idle -> IdlePanel(app, onImport, onSettings)
                is Display.Video -> Unit
            }

            if (!libraryOpen && display !is Display.Idle) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { app.dispatch(Command.ToggleLibrary) }) })
            }
            if (libraryOpen && display !is Display.Idle) {
                Surface(Modifier.fillMaxSize(), color = Color(0xF00B0F14)) { LibraryPanel(app, onImport) }
            }
            IncomingUploadsCard(app, Modifier.align(Alignment.BottomEnd).padding(24.dp))
            if (setup.isNotEmpty()) SetupBanner(setup, onSetup, Modifier.align(Alignment.BottomCenter))
            LockLayer(app, showUnlockFlow.collectAsState().value, onDismissUnlock)
        }
    }
}

@Composable
private fun Message(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
    }
}

@Composable
private fun IdlePanel(app: AndroPlayApp, onImport: () -> Unit, onSettings: () -> Unit) {
    val sync by app.syncStatus.collectAsState()
    val ble by app.bleStatus.collectAsState()
    val manifest by app.store.manifest.collectAsState()
    val dim = Color.White.copy(alpha = 0.6f)

    Row(Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        Column(Modifier.width(400.dp).fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("AndroPlay", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(app.settings.deviceName, color = dim, fontSize = 22.sp)
            Text("Board ID  ${app.settings.deviceId}", color = dim, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            StatusLine(
                "Cloud",
                when {
                    sync.error != null -> sync.error!!
                    sync.connected -> "connected"
                    else -> "connecting…"
                },
                ok = sync.connected && sync.error == null,
            )
            val guard by app.perf.status.collectAsState()
            StatusLine(
                "Performance",
                when {
                    guard.lastReason == null -> "guard on"
                    else -> "guard on · last boost: ${guard.lastReason} · ${guard.appsCleared} apps cleared"
                },
                ok = !guard.quiet,
            )
            StatusLine(
                "Bluetooth",
                ble.error ?: if (ble.advertising) "ready · ${ble.clients} connected" else "starting…",
                ok = ble.advertising,
            )
            Text("${manifest.items.size} items on board · ${formatSize(manifest.freeBytes)} free", color = dim, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport) { Text("Import files") }
                OutlinedButton(onClick = onSettings) { Text("Settings") }
                OutlinedButton(onClick = { app.dispatch(Command.Lock(true)) }) { Text("Lock screen") }
            }
        }
        LibraryPanel(app, onImport = null)
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).background(if (ok) Color(0xFF3FB950) else Color(0xFFD29922), MaterialTheme.shapes.small))
        Text("  $label  ", color = Color.White, fontSize = 20.sp)
        Text(value, color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LibraryPanel(app: AndroPlayApp, onImport: (() -> Unit)?) {
    val manifest by app.store.manifest.collectAsState()
    val state by app.engine.state.collectAsState()
    var filter by remember { mutableStateOf<ContentType?>(null) }
    val items = manifest.items.filter { filter == null || it.type == filter }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { app.sync?.requestRescan() }) { Text("Rescan") }
            onImport?.let { TextButton(onClick = it) { Text("Import") } }
            if (state.handle != PlaybackState.NO_HANDLE) {
                Button(onClick = { app.dispatch(Command.ToggleLibrary) }) { Text("Back to player") }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${manifest.items.size}") })
            ContentType.entries.forEach { t ->
                val n = manifest.items.count { it.type == t }
                if (n > 0) FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text("${t.folder} $n") })
            }
        }
        val shows by app.boardShows.collectAsState()
        if (shows.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Slideshows", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                shows.forEach { show ->
                    val handles = show.items.mapNotNull { app.store.item(it)?.handle }
                    Button(
                        enabled = handles.isNotEmpty(),
                        onClick = { app.dispatch(Command.Slideshow(handles, show.imageSeconds, show.loop)) },
                    ) { Text("▶ ${show.name} (${handles.size})") }
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                "Nothing here yet. Upload from the AndroPlay admin app, or put files in the board's Movies, Pictures, Music, Documents or Download folders.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                Card(
                    Modifier.clickable {
                        app.dispatch(Command.SetQueue(items.map { it.handle }))
                        app.dispatch(Command.Play(item.handle))
                    },
                ) {
                    Thumb(app, item)
                    Text(
                        item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumb(app: AndroPlayApp, item: Item) {
    val bitmap by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            app.thumbnails.file(item)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(Color(0xFF1C2128)), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                item.name.substringAfterLast('.', item.type.name).uppercase(),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SetupBanner(steps: List<SetupStep>, onSetup: (SetupStep) -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().padding(16.dp), color = Color(0xFF3D2E00), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setup", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${step.title}: ${step.why}", color = Color.White, modifier = Modifier.weight(1f))
                    Button(onClick = { onSetup(step) }) { Text("Fix") }
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
