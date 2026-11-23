package app.androplay.board

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import app.androplay.protocol.ContentType
import app.androplay.protocol.Item
import app.androplay.protocol.Manifest
import app.androplay.protocol.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What is physically on this board and playable right now. Downloads land in
 * /sdcard/AndroPlay/<Type>/; files already in the board's shared folders are indexed in place.
 */
class LocalStore(private val ctx: Context) {
    val root: File = File(Environment.getExternalStorageDirectory(), "AndroPlay")
    val incoming: File get() = File(root, ".incoming").apply { mkdirs() }

    private val sharedRoots: List<File> = listOf(
        Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM,
    ).map { Environment.getExternalStoragePublicDirectory(it) }

    @Serializable
    private data class Entry(val item: Item, val path: String, val mtime: Long)

    @Serializable
    private data class Index(val version: Long = 0, val entries: List<Entry> = emptyList())

    private val indexFile = File(ctx.filesDir, "local-index.json")
    private val lock = Any()
    private val byId = LinkedHashMap<String, Entry>()
    private val byHandle = HashMap<Int, Entry>()
    private var version = 0L

    private val _manifest = MutableStateFlow(Manifest(0, emptyList(), 0, 0))
    val manifest: StateFlow<Manifest> = _manifest.asStateFlow()

    init {
        load()
        publish()
    }

    fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    fun items(): List<Item> = _manifest.value.items

    fun ids(): Set<String> = synchronized(lock) { byId.keys.toSet() }

    fun item(id: String): Item? = synchronized(lock) { byId[id]?.item }

    fun byHandle(handle: Int): Item? = synchronized(lock) { byHandle[handle]?.item }

    fun fileOf(id: String): File? = synchronized(lock) { byId[id]?.path }?.let(::File)?.takeIf { it.isFile }

    fun put(item: Item, file: File) {
        synchronized(lock) {
            byId[item.id] = Entry(item, file.absolutePath, file.lastModified())
            reindex()
            version++
        }
        save()
        if (file.startsWith(root)) MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
    }

    /** Refreshes metadata (name, handle…) coming from the backend without touching the file. */
    fun update(item: Item) {
        val changed = synchronized(lock) {
            val e = byId[item.id] ?: return
            if (e.item == item) return
            byId[item.id] = e.copy(item = item)
            reindex()
            version++
            true
        }
        if (changed) save()
    }

    fun remove(id: String, deleteFile: Boolean): Boolean {
        val entry = synchronized(lock) { byId.remove(id)?.also { reindex(); version++ } } ?: return false
        if (deleteFile) File(entry.path).delete()
        save()
        return true
    }

    /** Files that are new or changed since the last scan, plus ids whose files disappeared. */
    fun scanChanges(): Pair<List<File>, List<String>> {
        if (!hasStorageAccess()) return emptyList<File>() to emptyList()
        val snapshot = synchronized(lock) { byId.values.associateBy { it.path } }
        val seen = HashSet<String>()
        val changed = ArrayList<File>()
        for (file in candidates()) {
            seen += file.absolutePath
            val prev = snapshot[file.absolutePath]
            if (prev == null || prev.mtime != file.lastModified() || prev.item.size != file.length()) changed += file
        }
        val vanished = snapshot.values.filter { it.path !in seen && !File(it.path).isFile }.map { it.item.id }
        return changed to vanished
    }

    fun destinationFor(rawName: String): File {
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').ifEmpty { "file" }
        val dir = File(root, ContentType.of(name).folder).apply { mkdirs() }
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return file
    }

    /** Duration (ms) for media. */
    fun probe(file: File, type: ContentType): Pair<Long, Int> = when (type) {
        ContentType.VIDEO -> runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L) to 0
        else -> 0L to 0
    }

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)

    private fun candidates(): Sequence<File> = sequence {
        root.mkdirs()
        yieldAll(walk(root, everything = true))
        for (dir in sharedRoots) if (dir.isDirectory) yieldAll(walk(dir, everything = false))
    }

    private fun walk(dir: File, everything: Boolean): Sequence<File> = dir.walkTopDown()
        .maxDepth(6)
        .onEnter { it == dir || !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.startsWith(".") }
        .filter { ContentType.of(it.name) != ContentType.OTHER }

    private fun reindex() {
        byHandle.clear()
        byId.values.forEach { byHandle[it.item.handle] = it }
    }

    private fun save() {
        val index = synchronized(lock) { Index(version, byId.values.toList()) }
        synchronized(indexFile) {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(ProtocolJson.encodeToString(Index.serializer(), index))
            tmp.renameTo(indexFile)
        }
        publish()
    }

    private fun load() {
        val index = runCatching { ProtocolJson.decodeFromString(Index.serializer(), indexFile.readText()) }.getOrNull() ?: return
        synchronized(lock) {
            index.entries.forEach { byId[it.item.id] = it }
            reindex()
            version = index.version
        }
    }

    private fun publish() = synchronized(lock) {
        val items = byId.values.map { it.item }.sortedByDescending { it.addedAt }
        _manifest.value = Manifest(version, items, freeBytes(), 0)
    }
}
