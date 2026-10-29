package io.mionav.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteDiffTest {

    /** 0.009 degrees of longitude at the equator is about 1000 m; every 0.0009 is about 100 m. */
    private val chosen = listOf(Point(0.0, 0.0), Point(0.0, 0.009))

    @Test
    fun `a route that follows the chosen one has nothing to draw`() {
        val same = listOf(Point(0.0, 0.0), Point(0.0, 0.0045), Point(0.0, 0.009))

        assertTrue(RouteDiff.divergent(same, chosen).isEmpty())
    }

    @Test
    fun `only the departing stretch is drawn, not the shared one`() {
        // Goes out to 1 km north in the middle, then comes back to the shared line.
        val detour = listOf(
            Point(0.0, 0.0),
            Point(0.0, 0.003),
            Point(0.01, 0.0045),
            Point(0.0, 0.006),
            Point(0.0, 0.009),
        )

        val runs = RouteDiff.divergent(detour, chosen)

        assertEquals(1, runs.size)
        val near = runs[0].filter { Math.abs(it.lat) < 0.0001 }
        // Where the detour departs, everything in the middle is hundreds of metres away.
        assertTrue(runs[0].any { it.lat > 0.005 }, "the departing stretch should be included")
        // The run must not swallow the whole route: the shared start and end stay out.
        assertTrue(runs[0].size < detour.size, "shared ends should be excluded")
        assertTrue(near.isNotEmpty(), "it should join the chosen line at both ends")
    }

    @Test
    fun `the run starts on the chosen line so the grey line does not begin in mid-air`() {
        val detour = listOf(
            Point(0.0, 0.0),
            Point(0.0, 0.003),
            Point(0.02, 0.0045),
            Point(0.0, 0.006),
            Point(0.0, 0.009),
        )

        val run = RouteDiff.divergent(detour, chosen).first()

        assertEquals(Point(0.0, 0.003), run.first())
        assertEquals(Point(0.0, 0.006), run.last())
    }

    @Test
    fun `two separated departures come back as two runs`() {
        val doubleDetour = listOf(
            Point(0.0, 0.0),
            Point(0.0, 0.002),
            Point(0.01, 0.0025),
            Point(0.0, 0.003),
            Point(0.0, 0.006),
            Point(0.01, 0.0065),
            Point(0.0, 0.007),
            Point(0.0, 0.009),
        )

        assertEquals(2, RouteDiff.divergent(doubleDetour, chosen).size)
    }

    @Test
    fun `a degenerate path draws nothing rather than crashing`() {
        assertTrue(RouteDiff.divergent(emptyList(), chosen).isEmpty())
        assertTrue(RouteDiff.divergent(chosen, emptyList()).isEmpty())
    }
}
