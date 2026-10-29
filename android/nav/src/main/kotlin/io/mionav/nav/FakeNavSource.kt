package io.mionav.nav

import io.mionav.geo.Point
import io.mionav.hud.ManeuverKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives a synthetic route so the watch and the link can be built without Amap or a bike.
 * Same numbers as the design doc examples: 42.6 km, 58 min, one service area at 2.3 km.
 */
class FakeNavSource(private val scope: CoroutineScope) : NavSource {

    private val _state = MutableStateFlow(NavState())
    override val state: StateFlow<NavState> = _state

    private var job: Job? = null

    override suspend fun planRoute(destination: Point, via: List<Point>, strategies: Int): RoutePlan {
        val path = (0..40).map { i -> Point(30.0 + i * 0.002, 120.0 + i * 0.002) }
        return RoutePlan(
            listOf(
                RouteCandidate(1, "推荐", 42_600, 58, path),
                RouteCandidate(2, "不走高速", 38_100, 64, path),
            ),
            selectedId = 1,
        )
    }

    override suspend fun start(routeId: Int) {
        job?.cancel()
        var step = 0
        var remain = 42_600
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                step++
                remain = (remain - 40).coerceAtLeast(0)
                _state.value = NavState(
                    active = true,
                    maneuver = when (step / 8 % 4) {
                        0 -> ManeuverKind.STRAIGHT
                        1 -> ManeuverKind.SLIGHT_RIGHT
                        2 -> ManeuverKind.RIGHT
                        else -> ManeuverKind.ROUNDABOUT
                    },
                    stepDistanceM = 320 - (step % 8) * 40,
                    remainDistanceM = remain,
                    remainMinutes = (remain / 730).coerceAtLeast(1),
                    speedKmh = 62,
                    limitKmh = if (step % 20 < 10) 80 else 0,
                    currentRoad = "G60 沪昆高速",
                    serviceAreaDistanceM = (2300 - step * 40).takeIf { it > 0 },
                    cameraDistanceM = 480,
                    cameraType = 2,
                    lanes = LaneInfo(0b0000_0111, 0b0000_0010),
                    etaEpochMin = 12 * 60 + 40,
                )
                delay(1_000)
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        _state.value = NavState()
    }
}
