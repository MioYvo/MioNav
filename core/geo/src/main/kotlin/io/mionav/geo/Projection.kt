package io.mionav.geo

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Local tangent plane in metres around an origin. Good for viewports of a few kilometres. */
class LocalProjection(private val originLat: Double, private val originLon: Double) {
    private val mPerDegLat = 110_540.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(originLat))

    fun toMetres(lat: Double, lon: Double): Pair<Double, Double> =
        (lon - originLon) * mPerDegLon to (lat - originLat) * mPerDegLat

    fun toLatLon(x: Double, y: Double): Pair<Double, Double> =
        (originLat + y / mPerDegLat) to (originLon + x / mPerDegLon)
}

data class Point(val lat: Double, val lon: Double)

object Simplify {
    /** Douglas-Peucker, tolerance in metres, using a local projection around the first point. */
    fun douglasPeucker(points: List<Point>, toleranceM: Double): List<Point> {
        if (points.size <= 2 || toleranceM <= 0.0) return points
        val projection = LocalProjection(points[0].lat, points[0].lon)
        val xy = points.map { projection.toMetres(it.lat, it.lon) }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        keepMiddle(xy, 0, points.size - 1, toleranceM * toleranceM, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun keepMiddle(xy: List<Pair<Double, Double>>, from: Int, to: Int, tol2: Double, keep: BooleanArray) {
        if (to <= from + 1) return
        val (ax, ay) = xy[from]
        val (bx, by) = xy[to]
        var best = -1
        var bestDist = -1.0
        for (i in from + 1 until to) {
            val (px, py) = xy[i]
            val d = distanceSquaredToSegment(px, py, ax, ay, bx, by)
            if (d > bestDist) {
                bestDist = d
                best = i
            }
        }
        if (bestDist > tol2 && best > 0) {
            keep[best] = true
            keepMiddle(xy, from, best, tol2, keep)
            keepMiddle(xy, best, to, tol2, keep)
        }
    }

    private fun distanceSquaredToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay).let { it * it }
        val t = max(0.0, minOf(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return ex * ex + ey * ey
    }
}

/** Quantises to int16 metres relative to an origin. Clamps, so one far-away vertex cannot wrap. */
object Quantize {
    const val LIMIT = 32_000

    fun toInt16(x: Double, y: Double): Pair<Short, Short> =
        x.toInt().coerceIn(-LIMIT, LIMIT).toShort() to y.toInt().coerceIn(-LIMIT, LIMIT).toShort()
}
