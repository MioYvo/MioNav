package io.mionav.geo

/**
 * Traffic along a route, as the basemap reports it.
 *
 * Amap gives a list of segments with a status and a length, in order along the path; it does not
 * give the geometry per segment. So the segments are walked into metres-along-route, and the path
 * is cut at those metres. Keeping this pure (no SDK types) is what makes it testable.
 */
data class TrafficSpan(val fromM: Int, val toM: Int, val status: Int) {
    companion object {
        const val UNKNOWN = 0
        const val SMOOTH = 1
        const val SLOW = 2
        const val JAM = 3
        const val SEVERE = 4

        /** Amap's own codes with a guard, because a router that ships a new code must not crash us. */
        fun statusOf(raw: Int): Int = when (raw) {
            SMOOTH, SLOW, JAM, SEVERE -> raw
            else -> UNKNOWN
        }
    }
}

/** A stretch of path drawn in one colour. */
data class TrafficSlice(val status: Int, val points: List<Point>)

object TrafficSlicer {

    /** Metres between two points, haversine; good enough at city scale. */
    fun metres(a: Point, b: Point): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }

    /**
     * Cuts [path] wherever the traffic status changes. Consecutive slices share the boundary point,
     * so the coloured line has no gaps. With no spans, the whole path comes back as one unknown
     * slice — the caller then draws it in the plain route colour.
     */
    fun slice(path: List<Point>, spans: List<TrafficSpan>): List<TrafficSlice> {
        if (path.size < 2) return emptyList()
        if (spans.isEmpty()) return listOf(TrafficSlice(TrafficSpan.UNKNOWN, path))

        // Cumulative metres at every vertex: the frame the spans are expressed in.
        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + metres(path[i - 1], path[i])
        }
        val total = cumulative.last()
        if (total <= 0.0) return listOf(TrafficSlice(TrafficSpan.UNKNOWN, path))

        // Sample at every vertex *and* at every status boundary. No fixed step, so a long route
        // stays a few hundred points instead of tens of thousands, and boundaries stay exact.
        val marks = sortedSetOf<Double>()
        cumulative.forEach { marks.add(it) }
        spans.forEach { span ->
            if (span.fromM > 0 && span.fromM < total) marks.add(span.fromM.toDouble())
            if (span.toM > 0 && span.toM < total) marks.add(span.toM.toDouble())
        }

        val out = mutableListOf<TrafficSlice>()
        var current = mutableListOf<Point>()
        var currentStatus = -1
        marks.forEach { mark ->
            val point = pointAt(path, cumulative, mark)
            val status = statusAt(mark, spans)
            if (status != currentStatus) {
                // Close the old stretch *on* the boundary point, or the colour would stop short.
                if (current.isNotEmpty()) current.add(point)
                if (current.size >= 2) out.add(TrafficSlice(currentStatus, current.toList()))
                current = mutableListOf(point)
                currentStatus = status
            } else {
                current.add(point)
            }
        }
        if (current.size >= 2) out.add(TrafficSlice(currentStatus, current.toList()))
        return out
    }

    private fun statusAt(metres: Double, spans: List<TrafficSpan>): Int {
        val hit = spans.firstOrNull { metres >= it.fromM && metres < it.toM }
        return hit?.status ?: spans.lastOrNull()?.status ?: TrafficSpan.UNKNOWN
    }

    /** Linear interpolation along the polyline at [distance] metres from the start. */
    private fun pointAt(path: List<Point>, cumulative: DoubleArray, distance: Double): Point {
        val i = cumulative.indexOfFirst { it >= distance }
        if (i <= 0) return path.first()
        val from = path[i - 1]
        val to = path[i]
        val span = cumulative[i] - cumulative[i - 1]
        if (span <= 0.0) return to
        val t = (distance - cumulative[i - 1]) / span
        return Point(
            from.lat + (to.lat - from.lat) * t,
            from.lon + (to.lon - from.lon) * t,
        )
    }
}
