package app.androplay.admin

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.androplay.board.formatSize
import app.androplay.core.Slideshow
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.Status
import app.androplay.ui.AndroPlayTheme
import app.androplay.ui.Brand
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
fun AdminScreen(repo: AdminRepository, onAdd: () -> Unit, onSettings: () -> Unit) {
    AndroPlayTheme {
        AdminContent(repo, onAdd, onSettings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val shows by repo.shows.collectAsState()
    val locked = state?.locked == true
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var lastQueue by remember { mutableStateOf<List<Int>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) } // 0 = Library, 1 = Slideshows
    var editing by remember { mutableStateOf<Slideshow?>(null) }
    val gridState = rememberLazyGridState()

    val send: (Command) -> Unit = { cmd -> scope.launch { repo.send(cmd) } }

    /** Plays [e], first sending the playback order (the library as arranged on screen) if it changed. */
    fun play(e: Entry, displayed: List<Entry>) {
        val c = e.content ?: return
        scope.launch {
            val queue = displayed.mapNotNull { it.content?.takeIf { _ -> it.status == EntryStatus.READY }?.handle }
            if (queue != lastQueue) {
                repo.send(Command.SetQueue(queue))
                lastQueue = queue
            }
            repo.send(Command.Play(c.handle))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    BoardPill(
                        repo = repo,
                        boards = boards,
                        board = board,
                        connected = connected,
                        error = error,
                        linked = bleStatus == BleLink.Status.READY,
                        locked = locked,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { send(Command.Lock(!locked)) },
                        enabled = board != null,
                        colors = if (locked) {
                            IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            IconButtonDefaults.iconButtonColors()
                        },
                    ) {
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
            val library = tab == 0
            ExtendedFloatingActionButton(
                onClick = { if (library) onAdd() else editing = newSlideshow(shows.size) },
                expanded = !library || gridState.firstVisibleItemIndex == 0,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(if (library) "Add content" else "New slideshow") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        bottomBar = { NowPlayingCard(repo, state, entries, send) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdateBanner(repo)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp)) },
                    label = { Text("Library") },
                )
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Default.Slideshow, null, Modifier.size(18.dp)) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Slideshows")
                            if (shows.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                CountBadge(shows.size)
                            }
                        }
                    },
                )
            }
            if (tab == 1) {
                SlideshowsPane(repo, entries, onEdit = { editing = it })
            } else {
                LibraryPane(
                    repo = repo,
                    entries = entries,
                    playingHandle = state?.handle,
                    uploadSummary = uploadSummary,
                    gridState = gridState,
                    onAdd = onAdd,
                    onPlay = ::play,
                    onDelete = { confirmDelete = it },
                    onRename = { renaming = it },
                )
            }
        }
    }

    editing?.let { show -> SlideshowEditor(repo, entries, show, onDismiss = { editing = null }) }

    renaming?.let { e -> e.content?.let { c -> RenameDialog(repo, c, onDismiss = { renaming = null }) } }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete everywhere?") },
            text = { Text("${e.name} will be removed from storage and every board.") },
            confirmButton = {
                TextButton(onClick = {
                    e.content?.let { c -> scope.launch { repo.delete(c); repo.removeUpload(c.id) } }
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun BoardPill(
    repo: AdminRepository,
    boards: List<app.androplay.core.BoardInfo>,
    board: app.androplay.core.BoardInfo?,
    connected: Boolean,
    error: String?,
    /** A direct in-room link is up (silent fast path; never shown by name). */
    linked: Boolean,
    locked: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    val online = board != null && ((connected && board.online) || linked)
    val subtitle = when {
        board == null && boards.isEmpty() -> if (connected) "No boards yet" else "Offline"
        board == null -> "Tap to choose a board"
        online -> "Connected"
        else -> "Offline"
    } + (if (board != null && locked) " · Screen locked" else "") +
        (board?.update?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (online) Brand.Success else Color.Gray, 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        board?.name ?: "Choose a board",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (locked && board != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ExpandMore, "Choose board", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!connected) {
                DropdownMenuItem(
                    text = { Text("Cloud offline${error?.let { ": $it" } ?: ""}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {},
                    enabled = false,
                )
                HorizontalDivider()
            }
            boards.forEach { b ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(b.name, fontWeight = if (b.id == board?.id) FontWeight.SemiBold else FontWeight.Normal)
                            Text(
                                if (b.online) "Online" else "Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { StatusDot(if (b.online) Brand.Success else Color.Gray, 10.dp) },
                    trailingIcon = { if (b.id == board?.id) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        repo.select(b.id)
                        open = false
                    },
                )
            }
            if (boards.isEmpty()) {
                DropdownMenuItem(text = { Text("Install AndroPlay on a board and choose Board") }, onClick = {}, enabled = false)
            }
        }
    }
}

// ---------------------------------------------------------------- rename

@Composable
private fun RenameDialog(repo: AdminRepository, c: app.androplay.core.Content, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = remember(c.name) { AdminRepository.extensionOf(c.name) }
    var field by remember(c.id) {
        mutableStateOf(TextFieldValue(c.name, selection = TextRange(0, c.name.length - ext.length)))
    }
    var saving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val trimmed = field.text.trim()
    val finalName = AdminRepository.withExtension(trimmed, c.name)
    val valid = trimmed.isNotEmpty() && trimmed != ext
    val changed = finalName != c.name

    fun save() {
        if (!valid || saving) return
        if (!changed) return onDismiss()
        saving = true
        scope.launch {
            val ok = repo.rename(c, trimmed)
            saving = false
            if (ok) onDismiss() else Toast.makeText(ctx, "Couldn't rename: check the connection", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        icon = { Icon(Icons.Default.Edit, null) },
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    enabled = !saving,
                    isError = !valid,
                    label = { Text("Name") },
                    leadingIcon = { Icon(typeIcon(c.type), null) },
                    supportingText = {
                        Text(
                            when {
                                !valid -> "Name can't be empty"
                                ext.isNotEmpty() && !trimmed.endsWith(ext, ignoreCase = true) -> "Saved as $finalName"
                                else -> "Updates on every board and phone"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = valid && !saving) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- library

@Composable
private fun LibraryPane(
    repo: AdminRepository,
    entries: List<Entry>,
    playingHandle: Int?,
    uploadSummary: UploadSummary,
    gridState: LazyGridState,
    onAdd: () -> Unit,
    onPlay: (Entry, List<Entry>) -> Unit,
    onDelete: (Entry) -> Unit,
    onRename: (Entry) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var savedIds by remember { mutableStateOf(LibraryOrder.load(ctx)) }
    // Local mirror of the library in display order; mutated live while dragging.
    val order = remember { mutableStateListOf<Entry>().apply { addAll(LibraryOrder.arrange(entries, savedIds)) } }
    LaunchedEffect(entries) {
        // Keep the on-screen order (including an in-progress drag); new items go first.
        val arranged = LibraryOrder.arrange(entries, order.map { it.id } + savedIds)
        if (arranged != order.toList()) {
            order.clear()
            order.addAll(arranged)
        }
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<ContentType?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var lastDragEnd by remember { mutableLongStateOf(0L) }

    val visible = order.filter { e ->
        (filter == null || e.type == filter) && (query.isBlank() || e.name.contains(query.trim(), ignoreCase = true))
    }

    fun saveOrder() {
        val ids = order.map { it.id }
        val all = (ids + savedIds.filter { it !in ids.toSet() }).take(5000)
        savedIds = all
        LibraryOrder.save(ctx, all)
    }

    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        val fi = order.indexOfFirst { it.id == from.key }
        val ti = order.indexOfFirst { it.id == to.key }
        if (fi >= 0 && ti >= 0) order.add(ti, order.removeAt(fi))
    }

    Column(Modifier.fillMaxSize()) {
        if (order.isNotEmpty()) {
            SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = filter == null,
                        onClick = { filter = null },
                        label = { Text("All ${order.size}") },
                        shape = CircleShape,
                    )
                }
                items(ContentType.entries.filter { t -> order.any { it.type == t } }) { t ->
                    FilterChip(
                        selected = filter == t,
                        onClick = { filter = if (filter == t) null else t },
                        leadingIcon = { Icon(typeIcon(t), null, Modifier.size(FilterChipDefaults.IconSize)) },
                        label = { Text("${t.folder} ${order.count { it.type == t }}") },
                        shape = CircleShape,
                    )
                }
            }
        }
        AnimatedVisibility(uploadSummary.active > 0) { UploadBanner(uploadSummary) }

        when {
            order.isEmpty() -> EmptyState(
                icon = Icons.Default.VideoLibrary,
                title = "Your library is empty",
                text = "Add videos, images, PDFs or documents. They sync to every board automatically.",
                action = "Add content",
                actionIcon = Icons.Default.Add,
                onAction = onAdd,
            )
            visible.isEmpty() -> EmptyState(
                icon = Icons.Default.SearchOff,
                title = "Nothing matches",
                text = "Try another name or file type.",
                action = "Clear filters",
                actionIcon = Icons.Default.Close,
                onAction = { query = ""; filter = null },
            )
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { e ->
                    ReorderableItem(reorder, key = e.id) { isDragging ->
                        val handle = Modifier.longPressDraggableHandle(
                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = {
                                lastDragEnd = SystemClock.uptimeMillis()
                                saveOrder()
                            },
                        )
                        LibraryTile(
                            repo = repo,
                            e = e,
                            playing = e.content != null && e.content.handle == playingHandle,
                            dragging = isDragging,
                            menuOpen = menuFor == e.id,
                            modifier = handle,
                            onClick = {
                                // A long-press drag ends with a release; don't treat it as a tap.
                                if (SystemClock.uptimeMillis() - lastDragEnd > 300) {
                                    when (e.status) {
                                        EntryStatus.READY -> onPlay(e, visible)
                                        EntryStatus.FAILED -> scope.launch { repo.retry(e.id) }
                                        else -> menuFor = e.id
                                    }
                                }
                            },
                            onMenu = { menuFor = e.id },
                            onDismissMenu = { menuFor = null },
                            menu = {
                                if (e.status == EntryStatus.READY) {
                                    DropdownMenuItem(
                                        text = { Text("Play on board") },
                                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                        onClick = { onPlay(e, visible); menuFor = null },
                                    )
                                }
                                if (e.status == EntryStatus.FAILED) {
                                    DropdownMenuItem(
                                        text = { Text("Retry upload") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = { scope.launch { repo.retry(e.id) }; menuFor = null },
                                    )
                                }
                                if (e.upload != null && e.content?.ready != true) {
                                    DropdownMenuItem(
                                        text = { Text("Cancel upload") },
                                        leadingIcon = { Icon(Icons.Default.Close, null) },
                                        onClick = { scope.launch { repo.removeUpload(e.id) }; menuFor = null },
                                    )
                                }
                                if (e.content != null) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { onRename(e); menuFor = null },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Delete everywhere", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { onDelete(e); menuFor = null },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTile(
    repo: AdminRepository,
    e: Entry,
    playing: Boolean,
    dragging: Boolean,
    menuOpen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val elevation by animateDpAsState(if (dragging) 14.dp else 1.dp, label = "elevation")
    val scale by animateFloatAsState(if (dragging) 1.06f else 1f, label = "scale")
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary

    Card(
        onClick = onClick,
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (playing) Modifier.border(2.5.dp, primary, shape) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                    ),
                ),
        ) {
            Icon(
                typeIcon(e.type),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
            val url = e.content?.let(repo::thumbUrl)
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(url).memoryCacheKey(e.id).diskCacheKey(e.id).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Scrim so the name stays readable on any thumbnail.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
            )
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(typeIcon(e.type), e.type.folder, tint = Color.White, modifier = Modifier.size(15.dp)) }
                if (playing) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, null, Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            val badge = e.content?.durationMs?.takeIf { it > 0 }?.let(::formatTime)
                ?: e.content?.pageCount?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "page" else "pages"}" }
            if (badge != null && !playing) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    e.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, "More options", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) { menu() }
                }
            }
            if (e.status == EntryStatus.UPLOADING || e.status == EntryStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { e.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = if (e.status == EntryStatus.UPLOADING) primary else MaterialTheme.colorScheme.secondary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        TileFooter(e)
    }
}

@Composable
private fun TileFooter(e: Entry) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (e.status == EntryStatus.READY) {
            Text(
                listOf(e.type.folder.removeSuffix("s"), formatSize(e.size)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        } else {
            val pct = (e.progress * 100).toInt()
            val (label, color) = when (e.status) {
                EntryStatus.QUEUED -> "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
                EntryStatus.UPLOADING -> "Uploading $pct%" to MaterialTheme.colorScheme.primary
                EntryStatus.WAITING -> "Queued for board" to MaterialTheme.colorScheme.onSurfaceVariant
                EntryStatus.DOWNLOADING -> "Board $pct%" to MaterialTheme.colorScheme.secondary
                EntryStatus.FAILED -> "Failed · tap to retry" to MaterialTheme.colorScheme.error
                EntryStatus.READY -> "" to Color.Unspecified
            }
            Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e.status == EntryStatus.FAILED) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(12.dp))
                    } else {
                        StatusDot(color, 6.dp)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().height(46.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search library", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange(""); focus.clearFocus() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "Clear search", Modifier.size(18.dp))
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    action: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(42.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (action == "Add content") {
            Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                Icon(actionIcon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(action)
            }
        } else {
            OutlinedButton(onClick = onAction) {
                Icon(actionIcon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(action)
            }
        }
    }
}

@Composable
private fun UploadBanner(summary: UploadSummary) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Uploading ${summary.active} file${if (summary.active == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(summary.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "${formatSize(summary.uploadedBytes)} of ${formatSize(summary.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { summary.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- now playing

@Composable
private fun NowPlayingCard(repo: AdminRepository, state: PlaybackState?, entries: List<Entry>, send: (Command) -> Unit) {
    val ctx = LocalContext.current
    val s = state ?: PlaybackState()
    val current = entries.firstOrNull { it.content?.handle == s.handle }
    val media = current?.type == ContentType.VIDEO || current?.type == ContentType.AUDIO
    val still = current?.type == ContentType.IMAGE || current?.type == ContentType.PDF
    // Something on screen can be paused/seeked: media, or a timed slideshow still.
    val active = s.status == Status.PLAYING || s.status == Status.PAUSED
    val pausable = (media && (active || s.status == Status.LOADING || s.status == Status.ENDED)) || (s.durationMs > 0 && active)
    val timed = s.durationMs > 0 && (media || active)
    val timedStill = timed && !media && (still || s.slideshow)
    val boardPlaying = s.status == Status.PLAYING

    // Optimistic play/pause: flip the icon on tap, reconcile when the board agrees or after 1.5 s.
    var optimistic by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(boardPlaying, s.handle) { if (optimistic == boardPlaying) optimistic = null }
    LaunchedEffect(s.handle) { optimistic = null }
    LaunchedEffect(optimistic) {
        if (optimistic != null) {
            delay(1_500)
            optimistic = null
        }
    }
    val playing = if (pausable) optimistic ?: boardPlaying else false

    // Smooth position between state updates while playing.
    var receivedAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(s) { receivedAt = SystemClock.uptimeMillis(); now = receivedAt }
    LaunchedEffect(boardPlaying && timed && optimistic != false) {
        if (boardPlaying && timed && optimistic != false) {
            while (true) {
                delay(200)
                now = SystemClock.uptimeMillis()
            }
        }
    }
    val livePosition = if (boardPlaying && optimistic != false) (s.positionMs + (now - receivedAt).coerceAtLeast(0)) else s.positionMs

    var seeking by remember { mutableStateOf<Float?>(null) }
    val position = (seeking?.toLong() ?: livePosition).coerceIn(0, s.durationMs.coerceAtLeast(0))
    var volume by remember { mutableFloatStateOf(s.volume.toFloat()) }
    // Last time a volume command went out while dragging; throttles live updates to one per 150 ms.
    val lastVolumeSend = remember { longArrayOf(0L) }
    var draggingVolume by remember { mutableStateOf(false) }
    LaunchedEffect(s.volume) { if (!draggingVolume) volume = s.volume.toFloat() }

    val statusText = when (s.status) {
        Status.IDLE -> "Ready"
        Status.LOADING -> "Loading…"
        Status.PLAYING -> when {
            !pausable && !media -> "On screen"
            optimistic == false -> "Paused"
            else -> "Playing"
        }
        Status.PAUSED -> if (optimistic == true) "Playing" else "Paused"
        Status.ENDED -> "Ended"
        Status.ERROR -> "Error"
        Status.EXTERNAL -> "Open in another app"
    }
    val subtitle = when {
        current == null && s.handle == PlaybackState.NO_HANDLE -> "Tap a tile to play it on the board"
        current?.type == ContentType.PDF && s.pageCount > 0 -> "Page ${s.page + 1}/${s.pageCount} · $statusText"
        else -> statusText
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        current?.let { typeIcon(it.type) } ?: Icons.Default.PlayArrow,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                    val url = current?.content?.let(repo::thumbUrl)
                    if (url != null && current != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(url).memoryCacheKey(current.id).diskCacheKey(current.id).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.name ?: "Nothing playing",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.slideshow) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = CircleShape,
                            ) {
                                Row(Modifier.padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Slideshow, null, Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Slideshow", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (media) Spacer(Modifier.width(8.dp))
                if (media) Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        draggingVolume = true
                        val t = SystemClock.uptimeMillis()
                        if (t - lastVolumeSend[0] >= 150) {
                            lastVolumeSend[0] = t
                            send(Command.Volume(it.toInt()))
                        }
                    },
                    onValueChangeFinished = {
                        lastVolumeSend[0] = 0L
                        draggingVolume = false
                        send(Command.Volume(volume.toInt()))
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.width(92.dp).height(36.dp),
                )
            }
            if (timedStill) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (position.toFloat() / s.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                )
                val left = ((s.durationMs - position + 999) / 1000 * 1000).coerceAtLeast(0)
                Text(
                    if (playing) "Next slide in ${formatTime(left)}" else "Slide paused · ${formatTime(left)} left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
            if (timed && media) {
                Slider(
                    value = position.toFloat(),
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let {
                            send(Command.Seek(it.toInt()))
                            // Show the new position right away instead of jumping back until the next update.
                            receivedAt = SystemClock.uptimeMillis()
                            now = receivedAt
                        }
                        seeking = null
                    },
                    valueRange = 0f..s.durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val labelStyle = MaterialTheme.typography.labelSmall
                    if (timedStill) {
                        val left = ((s.durationMs - position + 999) / 1000 * 1000).coerceAtLeast(0)
                        Icon(
                            Icons.Default.Timer,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (playing) "Next slide in ${formatTime(left)}" else "Slide paused · ${formatTime(left)} left",
                            style = labelStyle,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        "${formatTime(position)} / ${formatTime(s.durationMs)}",
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { optimistic = null; send(Command.Stop) }) { Icon(Icons.Default.Stop, "Stop") }
                if (media) IconButton(onClick = { send(Command.SeekBy(-10_000)) }, enabled = pausable && timed) { Icon(Icons.Default.Replay10, "Back 10 s") }
                IconButton(onClick = { optimistic = null; send(Command.Prev) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(30.dp))
                }
                if (pausable) FilledIconButton(
                    onClick = {
                        val target = !playing
                        optimistic = target
                        send(
                            when {
                                !target -> Command.Pause
                                s.status == Status.PAUSED -> Command.Resume
                                else -> Command.Toggle
                            },
                        )
                    },
                    enabled = pausable,
                    modifier = Modifier.size(60.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = { optimistic = null; send(Command.Next) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", Modifier.size(30.dp))
                }
                if (media) IconButton(onClick = { send(Command.SeekBy(10_000)) }, enabled = pausable && timed) { Icon(Icons.Default.Forward10, "Forward 10 s") }
                if (media) IconButton(onClick = { send(Command.ToggleMute) }) {
                    Icon(
                        if (s.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        if (s.muted) "Unmute" else "Mute",
                        tint = if (s.muted) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun CountBadge(n: Int) {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
        Text("$n", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

private fun typeIcon(t: ContentType): ImageVector = when (t) {
    ContentType.VIDEO -> Icons.Default.Movie
    ContentType.IMAGE -> Icons.Default.Image
    ContentType.AUDIO -> Icons.Default.MusicNote
    ContentType.PDF -> Icons.Default.PictureAsPdf
    ContentType.DOCUMENT -> Icons.Default.Description
    ContentType.OTHER -> Icons.Default.InsertDriveFile
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
