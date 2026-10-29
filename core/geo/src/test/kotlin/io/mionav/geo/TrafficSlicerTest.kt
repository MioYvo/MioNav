package io.mionav.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficSlicerTest {

    /** A straight 1 km eastward line, roughly 0.011 degrees of longitude at the equator. */
    private val line = listOf(Point(0.0, 0.0), Point(0.0, 0.009))

    @Test
    fun `no spans means one unknown slice covering everything`() {
        val slices = TrafficSlicer.slice(line, emptyList())

        assertEquals(1, slices.size)
        assertEquals(TrafficSpan.UNKNOWN, slices[0].status)
        assertEquals(line, slices[0].points)
    }

    @Test
    fun `a path with one point is not sliceable`() {
        assertTrue(TrafficSlicer.slice(listOf(Point(0.0, 0.0)), emptyList()).isEmpty())
    }

    @Test
    fun `spans split the path in order and keep their statuses`() {
        val spans = listOf(
            TrafficSpan(0, 500, TrafficSpan.SMOOTH),
            TrafficSpan(500, 1000, TrafficSpan.JAM),
            TrafficSpan(1000, 1500, TrafficSpan.SEVERE),
        )

        val slices = TrafficSlicer.slice(line, spans)

        assertEquals(
            listOf(TrafficSpan.SMOOTH, TrafficSpan.JAM, TrafficSpan.SEVERE),
            slices.map { it.status },
        )
        slices.forEach { assertTrue(it.points.size >= 2) }
    }

    @Test
    fun `consecutive slices meet, so the coloured line has no gap`() {
        val spans = listOf(
            TrafficSpan(0, 500, TrafficSpan.SMOOTH),
            TrafficSpan(500, 1000, TrafficSpan.SLOW),
        )

        val slices = TrafficSlicer.slice(line, spans)

        assertEquals(2, slices.size)
        assertEquals(slices[0].points.last(), slices[1].points.first())
        assertEquals(line.first(), slices.first().points.first())
        assertEquals(line.last(), slices.last().points.last())
    }

    @Test
    fun `an unknown status code is treated as unknown rather than trusted`() {
        assertEquals(TrafficSpan.UNKNOWN, TrafficSpan.statusOf(9))
        assertEquals(TrafficSpan.UNKNOWN, TrafficSpan.statusOf(-1))
        assertEquals(TrafficSpan.JAM, TrafficSpan.statusOf(3))
    }

    @Test
    fun `last reported status covers the tail when spans fall short of the path`() {
        val spans = listOf(TrafficSpan(0, 400, TrafficSpan.SLOW))

        val slices = TrafficSlicer.slice(line, spans)

        assertEquals(TrafficSpan.SLOW, slices.last().status)
    }
}
