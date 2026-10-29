package io.mionav.nav

import io.mionav.geo.Point
import kotlinx.coroutines.flow.StateFlow

data class RouteCandidate(
    val id: Int,
    val label: String,
    val distanceM: Int,
    val minutes: Int,
    val path: List<Point>,
    val tollYuan: Int? = null,
)

data class RoutePlan(val candidates: List<RouteCandidate>, val selectedId: Int)

/** The only surface the app talks to. Amap lives behind it. */
interface NavSource {
    val state: StateFlow<NavState>

    /** Start GPS and compute routes. [via] are optional waypoints in WGS84. */
    suspend fun planRoute(destination: Point, via: List<Point> = emptyList(), strategies: Int = 3): RoutePlan

    /** Begin turn-by-turn for one candidate. */
    suspend fun start(routeId: Int)

    suspend fun stop()
}
