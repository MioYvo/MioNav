package io.mionav.hud

/** What the arrow means. Rendering never sees an Amap icon number. */
enum class ManeuverKind {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    UTURN_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    UTURN_RIGHT,
    FORK_LEFT,
    FORK_RIGHT,
    ROUNDABOUT,
    EXIT_ROUNDABOUT,
    MERGE,
    TOLLGATE,
    SERVICE_AREA,
    TUNNEL,
    FERRY,
    WAYPOINT,
    DESTINATION,
    UNKNOWN,
}

/**
 * Amap `NaviInfo.getIconType()` to [ManeuverKind].
 *
 * Values below were read out of `AMapNavi_11.3.100` (`com.amap.api.navi.enums.IconType`), not
 * guessed. Re-check with `javap -v -classpath <jar> com.amap.api.navi.enums.IconType` after an
 * SDK upgrade; the enum is additive and the numbers stay stable.
 */
object IconMapper {
    fun map(iconType: Int): ManeuverKind = when (iconType) {
        0 -> ManeuverKind.UNKNOWN
        1, 9, 20, 27 -> ManeuverKind.STRAIGHT
        2 -> ManeuverKind.LEFT
        3 -> ManeuverKind.RIGHT
        4 -> ManeuverKind.SLIGHT_LEFT
        5 -> ManeuverKind.SLIGHT_RIGHT
        6 -> ManeuverKind.SHARP_LEFT
        7 -> ManeuverKind.SHARP_RIGHT
        8, 24, 28 -> ManeuverKind.UTURN_LEFT
        19 -> ManeuverKind.UTURN_RIGHT
        10 -> ManeuverKind.WAYPOINT
        11, 17, 21, 22, 23, 25, 26 -> ManeuverKind.ROUNDABOUT
        12, 18 -> ManeuverKind.EXIT_ROUNDABOUT
        13 -> ManeuverKind.SERVICE_AREA
        14 -> ManeuverKind.TOLLGATE
        15 -> ManeuverKind.DESTINATION
        16 -> ManeuverKind.TUNNEL
        46 -> ManeuverKind.FERRY
        59, 61, 63 -> ManeuverKind.SLIGHT_LEFT
        60, 62 -> ManeuverKind.SLIGHT_RIGHT
        else -> ManeuverKind.UNKNOWN
    }
}
