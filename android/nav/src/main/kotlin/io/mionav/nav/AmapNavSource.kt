package io.mionav.nav

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapCarInfo
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import io.mionav.geo.Gcj
import io.mionav.geo.Point
import io.mionav.hud.IconMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Calendar

/**
 * Amap adapter. Everything Amap-specific lives here, so the rest of the app only sees [NavState].
 *
 * The listener is a dynamic proxy on purpose: `AMapNaviListener` grows methods between SDK
 * releases, and a direct implementation breaks on every bump. Only the callbacks we care about
 * are dispatched; everything else returns null.
 *
 * Requires `AMapNavi.setApiKey` and the privacy calls to have run first (see `MioNavApp`).
 * Amap works in GCJ-02; this class converts at the boundary.
 */
class AmapNavSource(
    private val context: Context,
    private val vehicle: VehicleProfile? = null,
) : NavSource {

    /**
     * Amap vehicle description. `carType` "11" plus the engine displacement selects motorcycle
     * routing, which is a **paid** Amap interface (`setCarInfo`); without the entitlement the
     * service ignores the profile and routes as a car, so it is off by default.
     */
    data class VehicleProfile(val carType: String, val motorcycleCc: Int? = null)

    private val _state = MutableStateFlow(NavState())
    override val state: StateFlow<NavState> = _state

    private var routeResult: CompletableDeferred<Unit>? = null
    private var routeError: Throwable? = null
    private var lastFix: Pair<Double, Double>? = null

    private val navi: AMapNavi by lazy {
        AMapNavi.getInstance(context).apply {
            addAMapNaviListener(listenerProxy)
            // Our own TTS owns the audio focus policy; the SDK voice would ignore it.
            setUseInnerVoice(false)
        }
    }

    private val listenerProxy: AMapNaviListener by lazy {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "onNaviInfoUpdate" -> onNaviInfo(args?.getOrNull(0) as? NaviInfo)
                "updateCameraInfo" -> onCameras(args?.getOrNull(0))
                "onServiceAreaUpdate" -> onServiceAreas(args?.getOrNull(0))
                "showLaneInfo" -> onLanes(args?.getOrNull(0))
                "onCalculateRouteSuccess" -> {
                    io.mionav.link.Diag.event("route.cb.success")
                    routeResult?.complete(Unit)
                }
                "onCalculateRouteFailure" -> {
                    io.mionav.link.Diag.event("route.cb.failure", args?.firstOrNull())
                    routeError = IllegalStateException("route failed: ${args?.firstOrNull()}")
                    routeResult?.complete(Unit)
                }
                "onGpsSignalWeak" -> _state.value = _state.value.copy(gpsWeak = args?.getOrNull(0) == true)
                "onReCalculateRouteForYaw", "onReCalculateRouteForTrafficJam" ->
                    _state.value = _state.value.copy(recalculating = true)
                "onStartNavi" -> _state.value = _state.value.copy(active = true, recalculating = false)
                "onArriveDestination" -> _state.value = _state.value.copy(arrived = true, active = false)
                "showCross" -> _state.value = _state.value.copy(hasJunctionView = args?.getOrNull(0) != null)
                else -> null
            }
        }
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AMapNaviListener::class.java),
            handler,
        ) as AMapNaviListener
    }

    override suspend fun planRoute(
        destination: Point,
        via: List<Point>,
        strategies: Int,
    ): RoutePlan {
        navi.startGPS()
        applyVehicle()
        routeError = null

        // Route planning needs a start point. GPS can take a while (or never, indoors), so the
        // network fix from the location SDK is the fallback before giving up.
        val start = debugStart ?: awaitFix() ?: lastKnownFix()
        io.mionav.link.Diag.event(
            "route.start",
            "$start (debug=${debugStart != null}, fallback=${debugStart == null && start != null})",
        )
        if (start == null) throw IllegalStateException(
            "10 秒内没有定位。先到能看到天空的地方，或确认定位权限已给。",
        )
        val done = CompletableDeferred<Unit>().also { routeResult = it }

        val end = destination.toGcj()
        val waypoints = via.map { it.toGcj() }
        // TODO(MIO-2): expose the five strategy flags in settings.
        val strategy = navi.strategyConvert(true, false, false, false, strategies > 1)
        val accepted = if (waypoints.isEmpty()) {
            navi.calculateDriveRoute(listOf(start), listOf(end), null, strategy)
        } else {
            navi.calculateDriveRoute(listOf(start), listOf(end), waypoints, strategy)
        }
        io.mionav.link.Diag.event("route.requested", "accepted=$accepted")
        check(accepted) { "calculateDriveRoute rejected the request" }

        // A missing callback would otherwise leave the screen on "正在算路" forever.
        val answered = kotlinx.coroutines.withTimeoutOrNull(20_000) { done.await(); true } ?: false
        if (!answered) {
            io.mionav.link.Diag.event("route.timeout")
            throw IllegalStateException("20 秒没有收到算路回调")
        }
        routeError?.let { throw it }

        val paths = navi.naviPaths ?: error("no routes returned")
        val candidates = paths.map { (id, path) ->
            RouteCandidate(
                id = id,
                label = path.labels.orEmpty(),
                distanceM = path.allLength,
                minutes = (path.allTime / 60).coerceAtLeast(1),
                path = path.coordList.map { Gcj.gcjToWgs84(it.latitude, it.longitude) }
                    .map { (lat, lon) -> Point(lat, lon) },
            )
        }.sortedBy { it.minutes }

        check(candidates.isNotEmpty()) { "empty route list" }
        candidates.firstOrNull()?.let { first ->
            val lats = first.path.map { it.lat }
            val lons = first.path.map { it.lon }
            io.mionav.link.Diag.event(
                "route.shape",
                "points=${first.path.size} lat=${lats.minOrNull()}..${lats.maxOrNull()} " +
                    "lon=${lons.minOrNull()}..${lons.maxOrNull()}",
            )
        }
        return RoutePlan(candidates, candidates.first().id)
    }

    private fun applyVehicle() {
        val profile = vehicle ?: return
        val info = AMapCarInfo().apply {
            carType = profile.carType
            profile.motorcycleCc?.let { motorcycleCC = it }
            isRestriction = true
            isVehicleLoadSwitch = false
        }
        navi.carInfo = info
    }

    override suspend fun start(routeId: Int) {
        selectRoute(routeId)
        check(navi.startNavi(NaviType.GPS)) { "startNavi failed" }
    }

    override suspend fun stop() {
        runCatching { navi.stopNavi() }
        _state.value = NavState()
    }

    private companion object {
        /** Older than this and the cached fix is a guess about a different city. */
        const val MAX_FIX_AGE_MS = 10 * 60 * 1000L
    }

    /** Amap reports the current fix as "lon,lat" in GCJ-02; the app wants WGS84. */
    private fun readFix(): Pair<Double, Double>? {
        val raw = AMapNavi.getNaviLocation() ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size != 2) return null
        return Gcj.gcjToWgs84(parts[1], parts[0])
    }

    /**
     * Course over ground, not a compass: the phone's magnetometer sits next to a bike mount and a
     * speaker. Below walking pace the noise dominates, so the previous value is kept.
     */
    private fun courseTo(fix: Pair<Double, Double>): Float {
        val prev = lastFix
        lastFix = fix
        if (prev == null) return _state.value.bearingDeg
        val (lat1, lon1) = prev
        val (lat2, lon2) = fix
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(Math.toRadians(lat2))
        val x = kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.sin(Math.toRadians(lat2)) -
            kotlin.math.sin(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.cos(dLon)
        val movedM = kotlin.math.hypot(dLat, dLon * kotlin.math.cos(Math.toRadians(lat1))) * 6_371_000.0
        if (movedM < 3.0) return _state.value.bearingDeg
        val degrees = (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
        // Circular smoothing keeps 359 -> 1 from swinging the map.
        val prevRad = Math.toRadians(_state.value.bearingDeg.toDouble())
        val rad = Math.toRadians(degrees)
        val s = kotlin.math.sin(prevRad) * 0.7 + kotlin.math.sin(rad) * 0.3
        val c = kotlin.math.cos(prevRad) * 0.7 + kotlin.math.cos(rad) * 0.3
        return ((Math.toDegrees(kotlin.math.atan2(s, c)) + 360.0) % 360.0).toFloat()
    }

    /**
     * Debug-only start point (GCJ-02), set from the confirm screen's `start` extra so a route can
     * be planned indoors, where there is never a GPS fix. Null in normal use.
     */
    @Volatile
    var debugStart: NaviLatLng? = null

    /** The same fix, but in the shape `calculateDriveRoute` wants. */
    private fun naviFix(): NaviLatLng? {
        val raw = AMapNavi.getNaviLocation() ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size != 2) return null
        return NaviLatLng(parts[1], parts[0])
    }

    /**
     * AMapLocationClient answers from wifi/cell immediately, where a GPS fix can take minutes.
     * Only a recent fix is trusted: a cached one can be from another city entirely.
     */
    private fun lastKnownFix(): NaviLatLng? = runCatching {
        val client = com.amap.api.location.AMapLocationClient(context)
        try {
            val fix = client.lastKnownLocation ?: return null
            val ageMs = System.currentTimeMillis() - fix.time
            io.mionav.link.Diag.event("route.lastKnown", "age=${ageMs / 1000}s acc=${fix.accuracy}")
            if (ageMs > MAX_FIX_AGE_MS) return null
            NaviLatLng(fix.latitude, fix.longitude)
        } finally {
            runCatching { client.onDestroy() }
        }
    }.getOrNull()

    private suspend fun awaitFix(timeoutMs: Long = 8_000): NaviLatLng? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            naviFix()?.let { return it }
            kotlinx.coroutines.delay(250)
        }
        return null
    }

    private fun selectRoute(routeId: Int) {
        // Both names exist across SDK versions.
        runCatching {
            AMapNavi::class.java
                .getMethod("selectMainPathID", Int::class.javaPrimitiveType)
                .invoke(navi, routeId)
        }.recoverCatching {
            AMapNavi::class.java
                .getMethod("selectRouteId", Int::class.javaPrimitiveType)
                .invoke(navi, routeId)
        }
    }

    private fun Point.toGcj(): NaviLatLng {
        val (lat, lon) = Gcj.wgs84ToGcj(this.lat, this.lon)
        return NaviLatLng(lat, lon)
    }

    private fun onNaviInfo(info: NaviInfo?) {
        val i = info ?: return
        val clock = Calendar.getInstance()
        val fix = readFix()
        val bearing = if (fix != null) courseTo(fix) else _state.value.bearingDeg
        _state.value = _state.value.copy(
            active = true,
            maneuver = IconMapper.map(i.iconType),
            iconType = i.iconType,
            stepDistanceM = i.curStepRetainDistance,
            remainDistanceM = i.pathRetainDistance,
            remainMinutes = (i.pathRetainTime / 60).coerceAtLeast(0),
            currentRoad = i.currentRoadName.orEmpty(),
            speedKmh = i.currentSpeed,
            etaEpochMin = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE) + i.pathRetainTime / 60,
            lat = fix?.first ?: _state.value.lat,
            lon = fix?.second ?: _state.value.lon,
            bearingDeg = bearing,
        )
    }

    private fun onCameras(raw: Any?) {
        val list = (raw as? Array<*>)?.filterIsInstance<AMapNaviCameraInfo>().orEmpty()
        val nearest = list.minByOrNull { it.cameraDistance } ?: return
        _state.value = _state.value.copy(
            cameraDistanceM = nearest.cameraDistance,
            cameraType = nearest.cameraType,
            limitKmh = nearest.cameraSpeed.coerceIn(0, 199),
        )
    }

    private fun onServiceAreas(raw: Any?) {
        val list = (raw as? Array<*>)?.filterIsInstance<AMapServiceAreaInfo>().orEmpty()
        val nearest = list.minByOrNull { it.remainDist } ?: return
        _state.value = _state.value.copy(serviceAreaDistanceM = nearest.remainDist)
    }

    private fun onLanes(raw: Any?) {
        val list = (raw as? Array<*>)?.filterIsInstance<AMapLaneInfo>()?.take(8).orEmpty()
        if (list.isEmpty()) return
        var usable = 0
        var recommended = 0
        list.forEachIndexed { index, lane ->
            usable = usable or (1 shl index)
            if (lane.isRecommended) recommended = recommended or (1 shl index)
        }
        _state.value = _state.value.copy(lanes = LaneInfo(usable, recommended))
    }
}
