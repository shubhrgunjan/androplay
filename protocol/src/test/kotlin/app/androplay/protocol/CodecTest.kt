package app.androplay.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecTest {
    @Test
    fun commandsRoundTrip() {
        val commands = listOf(
            Command.Play(65000, 12_345), Command.Pause, Command.Resume, Command.Toggle,
            Command.Seek(3_600_000), Command.SeekBy(-10_000), Command.Volume(73), Command.ToggleMute,
            Command.Next, Command.Prev, Command.Stop, Command.ToggleLibrary,
            Command.SetQueue(listOf(1, 2, 300, 65000)), Command.Lock(true), Command.Lock(false),
            Command.Slideshow(listOf(5, 9, 1200), 8, loop = true, start = 1), Command.Slideshow(emptyList(), 30, loop = false),
            Command.Slideshow(listOf(5, 9, 5), 4, loop = true, start = 2, seconds = listOf(4, 12, 30)),
        )
        for (c in commands) assertEquals(c, Command.decode(c.encode()))

        val token = ByteArray(16) { it.toByte() }
        val auth = Command.decode(Command.Auth(token).encode()) as Command.Auth
        assertArrayEquals(token, auth.token)
    }

    @Test
    fun controlRequestsResolveTheContentIdEvenWhenHandlesChanged() {
        val created = ControlRequest.create(Command.Play(12, 500)) { if (it == 12) "image-id" else null }!!
        val received = ControlRequest.decode(created.encode())!!
        assertEquals(created.id, received.id)
        assertEquals(created.expiresAt, received.expiresAt)
        assertEquals(Command.Play(4, 500), received.resolve { if (it == "image-id") 4 else null })
        assertNull(received.resolve { null })
        assertNull(ControlRequest.decode(Command.Next.encode()))
    }

    @Test
    fun timedSlidesKeepTheirAlignmentWhenAFileIsUnavailable() {
        val show = Command.Slideshow(listOf(1, 2, 3), 5, false, seconds = listOf(5, 7, 9))
        val request = ControlRequest.create(show) { "id-$it" }!!
        val resolved = request.resolve { if (it == "id-2") null else it.last().digitToInt() + 10 }
        assertEquals(show.copy(handles = listOf(11, -1, 13)), resolved)
    }

    @Test
    fun stateRoundTrip() {
        val s = PlaybackState(Status.PAUSED, 42, 61_000, 3_600_000, 55, true, 3, 12, 99, locked = true, slideshow = true)
        val bytes = s.encode()
        assertEquals(PlaybackState.SIZE, bytes.size)
        assertEquals(s, PlaybackState.decode(bytes))
    }

    @Test
    fun contentTypes() {
        assertEquals(ContentType.VIDEO, ContentType.of("talk.MP4"))
        assertEquals(ContentType.IMAGE, ContentType.of("photo.JPG"))
        assertEquals(ContentType.OTHER, ContentType.of("app.apk", "application/vnd.android.package-archive"))
    }
}
