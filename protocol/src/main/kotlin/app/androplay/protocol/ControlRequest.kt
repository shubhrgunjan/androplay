package app.androplay.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.Base64
import java.util.UUID

@Serializable
data class ControlRequest(
    val id: String,
    val expiresAt: Long,
    private val command: String,
    private val contentIds: List<String> = emptyList(),
) {
    fun encode(): ByteArray = byteArrayOf(MARKER) + ProtocolJson.encodeToString(this).encodeToByteArray()

    fun resolve(handleFor: (String) -> Int?): Command? {
        val bytes = runCatching { Base64.getDecoder().decode(command) }.getOrNull() ?: return null
        val cmd = Command.decode(bytes) ?: return null
        return when (cmd) {
            is Command.Play -> contentIds.singleOrNull()?.let(handleFor)?.let { cmd.copy(handle = it) }
            is Command.SetQueue -> if (contentIds.size == cmd.handles.size) cmd.copy(handles = contentIds.mapNotNull(handleFor)) else null
            is Command.Slideshow -> if (contentIds.size == cmd.handles.size) {
                cmd.copy(handles = contentIds.map { handleFor(it) ?: -1 })
                    .takeIf { show -> show.handles.any { it >= 0 } }
            } else null
            is Command.Auth -> null
            else -> cmd
        }
    }

    companion object {
        const val MARKER: Byte = 0
        private const val LIFE_MS = 15_000L

        fun create(cmd: Command, idFor: (Int) -> String?): ControlRequest? {
            val handles = when (cmd) {
                is Command.Play -> listOf(cmd.handle)
                is Command.SetQueue -> cmd.handles
                is Command.Slideshow -> cmd.handles
                else -> emptyList()
            }.take(Command.MAX_QUEUE)
            val ids = handles.map { idFor(it) ?: return null }
            return ControlRequest(UUID.randomUUID().toString(), System.currentTimeMillis() + LIFE_MS, Base64.getEncoder().encodeToString(cmd.encode()), ids)
        }

        fun decode(bytes: ByteArray): ControlRequest? {
            if (bytes.firstOrNull() != MARKER || bytes.size > 160_000) return null
            return runCatching {
                ProtocolJson.decodeFromString<ControlRequest>(bytes.decodeToString(startIndex = 1))
                    .takeIf { UUID.fromString(it.id).toString() == it.id }
            }.getOrNull()
        }
    }
}
