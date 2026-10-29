package io.mionav.geo

/**
 * Where an unchosen route differs from the chosen one.
 *
 * Drawing two routes that share ninety percent of their length means the second one is either
 * hidden underneath the first or, worse, painted over it. So only the stretch that actually
 * departs gets drawn — the rest is already on screen as the chosen route.
 */
object RouteDiff {

    /**
     * Runs of [path] that stay more than [thresholdM] away from [chosen].
     *
     * Each run is padded by one point at both ends so the grey line visibly leaves the chosen one
     * instead of starting in mid-air. Runs shorter than two points are dropped: a stub of line
     * hanging in space is worse than nothing.
     */
    fun divergent(
        path: List<Point>,
        chosen: List<Point>,
        thresholdM: Double = 25.0,
    ): List<List<Point>> {
        if (path.size < 2 || chosen.size < 2) return emptyList()

        val origin = chosen.first()
        val cosLat = Math.cos(Math.toRadians(origin.lat))
        fun x(p: Point) = (p.lon - origin.lon) * 111_320.0 * cosLat
        fun y(p: Point) = (p.lat - origin.lat) * 110_540.0

        val chosenXY = chosen.map { x(it) to y(it) }
        val pathXY = path.map { x(it) to y(it) }

        // A grid of the chosen line's segments. A thousand-kilometre route carries a thousand
        // points per side, and comparing every point against every segment is what froze the UI
        // for five seconds on a long route.
        val grid = SegmentGrid(chosenXY, cell = maxOf(thresholdM * 2, 100.0))

        val runs = mutableListOf<List<Point>>()
        var run = mutableListOf<Point>()

        for (i in path.indices) {
            val far = grid.distanceTo(pathXY[i]) > thresholdM
            if (far) {
                // Start the run one point early, so it grows out of the chosen line.
                if (run.isEmpty() && i > 0) run.add(path[i - 1])
                run.add(path[i])
            } else if (run.isNotEmpty()) {
                run.add(path[i]) // one point past the departure, for a clean join
                if (run.size >= 2) runs.add(run)
                run = mutableListOf()
            }
        }
        if (run.size >= 2) runs.add(run)
        // Drop runs that are all but a single segment of noise.
        return runs.filter { it.size >= 2 }
    }

    /** Uniform-grid index over the segments of one polyline; keeps the test local in space. */
    private class SegmentGrid(private val line: List<Pair<Double, Double>>, private val cell: Double) {
        private val buckets = HashMap<Long, MutableList<Int>>()

        init {
            for (i in 1 until line.size) {
                val minX = minOf(line[i - 1].first, line[i].first)
                val maxX = maxOf(line[i - 1].first, line[i].first)
                val minY = minOf(line[i - 1].second, line[i].second)
                val maxY = maxOf(line[i - 1].second, line[i].second)
                for (cx in keyOf(minX)..keyOf(maxX)) {
                    for (cy in keyOf(minY)..keyOf(maxY)) {
                        buckets.getOrPut(pair(cx, cy)) { mutableListOf() }.add(i)
                    }
                }
            }
        }

        fun distanceTo(p: Pair<Double, Double>): Double {
            val cx = keyOf(p.first)
            val cy = keyOf(p.second)
            var best = Double.MAX_VALUE
            // Two cells out: enough to cover a point that sits just across a cell border.
            for (dx in -2..2) {
                for (dy in -2..2) {
                    val candidates = buckets[pair(cx + dx, cy + dy)] ?: continue
                    candidates.forEach { i ->
                        val d = distanceToSegment(p, line[i - 1], line[i])
                        if (d < best) best = d
                    }
                    if (best <= cell) return best
                }
            }
            return best
        }

        private fun keyOf(value: Double): Int = Math.floor(value / cell).toInt()
        private fun pair(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)
    }

    /** Shortest distance from [p] to the polyline [line], both in local metres. */
    private fun distanceToPolyline(
        p: Pair<Double, Double>,
        line: List<Pair<Double, Double>>,
    ): Double {
        var best = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val d = distanceToSegment(p, line[i - 1], line[i])
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 0.0) return hypot(p.first - a.first, p.second - a.second)
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSq)
            .coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }

    private fun hypot(a: Double, b: Double): Double = Math.hypot(a, b)
}
