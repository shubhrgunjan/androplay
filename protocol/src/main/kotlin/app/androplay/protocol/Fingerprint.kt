package app.androplay.protocol

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Fast content id: SHA-256 over (size, first 1 MiB, last 1 MiB), truncated to 128 bits.
 * Reads at most 2 MiB regardless of file size, so multi-GB videos are identified instantly
 * on both the phone and the board.
 */
object Fingerprint {
    const val SAMPLE = 1 shl 20

    fun of(file: File): String = RandomAccessFile(file, "r").use { of(it.channel, it.length()) }

    fun of(channel: FileChannel, size: Long = channel.size()): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
        val buf = ByteBuffer.allocate(64 * 1024)

        fun feed(start: Long, length: Long) {
            var pos = start
            val end = start + length
            while (pos < end) {
                buf.clear()
                buf.limit(minOf(buf.capacity().toLong(), end - pos).toInt())
                val n = channel.read(buf, pos)
                if (n <= 0) throw IOException("Unexpected end of file at $pos")
                buf.flip()
                md.update(buf)
                pos += n
            }
        }

        if (size <= 2L * SAMPLE) {
            feed(0, size)
        } else {
            feed(0, SAMPLE.toLong())
            feed(size - SAMPLE, SAMPLE.toLong())
        }
        return md.digest().copyOf(16).toHex()
    }
}
