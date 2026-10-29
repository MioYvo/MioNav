package io.mionav.hud

import kotlin.math.roundToInt

/** Display strings for the watch. Units stay short so a 410 px screen can hold them. */
object HudText {
    fun distance(metres: Int): String {
        val m = metres.coerceAtLeast(0)
        return when {
            m < 10 -> "$m m"
            m < 1000 -> "${(m / 10.0).roundToInt() * 10} m"
            m < 10_000 -> String.format("%.1f km", m / 1000.0)
            else -> "${(m / 1000.0).roundToInt()} km"
        }
    }

    /** Minutes to "58 分" or "1 时 05". The hour form pads to two digits on purpose. */
    fun duration(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        if (m < 60) return "$m 分"
        val h = m / 60
        val rest = m % 60
        return "$h 时 " + rest.toString().padStart(2, '0')
    }

    /** Speed limit badge, empty when unknown. */
    fun limit(kmh: Int): String = if (kmh in 1..199) kmh.toString() else ""

    /** "服务区 2.3 km" or null when there is no service area ahead. */
    fun serviceArea(metres: Int, hasServiceArea: Boolean): String? =
        if (!hasServiceArea || metres <= 0) null else "服务区 " + distance(metres)

    /** Remaining distance plus ETA time, e.g. "12.4 km · 58 分". */
    fun remaining(metres: Int, minutes: Int): String = distance(metres) + " · " + duration(minutes)

    /** Lane bitmap to the number of lanes, capped so the strip stays legible. */
    fun laneCount(mask: Int, max: Int = 6): Int {
        var count = 0
        var bits = mask and 0xFF
        while (bits != 0) {
            count += bits and 1
            bits = bits ushr 1
        }
        return count.coerceAtMost(max)
    }

    fun laneRecommended(mask: Int, index: Int): Boolean =
        index in 0..7 && (mask ushr index) and 1 == 1
}
