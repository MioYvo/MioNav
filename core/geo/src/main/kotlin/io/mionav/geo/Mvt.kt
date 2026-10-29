package io.mionav.geo

/**
 * Minimal Mapbox Vector Tile decoder (protobuf). Only what the HUD needs: layers, string tags,
 * line geometry. Points and polygons are parsed but the scene builder ignores them for now.
 *
 * Spec: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */
object Mvt {

    class Tile(val layers: List<Layer>)

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    /** [geometry] is layer space with y pointing down, as the spec defines it. */
    class Feature(
        val type: Int,
        val tags: Map<String, String>,
        val geometry: List<List<Pair<Int, Int>>>,
    )

    /**
     * MBTiles stores vector tiles gzipped (the spec assumes it), so the decoder unwraps that first.
     * Planetiler's output is gzipped; a hand-built fixture is not; both arrive here.
     */
    fun decode(bytes: ByteArray): Tile {
        val p = Proto(if (isGzip(bytes)) gunzip(bytes) else bytes)
        val layers = mutableListOf<Layer>()
        while (!p.done()) {
            val (field, wire) = p.tag()
            when {
                field == 3 && wire == 2 -> layers += p.bytes().let(::layer)
                else -> p.skip(wire)
            }
        }
        return Tile(layers)
    }

    private class RawFeature(val type: Int, val tagIndexes: IntArray, val geometry: List<List<Pair<Int, Int>>>)

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun gunzip(bytes: ByteArray): ByteArray =
        java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun layer(bytes: ByteArray): Layer {
        val p = Proto(bytes)
        var name = ""
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<String>()
        val raws = mutableListOf<RawFeature>()
        while (!p.done()) {
            val (field, wire) = p.tag()
            when {
                field == 1 && wire == 2 -> name = p.string()
                field == 2 && wire == 2 -> raws += p.bytes().let(::feature)
                field == 3 && wire == 2 -> keys += p.string()
                field == 4 && wire == 2 -> values += p.bytes().let(::value)
                field == 5 && wire == 0 -> extent = p.varint().toInt()
                else -> p.skip(wire)
            }
        }
        val features = raws.map { raw ->
            val tags = LinkedHashMap<String, String>()
            var i = 0
            while (i + 1 < raw.tagIndexes.size) {
                val k = raw.tagIndexes[i]
                val v = raw.tagIndexes[i + 1]
                if (k < keys.size && v < values.size) tags[keys[k]] = values[v]
                i += 2
            }
            Feature(raw.type, tags, raw.geometry)
        }
        return Layer(name, extent, features)
    }

    private fun feature(bytes: ByteArray): RawFeature {
        val p = Proto(bytes)
        var type = 0
        val tags = mutableListOf<Int>()
        var geometry = emptyList<List<Pair<Int, Int>>>()
        while (!p.done()) {
            val (field, wire) = p.tag()
            when {
                field == 1 && wire == 0 -> p.varint()
                field == 2 && wire == 2 -> {
                    val tp = Proto(p.bytes())
                    while (!tp.done()) tags += tp.varint().toInt()
                }
                field == 3 && wire == 0 -> type = p.varint().toInt()
                field == 4 && wire == 2 -> geometry = decodeGeometry(p.bytes())
                else -> p.skip(wire)
            }
        }
        return RawFeature(type, tags.toIntArray(), geometry)
    }

    private fun value(bytes: ByteArray): String {
        val p = Proto(bytes)
        var out = ""
        while (!p.done()) {
            val (field, wire) = p.tag()
            when {
                field == 1 && wire == 2 -> out = p.string()
                field == 2 && wire == 5 -> out = java.lang.Float.intBitsToFloat(p.fixed32()).toString()
                field == 3 && wire == 1 -> out = java.lang.Double.longBitsToDouble(p.fixed64()).toString()
                field == 4 && wire == 0 -> out = p.varint().toString()
                field == 5 && wire == 0 -> out = p.varint().toString()
                field == 6 && wire == 0 -> out = p.zigzag().toString()
                field == 7 && wire == 0 -> out = (p.varint() != 0L).toString()
                else -> p.skip(wire)
            }
        }
        return out
    }

    /** Spec 4.3: command integer = (id & 0x7) | (count << 3); parameters are zigzag deltas. */
    fun decodeGeometry(bytes: ByteArray): List<List<Pair<Int, Int>>> {
        val p = Proto(bytes)
        val lines = mutableListOf<List<Pair<Int, Int>>>()
        var current: MutableList<Pair<Int, Int>>? = null
        var x = 0
        var y = 0
        while (!p.done()) {
            val command = p.varint().toInt()
            val id = command and 0x7
            val count = command shr 3
            when (id) {
                1 -> {
                    current = mutableListOf()
                    lines += current
                    repeat(count) {
                        x += p.zigzag()
                        y += p.zigzag()
                        current?.add(x to y)
                    }
                }
                2 -> repeat(count) {
                    x += p.zigzag()
                    y += p.zigzag()
                    current?.add(x to y)
                }
                7 -> Unit
                else -> return lines.filter { it.isNotEmpty() }
            }
        }
        return lines.filter { it.isNotEmpty() }
    }

    private class Proto(private val b: ByteArray) {
        private var at = 0

        fun done() = at >= b.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (at < b.size) {
                val byte = b[at++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
            throw IllegalArgumentException("truncated varint")
        }

        fun zigzag(): Int {
            val v = varint()
            return ((v shr 1) xor -(v and 1)).toInt()
        }

        fun tag(): Pair<Int, Int> {
            val t = varint().toInt()
            return (t ushr 3) to (t and 0x7)
        }

        fun fixed32(): Int {
            val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return v
        }

        fun fixed64(): Long {
            var out = 0L
            repeat(8) { i -> out = out or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
            at += 8
            return out
        }

        fun bytes(): ByteArray {
            val len = varint().toInt()
            require(len >= 0 && at + len <= b.size) { "bad length $len" }
            return b.copyOfRange(at, at + len).also { at += len }
        }

        fun string(): String = bytes().toString(Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> at += 8
                2 -> at += varint().toInt()
                5 -> at += 4
                else -> error("unsupported wire type $wire")
            }
            require(at <= b.size) { "truncated message" }
        }
    }
}
