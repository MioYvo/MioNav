package io.mionav.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelloFrameTest {

    @Test
    fun `round trips a watch claim`() {
        val frame = HelloFrame(
            flags = HelloFrame.FLAG_HAS_PACK,
            packId = "zhejiang",
            packVersion = "2026.10.28",
            packBytes = 92_431_123L,
            packTiles = 184_320,
            packSha = "9f".repeat(32),
        )
        val decoded = HelloFrame.decode(frame.encode())
        assertEquals(frame, decoded)
        assertTrue(decoded.hasPack)
        assertTrue(decoded.url.isEmpty())
    }

    @Test
    fun `round trips a phone answer`() {
        val frame = HelloFrame(
            flags = HelloFrame.FLAG_SERVING,
            url = "http://192.168.43.1:8080",
        )
        assertEquals(frame, HelloFrame.decode(frame.encode()))
    }

    @Test
    fun `an empty watch frame still decodes`() {
        val decoded = HelloFrame.decode(HelloFrame().encode())
        assertFalse(decoded.hasPack)
        assertEquals(0L, decoded.packBytes)
        assertTrue(decoded.url.isEmpty())
    }

    @Test
    fun `unknown version is refused`() {
        val bytes = HelloFrame().encode()
        bytes[0] = 9
        assertNull(HelloFrame.tryDecode(bytes))
    }

    @Test
    fun `truncated payload is refused`() {
        val bytes = HelloFrame(packId = "x", packVersion = "1", packBytes = 10).encode()
        assertNull(HelloFrame.tryDecode(bytes.copyOf(bytes.size - 3)))
        assertNull(HelloFrame.tryDecode(ByteArray(2)))
    }

    @Test
    fun `oversized field is refused`() {
        val bytes = HelloFrame().encode()
        bytes[2] = 200.toByte() // id length beyond MAX_ID
        assertNull(HelloFrame.tryDecode(bytes))
    }
}
