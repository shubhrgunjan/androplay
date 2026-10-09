@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.androplay.admin

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.androplay.core.Slide
import app.androplay.core.Slideshow
import app.androplay.core.flatten
import app.androplay.protocol.ContentType
import app.androplay.ui.Brand
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.UUID
import kotlin.math.roundToInt

/** Types a slideshow can time: documents open in other apps, so they're excluded. */
private val SLIDE_TYPES = setOf(ContentType.VIDEO, ContentType.IMAGE, ContentType.AUDIO, ContentType.PDF)

fun newSlideshow(existing: Int) = Slideshow(id = UUID.randomUUID().toString().take(12), name = "Slideshow ${existing + 1}")

// =============================================================================================
// Slideshow list
// =============================================================================================

@Composable
fun SlideshowsPane(repo: AdminRepository, entries: List<Entry>, onEdit: (Slideshow) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val shows by repo.shows.collectAsState()
    val byId = remember(entries) { entries.associateBy { it.id } }
    val allShows = remember(shows) { shows.associateBy { it.id } }
    var loopAll by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<Slideshow?>(null) }
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // Local mirror so drag and drop moves cards instantly; resynced whenever nothing is being dragged.
    val local = remember { mutableStateListOf<Slideshow>().apply { addAll(repo.shows.value) } }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = local.indexOfFirst { it.id == from.key }
        val t = local.indexOfFirst { it.id == to.key }
        if (f >= 0 && t >= 0 && f != t) {
            local.add(t, local.removeAt(f))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(shows, reorder.isAnyItemDragging) {
        if (!reorder.isAnyItemDragging && local.toList() != shows) {
            local.clear()
            local.addAll(shows)
        }
    }
    // Undispatched so repo.shows already has the new order when the drag ends (no snap-back flicker).
    val persistOrder: () -> Unit = {
        if (local.map { it.id } != shows.map { it.id }) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (!repo.reorderShows(local.toList())) toast("Couldn't save the new order: check the connection")
            }
        }
    }

    if (shows.isEmpty()) {
        EmptyShows()
    } else {
        val totals = remember(shows, entries) {
            val flat = shows.map { it.flatten(allShows) }
            Triple(flat.sumOf { it.size }, flat.sumOf { it.durationMs(byId) }, shows.size)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "__play_all") {
                PlayAllCard(
                    shows = totals.third,
                    slides = totals.first,
                    durationMs = totals.second,
                    loop = loopAll,
                    onLoop = { loopAll = it },
                    onPlay = {
                        scope.launch {
                            val n = repo.playAll(loopAll)
                            toast(if (n == 0) "Nothing is on the board yet" else "Playing all · $n slides")
                        }
                    },
                )
            }
            item(key = "__order_hint") {
                Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Play order",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.DragHandle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        " Drag to rearrange",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(local, key = { it.id }) { show ->
                ReorderableItem(reorder, key = show.id) { isDragging ->
                    ShowCard(
                        repo = repo,
                        show = show,
                        number = local.indexOfFirst { it.id == show.id } + 1,
                        allShows = allShows,
                        byId = byId,
                        isDragging = isDragging,
                        cardModifier = Modifier.longPressDraggableHandle(
                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = persistOrder,
                        ),
                        handleModifier = Modifier.draggableHandle(
                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = persistOrder,
                        ),
                        onPlay = {
                            scope.launch {
                                val n = repo.playShow(show)
                                toast(if (n == 0) "Nothing in \"${show.name}\" is on the board yet" else "Playing ${show.name} · $n slides")
                            }
                        },
                        onEdit = { onEdit(show) },
                        onDuplicate = {
                            scope.launch {
                                val copy = show.copy(id = newSlideshow(0).id, name = "${show.name} copy")
                                toast(if (repo.saveShow(copy)) "Duplicated as \"${copy.name}\"" else "Couldn't duplicate: check the connection")
                            }
                        },
                        onDelete = { confirmDelete = show },
                    )
                }
            }
        }
    }

    confirmDelete?.let { show ->
        val ref = Slideshow.ref(show.id)
        val parents = shows.filter { it.id != show.id && ref in it.items }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete \"${show.name}\"?") },
            text = {
                Text(
                    "Only the slideshow is deleted; its media stays in the library." +
                        if (parents.isEmpty()) "" else "\n\nIt will also be removed from ${parents.joinToString { "\"${it.name}\"" }}.",
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            parents.forEach { p -> repo.saveShow(p.copy(items = p.items.filterNot { it == ref })) }
                            if (!repo.deleteShow(show.id)) toast("Couldn't delete: check the connection")
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyShows() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Slideshow, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text("No slideshows yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap + to create a slideshow: pick videos, images, audio and PDFs in the order you want — " +
                "or even other slideshows — then play it on the board in one tap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlayAllCard(shows: Int, slides: Int, durationMs: Long, loop: Boolean, onLoop: (Boolean) -> Unit, onPlay: () -> Unit) {
    val gradient = Brush.linearGradient(listOf(Brand.Blue, Color(0xFF4B3FD9)))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(gradient)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Slideshow, null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Play all", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "${plural(shows, "slideshow")} · ${plural(slides, "slide")} · ~${formatDuration(durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.Blue),
                    contentPadding = PaddingValues(horizontal = 22.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play all", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { onLoop(!loop) }.padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Repeat, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(" Loop  ", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Switch(
                        checked = loop,
                        onCheckedChange = onLoop,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Brand.Blue,
                            checkedTrackColor = Color.White,
                            checkedBorderColor = Color.White,
                            uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                            uncheckedBorderColor = Color.White.copy(alpha = 0.6f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowCard(
    repo: AdminRepository,
    show: Slideshow,
    number: Int,
    allShows: Map<String, Slideshow>,
    byId: Map<String, Entry>,
    isDragging: Boolean,
    cardModifier: Modifier,
    handleModifier: Modifier,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val stats = showStats(show, allShows, byId)
    val ready = repo.playableCount(show)
    val elevation by animateDpAsState(if (isDragging) 12.dp else 1.dp, label = "elevation")
    val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "scale")
    var menu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(cardModifier),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (isDragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.clickable(onClick = onEdit)) {
                    Mosaic(repo, stats.thumbs, Modifier.size(92.dp))
                    NumberBadge(number, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                    Text(
                        show.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatChip(Icons.Default.Collections, plural(stats.slides, "slide"))
                        StatChip(Icons.Default.Schedule, "~${formatDuration(stats.durationMs)}")
                        if (show.loop) StatChip(Icons.Default.Repeat, "Loops", highlight = true)
                        if (stats.nested > 0) StatChip(Icons.Default.Layers, "Contains ${plural(stats.nested, "slideshow")}", highlight = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    ReadyLine(ready, stats.slides)
                }
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = handleModifier.clip(CircleShape).size(40.dp).padding(8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyLine(ready: Int, total: Int) {
    val (icon, color, text) = when {
        total == 0 -> Triple(Icons.Default.WarningAmber, Brand.Warning, "Empty")
        ready >= total -> Triple(Icons.Default.CheckCircle, Brand.Success, "All ready on board")
        ready == 0 -> Triple(Icons.Default.WarningAmber, Brand.Warning, "Not on board yet")
        else -> Triple(Icons.Default.Sync, Brand.Warning, "$ready of $total ready on board")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// =============================================================================================
// Editor
// =============================================================================================

/** One row in the editor; [uid] keeps drag and drop stable even when the same item appears twice. */
private data class ShowEditorRow(val uid: Long, val item: String)

@Composable
fun SlideshowEditor(repo: AdminRepository, entries: List<Entry>, initial: Slideshow, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val shows by repo.shows.collectAsState()
    val byId = remember(entries) { entries.associateBy { it.id } }
    val isNew = remember { shows.none { it.id == initial.id } }

    var name by remember { mutableStateOf(initial.name) }
    var seconds by remember { mutableFloatStateOf(initial.imageSeconds.toFloat().coerceIn(2f, 60f)) }
    var loop by remember { mutableStateOf(initial.loop) }
    var nextUid by remember { mutableIntStateOf(initial.items.size) }
    val rows = remember { mutableStateListOf<ShowEditorRow>().apply { initial.items.forEachIndexed { i, it -> add(ShowEditorRow(i.toLong(), it)) } } }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val draft = initial.copy(
        name = name.trim(),
        items = rows.map { it.item },
        imageSeconds = seconds.roundToInt(),
        loop = loop,
        position = shows.firstOrNull { it.id == initial.id }?.position ?: initial.position,
    )
    val all = shows.associateBy { it.id } + (draft.id to draft)
    val flat = draft.flatten(all)
    val dirty = draft.name != initial.name || draft.items != initial.items || draft.imageSeconds != initial.imageSeconds || draft.loop != initial.loop
    val canSave = name.isNotBlank() && rows.isNotEmpty() && !saving

    fun requestClose() {
        if (dirty) confirmDiscard = true else onDismiss()
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = rows.indexOfFirst { it.uid == from.key }
        val t = rows.indexOfFirst { it.uid == to.key }
        if (f >= 0 && t >= 0 && f != t) {
            rows.add(t, rows.removeAt(f))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = ::requestClose) { Icon(Icons.Default.Close, "Close") } },
                    title = { Text(if (isNew) "New slideshow" else "Edit slideshow", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        Button(
                            enabled = canSave,
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = {
                                saving = true
                                scope.launch {
                                    val ok = repo.saveShow(draft)
                                    saving = false
                                    if (ok) onDismiss() else Toast.makeText(ctx, "Couldn't save: check the connection", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) { Text(if (saving) "Saving…" else "Save") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { picking = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add") },
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "__settings") {
                    EditorSettings(
                        name = name,
                        onName = { name = it },
                        seconds = seconds,
                        onSeconds = { seconds = it },
                        loop = loop,
                        onLoop = { loop = it },
                        slides = flat.size,
                        ready = flat.count { byId[it.contentId]?.status == EntryStatus.READY },
                        durationMs = flat.durationMs(byId),
                    )
                }
                item(key = "__order") {
                    Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Play order", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (rows.size > 1) {
                            Icon(Icons.Default.DragHandle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" Drag to rearrange", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "__empty") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable { picking = true }
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text("Add media or slideshows", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Pick them in the order they should play",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(rows, key = { _, r -> r.uid }) { index, row ->
                    ReorderableItem(reorder, key = row.uid) { isDragging ->
                        EditorItemRow(
                            repo = repo,
                            item = row.item,
                            number = index + 1,
                            seconds = seconds.roundToInt(),
                            all = all,
                            byId = byId,
                            isDragging = isDragging,
                            rowModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            ),
                            handleModifier = Modifier.draggableHandle(
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            ),
                            onRemove = { rows.removeAll { it.uid == row.uid } },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        SlideshowPicker(
            repo = repo,
            entries = entries,
            editing = draft,
            shows = shows,
            byId = byId,
            onDismiss = { picking = false },
        ) { picked ->
            picked.forEach { rows.add(ShowEditorRow((nextUid++).toLong(), it)) }
            picking = false
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to this slideshow haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun EditorSettings(
    name: String,
    onName: (String) -> Unit,
    seconds: Float,
    onSeconds: (Float) -> Unit,
    loop: Boolean,
    onLoop: (Boolean) -> Unit,
    slides: Int,
    ready: Int,
    durationMs: Long,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("Name") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Seconds per image / PDF page", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "${seconds.roundToInt()} s",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Slider(value = seconds, onValueChange = { onSeconds(it.roundToInt().toFloat()) }, valueRange = 2f..60f)
                Text(
                    "Videos and audio always play to the end",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onLoop(!loop) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Loop", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Start again after the last slide",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = loop, onCheckedChange = onLoop)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatChip(Icons.Default.Collections, plural(slides, "slide"))
                StatChip(Icons.Default.Schedule, "Total ~${formatDuration(durationMs)}", highlight = true)
                if (slides > 0) {
                    StatChip(
                        if (ready >= slides) Icons.Default.CheckCircle else Icons.Default.Sync,
                        if (ready >= slides) "All on board" else "$ready of $slides on board",
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorItemRow(
    repo: AdminRepository,
    item: String,
    number: Int,
    seconds: Int,
    all: Map<String, Slideshow>,
    byId: Map<String, Entry>,
    isDragging: Boolean,
    rowModifier: Modifier,
    handleModifier: Modifier,
    onRemove: () -> Unit,
) {
    val nestedId = Slideshow.showIdOf(item)
    val nested = nestedId?.let { all[it] }
    val entry = if (nestedId == null) byId[item] else null
    val warn = nestedId == null && entry?.status != EntryStatus.READY
    val container = when {
        nestedId != null -> MaterialTheme.colorScheme.primaryContainer
        warn -> Brand.Warning.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "elevation")
    val scale by animateFloatAsState(if (isDragging) 1.02f else 1f, label = "scale")

    Surface(
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.then(rowModifier),
        shape = MaterialTheme.shapes.medium,
        color = container,
        shadowElevation = elevation,
        border = if (isDragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handleModifier.clip(CircleShape).size(44.dp).padding(10.dp),
            )
            Box {
                if (nestedId != null) {
                    val thumbs = nested?.let { showStats(it, all, byId).thumbs }.orEmpty()
                    StackedThumbs(repo, thumbs.firstOrNull())
                } else {
                    Thumb(repo, entry, Modifier.width(76.dp).aspectRatio(16 / 9f))
                }
                NumberBadge(number, Modifier.align(Alignment.TopStart).padding(3.dp), small = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = when {
                    nestedId != null -> nested?.name ?: "Deleted slideshow"
                    else -> entry?.name ?: "Removed from library"
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (nestedId != null) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (nestedId != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = when {
                    nestedId != null && nested != null -> {
                        val st = showStats(nested, all, byId)
                        "Slideshow · ${plural(st.slides, "slide")} · ~${formatDuration(st.durationMs)}"
                    }
                    nestedId != null -> "Slideshow · will be skipped"
                    entry != null -> slideLabel(entry, seconds)
                    else -> "Will be skipped"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (nestedId != null) {
                        Icon(Icons.Default.Slideshow, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (nestedId != null) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (warn && entry != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, Modifier.size(14.dp), tint = Brand.Warning)
                        Spacer(Modifier.width(4.dp))
                        Text("Not on board yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}

/** A nested slideshow: a small stack of cards with the first slide on top. */
@Composable
private fun StackedThumbs(repo: AdminRepository, top: Entry?) {
    Box(Modifier.size(width = 76.dp, height = 48.dp)) {
        val w = 66.dp
        val h = 37.dp
        Box(
            Modifier.offset(x = 10.dp).size(w, h).clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        )
        Box(
            Modifier.offset(x = 5.dp, y = 5.dp).size(w, h).clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        )
        Box(Modifier.offset(y = 11.dp).size(w, h)) {
            Thumb(repo, top, Modifier.fillMaxSize(), fallback = Icons.Default.Slideshow, dimNotReady = false)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Slideshow, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

// =============================================================================================
// Picker
// =============================================================================================

/** Adds media and/or other slideshows, in tap order (the numbers show the order they'll be added). */
@Composable
private fun SlideshowPicker(
    repo: AdminRepository,
    entries: List<Entry>,
    editing: Slideshow,
    shows: List<Slideshow>,
    byId: Map<String, Entry>,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<ContentType?>(null) }
    val all = shows.associateBy { it.id } + (editing.id to editing)
    val media = entries.filter { it.content != null && it.type in SLIDE_TYPES }
    val others = shows.filter { it.id != editing.id }
    val embeddable = others.filter { !reaches(it, editing.id, all) }
    val blocked = others.size - embeddable.size
    fun toggle(item: String) {
        if (item in selected) selected.remove(item) else selected.add(item)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                        title = {
                            Column {
                                Text("Add to slideshow", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Tap in play order",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            Button(
                                enabled = selected.isNotEmpty(),
                                modifier = Modifier.padding(end = 8.dp),
                                onClick = { onAdd(selected.toList()) },
                            ) { Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text("Media (${media.size})") },
                            icon = { Icon(Icons.Default.Collections, null) },
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text("Slideshows (${embeddable.size})") },
                            icon = { Icon(Icons.Default.Slideshow, null) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (tab == 0) {
                    val types = SLIDE_TYPES.filter { t -> media.any { it.type == t } }
                    val shown = media.filter { filter == null || it.type == filter }
                    Column(Modifier.fillMaxSize()) {
                        if (types.size > 1) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") }) }
                                items(types) { t ->
                                    FilterChip(
                                        selected = filter == t,
                                        onClick = { filter = if (filter == t) null else t },
                                        label = { Text(t.folder) },
                                        leadingIcon = { Icon(typeIcon(t), null, Modifier.size(18.dp)) },
                                    )
                                }
                            }
                        }
                        if (media.isEmpty()) {
                            PickerEmpty(Icons.Default.Collections, "No media yet", "Upload videos, images, audio or PDFs first.")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(140.dp),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(shown, key = { it.id }) { e ->
                                    val n = selected.indexOf(e.id)
                                    Column(Modifier.clip(MaterialTheme.shapes.small).clickable { toggle(e.id) }) {
                                        Box {
                                            Thumb(
                                                repo, e,
                                                Modifier.fillMaxWidth().aspectRatio(16 / 9f).then(
                                                    if (n >= 0) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier,
                                                ),
                                            )
                                            SelectBadge(n, Modifier.align(Alignment.TopEnd).padding(6.dp))
                                        }
                                        Text(
                                            e.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
                                        )
                                        Text(
                                            mediaSubtitle(e),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (embeddable.isEmpty()) {
                            item {
                                PickerEmpty(
                                    Icons.Default.Slideshow,
                                    "No other slideshows to add",
                                    "Create more slideshows to combine them into this one.",
                                )
                            }
                        }
                        items(embeddable, key = { it.id }) { s ->
                            val ref = Slideshow.ref(s.id)
                            val n = selected.indexOf(ref)
                            val st = showStats(s, all, byId)
                            Surface(
                                onClick = { toggle(ref) },
                                shape = MaterialTheme.shapes.medium,
                                color = if (n >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                border = if (n >= 0) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Mosaic(repo, st.thumbs, Modifier.size(64.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${plural(st.slides, "slide")} · ~${formatDuration(st.durationMs)}" +
                                                if (st.nested > 0) " · contains ${plural(st.nested, "slideshow")}" else "",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    SelectBadge(n, Modifier, showEmpty = true)
                                }
                            }
                        }
                        if (blocked > 0) {
                            item {
                                Text(
                                    "${plural(blocked, "slideshow")} already contain${if (blocked == 1) "s" else ""} this one, " +
                                        "so ${if (blocked == 1) "it" else "they"} can't be added (it would play forever).",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerEmpty(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Numbered circle when selected (tap order); an empty ring when [showEmpty]. */
@Composable
private fun SelectBadge(index: Int, modifier: Modifier, showEmpty: Boolean = false) {
    if (index >= 0) {
        Box(
            modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
    } else if (showEmpty) {
        Box(modifier.size(28.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    } else {
        Box(modifier.size(28.dp).background(Color.Black.copy(alpha = 0.25f), CircleShape).border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape))
    }
}

// =============================================================================================
// Shared bits
// =============================================================================================

@Composable
private fun NumberBadge(n: Int, modifier: Modifier, small: Boolean = false) {
    val size: Dp = if (small) 18.dp else 24.dp
    Box(
        modifier.size(size).background(Color.Black.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$n",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun StatChip(icon: ImageVector, text: String, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (highlight) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = MaterialTheme.shapes.small, color = bg) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = fg)
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

/** Up to four thumbnails in a rounded square: 1 full, 2 side by side, 3–4 as a 2×2 grid. */
@Composable
private fun Mosaic(repo: AdminRepository, thumbs: List<Entry?>, modifier: Modifier) {
    val gap = 2.dp
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when (thumbs.size) {
            0 -> Icon(Icons.Default.Slideshow, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            1 -> Thumb(repo, thumbs[0], Modifier.fillMaxSize(), shaped = false)
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                thumbs.forEach { Thumb(repo, it, Modifier.weight(1f).fillMaxSize(), shaped = false) }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                listOf(thumbs.take(2), thumbs.drop(2).take(2)).forEach { rowThumbs ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        rowThumbs.forEach { Thumb(repo, it, Modifier.weight(1f).fillMaxSize(), shaped = false) }
                        if (rowThumbs.size == 1) Box(Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(
    repo: AdminRepository,
    e: Entry?,
    modifier: Modifier,
    shaped: Boolean = true,
    fallback: ImageVector? = null,
    dimNotReady: Boolean = true,
) {
    val ctx = LocalContext.current
    Box(
        modifier
            .then(if (shaped) Modifier.clip(MaterialTheme.shapes.small) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            fallback ?: e?.type?.let(::typeIcon) ?: Icons.Default.BrokenImage,
            null,
            Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        e?.content?.let(repo::thumbUrl)?.let { url ->
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).memoryCacheKey(e.id).diskCacheKey(e.id).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (dimNotReady && e != null && e.status != EntryStatus.READY) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
    }
}

private fun typeIcon(t: ContentType): ImageVector = when (t) {
    ContentType.VIDEO -> Icons.Default.Movie
    ContentType.IMAGE -> Icons.Default.Image
    ContentType.AUDIO -> Icons.Default.MusicNote
    ContentType.PDF -> Icons.Default.PictureAsPdf
    else -> Icons.Default.InsertDriveFile
}

private fun typeName(t: ContentType) = if (t == ContentType.PDF) "PDF" else t.folder.removeSuffix("s")

private fun mediaSubtitle(e: Entry): String {
    val c = e.content
    val extra = when (e.type) {
        ContentType.VIDEO, ContentType.AUDIO -> c?.durationMs?.takeIf { it > 0 }?.let(::formatDuration)
        ContentType.PDF -> c?.pageCount?.takeIf { it > 0 }?.let { plural(it, "page") }
        else -> null
    }
    val ready = if (e.status == EntryStatus.READY) "" else " · not on board"
    return typeName(e.type) + (extra?.let { " · $it" } ?: "") + ready
}

private fun slideLabel(e: Entry, seconds: Int): String {
    val c = e.content
    val timing = when (e.type) {
        ContentType.VIDEO, ContentType.AUDIO -> c?.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) } ?: "plays to end"
        ContentType.PDF -> c?.pageCount?.takeIf { it > 0 }?.let { "$it pages × ${seconds}s" } ?: "${seconds}s per page"
        else -> "${seconds}s"
    }
    return "${typeName(e.type)} · $timing"
}

private class ShowStats(val slides: Int, val durationMs: Long, val nested: Int, val thumbs: List<Entry?>)

private fun showStats(show: Slideshow, all: Map<String, Slideshow>, byId: Map<String, Entry>): ShowStats {
    val flat = show.flatten(all)
    return ShowStats(
        slides = flat.size,
        durationMs = flat.durationMs(byId),
        nested = show.items.count { Slideshow.showIdOf(it) != null },
        thumbs = flat.asSequence().map { it.contentId }.distinct().mapNotNull { byId[it] }.take(4).toList(),
    )
}

/** Estimated play time; each slide uses the timing of the slideshow it came from. */
private fun List<Slide>.durationMs(byId: Map<String, Entry>): Long = sumOf { s ->
    val e = byId[s.contentId] ?: return@sumOf 0L
    when (e.type) {
        ContentType.VIDEO, ContentType.AUDIO -> e.content?.durationMs ?: 0L
        ContentType.PDF -> (e.content?.pageCount?.coerceAtLeast(1) ?: 1) * s.seconds * 1000L
        else -> s.seconds * 1000L
    }
}

/** True if [targetId] is reachable from [from] through nested slideshows (embedding would loop). */
private fun reaches(from: Slideshow, targetId: String, all: Map<String, Slideshow>, seen: MutableSet<String> = mutableSetOf()): Boolean {
    if (!seen.add(from.id)) return false
    return from.items.any { item ->
        val nested = Slideshow.showIdOf(item) ?: return@any false
        nested == targetId || all[nested]?.let { reaches(it, targetId, all, seen) } == true
    }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
