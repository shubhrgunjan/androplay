package app.androplay.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ContentType(val folder: String) {
    VIDEO("Videos"),
    IMAGE("Images"),
    OTHER("Other");

    companion object {
        fun of(fileName: String, mime: String? = null): ContentType {
            val m = mime?.lowercase()?.takeUnless { it.isBlank() || it == MimeTypes.BINARY }
                ?: MimeTypes.of(fileName)
            return when {
                m.startsWith("video/") -> VIDEO
                m.startsWith("image/") -> IMAGE
                else -> OTHER
            }
        }
    }
}

object MimeTypes {
    const val BINARY = "application/octet-stream"

    private val byExtension = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "ts" to "video/mp2t", "avi" to "video/x-msvideo",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun of(name: String): String = byExtension[extension(name)] ?: BINARY
}

@Serializable
enum class Origin { BOARD, UPLOAD }

/** One piece of content stored on the board. [id] is its [Fingerprint]; [handle] is the short BLE id. */
@Serializable
data class Item(
    val id: String,
    val handle: Int,
    val name: String,
    val type: ContentType,
    val mime: String,
    val size: Long,
    val durationMs: Long = 0,
    val pageCount: Int = 0,
    val addedAt: Long = 0,
    val origin: Origin = Origin.BOARD,
)

@Serializable
data class Manifest(
    val version: Long,
    val items: List<Item>,
    val freeBytes: Long,
    val totalBytes: Long,
)

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val apiVersion: Int = Net.API_VERSION,
)

@Serializable
data class PairRequest(val pin: String, val deviceName: String)

@Serializable
data class PairResponse(val token: String)

@Serializable
data class UploadStatus(val id: String, val received: Long, val size: Long, val complete: Boolean)
