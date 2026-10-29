package io.mionav.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Crc16Test {
    @Test
    fun `check value matches CRC-16-CCITT-FALSE`() {
        assertEquals(0x29B1, Crc16.of("123456789".toByteArray(Charsets.US_ASCII)))
    }
}

class FrameTest {
    @Test
    fun `round trips`() {
        val frame = Frame(MsgType.SCENE, seq = 7, flags = Flags.ACK_REQUIRED, payload = byteArrayOf(1, 2, 3))
        val bytes = frame.encode()
        assertEquals(Frame.HEADER + 3, bytes.size)
        assertEquals(frame, Frame.decode(bytes))
    }

    @Test
    fun `rejects corrupt payload`() {
        val bytes = Frame(MsgType.NAV, 1, 0, ByteArray(48) { it.toByte() }).encode()
        bytes[Frame.HEADER + 5] = (bytes[Frame.HEADER + 5] + 1).toByte()
        assertFailsWith<ProtocolException> { Frame.decode(bytes) }
        assertNull(Frame.tryDecode(bytes))
    }

    @Test
    fun `rejects bad magic and length`() {
        val bytes = Frame(MsgType.HELLO, 1).encode()
        val badMagic = bytes.copyOf().also { it[0] = 0 }
        assertFailsWith<ProtocolException> { Frame.decode(badMagic) }
        assertFailsWith<ProtocolException> { Frame.decode(bytes, 0, bytes.size - 1) }
    }
}

class ChunkerTest {
    @Test
    fun `small payload passes through`() {
        val frame = Frame(MsgType.NAV, 3, 0, ByteArray(20))
        assertEquals(listOf(frame), Chunker.chunk(frame, maxPayload = 64))
    }

    @Test
    fun `reassembles out of order`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val chunks = Chunker.chunk(Frame(MsgType.SCENE, 9, Flags.ACK_REQUIRED, payload), maxPayload = 64)
        assertTrue(chunks.size > 1)

        val reassembler = Reassembler()
        var result: Frame? = null
        for (chunk in chunks.reversed()) result = reassembler.accept(chunk) ?: result

        assertEquals(MsgType.SCENE, result?.type)
        assertEquals(Flags.ACK_REQUIRED, result?.flags)
        assertContentEquals(payload, result?.payload)
        assertEquals(0, reassembler.pendingCount())
    }

    @Test
    fun `missing chunk keeps message incomplete`() {
        val payload = ByteArray(300)
        val chunks = Chunker.chunk(Frame(MsgType.SCENE, 1, 0, payload), maxPayload = 64)
        val reassembler = Reassembler()
        for (chunk in chunks.drop(1)) assertNull(reassembler.accept(chunk))
        assertEquals(1, reassembler.pendingCount())
    }

    @Test
    fun `expires stale partials`() {
        var now = 1_000L
        val reassembler = Reassembler(timeoutMs = 100) { now }
        val chunks = Chunker.chunk(Frame(MsgType.SCENE, 2, 0, ByteArray(300)), maxPayload = 64)
        assertNull(reassembler.accept(chunks[0]))
        now += 500
        assertNull(reassembler.accept(chunks[1]))
        assertEquals(1, reassembler.pendingCount())
    }
}

class NavFrameTest {
    @Test
    fun `round trips every field`() {
        val nav = NavFrame(
            iconType = 8, flags = NavFlags.NAVIGATING or NavFlags.HAS_LANES,
            stepDistM = 320, remainDist10m = 4260, remainTimeMin = 58,
            speedKmh = 96, limitKmh = 120, svcDist100m = 23,
            roadId = 12, nextRoadId = 13, laneUsable = 0b00011111, laneRec = 0b00000100,
            cameraDistM = 480, cameraType = 2, crossFlag = 1,
            etaMin = 13 * 60 + 42, sceneVersion = 7, tsMs = 0xFFFF_FFF0L,
        )
        val bytes = nav.encode()
        assertEquals(NavFrame.SIZE, bytes.size)
        assertEquals(nav, NavFrame.decode(bytes))
    }

    @Test
    fun `rejects out of range values`() {
        assertFailsWith<IllegalArgumentException> { NavFrame(limitKmh = 300) }
        assertFailsWith<IllegalArgumentException> { NavFrame(stepDistM = 70_000) }
    }
}

class SceneTest {
    private fun scene() = Scene(
        version = 3, themeId = 1,
        originLatE7 = 303_000_000, originLonE7 = 1_200_000_000,
        radiusM = 800,
        lines = listOf(
            SceneLine(RoadClass.MAJOR, shortArrayOf(0, 100, 200), shortArrayOf(0, -50, -120)),
            SceneLine(RoadClass.ROUTE, shortArrayOf(-10, 40), shortArrayOf(10, 90)),
        ),
    )

    @Test
    fun `round trips`() {
        val bytes = scene().encode()
        val back = Scene.decode(bytes)
        assertEquals(3, back.version)
        assertEquals(2, back.lines.size)
        assertContentEquals(shortArrayOf(0, 100, 200), back.lines[0].xs)
        assertContentEquals(shortArrayOf(0, -50, -120), back.lines[0].ys)
        assertEquals(RoadClass.ROUTE, back.lines[1].cls)
    }

    @Test
    fun `rejects truncated input`() {
        val bytes = scene().encode()
        assertFailsWith<ProtocolException> { Scene.decode(bytes.copyOf(bytes.size - 2)) }
    }
}

class StringTableTest {
    @Test
    fun `dedupes and emits deltas`() {
        val table = StringTable()
        val a = table.intern("G60 沪昆高速")
        val b = table.intern("沪昆高速")
        assertEquals(a, table.intern("G60 沪昆高速"))
        assertEquals(2, table.size)
        assertEquals("沪昆高速", table.lookup(b))

        val delta = table.entriesAfter(1)
        assertEquals(1, delta.size)
        val decoded = StringTable.decode(StringTable.encode(delta))
        assertEquals(StringEntry(b, "沪昆高速"), decoded.single())
    }

    @Test
    fun `rejects truncated table`() {
        val bytes = StringTable.encode(listOf(StringEntry(1, "abc")))
        assertFailsWith<ProtocolException> { StringTable.decode(bytes.copyOf(bytes.size - 1)) }
    }
}

class BudgetTest {
    @Test
    fun `a full scene fits the bandwidth budget and survives chunking`() {
        val lines = (0 until 300).map { i ->
            SceneLine(
                RoadClass.of(i % 6),
                ShortArray(6) { p -> ((i * 7 + p * 13) % 800 - 400).toShort() },
                ShortArray(6) { p -> ((i * 11 + p * 17) % 800 - 400).toShort() },
            )
        }
        val scene = Scene(1, 0, 300_000_000, 1_200_000_000, 800, lines)
        val payload = scene.encode()
        assertTrue(payload.size < 8 * 1024, "scene is ${payload.size} bytes")

        val chunks = Chunker.chunk(Frame(MsgType.SCENE, 1, 0, payload), maxPayload = 503)
        val reassembler = Reassembler()
        var whole: Frame? = null
        for (chunk in chunks) reassembler.accept(chunk)?.let { whole = it }

        assertContentEquals(payload, whole?.payload)
        assertEquals(300, Scene.decode(whole!!.payload).lines.size)
    }

    @Test
    fun `nav frame is exactly 48 bytes on the wire`() {
        assertEquals(48, NavFrame().encode().size)
        assertEquals(11 + 48, Frame(MsgType.NAV, 1, 0, NavFrame().encode()).encode().size)
    }
}
