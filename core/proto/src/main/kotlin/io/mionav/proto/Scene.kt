package io.mionav.proto

/** Background geometry for one scene. Coordinates are metres relative to [originLatE7], [originLonE7]. */
enum class RoadClass(val code: Int) {
    MAJOR(0),
    SECONDARY(1),
    MINOR(2),
    WATERWAY(3),
    WATER(4),
    ROUTE(5);

    companion object {
        fun of(code: Int): RoadClass =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown road class $code")
    }
}

class SceneLine(val cls: RoadClass, val xs: ShortArray, val ys: ShortArray) {
    init {
        require(xs.size == ys.size) { "x/y length mismatch" }
    }

    val points: Int get() = xs.size
}

class Scene(
    val version: Int,
    val themeId: Int,
    val originLatE7: Int,
    val originLonE7: Int,
    val radiusM: Int,
    val lines: List<SceneLine>,
) {
    init {
        require(version in 0..0xFF)
        require(themeId in 0..0xFF)
        require(radiusM in 0..0xFFFF)
    }

    fun encode(): ByteArray {
        require(lines.size <= MAX_LINES) { "too many lines: ${lines.size}" }
        var size = FIXED
        for (line in lines) {
            require(line.points in 1..MAX_POINTS_PER_LINE) { "bad point count: ${line.points}" }
            size += 2 + line.points * 4
        }
        val b = ByteArray(size)
        var at = 0
        Le.putU8(b, at++, version)
        Le.putU8(b, at++, themeId)
        Le.putU32(b, at, originLatE7.toLong() and 0xFFFF_FFFFL); at += 4
        Le.putU32(b, at, originLonE7.toLong() and 0xFFFF_FFFFL); at += 4
        Le.putU16(b, at, radiusM); at += 2
        Le.putU16(b, at, lines.size); at += 2
        for (line in lines) {
            Le.putU8(b, at++, line.cls.code)
            Le.putU8(b, at++, line.points)
            for (i in 0 until line.points) {
                Le.putI16(b, at, line.xs[i]); at += 2
                Le.putI16(b, at, line.ys[i]); at += 2
            }
        }
        return b
    }

    companion object {
        const val MAX_LINES = 400
        const val MAX_POINTS_PER_LINE = 255
        private const val FIXED = 14

        fun decode(b: ByteArray): Scene {
            if (b.size < FIXED) throw ProtocolException("short scene: ${b.size}")
            var at = 0
            val version = Le.u8(b, at++)
            val themeId = Le.u8(b, at++)
            val lat = Le.u32(b, at).toInt(); at += 4
            val lon = Le.u32(b, at).toInt(); at += 4
            val radius = Le.u16(b, at); at += 2
            val count = Le.u16(b, at); at += 2
            if (count > MAX_LINES) throw ProtocolException("too many lines: $count")

            val lines = ArrayList<SceneLine>(count)
            repeat(count) {
                if (at + 2 > b.size) throw ProtocolException("truncated line header")
                val cls = RoadClass.of(Le.u8(b, at++))
                val n = Le.u8(b, at++)
                if (n == 0) throw ProtocolException("empty line")
                if (at + n * 4 > b.size) throw ProtocolException("truncated line body")
                val xs = ShortArray(n)
                val ys = ShortArray(n)
                for (i in 0 until n) {
                    xs[i] = Le.i16(b, at); at += 2
                    ys[i] = Le.i16(b, at); at += 2
                }
                lines += SceneLine(cls, xs, ys)
            }
            if (at != b.size) throw ProtocolException("trailing bytes: ${b.size - at}")
            return Scene(version, themeId, lat, lon, radius, lines)
        }
    }
}
