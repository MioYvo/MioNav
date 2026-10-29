package io.mionav.geo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcjTest {
    private fun offsetMetres(lat: Double, lon: Double): Double {
        val (gLat, gLon) = Gcj.wgs84ToGcj(lat, lon)
        val projection = LocalProjection(lat, lon)
        val (x, y) = projection.toMetres(gLat, gLon)
        return hypot(x, y)
    }

    @Test
    fun `outside china is identity`() {
        val (lat, lon) = Gcj.wgs84ToGcj(48.8566, 2.3522)
        assertEquals(48.8566, lat, 1e-12)
        assertEquals(2.3522, lon, 1e-12)
        assertTrue(Gcj.outOfChina(48.8566, 2.3522))
        assertTrue(!Gcj.outOfChina(39.9042, 116.4074))
    }

    @Test
    fun `offset is in the expected band`() {
        // GCJ-02 offsets in China are a few hundred metres, never kilometres.
        for ((lat, lon) in listOf(39.9042 to 116.4074, 31.2304 to 121.4737, 30.5728 to 104.0668)) {
            val d = offsetMetres(lat, lon)
            assertTrue(d in 100.0..800.0, "offset $d m at $lat,$lon")
        }
    }

    @Test
    fun `round trip converges`() {
        val samples = listOf(
            39.9042 to 116.4074,
            31.2304 to 121.4737,
            30.5728 to 104.0668,
            22.5431 to 114.0579,
        )
        for ((lat, lon) in samples) {
            val (gLat, gLon) = Gcj.wgs84ToGcj(lat, lon)
            val (wLat, wLon) = Gcj.gcjToWgs84(gLat, gLon)
            assertTrue(abs(wLat - lat) < 1e-6, "lat drift ${wLat - lat}")
            assertTrue(abs(wLon - lon) < 1e-6, "lon drift ${wLon - lon}")
        }
    }
}

class SimplifyTest {
    private fun line(n: Int): List<Point> = (0 until n).map { Point(30.0 + it * 1e-5, 120.0 + it * 1e-5) }

    @Test
    fun `collinear points collapse to endpoints`() {
        val simplified = Simplify.douglasPeucker(line(50), toleranceM = 1.0)
        assertEquals(2, simplified.size)
        assertEquals(line(50).first(), simplified.first())
        assertEquals(line(50).last(), simplified.last())
    }

    @Test
    fun `keeps a detour`() {
        val points = listOf(
            Point(30.0, 120.0),
            Point(30.0, 120.001),
            Point(30.001, 120.001), // ~110 m north of the straight line
            Point(30.001, 120.002),
        )
        val simplified = Simplify.douglasPeucker(points, toleranceM = 20.0)
        assertEquals(points.size, simplified.size)
    }

    @Test
    fun `short input passes through`() {
        assertEquals(2, Simplify.douglasPeucker(line(2), 5.0).size)
        assertTrue(Simplify.douglasPeucker(emptyList(), 5.0).isEmpty())
    }
}

class QuantizeTest {
    @Test
    fun `metres round trip within one metre`() {
        val (x, y) = Quantize.toInt16(123.7, -456.2)
        assertEquals(123, x.toInt())
        assertEquals(-456, y.toInt())
    }

    @Test
    fun `clamps far vertices`() {
        val (x, y) = Quantize.toInt16(1e9, -1e9)
        assertEquals(Quantize.LIMIT, x.toInt())
        assertEquals(-Quantize.LIMIT, y.toInt())
    }
}
