package io.mionav.app

import io.mionav.geo.TrafficSpan

/**
 * Two palettes, one for sunlight and one for the dark, following the in-car colour card:
 * neutral basemap, one saturated route, everything else subordinate to it.
 *
 * Rules this follows, in the order they matter:
 *  - the route is the only saturated thing we draw; alternates are muted, cased and drawn below
 *  - every line carries a casing (white by day, black by night), so a route never disappears into
 *    whatever the basemap is doing under it, and stays visible under direct sun
 *  - traffic keeps the same meaning in both modes: green flows, amber hesitates, red stops
 */
data class MapPalette(
    val route: Int,
    val casing: Int,
    val alternative: Int,
    val alternativeCasing: Int,
    val arrow: Int,
    val arrowCasing: Int,
    val startLabel: Int,
    val endLabel: Int,
    val smooth: Int,
    val slow: Int,
    val jam: Int,
    val severe: Int,
    val night: Boolean,
) {
    /** Colour for one traffic status; unknown falls back to the plain route colour. */
    fun traffic(status: Int): Int = when (status) {
        TrafficSpan.SMOOTH -> smooth
        TrafficSpan.SLOW -> slow
        TrafficSpan.JAM -> jam
        TrafficSpan.SEVERE -> severe
        else -> route
    }

    companion object {
        /** Cool light basemap, saturated blue route, white casing. */
        val Day = MapPalette(
            route = 0xFF3282F6.toInt(),
            casing = 0xFFFFFFFF.toInt(),
            // The card's #A0AFC0 proved indistinguishable from the basemap's road grey, so the
            // same hue goes darker: still subordinate, still clearly a line.
            alternative = 0xFF8C9CB0.toInt(),
            alternativeCasing = 0xFFFFFFFF.toInt(),
            arrow = 0xFFFFFFFF.toInt(),
            arrowCasing = 0xFF10151B.toInt(),
            startLabel = 0xFF2E7D5B.toInt(),
            endLabel = 0xFF1B4B8F.toInt(),
            smooth = 0xFF26C281.toInt(),
            slow = 0xFFFF9F43.toInt(),
            jam = 0xFFE63946.toInt(),
            severe = 0xFF8B0000.toInt(),
            night = false,
        )

        /** Near-black basemap, electric blue route, black casing, no glare. */
        val Night = MapPalette(
            route = 0xFF007AFF.toInt(),
            casing = 0xFF000000.toInt(),
            alternative = 0xFF6B7B90.toInt(),
            alternativeCasing = 0xFF000000.toInt(),
            arrow = 0xFFFFFFFF.toInt(),
            arrowCasing = 0xFF10151B.toInt(),
            startLabel = 0xFF10B981.toInt(),
            endLabel = 0xFF007AFF.toInt(),
            smooth = 0xFF10B981.toInt(),
            slow = 0xFFF59E0B.toInt(),
            jam = 0xFFEF4444.toInt(),
            severe = 0xFF991B1B.toInt(),
            night = true,
        )

        fun of(night: Boolean): MapPalette = if (night) Night else Day
    }
}
