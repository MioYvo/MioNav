package io.mionav.voice

import io.mionav.hud.ManeuverKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoicePlannerTest {

    private fun moving(distanceM: Int, road: String = "科技路", maneuver: ManeuverKind = ManeuverKind.RIGHT) =
        VoiceInput(active = true, maneuver = maneuver, stepDistanceM = distanceM, nextRoad = road)

    @Test
    fun announcesEachThresholdOncePerInstruction() {
        val planner = VoicePlanner(minGapMs = 0)
        val heard = mutableListOf<String>()
        var now = 0L

        listOf(800, 600, 520, 500, 400, 300, 210, 200, 150, 70, 60, 30).forEach { distance ->
            planner.onState(moving(distance), now)?.let { heard.add(it.text) }
            now += 1_000
        }

        assertEquals(3, heard.size, "heard: $heard")
        assertTrue(heard[0].contains("500 米"), heard[0])
        assertTrue(heard[1].contains("200 米"), heard[1])
        assertTrue(heard[2].contains("左转").not(), heard[2])
    }

    @Test
    fun aNewInstructionResetsTheThresholds() {
        val planner = VoicePlanner(minGapMs = 0)
        assertNotNull(planner.onState(moving(500), 0))
        // Well past the cooldown, so the next threshold is spoken too.
        assertNotNull(planner.onState(moving(200), 10_000))
        // Same distance on the next instruction speaks again.
        assertNotNull(planner.onState(moving(500, road = "高新路"), 20_000))
    }

    @Test
    fun steadyDistanceDoesNotRepeat() {
        val planner = VoicePlanner(minGapMs = 0)
        assertNotNull(planner.onState(moving(500), 0))
        repeat(5) { assertNull(planner.onState(moving(500), 1_000L * (it + 1))) }
    }

    @Test
    fun theCooldownMutesQuickFireAnnouncements() {
        val planner = VoicePlanner(minGapMs = 4_000)
        assertNotNull(planner.onState(moving(500), 0))
        // 200 m arrives 2 s later: too soon.
        assertNull(planner.onState(moving(200), 2_000))
        // 60 m still inside the window.
        assertNull(planner.onState(moving(60), 3_000))
    }

    @Test
    fun arrivalIgnoresTheCooldown() {
        val planner = VoicePlanner(minGapMs = 10_000)
        assertNotNull(planner.onState(moving(500), 0))
        val arrival = planner.onState(VoiceInput(active = true, arrived = true), 1_000)
        assertNotNull(arrival)
        assertTrue(arrival.text.contains("到达"))
        assertEquals(2, arrival.priority)
    }

    @Test
    fun recalculationIsAnnouncedOncePerEpisode() {
        val planner = VoicePlanner(minGapMs = 0)
        val first = planner.onState(VoiceInput(active = true, recalculating = true), 0)
        assertNotNull(first)
        assertNull(planner.onState(VoiceInput(active = true, recalculating = true), 1_000))
        planner.onState(VoiceInput(active = true, recalculating = false), 2_000)
        assertNotNull(planner.onState(VoiceInput(active = true, recalculating = true), 3_000))
    }

    @Test
    fun leavingNavigationClearsTheState() {
        val planner = VoicePlanner(minGapMs = 0)
        assertNotNull(planner.onState(moving(500), 0))
        planner.onState(VoiceInput(active = false), 1_000)
        assertNotNull(planner.onState(moving(500), 2_000))
    }

    @Test
    fun phrasesReadLikeDirections() {
        assertEquals("前方 500 米右转，进入科技路", Phrases.turn(ManeuverKind.RIGHT, 500, "科技路"))
        assertEquals("前方路口左转", Phrases.turn(ManeuverKind.LEFT, 20, ""))
        assertEquals("前方 1 点 2 公里继续直行", Phrases.turn(ManeuverKind.STRAIGHT, 1234, ""))
        assertEquals("前方路口掉头", Phrases.turn(ManeuverKind.UTURN_LEFT, 15, ""))
    }
}
