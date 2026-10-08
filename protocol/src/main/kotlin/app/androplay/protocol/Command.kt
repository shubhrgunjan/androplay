package app.androplay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Remote-control commands. Binary-encoded so each one is a single small BLE write. */
sealed interface Command {
    /** [start] is a position in ms for media, or a page index for PDFs. */
    data class Play(val handle: Int, val start: Int = 0) : Command
    data object Pause : Command
    data object Resume : Command
    data object Toggle : Command
    data class Seek(val positionMs: Int) : Command
    data class SeekBy(val deltaMs: Int) : Command
    data class Volume(val percent: Int) : Command
    data object ToggleMute : Command
    /** Next page for PDFs, otherwise next item in the queue. */
    data object Next : Command
    data object Prev : Command
    data class GoToPage(val page: Int) : Command
    data object Stop : Command
    data object ToggleLibrary : Command
    /** Presentation order used by Next/Prev and for preloading. */
    data class SetQueue(val handles: List<Int>) : Command
    class Auth(val token: ByteArray) : Command

    companion object {
        const val MAX_QUEUE = 2000

        fun decode(bytes: ByteArray): Command? = try {
            decodeOrNull(bytes)
        } catch (e: RuntimeException) {
            null
        }

        private fun decodeOrNull(bytes: ByteArray): Command? {
            if (bytes.isEmpty()) return null
            val b = ByteBuffer.wrap(bytes, 1, bytes.size - 1).order(ByteOrder.LITTLE_ENDIAN)
            return when (bytes[0].toInt()) {
                OP_PLAY -> Play(b.u16(), b.int)
                OP_PAUSE -> Pause
                OP_RESUME -> Resume
                OP_TOGGLE -> Toggle
                OP_SEEK -> Seek(b.int)
                OP_SEEK_BY -> SeekBy(b.int)
                OP_VOLUME -> Volume(b.get().toInt() and 0xFF)
                OP_MUTE -> ToggleMute
                OP_NEXT -> Next
                OP_PREV -> Prev
                OP_PAGE -> GoToPage(b.u16())
                OP_STOP -> Stop
                OP_LIBRARY -> ToggleLibrary
                OP_SET_QUEUE -> SetQueue(List(b.u16()) { b.u16() })
                OP_AUTH -> Auth(ByteArray(b.remaining()).also { b.get(it) })
                else -> null
            }
        }
    }
}

fun Command.encode(): ByteArray = when (this) {
    is Command.Play -> frame(OP_PLAY, 6) { putShort(handle.toShort()); putInt(start) }
    Command.Pause -> frame(OP_PAUSE)
    Command.Resume -> frame(OP_RESUME)
    Command.Toggle -> frame(OP_TOGGLE)
    is Command.Seek -> frame(OP_SEEK, 4) { putInt(positionMs) }
    is Command.SeekBy -> frame(OP_SEEK_BY, 4) { putInt(deltaMs) }
    is Command.Volume -> frame(OP_VOLUME, 1) { put(percent.coerceIn(0, 100).toByte()) }
    Command.ToggleMute -> frame(OP_MUTE)
    Command.Next -> frame(OP_NEXT)
    Command.Prev -> frame(OP_PREV)
    is Command.GoToPage -> frame(OP_PAGE, 2) { putShort(page.toShort()) }
    Command.Stop -> frame(OP_STOP)
    Command.ToggleLibrary -> frame(OP_LIBRARY)
    is Command.SetQueue -> {
        val hs = handles.take(Command.MAX_QUEUE)
        frame(OP_SET_QUEUE, 2 + hs.size * 2) {
            putShort(hs.size.toShort())
            hs.forEach { putShort(it.toShort()) }
        }
    }
    is Command.Auth -> frame(OP_AUTH, token.size) { put(token) }
}

private inline fun frame(op: Int, size: Int = 0, body: ByteBuffer.() -> Unit = {}): ByteArray {
    val b = ByteBuffer.allocate(1 + size).order(ByteOrder.LITTLE_ENDIAN)
    b.put(op.toByte())
    b.body()
    return b.array()
}

private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF

private const val OP_PLAY = 0x01
private const val OP_PAUSE = 0x02
private const val OP_RESUME = 0x03
private const val OP_TOGGLE = 0x04
private const val OP_SEEK = 0x05
private const val OP_SEEK_BY = 0x06
private const val OP_VOLUME = 0x07
private const val OP_MUTE = 0x08
private const val OP_NEXT = 0x09
private const val OP_PREV = 0x0A
private const val OP_PAGE = 0x0B
private const val OP_STOP = 0x0C
private const val OP_LIBRARY = 0x0D
private const val OP_SET_QUEUE = 0x0E
private const val OP_AUTH = 0x7F
