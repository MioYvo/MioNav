package io.mionav.voice

import io.mionav.hud.ManeuverKind

/** The few fields a decision needs; [io.mionav.nav.NavState] maps onto it in one line. */
data class VoiceInput(
    val active: Boolean = false,
    val recalculating: Boolean = false,
    val arrived: Boolean = false,
    val maneuver: ManeuverKind = ManeuverKind.UNKNOWN,
    val stepDistanceM: Int = 0,
    val nextRoad: String = "",
)

/**
 * Decides what to say, and when — never how to play it.
 *
 * One `NavState` arrives per second, so every rule has to be idempotent: a threshold is announced
 * once per instruction, and two announcements never sit closer than [minGapMs] unless the later
 * one is urgent (arrival, recalculation).
 */
class VoicePlanner(
    private val thresholds: List<Int> = listOf(500, 200, 60),
    private val minGapMs: Long = 4_000,
) {

    /** Identifies the instruction, not the distance: distance changes every second. */
    private var instruction = ""
    private val spoken = mutableSetOf<Int>()
    private var arrivedSpoken = false
    private var recalcSpoken = false
    /** Not Long.MIN_VALUE: subtracting it overflows and the gap check silently passes. */
    private var lastSpokenAt = -1_000_000L

    fun onState(state: VoiceInput, nowMs: Long): Announcement? {
        if (state.arrived && !arrivedSpoken) {
            arrivedSpoken = true
            return emit(Announcement("已到达目的地附近", priority = 2, kind = Announcement.Kind.ARRIVE), nowMs)
        }

        if (state.recalculating && !recalcSpoken) {
            recalcSpoken = true
            return emit(Announcement("正在重新规划路线", priority = 2, kind = Announcement.Kind.RECALC), nowMs)
        }
        if (!state.recalculating) recalcSpoken = false

        if (!state.active) {
            // Leaving navigation resets everything so the next ride starts clean.
            instruction = ""
            spoken.clear()
            arrivedSpoken = false
            return null
        }

        val signature = "${state.maneuver.name}|${state.nextRoad}"
        if (signature != instruction) {
            instruction = signature
            spoken.clear()
        }

        val crossed = thresholds
            .filter { it !in spoken && state.stepDistanceM in 1..it }
            .maxOrNull()
            ?: return null

        spoken.add(crossed)
        val text = Phrases.turn(state.maneuver, crossed, state.nextRoad)
        return emit(Announcement(text, priority = if (crossed == thresholds.min()) 1 else 0), nowMs)
    }

    private fun emit(announcement: Announcement, nowMs: Long): Announcement? {
        val urgent = announcement.priority >= 2
        if (!urgent && nowMs - lastSpokenAt < minGapMs) return null
        lastSpokenAt = nowMs
        return announcement
    }
}
