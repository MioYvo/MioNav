package io.mionav.voice

import io.mionav.hud.ManeuverKind

/** One thing to say. [priority] 2 interrupts the four-second gap, 0 waits its turn. */
data class Announcement(
    val text: String,
    val priority: Int = 0,
    /** What kind of event produced this, for logs and for the watch banner. */
    val kind: Kind = Kind.TURN,
) {
    enum class Kind { TURN, ROAD, RECALC, ARRIVE, CAMERA }
}
