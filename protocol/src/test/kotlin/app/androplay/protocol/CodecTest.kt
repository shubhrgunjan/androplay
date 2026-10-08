package app.androplay.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {
    @Test
    fun commandsRoundTrip() {
        val commands = listOf(
            Command.Play(65000, 12_345), Command.Pause, Command.Resume, Command.Toggle,
            Command.Seek(3_600_000), Command.SeekBy(-10_000), Command.Volume(73), Command.ToggleMute,
            Command.Next, Command.Prev, Command.GoToPage(41), Command.Stop, Command.ToggleLibrary,
            Command.SetQueue(listOf(1, 2, 300, 65000)),
        )
        for (c in commands) assertEquals(c, Command.decode(c.encode()))

        val token = ByteArray(16) { it.toByte() }
        val auth = Command.decode(Command.Auth(token).encode()) as Command.Auth
        assertArrayEquals(token, auth.token)
    }

    @Test
    fun stateRoundTrip() {
        val s = PlaybackState(Status.PAUSED, 42, 61_000, 3_600_000, 55, true, 3, 12, 99)
        val bytes = s.encode()
        assertEquals(PlaybackState.SIZE, bytes.size)
        assertEquals(s, PlaybackState.decode(bytes))
    }

    @Test
    fun contentTypes() {
        assertEquals(ContentType.VIDEO, ContentType.of("talk.MP4"))
        assertEquals(ContentType.PDF, ContentType.of("x", "application/pdf"))
        assertEquals(ContentType.DOCUMENT, ContentType.of("deck.pptx"))
        assertEquals(ContentType.OTHER, ContentType.of("app.apk", "application/vnd.android.package-archive"))
    }
}
