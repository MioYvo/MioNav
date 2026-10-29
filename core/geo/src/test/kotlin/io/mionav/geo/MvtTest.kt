package io.mionav.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvtTest {

    /**
     * Hand-built tile:
     *   layer "transportation" { extent 4096
     *     features: type 2 (line), tags {0:0}, geometry MoveTo(1) -> (2,3), LineTo(1) -> (+1,+1) }
     *     keys: ["class"], values: ["primary"] }
     */
    private fun tile(): ByteArray {
        val geometry = byteArrayOf(
            9, // command 1, count 1 (MoveTo)
            4, 6, // zigzag 2, 3 -> 2, 3
            10, // command 2, count 1 (LineTo)
            2, 2, // +1, +1
        )
        val feature = bytes(
            listOf(field(2, geomTags(0, 0)), field(3, 2L), field(4, geometry)),
        )
        val layer = bytes(
            listOf(
                field(1, "transportation".toByteArray()),
                field(2, feature),
                field(3, "class".toByteArray()),
                field(4, bytes(listOf(field(1, "primary".toByteArray())))),
                field(5, 4096L),
            ),
        )
        return bytes(listOf(field(3, layer)))
    }

    @Test
    fun decodesLayerFeatureAndTags() {
        val tile = Mvt.decode(tile())
        assertEquals(1, tile.layers.size)
        val layer = tile.layers[0]
        assertEquals("transportation", layer.name)
        assertEquals(4096, layer.extent)
        val feature = layer.features.single()
        assertEquals(2, feature.type)
        assertEquals("primary", feature.tags["class"])
        assertEquals(listOf(2 to 3, 3 to 4), feature.geometry.single())
    }

    @Test
    fun truncatedTileThrows() {
        assertTrue(runCatching { Mvt.decode(byteArrayOf(0x1A, 0x7F)) }.isFailure)
    }

    @Test
    fun emptyGeometryIsDropped() {
        assertEquals(emptyList(), Mvt.decodeGeometry(byteArrayOf()))
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        varint((number shl 3 or 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun field(number: Int, value: Long): ByteArray = varint((number shl 3).toLong()) + varint(value)

    private fun geomTags(vararg pairs: Int): ByteArray = bytes(pairs.map { varint(it.toLong()) })

    private fun bytes(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + p }

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out += byte.toByte()
                return out.toByteArray()
            }
            out += (byte or 0x80).toByte()
        }
    }
}
