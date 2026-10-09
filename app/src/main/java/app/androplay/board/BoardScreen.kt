package app.androplay.board

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import app.androplay.AndroPlayApp
import app.androplay.board.PlayerEngine.Display
import app.androplay.core.flatten
import app.androplay.core.playAllSlides
import app.androplay.core.slideshowCommand
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.Item
import app.androplay.protocol.PlaybackState
import app.androplay.ui.AndroPlayTheme
import app.androplay.ui.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

enum class SetupStep(val title: String, val why: String) {
    BLUETOOTH("Allow Bluetooth", "so phones in the room can control the board"),
    STORAGE("Allow file access", "to store synced content and play files already on the board"),
    OVERLAY("Allow display over other apps", "so remote commands can bring the player to the front"),
    INSTALL("Allow app updates", "so the board can update itself when idle"),
}

/** Board palette: near-black surfaces with soft white text layers. */
private object Board {
    val Bg = Color(0xFF07090D)
    val BgTop = Color(0xFF0F1522)
    val Surface = Color(0xFF141924)
    val Overlay = Color(0xF207090D)
    val Text = Color.White
    val TextDim = Color.White.copy(alpha = 0.62f)
    val TextFaint = Color.White.copy(alpha = 0.40f)
    val Glass = Color.White.copy(alpha = 0.08f)
    val GlassBorder = Color.White.copy(alpha = 0.10f)
    val Tile = RoundedCornerShape(16.dp)
    val Card = RoundedCornerShape(22.dp)
    val background = Brush.verticalGradient(listOf(BgTop, Bg))
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

    AndroPlayTheme(dark = true) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

            when (val d = display) {
                is Display.Still -> Image(
                    bitmap = d.bitmap.asImageBitmap(),
                    contentDescription = d.item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                )
                is Display.External -> Message(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    Brand.BlueDark,
                    d.item.name,
                    "Opened in another app. Press Stop on the remote to return.",
                )
                is Display.Failed -> Message(Icons.Filled.ErrorOutline, Brand.Danger, d.item?.name ?: "Something went wrong", d.message)
                Display.Idle -> IdlePanel(app, onImport, onSettings)
                is Display.Video -> Unit
            }

            if (!libraryOpen && display !is Display.Idle) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { app.dispatch(Command.ToggleLibrary) }) })
            }
            if (libraryOpen && display !is Display.Idle) {
                Box(Modifier.fillMaxSize().background(Board.Overlay)) { LibraryOverlay(app) }
            }
            IncomingUploadsCard(app, Modifier.align(Alignment.BottomEnd).padding(24.dp))
            if (display is Display.Idle) UpdateChip(app, Modifier.align(Alignment.BottomStart).padding(24.dp))
            if (setup.isNotEmpty()) SetupBanner(setup, onSetup, Modifier.align(Alignment.BottomCenter))
            LockLayer(app, showUnlockFlow.collectAsState().value, onDismissUnlock)
        }
    }
}

// ---------------------------------------------------------------- message screens

@Composable
private fun Message(icon: ImageVector, tint: Color, title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().background(Board.background).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(128.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text(
            title,
            color = Board.Text,
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Text(detail, color = Board.TextDim, fontSize = 22.sp, textAlign = TextAlign.Center, lineHeight = 30.sp)
    }
}

// ---------------------------------------------------------------- idle

@Composable
private fun IdlePanel(app: AndroPlayApp, onImport: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Board.background).padding(horizontal = 48.dp, vertical = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(18.dp))
            Column {
                Text("AndroPlay", color = Board.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(app.settings.deviceName, color = Board.TextFaint, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            ConnectionPill(app)
            Spacer(Modifier.width(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Filled.Upload, "Import files", onImport)
                RoundIconButton(Icons.Filled.Refresh, "Rescan") { app.sync?.requestRescan() }
                RoundIconButton(Icons.Filled.Lock, "Lock screen") { app.dispatch(Command.Lock(true)) }
                RoundIconButton(Icons.Filled.Settings, "Settings", onSettings)
            }
        }
        Spacer(Modifier.height(36.dp))
        LibraryContent(app, Modifier.weight(1f))
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Brand.Blue, Color(0xFF6A4BFF)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun ConnectionPill(app: AndroPlayApp) {
    val sync by app.syncStatus.collectAsState()
    val (label, color) = when {
        sync.error != null -> "Offline" to Brand.Danger
        sync.connected -> "Connected" to Brand.Success
        else -> "Connecting…" to Brand.Warning
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Board.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Board.Glass)
            .border(1.dp, Board.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Board.Text, modifier = Modifier.size(26.dp))
    }
}

// ---------------------------------------------------------------- overlay library

@Composable
private fun LibraryOverlay(app: AndroPlayApp) {
    val state by app.engine.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", color = Board.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            RoundIconButton(Icons.Filled.Refresh, "Rescan") { app.sync?.requestRescan() }
            if (state.handle != PlaybackState.NO_HANDLE) {
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = { app.dispatch(Command.ToggleLibrary) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Blue, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Back to player", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        LibraryContent(app, Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- library content

@Composable
private fun LibraryContent(app: AndroPlayApp, modifier: Modifier = Modifier) {
    val manifest by app.store.manifest.collectAsState()
    val state by app.engine.state.collectAsState()
    val shows by app.boardShows.collectAsState()
    var filter by remember { mutableStateOf<ContentType?>(null) }
    val items = manifest.items.filter { filter == null || it.type == filter }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(260.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        if (shows.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "shows") {
                Column {
                    SectionTitle("Slideshows")
                    Spacer(Modifier.height(14.dp))
                    SlideshowRow(app, shows)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
            Column {
                SectionTitle("Library")
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { TypeChip(Icons.Filled.Apps, "All", manifest.items.size, filter == null) { filter = null } }
                    ContentType.entries.forEach { t ->
                        val n = manifest.items.count { it.type == t }
                        if (n > 0) item(key = t.name) { TypeChip(typeIcon(t), t.folder, n, filter == t) { filter = t } }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyState() }
        }
        items(items, key = { it.id }) { item ->
            Tile(app, item, playing = item.handle == state.handle) {
                app.dispatch(Command.SetQueue(items.map { it.handle }))
                app.dispatch(Command.Play(item.handle))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Board.TextFaint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
}

@Composable
private fun SlideshowRow(app: AndroPlayApp, shows: List<app.androplay.core.Slideshow>) {
    val all = shows.associateBy { it.id }
    val handleOf: (String) -> Int? = { app.store.item(it)?.handle }
    val playAll = slideshowCommand(playAllSlides(shows), loop = true, handleOf)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (playAll != null) {
            item(key = "all") {
                ShowCard(
                    icon = Icons.Filled.Repeat,
                    title = "Play all",
                    subtitle = slideCount(playAll.handles.size) + " · loops",
                    background = Brush.linearGradient(listOf(Brand.Blue, Color(0xFF7B4BFF))),
                    enabled = true,
                ) { app.dispatch(playAll) }
            }
        }
        items(shows, key = { it.id }) { show ->
            val cmd = slideshowCommand(show.flatten(all), show.loop, handleOf)
            ShowCard(
                icon = Icons.Filled.Slideshow,
                title = show.name,
                subtitle = if (cmd == null) "Nothing to play yet" else slideCount(cmd.handles.size),
                background = Brush.linearGradient(listOf(Board.Surface, Color(0xFF1B2232))),
                enabled = cmd != null,
            ) { cmd?.let(app::dispatch) }
        }
    }
}

private fun slideCount(n: Int) = if (n == 1) "1 slide" else "$n slides"

@Composable
private fun ShowCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    background: Brush,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(300.dp)
            .height(168.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(Board.Card)
            .background(background)
            .border(1.dp, Board.GlassBorder, Board.Card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(22.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun TypeChip(icon: ImageVector, label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) Color.White else Board.Glass)
            .border(1.dp, if (selected) Color.White else Board.GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) Board.Bg else Board.Text
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = fg.copy(alpha = 0.55f), fontSize = 17.sp)
    }
}

private fun typeIcon(t: ContentType): ImageVector = when (t) {
    ContentType.VIDEO -> Icons.Filled.Movie
    ContentType.IMAGE -> Icons.Filled.Image
    ContentType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Tile(app: AndroPlayApp, item: Item, playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16 / 9f)
            .clip(Board.Tile)
            .background(Board.Surface)
            .border(
                if (playing) BorderStroke(3.dp, Brand.Blue) else BorderStroke(1.dp, Board.GlassBorder),
                Board.Tile,
            )
            .clickable(onClick = onClick),
    ) {
        Thumb(app, item)
        // Bottom scrim with the name.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )
        Text(
            item.name,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp),
        )
        // Type badge.
        Box(
            Modifier.align(Alignment.TopStart).padding(10.dp).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(typeIcon(item.type), null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        // Duration / pages / now playing.
        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (playing) Badge("NOW PLAYING", Brand.Blue)
            val meta = when {
                item.durationMs > 0 -> formatDuration(item.durationMs)
                item.pageCount > 0 -> if (item.pageCount == 1) "1 page" else "${item.pageCount} pages"
                else -> null
            }
            if (meta != null) Badge(meta, Color.Black.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun Badge(text: String, background: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Thumb(app: AndroPlayApp, item: Item) {
    val bitmap by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            app.thumbnails.file(item)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1A2130), Color(0xFF111620)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(typeIcon(item.type), null, tint = Board.TextFaint, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    item.name.substringAfterLast('.', item.type.name).uppercase(),
                    color = Board.TextFaint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(Board.Glass), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Inbox, null, tint = Board.TextDim, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Nothing here yet", color = Board.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Upload from the AndroPlay admin app, or put files in the board's Movies, Pictures, or Download folders.",
            color = Board.TextDim,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(640.dp),
        )
    }
}

// ---------------------------------------------------------------- setup banner

@Composable
private fun SetupBanner(steps: List<SetupStep>, onSetup: (SetupStep) -> Unit, modifier: Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(24.dp),
        color = Color(0xF0161B26),
        contentColor = Color.White,
        shape = Board.Card,
        border = BorderStroke(1.dp, Brand.Warning.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TaskAlt, null, tint = Brand.Warning, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text("Finish setup", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(step.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(step.why, color = Board.TextDim, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = { onSetup(step) },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Blue, contentColor = Color.White),
                        shape = CircleShape,
                    ) { Text("Fix", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
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
