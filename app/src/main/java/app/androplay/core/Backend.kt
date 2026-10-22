package app.androplay.core

import android.util.Base64
import app.androplay.protocol.Command
import app.androplay.protocol.ContentType
import app.androplay.protocol.Item
import app.androplay.protocol.Origin
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.ProtocolJson
import app.androplay.protocol.encode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.Closeable

/** Content metadata as stored in Redis (`ap:content:{id}`). Bytes live in object storage. */
data class Content(
    val id: String,
    val handle: Int,
    val name: String,
    val type: ContentType,
    val mime: String,
    val size: Long,
    val objectKey: String = "",
    val ready: Boolean = false,
    val origin: Origin = Origin.UPLOAD,
    /** Board id for board-local files that exist only there; empty = every board syncs it. */
    val onlyOn: String = "",
    val addedBy: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val pageCount: Int = 0,
    val hasThumb: Boolean = false,
    /** Bytes the uploader has sent so far (for live upload progress on every device). */
    val uploaded: Long = 0,
) {
    fun isFor(boardId: String) = onlyOn.isEmpty() || onlyOn == boardId

    fun toItem() = Item(id, handle, name, type, mime, size, durationMs, pageCount, addedAt, origin)

    fun toHash(): Array<String> = arrayOf(
        "id", id, "handle", handle.toString(), "name", name, "type", type.name, "mime", mime,
        "size", size.toString(), "objectKey", objectKey, "status", if (ready) "ready" else "uploading",
        "origin", origin.name, "onlyOn", onlyOn, "addedBy", addedBy, "addedAt", addedAt.toString(),
        "durationMs", durationMs.toString(), "pageCount", pageCount.toString(), "thumb", if (hasThumb) "1" else "0",
        "uploaded", uploaded.toString(),
    )

    companion object {
        fun fromHash(h: Map<String, String>): Content? {
            val id = h["id"] ?: return null
            return Content(
                id = id,
                handle = h["handle"]?.toIntOrNull() ?: return null,
                name = h["name"] ?: id,
                type = h["type"]?.let { runCatching { ContentType.valueOf(it) }.getOrNull() } ?: ContentType.OTHER,
                mime = h["mime"].orEmpty(),
                size = h["size"]?.toLongOrNull() ?: 0,
                objectKey = h["objectKey"].orEmpty(),
                ready = h["status"] == "ready",
                origin = h["origin"]?.let { runCatching { Origin.valueOf(it) }.getOrNull() } ?: Origin.UPLOAD,
                onlyOn = h["onlyOn"].orEmpty(),
                addedBy = h["addedBy"].orEmpty(),
                addedAt = h["addedAt"]?.toLongOrNull() ?: 0,
                durationMs = h["durationMs"]?.toLongOrNull() ?: 0,
                pageCount = h["pageCount"]?.toIntOrNull() ?: 0,
                hasThumb = h["thumb"] == "1",
                uploaded = h["uploaded"]?.toLongOrNull() ?: 0,
            )
        }
    }
}

data class BoardInfo(
    val id: String,
    val name: String,
    val model: String = "",
    val ip: String = "",
    val freeBytes: Long = 0,
    val appVersion: String = "",
    val bleToken: String = "",
    val lastSeen: Long = 0,
    val online: Boolean = false,
    /** Board's in-app update status ("Downloading v0.8.0 40%", "Update failed: …"); empty when up to date. */
    val update: String = "",
) {
    fun toHash(): Array<String> = arrayOf(
        "id", id, "name", name, "model", model, "ip", ip, "freeBytes", freeBytes.toString(),
        "appVersion", appVersion, "bleToken", bleToken, "lastSeen", lastSeen.toString(), "update", update,
    )

    companion object {
        fun fromHash(h: Map<String, String>, online: Boolean): BoardInfo? = BoardInfo(
            id = h["id"] ?: return null,
            name = h["name"] ?: "Board",
            model = h["model"].orEmpty(),
            ip = h["ip"].orEmpty(),
            freeBytes = h["freeBytes"]?.toLongOrNull() ?: 0,
            appVersion = h["appVersion"].orEmpty(),
            bleToken = h["bleToken"].orEmpty(),
            lastSeen = h["lastSeen"]?.toLongOrNull() ?: 0,
            online = online,
            update = h["update"].orEmpty(),
        )
    }
}

/**
 * An ordered series played one after another (stored as JSON in `ap:shows`).
 * Items are content ids, or `show:<id>` to embed another slideshow (slideshow of slideshows).
 */
@Serializable
data class Slideshow(
    val id: String,
    val name: String,
    val items: List<String> = emptyList(),
    val imageSeconds: Int = 8, // how long each image stays up
    val loop: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Position in the slideshow list (drag to reorder); also the order used by "Play all". */
    val position: Int = 0,
) {
    companion object {
        const val SHOW_PREFIX = "show:"

        fun ref(showId: String) = SHOW_PREFIX + showId

        fun showIdOf(item: String): String? = item.takeIf { it.startsWith(SHOW_PREFIX) }?.removePrefix(SHOW_PREFIX)
    }
}

/** One playable slide after expanding nested slideshows. */
data class Slide(val contentId: String, val seconds: Int)

/**
 * Expands nested slideshows depth-first. Each slide keeps the timing of the slideshow it
 * came from; a slideshow that (indirectly) contains itself is skipped instead of looping forever.
 */
fun Slideshow.flatten(all: Map<String, Slideshow>, visiting: Set<String> = emptySet()): List<Slide> {
    if (id in visiting) return emptyList()
    val path = visiting + id
    return items.flatMap { item ->
        val nested = Slideshow.showIdOf(item)
        if (nested != null) all[nested]?.flatten(all, path).orEmpty() else listOf(Slide(item, imageSeconds))
    }
}

/**
 * Slides for "Play all": every slideshow in list order, except ones already embedded in
 * another slideshow (they play inside their parent, so they aren't repeated).
 */
fun playAllSlides(shows: List<Slideshow>): List<Slide> {
    val all = shows.associateBy { it.id }
    val embedded = shows.flatMap { s -> s.items.mapNotNull { Slideshow.showIdOf(it) } }.toSet()
    return shows.filter { it.id !in embedded }.flatMap { it.flatten(all) }
}

/** Builds the board command for [slides], keeping only items the board has ([handleOf] != null). */
fun slideshowCommand(slides: List<Slide>, loop: Boolean, handleOf: (String) -> Int?): Command.Slideshow? {
    val playable = slides.mapNotNull { s -> handleOf(s.contentId)?.let { it to s.seconds } }
    if (playable.isEmpty()) return null
    return Command.Slideshow(playable.map { it.first }, playable.first().second, loop, 0, playable.map { it.second })
}

/** Sync status of one board: what it has and what it's downloading. */
data class BoardSyncState(val have: Set<String> = emptySet(), val progress: Map<String, Long> = emptyMap())

/** Typed access to the Redis key schema documented in docs/ARCHITECTURE.md. */
class Backend(val config: BackendConfig) : Closeable {
    val url: RedisUrl = RedisUrl.parse(config.redisUrl)
    private val redis = Redis(url)
    private val p = config.prefix

    private val kContent = "${p}content"
    private val kEvents = "${p}events"
    private val kBoards = "${p}boards"
    private val kHandle = "${p}handle"
    private val kShows = "${p}shows"
    private fun kItem(id: String) = "${p}content:$id"
    private fun kBoard(b: String) = "${p}board:$b"
    private fun kOnline(b: String) = "${p}board:$b:online"
    private fun kHave(b: String) = "${p}board:$b:have"
    private fun kProgress(b: String) = "${p}board:$b:progress"
    private fun kState(b: String) = "${p}board:$b:state"
    private fun kCursor(b: String) = "${p}board:$b:cursor"

    fun commandChannel(board: String) = "${p}board:$board:cmd"
    fun stateChannel(board: String) = "${p}board:$board:live"

    fun ping() {
        redis.call("PING")
    }

    // --- Content -----------------------------------------------------------------------------

    fun newHandle(): Int {
        val n = redis.call("INCR", kHandle) as Long
        return ((n - 1) % (PlaybackState.NO_HANDLE - 1) + 1).toInt()
    }

    fun allContent(): List<Content> {
        val ids = (redis.call("ZREVRANGE", kContent, "0", "-1") as? List<*>)?.filterIsInstance<String>().orEmpty()
        return redis.pipeline(ids.map { arrayOf("HGETALL", kItem(it)) })
            .mapNotNull { (it as? List<*>)?.toStringMap()?.let(Content::fromHash) }
    }

    fun content(id: String): Content? =
        (redis.call("HGETALL", kItem(id)) as? List<*>)?.toStringMap()?.takeIf { it.isNotEmpty() }?.let(Content::fromHash)

    /** Creates or replaces metadata. Emits an event only when the content is ready to sync. */
    fun putContent(c: Content) {
        val cmds = mutableListOf(
            arrayOf("HSET", kItem(c.id), *c.toHash()),
            arrayOf("ZADD", kContent, c.addedAt.toString(), c.id),
        )
        if (c.ready) cmds += event("add", c.id)
        redis.pipeline(cmds)
    }

    fun updateContent(id: String, vararg fields: Pair<String, String>, emit: Boolean = true) {
        val cmds = mutableListOf(arrayOf("HSET", kItem(id), *fields.flatMap { listOf(it.first, it.second) }.toTypedArray()))
        if (emit) cmds += event("update", id)
        redis.pipeline(cmds)
    }

    /**
     * Live upload progress. Emits a `progress` event so boards and other admins update in
     * real time; call at most a few times per second (once per uploaded part is ideal).
     */
    fun setUploadProgress(id: String, uploaded: Long) {
        redis.pipeline(listOf(arrayOf("HSET", kItem(id), "uploaded", uploaded.toString()), event("progress", id)))
    }

    fun markReady(id: String, objectKey: String, hasThumb: Boolean) {
        redis.pipeline(
            listOf(
                arrayOf("HSET", kItem(id), "status", "ready", "objectKey", objectKey, "thumb", if (hasThumb) "1" else "0"),
                event("add", id),
            ),
        )
    }

    fun deleteContent(id: String) {
        redis.pipeline(listOf(arrayOf("DEL", kItem(id)), arrayOf("ZREM", kContent, id), event("remove", id)))
    }

    // --- App updates ------------------------------------------------------------------------

    /** Latest published build (written by scripts/publish_update.py). */
    fun latestRelease(): ReleaseInfo? {
        val h = (redis.call("HGETALL", "${p}release") as? List<*>)?.toStringMap() ?: return null
        return ReleaseInfo(
            versionCode = h["versionCode"]?.toIntOrNull() ?: return null,
            versionName = h["versionName"].orEmpty(),
            objectKey = h["objectKey"] ?: return null,
            sha256 = h["sha256"].orEmpty(),
            size = h["size"]?.toLongOrNull() ?: 0,
            notes = h["notes"].orEmpty(),
        )
    }

    // --- Slideshows --------------------------------------------------------------------------

    fun slideshows(): List<Slideshow> =
        (redis.call("HGETALL", kShows) as? List<*>)?.toStringMap()?.values
            ?.mapNotNull { runCatching { ProtocolJson.decodeFromString<Slideshow>(it) }.getOrNull() }
            ?.sortedWith(compareBy<Slideshow> { it.position }.thenByDescending { it.updatedAt })
            .orEmpty()

    fun putSlideshow(show: Slideshow) = putSlideshows(listOf(show))

    /** Saves several at once (e.g. new positions after a drag) with a single event. */
    fun putSlideshows(shows: List<Slideshow>) {
        if (shows.isEmpty()) return
        val fields = shows.flatMap { listOf(it.id, ProtocolJson.encodeToString(it)) }.toTypedArray()
        redis.pipeline(listOf(arrayOf("HSET", kShows, *fields), event("show", shows.first().id)))
    }

    fun deleteSlideshow(id: String) {
        redis.pipeline(listOf(arrayOf("HDEL", kShows, id), event("show", id)))
    }

    private fun event(op: String, id: String) = arrayOf("XADD", kEvents, "MAXLEN", "~", "10000", "*", "op", op, "id", id)

    fun lastEventId(): String {
        val r = redis.call("XREVRANGE", kEvents, "+", "-", "COUNT", "1") as? List<*>
        return (r?.firstOrNull() as? List<*>)?.firstOrNull() as? String ?: "0-0"
    }

    // --- Boards ------------------------------------------------------------------------------

    fun heartbeat(info: BoardInfo) {
        redis.pipeline(
            listOf(
                arrayOf("SADD", kBoards, info.id),
                arrayOf("HSET", kBoard(info.id), *info.toHash()),
                arrayOf("SET", kOnline(info.id), "1", "EX", ONLINE_TTL),
            ),
        )
    }

    fun stillOnline(board: String) {
        redis.call("SET", kOnline(board), "1", "EX", ONLINE_TTL)
    }

    fun boards(): List<BoardInfo> {
        val ids = (redis.call("SMEMBERS", kBoards) as? List<*>)?.filterIsInstance<String>().orEmpty()
        val replies = redis.pipeline(ids.flatMap { listOf(arrayOf("HGETALL", kBoard(it)), arrayOf("EXISTS", kOnline(it))) })
        return ids.indices.mapNotNull { i ->
            val hash = (replies[i * 2] as? List<*>)?.toStringMap() ?: return@mapNotNull null
            BoardInfo.fromHash(hash, online = replies[i * 2 + 1] == 1L)
        }.sortedBy { it.name }
    }

    fun forgetBoard(board: String) {
        redis.pipeline(
            listOf(
                arrayOf("SREM", kBoards, board),
                arrayOf("DEL", kBoard(board), kHave(board), kProgress(board), kState(board), kCursor(board)),
            ),
        )
    }

    fun syncState(board: String): BoardSyncState {
        val r = redis.pipeline(listOf(arrayOf("SMEMBERS", kHave(board)), arrayOf("HGETALL", kProgress(board))))
        return BoardSyncState(
            have = (r[0] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
            progress = (r[1] as? List<*>)?.toStringMap()?.mapValues { it.value.toLongOrNull() ?: 0 }.orEmpty(),
        )
    }

    fun setHave(board: String, ids: Set<String>) {
        val current = (redis.call("SMEMBERS", kHave(board)) as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val cmds = mutableListOf<Array<String>>()
        (ids - current).chunked(500).forEach { cmds += arrayOf("SADD", kHave(board), *it.toTypedArray()) }
        (current - ids).chunked(500).forEach { cmds += arrayOf("SREM", kHave(board), *it.toTypedArray()) }
        redis.pipeline(cmds)
    }

    fun addHave(board: String, id: String) {
        redis.pipeline(listOf(arrayOf("SADD", kHave(board), id), arrayOf("HDEL", kProgress(board), id)))
    }

    fun removeHave(board: String, id: String) {
        redis.pipeline(listOf(arrayOf("SREM", kHave(board), id), arrayOf("HDEL", kProgress(board), id)))
    }

    fun setProgress(board: String, id: String, bytes: Long) {
        redis.call("HSET", kProgress(board), id, bytes.toString())
    }

    fun cursor(board: String): String? = redis.call("GET", kCursor(board)) as? String

    fun setCursor(board: String, id: String) {
        redis.call("SET", kCursor(board), id)
    }

    // --- Control -----------------------------------------------------------------------------

    fun publishCommand(board: String, cmd: Command) {
        redis.call("PUBLISH", commandChannel(board), cmd.encode().b64())
    }

    fun publishState(board: String, state: PlaybackState) {
        val v = state.encode().b64()
        redis.pipeline(listOf(arrayOf("SET", kState(board), v), arrayOf("PUBLISH", stateChannel(board), v)))
    }

    fun state(board: String): PlaybackState? =
        (redis.call("GET", kState(board)) as? String)?.let { PlaybackState.decode(it.unb64()) }

    /** A dedicated connection for blocking XREAD, so it never stalls normal commands. */
    fun eventReader() = EventReader(Redis(url, readTimeoutMs = 75_000), kEvents)

    override fun close() = redis.close()

    data class Event(val streamId: String, val op: String, val contentId: String)

    class EventReader(private val redis: Redis, private val key: String) : Closeable {
        fun read(after: String, blockMs: Long = 55_000): List<Event> {
            val reply = redis.call("XREAD", "COUNT", "200", "BLOCK", blockMs.toString(), "STREAMS", key, after) as? List<*>
                ?: return emptyList()
            val entries = ((reply.firstOrNull() as? List<*>)?.getOrNull(1) as? List<*>).orEmpty()
            return entries.mapNotNull { e ->
                val entry = e as? List<*> ?: return@mapNotNull null
                val fields = (entry.getOrNull(1) as? List<*>)?.toStringMap() ?: return@mapNotNull null
                Event(entry[0] as String, fields["op"].orEmpty(), fields["id"].orEmpty())
            }
        }

        override fun close() = redis.close()
    }
}

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

/** Seconds a board counts as online after its last heartbeat (sent every minute). */
private const val ONLINE_TTL = "150"

private fun List<*>.toStringMap(): Map<String, String> {
    val m = HashMap<String, String>(size / 2)
    var i = 0
    while (i + 1 < size) {
        val k = get(i) as? String
        val v = get(i + 1) as? String
        if (k != null && v != null) m[k] = v
        i += 2
    }
    return m
}
