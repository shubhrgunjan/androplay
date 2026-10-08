package app.androplay.protocol

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
enum class Status { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR, EXTERNAL }

/** What the board is showing right now. Binary form is exactly [SIZE] bytes (one BLE notification). */
@Serializable
data class PlaybackState(
    val status: Status = Status.IDLE,
    val handle: Int = NO_HANDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 0,
    val muted: Boolean = false,
    val page: Int = 0,
    val pageCount: Int = 0,
    val manifestVersion: Long = 0,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(status.ordinal.toByte())
        putShort(handle.toShort())
        putInt(positionMs.u32())
        putInt(durationMs.u32())
        put((volume.coerceIn(0, 100) or (if (muted) 0x80 else 0)).toByte())
        putShort(page.toShort())
        putShort(pageCount.toShort())
        putInt(manifestVersion.u32())
    }.array()

    companion object {
        const val SIZE = 20
        const val NO_HANDLE = 0xFFFF

        fun decode(bytes: ByteArray): PlaybackState? {
            if (bytes.size < SIZE) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val status = Status.entries.getOrNull(b.get().toInt()) ?: return null
            val handle = b.short.toInt() and 0xFFFF
            val position = b.int.toLong() and 0xFFFFFFFFL
            val duration = b.int.toLong() and 0xFFFFFFFFL
            val vol = b.get().toInt() and 0xFF
            val page = b.short.toInt() and 0xFFFF
            val pageCount = b.short.toInt() and 0xFFFF
            val manifest = b.int.toLong() and 0xFFFFFFFFL
            return PlaybackState(status, handle, position, duration, vol and 0x7F, vol and 0x80 != 0, page, pageCount, manifest)
        }

        private fun Long.u32(): Int = coerceIn(0, 0xFFFFFFFFL).toInt()
    }
}
