package app.androplay.admin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.androplay.core.Slideshow
import app.androplay.protocol.ContentType
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.util.UUID

/** Types a slideshow can time: documents open in other apps, so they're excluded. */
private val SLIDE_TYPES = setOf(ContentType.VIDEO, ContentType.IMAGE, ContentType.AUDIO, ContentType.PDF)

fun newSlideshow(existing: Int) = Slideshow(id = UUID.randomUUID().toString().take(12), name = "Slideshow ${existing + 1}")

@Composable
fun SlideshowsPane(repo: AdminRepository, entries: List<Entry>, onEdit: (Slideshow) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shows by repo.shows.collectAsState()
    var confirmDelete by remember { mutableStateOf<Slideshow?>(null) }
    val byId = entries.associateBy { it.id }

    if (shows.isEmpty()) {
        Text(
            "No slideshows yet. Tap + to create one: pick media in the order you want, set how long each image shows, and play it on the board in one tap.",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(shows, key = { it.id }) { show ->
            val ready = repo.playableHandles(show).size
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(show.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${show.items.size} items · $ready ready on board · ${show.imageSeconds}s per image" +
                            (if (show.loop) " · loops" else "") + " · ~${formatDuration(estimate(show, byId))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        show.items.take(5).forEach { id -> MiniThumb(repo, byId[id], Modifier.width(56.dp)) }
                        if (show.items.size > 5) Text("+${show.items.size - 5}", Modifier.align(Alignment.CenterVertically))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            scope.launch {
                                val n = repo.playShow(show)
                                val msg = if (n == 0) "Nothing in this slideshow is on the board yet" else "Playing ${show.name} ($n items)"
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text(" Play")
                        }
                        OutlinedButton(onClick = { onEdit(show) }) {
                            Icon(Icons.Default.Edit, null)
                            Text(" Edit")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { confirmDelete = show }) { Icon(Icons.Default.Delete, "Delete slideshow") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { show ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${show.name}\"?") },
            text = { Text("Only the slideshow is deleted; its media stays in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.deleteShow(show.id) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SlideshowEditor(repo: AdminRepository, entries: List<Entry>, initial: Slideshow, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var seconds by remember { mutableFloatStateOf(initial.imageSeconds.toFloat()) }
    var loop by remember { mutableStateOf(initial.loop) }
    val items = remember { mutableStateListOf<String>().apply { addAll(initial.items) } }
    var picking by remember { mutableStateOf(false) }
    val byId = entries.associateBy { it.id }

    fun current() = initial.copy(name = name.trim(), items = items.toList(), imageSeconds = seconds.toInt(), loop = loop)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Text("Slideshow", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(
                        enabled = name.isNotBlank() && items.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                if (repo.saveShow(current())) onDismiss()
                                else Toast.makeText(ctx, "Couldn't save: check the connection", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text("Save") }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Each image / PDF page shows for ${seconds.toInt()} s · videos play to the end", style = MaterialTheme.typography.labelLarge)
                Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 2f..60f, steps = 57)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loop (start again after the last item)", modifier = Modifier.weight(1f))
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${items.size} items · ~${formatDuration(estimate(current(), byId))}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { picking = true }) {
                        Icon(Icons.Default.Add, null)
                        Text(" Add media")
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(items, key = { i, id -> "$i-$id" }) { i, id ->
                        val e = byId[id]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
                            MiniThumb(repo, e, Modifier.width(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e?.name ?: "(removed from library)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    e?.let { slideLabel(it, seconds.toInt()) } ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(enabled = i > 0, onClick = { items.add(i - 1, items.removeAt(i)) }) { Icon(Icons.Default.ArrowUpward, "Move up") }
                            IconButton(enabled = i < items.lastIndex, onClick = { items.add(i + 1, items.removeAt(i)) }) { Icon(Icons.Default.ArrowDownward, "Move down") }
                            IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        MediaPicker(repo, entries, onDismiss = { picking = false }) { picked ->
            items.addAll(picked)
            picking = false
        }
    }
}

/** Multi-select in tap order; the numbers show the order they'll be added. */
@Composable
private fun MediaPicker(repo: AdminRepository, entries: List<Entry>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    val choices = entries.filter { it.content != null && it.type in SLIDE_TYPES }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Text("Tap in play order", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(enabled = selected.isNotEmpty(), onClick = { onAdd(selected.toList()) }) { Text("Add ${selected.size}") }
                }
                if (choices.isEmpty()) Text("Upload videos, images or PDFs first.", Modifier.padding(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(130.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(choices, key = { it.id }) { e ->
                        val n = selected.indexOf(e.id)
                        Box(
                            Modifier
                                .clickable { if (n >= 0) selected.remove(e.id) else selected.add(e.id) }
                                .then(if (n >= 0) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier),
                        ) {
                            Column {
                                MiniThumb(repo, e, Modifier.fillMaxWidth())
                                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp))
                            }
                            if (n >= 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${n + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniThumb(repo: AdminRepository, e: Entry?, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier.aspectRatio(16 / 9f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(
            e?.name?.substringAfterLast('.', e.type.name)?.uppercase()?.take(4) ?: "?",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        e?.content?.let(repo::thumbUrl)?.let { url ->
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).memoryCacheKey(e.id).diskCacheKey(e.id).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (e != null && e.status != EntryStatus.READY) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
    }
}

private fun slideLabel(e: Entry, seconds: Int): String {
    val c = e.content
    val timing = when (e.type) {
        ContentType.VIDEO, ContentType.AUDIO -> c?.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) } ?: "plays to end"
        ContentType.PDF -> c?.pageCount?.takeIf { it > 0 }?.let { "$it pages × ${seconds}s" } ?: "${seconds}s per page"
        else -> "${seconds}s"
    }
    val ready = if (e.status == EntryStatus.READY) "" else " · not on board yet"
    return "${e.type.folder.removeSuffix("s")} · $timing$ready"
}

private fun estimate(show: Slideshow, byId: Map<String, Entry>): Long = show.items.sumOf { id ->
    val e = byId[id] ?: return@sumOf 0L
    when (e.type) {
        ContentType.VIDEO, ContentType.AUDIO -> e.content?.durationMs ?: 0L
        ContentType.PDF -> (e.content?.pageCount?.coerceAtLeast(1) ?: 1) * show.imageSeconds * 1000L
        else -> show.imageSeconds * 1000L
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
