package app.androplay.admin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.androplay.core.Slideshow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.androplay.board.formatSize
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.Status
import kotlinx.coroutines.launch

@Composable
fun AdminScreen(repo: AdminRepository, onAdd: () -> Unit, onSettings: () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        AdminContent(repo, onAdd, onSettings)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AdminContent(repo: AdminRepository, onAdd: () -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entries by repo.entries.collectAsState()
    val boards by repo.boards.collectAsState()
    val board by repo.selected.collectAsState()
    val connected by repo.connected.collectAsState()
    val error by repo.error.collectAsState()
    val bleStatus by repo.ble.status.collectAsState()
    val state by repo.state.collectAsState()
    val uploadSummary by repo.uploadSummary.collectAsState()
    val locked = state?.locked == true
    var filter by remember { mutableStateOf<ContentType?>(null) }
    var boardMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }
    var lastQueue by remember { mutableStateOf<List<Int>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) } // 0 = Library, 1 = Slideshows
    var editing by remember { mutableStateOf<Slideshow?>(null) }
    val shows by repo.shows.collectAsState()

    val visible = entries.filter { filter == null || it.type == filter }
    val send: (Command) -> Unit = { cmd -> scope.launch { repo.send(cmd) } }

    fun play(e: Entry) {
        val c = e.content ?: return
        scope.launch {
            val queue = visible.mapNotNull { it.content?.takeIf { x -> it.status == EntryStatus.READY }?.handle }
            if (queue != lastQueue) {
                repo.send(Command.SetQueue(queue))
                lastQueue = queue
            }
            repo.send(Command.Play(c.handle))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.combinedClickable(onClick = { boardMenu = true })) {
                        Text(board?.name ?: if (boards.isEmpty()) "No boards yet" else "Choose a board", maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (board?.online == true) Color(0xFF3FB950) else Color.Gray)
                            Text(
                                when {
                                    !connected -> " Cloud offline${error?.let { ": $it" } ?: ""}"
                                    board == null -> " Tap to pick a board"
                                    bleStatus == BleLink.Status.READY -> " Online · Bluetooth"
                                    board?.online == true -> " Online · Cloud"
                                    else -> " Offline"
                                } + if (board != null && locked) " · Screen locked" else "",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
                        boards.forEach { b ->
                            DropdownMenuItem(
                                text = { Text("${b.name}${if (b.online) "" else " (offline)"}") },
                                leadingIcon = { Dot(if (b.online) Color(0xFF3FB950) else Color.Gray) },
                                onClick = {
                                    repo.select(b.id)
                                    boardMenu = false
                                },
                            )
                        }
                        if (boards.isEmpty()) DropdownMenuItem(text = { Text("Install AndroPlay on a board and choose Board") }, onClick = {})
                    }
                },
                actions = {
                    IconButton(onClick = { send(Command.Lock(!locked)) }, enabled = board != null) {
                        Icon(
                            if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            if (locked) "Unlock board screen" else "Lock board screen",
                        )
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (tab == 0) onAdd() else editing = newSlideshow(shows.size) }) {
                Icon(Icons.Default.Add, if (tab == 0) "Add content" else "New slideshow")
            }
        },
        bottomBar = { RemoteBar(state, entries, send) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Library") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Slideshows${if (shows.isNotEmpty()) " ${shows.size}" else ""}") })
            }
            if (tab == 1) {
                SlideshowsPane(repo, entries, onEdit = { editing = it })
            } else Column(Modifier.fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${entries.size}") }) }
                items(ContentType.entries.filter { t -> entries.any { it.type == t } }) { t ->
                    FilterChip(
                        selected = filter == t,
                        onClick = { filter = t },
                        label = { Text("${t.folder} ${entries.count { it.type == t }}") },
                    )
                }
            }
            if (uploadSummary.active > 0) {
                UploadBanner(uploadSummary)
            }
            if (visible.isEmpty()) {
                Text(
                    "Tap + to add videos, images, PDFs or documents. They sync to every board automatically.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { e ->
                    val playing = e.content != null && e.content.handle == state?.handle
                    Box {
                        Card(
                            Modifier
                                .then(if (playing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
                                .combinedClickable(
                                    onClick = {
                                        when (e.status) {
                                            EntryStatus.READY -> play(e)
                                            EntryStatus.FAILED -> scope.launch { repo.retry(e.id) }
                                            else -> menuFor = e
                                        }
                                    },
                                    onLongClick = { menuFor = e },
                                ),
                        ) {
                            Tile(repo, e)
                        }
                        DropdownMenu(expanded = menuFor?.id == e.id, onDismissRequest = { menuFor = null }) {
                            if (e.status == EntryStatus.READY) DropdownMenuItem(text = { Text("Play on board") }, onClick = { play(e); menuFor = null })
                            if (e.status == EntryStatus.FAILED) DropdownMenuItem(text = { Text("Retry upload") }, onClick = { scope.launch { repo.retry(e.id) }; menuFor = null })
                            if (e.upload != null && e.content?.ready != true) {
                                DropdownMenuItem(text = { Text("Cancel upload") }, onClick = { scope.launch { repo.removeUpload(e.id) }; menuFor = null })
                            }
                            if (e.content != null) DropdownMenuItem(text = { Text("Delete everywhere") }, onClick = { confirmDelete = e; menuFor = null })
                        }
                    }
                }
            }
            }
        }
    }

    editing?.let { show -> SlideshowEditor(repo, entries, show, onDismiss = { editing = null }) }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete everywhere?") },
            text = { Text("${e.name} will be removed from storage and every board.") },
            confirmButton = {
                TextButton(onClick = {
                    e.content?.let { c -> scope.launch { repo.delete(c); repo.removeUpload(c.id) } }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Tile(repo: AdminRepository, e: Entry) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(e.name.substringAfterLast('.', e.type.name).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val url = e.content?.let(repo::thumbUrl)
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).memoryCacheKey(e.id).diskCacheKey(e.id).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (e.status != EntryStatus.READY) {
            Surface(Modifier.align(Alignment.BottomStart).padding(6.dp), color = Color.Black.copy(alpha = 0.65f), shape = MaterialTheme.shapes.small) {
                Text(
                    when (e.status) {
                        EntryStatus.QUEUED -> "Queued"
                        EntryStatus.UPLOADING -> "Uploading ${(e.progress * 100).toInt()}%"
                        EntryStatus.WAITING -> "Queued for board"
                        EntryStatus.DOWNLOADING -> "Board ${(e.progress * 100).toInt()}%"
                        EntryStatus.FAILED -> "Failed · tap to retry"
                        EntryStatus.READY -> ""
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (e.status == EntryStatus.UPLOADING || e.status == EntryStatus.DOWNLOADING) {
            LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
    }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                e.type.folder.removeSuffix("s"),
                formatSize(e.size),
                e.content?.durationMs?.takeIf { it > 0 }?.let(::formatTime),
                e.content?.pageCount?.takeIf { it > 0 }?.let { "$it pages" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoteBar(state: PlaybackState?, entries: List<Entry>, send: (Command) -> Unit) {
    val s = state ?: PlaybackState()
    val current = entries.firstOrNull { it.content?.handle == s.handle }
    val media = current?.type == ContentType.VIDEO || current?.type == ContentType.AUDIO
    var seeking by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableFloatStateOf(s.volume.toFloat()) }
    // Last time a volume command went out while dragging; throttles live updates to one per 150 ms.
    val lastVolumeSend = remember { longArrayOf(0L) }
    var draggingVolume by remember { mutableStateOf(false) }
    LaunchedEffect(s.volume) { if (!draggingVolume) volume = s.volume.toFloat() }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (s.slideshow) "Slideshow · " else "") + (current?.name ?: "Nothing playing"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            current?.type == ContentType.PDF -> "Page ${s.page + 1} of ${s.pageCount}"
                            media -> "${formatTime(s.positionMs)} / ${formatTime(s.durationMs)} · ${s.status.name.lowercase()}"
                            else -> s.status.name.lowercase()
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                IconButton(onClick = { send(Command.ToggleMute) }) {
                    Icon(if (s.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Mute")
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        draggingVolume = true
                        val now = SystemClock.uptimeMillis()
                        if (now - lastVolumeSend[0] >= 150) {
                            lastVolumeSend[0] = now
                            send(Command.Volume(it.toInt()))
                        }
                    },
                    onValueChangeFinished = {
                        lastVolumeSend[0] = 0L
                        draggingVolume = false
                        send(Command.Volume(volume.toInt()))
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.size(width = 110.dp, height = 40.dp),
                )
            }
            if (media && s.durationMs > 0) {
                Slider(
                    value = seeking ?: s.positionMs.toFloat(),
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { send(Command.Seek(it.toInt())) }
                        seeking = null
                    },
                    valueRange = 0f..s.durationMs.toFloat(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { send(Command.Prev) }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                IconButton(onClick = { send(Command.SeekBy(-10_000)) }, enabled = media) { Icon(Icons.Default.Replay10, "Back 10 s") }
                FilledIconButton(onClick = { send(Command.Toggle) }, modifier = Modifier.size(56.dp)) {
                    Icon(if (s.status == Status.PLAYING && media) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause")
                }
                IconButton(onClick = { send(Command.SeekBy(10_000)) }, enabled = media) { Icon(Icons.Default.Forward10, "Forward 10 s") }
                IconButton(onClick = { send(Command.Next) }) { Icon(Icons.Default.SkipNext, "Next") }
                IconButton(onClick = { send(Command.Stop) }) { Icon(Icons.Default.Stop, "Stop") }
            }
        }
    }
}

@Composable
private fun UploadBanner(summary: UploadSummary) {
    val fraction = if (summary.totalBytes > 0) (summary.uploadedBytes.toFloat() / summary.totalBytes).coerceIn(0f, 1f) else 0f
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Uploading ${summary.active} file${if (summary.active == 1) "" else "s"} · " +
                    "${formatSize(summary.uploadedBytes)} of ${formatSize(summary.totalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(6.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
