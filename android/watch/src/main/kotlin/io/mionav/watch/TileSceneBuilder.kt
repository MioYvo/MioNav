package io.mionav.watch

import io.mionav.geo.LocalProjection
import io.mionav.geo.Mvt
import io.mionav.geo.Point
import io.mionav.geo.Simplify
import io.mionav.pack.TileStore
import io.mionav.proto.RoadClass
import io.mionav.proto.Scene
import io.mionav.proto.SceneLine
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Builds a [Scene] from local MBTiles around the current fix. Runs on the watch: the phone sends
 * only the 48-byte NAV frame, so a lost packet costs nothing and offline riding still has a map.
 *
 * One zoom level, nine tiles, a few hundred lines. Rebuild only when the fix moves far enough
 * ([shouldRebuild]), because decoding MVT at 1 Hz would drain the battery for nothing.
 */
class TileSceneBuilder(
    private val store: TileStore,
    private val zoom: Int = 14,
    private val radiusM: Int = 450,
) {
    private val cache = object : LinkedHashMap<Long, Mvt.Tile?>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Mvt.Tile?>?) = size > 16
    }

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastVersion = 0

    /** Rebuild after 60 m, or when the version counter is bumped by the caller. */
    fun shouldRebuild(lat: Double, lon: Double): Boolean {
        if (lastLat.isNaN()) return true
        val projection = LocalProjection(lastLat, lastLon)
        val (dx, dy) = projection.toMetres(lat, lon)
        return dx * dx + dy * dy > 60.0 * 60.0
    }

    fun build(lat: Double, lon: Double, route: List<Point> = emptyList()): Scene {
        lastLat = lat
        lastLon = lon
        val projection = LocalProjection(lat, lon)
        val lines = mutableListOf<SceneLine>()

        if (route.size >= 2) {
            toLine(RoadClass.ROUTE, route, projection)?.let(lines::add)
        }

        val n = (1 shl zoom).toDouble()
        val px = (lon + 180.0) / 360.0 * n
        val py = latToY(lat, n)
        val tx = floor(px).toInt()
        val ty = floor(py).toInt()

        for (dx in -1..1) {
            for (dy in -1..1) {
                val x = tx + dx
                val y = ty + dy
                if (x < 0 || y < 0 || x >= n.toInt() || y >= n.toInt()) continue
                val tile = tile(x, y) ?: continue
                for (layer in tile.layers) {
                    val cls = classOf(layer.name) ?: continue
                    for (feature in layer.features) {
                        if (feature.type != 2) continue // 2 = LineString
                        val featureClass = layerClass(cls, feature.tags["class"])
                            ?: continue
                        for (part in feature.geometry) {
                            val geo = part.map { (gx, gy) ->
                                val fx = (x + gx.toDouble() / layer.extent) / n
                                val fy = (y + gy.toDouble() / layer.extent) / n
                                Point(yToLat(fy, n), fx * 360.0 - 180.0)
                            }
                            toLine(featureClass, geo, projection)?.let(lines::add)
                            if (lines.size >= MAX_LINES) break
                        }
                        if (lines.size >= MAX_LINES) break
                    }
                    if (lines.size >= MAX_LINES) break
                }
                if (lines.size >= MAX_LINES) break
            }
            if (lines.size >= MAX_LINES) break
        }

        lastVersion = (lastVersion + 1) and 0xFFFF
        return Scene(
            version = 1,
            themeId = 0,
            originLatE7 = (lat * 1e7).toInt(),
            originLonE7 = (lon * 1e7).toInt(),
            radiusM = radiusM,
            lines = lines,
        )
    }

    private fun tile(x: Int, y: Int): Mvt.Tile? {
        val key = (zoom.toLong() shl 40) or (x.toLong() shl 20) or y.toLong()
        cache[key]?.let { return it }
        val bytes = store.tile(zoom, x, y) ?: return null
        val decoded = runCatching { Mvt.decode(bytes) }.getOrNull()
        cache[key] = decoded
        return decoded
    }

    /** Project to metres, drop what is off-screen, simplify, then quantise to int16. */
    private fun toLine(cls: RoadClass, points: List<Point>, projection: LocalProjection): SceneLine? {
        if (points.size < 2) return null
        val simplified = Simplify.douglasPeucker(points, TOLERANCE_M)
        val xy = simplified.map { projection.toMetres(it.lat, it.lon) }
            .filter { (mx, my) -> abs(mx) < radiusM * 1.3 && abs(my) < radiusM * 1.3 }
        if (xy.size < 2) return null
        val sampled = if (xy.size <= MAX_POINTS) xy else stride(xy)
        val xs = ShortArray(sampled.size) { sampled[it].first.coerceIn(-32000.0, 32000.0).toInt().toShort() }
        val ys = ShortArray(sampled.size) { sampled[it].second.coerceIn(-32000.0, 32000.0).toInt().toShort() }
        return SceneLine(cls, xs, ys)
    }

    private fun stride(xy: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val step = (xy.size - 1).toDouble() / (MAX_POINTS - 1)
        return (0 until MAX_POINTS).map { xy[(it * step).toInt().coerceAtMost(xy.size - 1)] }
    }

    private fun classOf(layer: String): RoadClass? = when (layer) {
        "transportation" -> RoadClass.MINOR // refined per feature by `class`
        "water" -> RoadClass.WATER
        "waterway" -> RoadClass.WATERWAY
        else -> null
    }

    private fun layerClass(fallback: RoadClass, value: String?): RoadClass? = when (value) {
        "motorway", "trunk", "primary", "motorway_link", "trunk_link", "primary_link" -> RoadClass.MAJOR
        "secondary", "tertiary", "secondary_link", "tertiary_link" -> RoadClass.SECONDARY
        "residential", "service", "unclassified", "living_street", "minor" -> RoadClass.MINOR
        null -> fallback
        else -> null
    }

    private fun latToY(lat: Double, n: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / Math.PI) / 2.0 * n
    }

    private fun yToLat(y: Double, n: Double): Double =
        Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y / n))))

    private companion object {
        const val TOLERANCE_M = 4.0
        const val MAX_POINTS = 14
        const val MAX_LINES = 300
    }
}
