package io.mionav.nav

import io.mionav.hud.ManeuverKind

data class LaneInfo(val usableMask: Int, val recommendedMask: Int)

/** Everything the watch needs. No Amap types leak past this class. */
data class NavState(
    val active: Boolean = false,
    val recalculating: Boolean = false,
    val gpsWeak: Boolean = false,
    val arrived: Boolean = false,
    val maneuver: ManeuverKind = ManeuverKind.UNKNOWN,
    val iconType: Int = 0,
    val stepDistanceM: Int = 0,
    val remainDistanceM: Int = 0,
    val remainMinutes: Int = 0,
    val speedKmh: Int = 0,
    val limitKmh: Int = 0,
    val currentRoad: String = "",
    val nextRoad: String = "",
    val serviceAreaDistanceM: Int? = null,
    val cameraDistanceM: Int? = null,
    val cameraType: Int = 0,
    val lanes: LaneInfo? = null,
    val etaEpochMin: Int = 0,
    val hasJunctionView: Boolean = false,
    /** WGS84 fix; null before the first GPS callback. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Travel direction in degrees clockwise from north, smoothed over the last few fixes. */
    val bearingDeg: Float = 0f,
)
