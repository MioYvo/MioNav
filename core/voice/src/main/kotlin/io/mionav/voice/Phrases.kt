package io.mionav.voice

import io.mionav.hud.ManeuverKind

/**
 * Turns a [ManeuverKind] plus a distance into the sentence a rider hears.
 *
 * Kept away from the Android layer so the wording can be unit-tested and, later, swapped for a
 * recorded voice pack: the planner decides the words, the player only decides how they sound.
 */
object Phrases {

    /** Spoken distance: short numbers stay in metres, long ones switch to kilometres. */
    fun distance(metres: Int): String = when {
        metres >= 1000 -> {
            val km = metres / 1000.0
            val whole = km.toInt()
            val decimal = ((km - whole) * 10).toInt()
            if (decimal == 0) "$whole 公里" else "$whole 点 $decimal 公里"
        }
        metres <= 20 -> "路口"
        else -> "${metres / 10 * 10} 米"
    }

    fun action(maneuver: ManeuverKind): String = when (maneuver) {
        ManeuverKind.LEFT -> "左转"
        ManeuverKind.SLIGHT_LEFT -> "稍向左转"
        ManeuverKind.SHARP_LEFT -> "向左急转"
        ManeuverKind.UTURN_LEFT, ManeuverKind.UTURN_RIGHT -> "掉头"
        ManeuverKind.RIGHT -> "右转"
        ManeuverKind.SLIGHT_RIGHT -> "稍向右转"
        ManeuverKind.SHARP_RIGHT -> "向右急转"
        ManeuverKind.FORK_LEFT -> "靠左行驶"
        ManeuverKind.FORK_RIGHT -> "靠右行驶"
        ManeuverKind.MERGE -> "汇入主路"
        ManeuverKind.ROUNDABOUT -> "进入环岛"
        ManeuverKind.EXIT_ROUNDABOUT -> "驶出环岛"
        ManeuverKind.TOLLGATE -> "通过收费站"
        ManeuverKind.SERVICE_AREA -> "前方服务区"
        ManeuverKind.TUNNEL -> "进入隧道"
        ManeuverKind.FERRY -> "上轮渡"
        ManeuverKind.WAYPOINT -> "经过途经点"
        ManeuverKind.DESTINATION -> "到达目的地"
        ManeuverKind.STRAIGHT, ManeuverKind.UNKNOWN -> "继续直行"
    }

    /** 「前方 500 米右转，进入科技路」 — the road name is dropped when there is none. */
    fun turn(maneuver: ManeuverKind, distanceM: Int, nextRoad: String): String {
        val head = if (distanceM > 20) {
            "前方 ${distance(distanceM)}${action(maneuver)}"
        } else {
            when (maneuver) {
                // "前方路口继续直行" is noise; only turns belong to an intersection.
                ManeuverKind.STRAIGHT, ManeuverKind.UNKNOWN, ManeuverKind.DESTINATION ->
                    "前方${action(maneuver)}"
                else -> "前方路口${action(maneuver)}"
            }
        }
        return if (nextRoad.isBlank()) head else "$head，进入$nextRoad"
    }
}
