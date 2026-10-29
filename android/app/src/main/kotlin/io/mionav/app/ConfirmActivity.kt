package io.mionav.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import io.mionav.geo.Gcj
import io.mionav.geo.Point
import io.mionav.geo.ShareLinkParser
import io.mionav.geo.ShareTarget
import io.mionav.nav.ShareResolver
import io.mionav.hud.HudText
import io.mionav.nav.RouteCandidate
import io.mionav.nav.RoutePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The confirmation screen from the design. Route geometry comes from Amap in GCJ-02, and the
 * map is Amap too, so nothing is converted for display. The WGS84 copy is only for the watch.
 */
class ConfirmActivity : ComponentActivity() {

    /** Held in state so a share arriving on top of an open screen re-plans. */
    private val targetState = mutableStateOf<ShareTarget?>(null)

    companion object {
        /** Amap hands us the whole share body; only the link inside it matters. */
        fun takeSharedText(intent: Intent?, container: AppContainer) {
            // A share sheet hands over text; a tapped link hands over data.
            val text = when (intent?.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.dataString
                else -> null
            } ?: return
            container.lastShareText = text
            io.mionav.link.Diag.event("share.received", text)
            val parsed = ShareLinkParser.parse(text)
            io.mionav.link.Diag.event("share.parsed", parsed ?: "nothing")
            parsed?.let { container.pendingTarget = it }
        }
    }

    /** A second share while this screen is up replaces the destination instead of being ignored. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as MioNavApp).container
        container.pendingTarget = null
        takeSharedText(intent, container)
        readDebugExtras(intent, container)
        targetState.value = container.pendingTarget
    }

    /** Debug hooks: a fixed start and a fixed destination, so the SDK path runs without a share. */
    private fun readDebugExtras(intent: Intent?, container: AppContainer) {
        if (!BuildConfig.DEBUG) return
        intent?.getStringExtra("start")?.split(",")?.map { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 && it.all { v -> v != null } }
            ?.let { (lat, lon) ->
                (container.nav as? io.mionav.nav.AmapNavSource)?.debugStart =
                    com.amap.api.maps.model.LatLng(lat!!, lon!!)
                        .let { com.amap.api.navi.model.NaviLatLng(it.latitude, it.longitude) }
                io.mionav.link.Diag.event("route.debugStart", "$lat,$lon")
            }
        // A destination without an Amap share, for looking at the map itself.
        intent?.getStringExtra("dest")?.split(",")?.map { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 && it.all { v -> v != null } }
            ?.let { (lat, lon) ->
                container.pendingTarget =
                    io.mionav.geo.ShareTarget.Coordinates(lat!!, lon!!, "演示目的地")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MioNavApp).container
        takeSharedText(intent, container)
        readDebugExtras(intent, container)
        targetState.value = container.pendingTarget

        setContent {
            val prefs = remember {
                getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            }
            val systemNight = androidx.compose.foundation.isSystemInDarkTheme()
            var forcedNight by remember {
                mutableStateOf<Boolean?>(
                    when (prefs.getString("night", "auto")) {
                        "on" -> true
                        "off" -> false
                        else -> null
                    },
                )
            }
            val night = forcedNight ?: systemNight
            // A bright sheet next to a dark map blinds the passenger, so the whole screen follows.
            val colors = if (night) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
            SideEffect {
                window.statusBarColor = if (night) 0xFF12161A.toInt() else 0xFFF6F6F1.toInt()
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !night
            }
            MaterialTheme(colorScheme = colors) {
                val target = targetState.value
                var plan by remember { mutableStateOf(container.plan) }
                var focus by remember { mutableStateOf<Point?>(null) }
                var selected by remember { mutableIntStateOf(plan?.selectedId ?: 0) }
                var error by remember { mutableStateOf<String?>(null) }
                var loading by remember { mutableStateOf(true) }

                LaunchedEffect(target) {
                    loading = true
                    if (target == null) {
                        error = "没有可用的分享目标"
                    } else {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                when (val resolved = container.resolver.resolve(target)) {
                                    is ShareResolver.Result.Resolved -> {
                                        io.mionav.link.Diag.event("share.resolved", resolved.point)
                                        focus = resolved.point
                                        container.nav.planRoute(resolved.point).also { container.destinationName = resolved.name }
                                    }
                                    is ShareResolver.Result.Failed -> error(resolved.reason)
                                }
                            }
                        }
                        result.onSuccess {
                            plan = it
                            selected = it.selectedId
                            container.plan = it
                            io.mionav.link.Diag.event("route.planned", "${it.candidates.size} candidates")
                        }.onFailure {
                            io.mionav.link.Diag.event("route.failed", it.message ?: it.javaClass.simpleName)
                            error = it.message
                        }
                    }
                    loading = false
                }

                // A Surface, not a bare Column: it paints the background *and* hands the right
                // content colour to every Text inside, which is why the error line was unreadable.
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                Column(Modifier.fillMaxSize()) {
                    MapPanel(
                        candidates = plan?.candidates.orEmpty(),
                        focus = focus,
                        selectedId = selected,
                        destinationName = container.destinationName,
                        night = night,
                        onToggleNight = {
                            val next = !night
                            forcedNight = next
                            prefs.edit().putString("night", if (next) "on" else "off").apply()
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    Text(
                        text = error ?: if (loading) "正在算路…" else "选择路线后开始导航",
                        color = if (error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // A failed parse is the one thing worth copying back verbatim.
                    if (error != null) {
                        container.lastShareText?.let { raw ->
                            Text(
                                text = raw,
                                modifier = Modifier.padding(horizontal = 16.dp).weight(0.3f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        items(plan?.candidates.orEmpty()) { candidate ->
                            RouteCard(candidate, candidate.id == selected) { selected = candidate.id }
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            enabled = plan != null && container.pendingTarget != null,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                startForegroundService(Intent(this@ConfirmActivity, NavService::class.java))
                                finish()
                            },
                        ) { Text("开始导航") }
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun RouteCard(candidate: RouteCandidate, selected: Boolean, onClick: () -> Unit) {
        Card(
            modifier = Modifier.padding(vertical = 4.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${HudText.distance(candidate.distanceM)} · ${HudText.duration(candidate.minutes)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    candidate.label.ifBlank { "路线 ${candidate.id}" },
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) {
                    Text(if (selected) "已选" else "选它")
                }
            }
        }
    }
}

/** Thin MapView wrapper: lifecycle forwarding, route drawing, compass. */
@Composable
private fun MapPanel(
    candidates: List<RouteCandidate>,
    focus: Point?,
    selectedId: Int,
    destinationName: String?,
    night: Boolean,
    onToggleNight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density
    val palette = remember(night) { MapPalette.of(night) }
    val arrow = remember(palette) { arrowBitmap(palette) }
    var aMap by remember { mutableStateOf<AMap?>(null) }

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().background(Color(0xFFF6F6F1)),
            update = { view ->
                val map: AMap = view.map ?: return@AndroidView
                aMap = map
                map.mapType = if (palette.night) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
                // The SDK compass and zoom buttons sit inside the map; ours live outside it.
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isZoomControlsEnabled = false
                val draw = {
                    drawRoutes(
                        map, candidates, selectedId, focus, destinationName, arrow, palette, density,
                    )
                }
                // The first update can run before layout, when a bounds fit silently misses.
                draw()
                view.post { draw() }
            },
        )

        DayNightToggle(
            night = night,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                .padding(top = 76.dp, end = 12.dp),
            onToggle = onToggleNight,
        )

        CompassButton(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            onClick = {
                val map = aMap ?: return@CompassButton
                val camera = map.cameraPosition
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition(camera.target, camera.zoom, 0f, 0f),
                    ),
                )
            },
        )
    }
}

/** Sun or moon; tapping it switches the map between its day and night palettes. */
@Composable
private fun DayNightToggle(night: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (night) Color(0xFF1F2933) else Color.White)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (night) "夜" else "日",
            color = if (night) Color(0xFFE8EEF5) else Color(0xFF1F2933),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Points north; tapping it lines the map back up with north. */
@Composable
private fun CompassButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.94f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val mid = w / 2f
            drawPath(
                Path().apply {
                    moveTo(mid, 0f)
                    lineTo(w, h * 0.52f)
                    lineTo(mid, h * 0.4f)
                    close()
                },
                Color(0xFFE8452C),
            )
            drawPath(
                Path().apply {
                    moveTo(mid, h)
                    lineTo(0f, h * 0.48f)
                    lineTo(mid, h * 0.6f)
                    close()
                },
                Color(0xFF9AA1AC),
            )
        }
    }
}



private fun drawRoutes(
    aMap: AMap,
    candidates: List<RouteCandidate>,
    selectedId: Int,
    focus: Point?,
    destinationName: String?,
    arrow: BitmapDescriptor?,
    palette: MapPalette,
    density: Float,
) {
    aMap.clear()

    // Z-order is the whole point of this pass: an unchosen route must never sit on top of the
    // chosen one, and the chosen one is always the last thing painted.
    val chosen = candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull()
    // Only where an unchosen route actually leaves the chosen one. Drawing it in full either hides
    // it under the chosen line or, worse, paints grey over it.
    candidates.filter { it.id != selectedId }.forEach { candidate ->
        if (chosen == null || chosen.path.size < 2) return@forEach
        io.mionav.geo.RouteDiff.divergent(candidate.path, chosen.path).forEach { run ->
            val points = run.map { toGcj(it) }
            if (points.size < 2) return@forEach
            aMap.addPolyline(
                PolylineOptions().addAll(points).width(15f)
                    .color(palette.alternativeCasing).zIndex(2f),
            )
            aMap.addPolyline(
                PolylineOptions().addAll(points).width(11f)
                    .color(palette.alternative).zIndex(3f),
            )
        }
    }

    candidates.firstOrNull { it.id == selectedId }?.let { candidate ->
        val points = candidate.path.map { toGcj(it) }
        if (points.size >= 2) {
            // Casing first, route on top: the line is carved out of the map instead of blending in.
            aMap.addPolyline(
                PolylineOptions().addAll(points).width(26f).color(palette.casing).zIndex(4f),
            )
            // Coloured by traffic, one polyline per status; unknown sections keep the route colour.
            val slices = io.mionav.geo.TrafficSlicer.slice(candidate.path, candidate.traffic)
            io.mionav.link.Diag.event(
                "map.slices",
                "spans=${candidate.traffic.size} by status=${slices.groupingBy { it.status }.eachCount()}",
            )
            slices.forEach { slice ->
                val segment = slice.points.map { toGcj(it) }
                if (segment.size >= 2) {
                    aMap.addPolyline(
                        PolylineOptions()
                            .addAll(segment)
                            .width(16f)
                            .color(palette.traffic(slice.status))
                            .zIndex(5f),
                    )
                }
            }
            arrow?.let { icon ->
                arrowsAlong(points).forEach { (position, bearing) ->
                    val marker = aMap.addMarker(
                        MarkerOptions()
                            .position(position)
                            .icon(icon)
                            .anchor(0.5f, 0.5f)
                            .setFlat(true)
                            .zIndex(6f),
                    )
                    // Amap's rotate angle runs counter-clockwise from north.
                    marker?.setRotateAngle((360f - bearing) % 360f)
                }
            }
        }
    }

    val selected = candidates.firstOrNull { it.id == selectedId } ?: candidates.firstOrNull()
    selected?.let { candidate ->
        if (candidate.path.size >= 2) {
            val start = candidate.path.first()
            val end = candidate.path.last()
            aMap.addMarker(
                MarkerOptions()
                    .position(toGcj(start))
                    .icon(labelBitmap("起点", palette.startLabel, density))
                    .anchor(0.5f, 1f)
                    .zIndex(9f),
            )
            aMap.addMarker(
                MarkerOptions()
                    .position(toGcj(end))
                    .icon(labelBitmap(destinationName ?: "终点", palette.endLabel, density))
                    .anchor(0.5f, 1f)
                    .zIndex(9f),
            )
            val builder = LatLngBounds.Builder()
            candidate.path.forEach { builder.include(toGcj(it)) }
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 140))
        }
    }

    if (candidates.isEmpty()) {
        focus?.let {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(toGcj(it), 13f))
        }
    }
}

/** Chunks of the path that should be drawn, the rest stays empty: an honest dashed line. */
private fun dashes(
    path: List<LatLng>,
    dashM: Double = 60.0,
    gapM: Double = 40.0,
): List<List<LatLng>> {
    val runs = mutableListOf<List<LatLng>>()
    if (path.size < 2) return runs
    var run = mutableListOf(path.first())
    var drawing = true
    var phase = 0.0
    var prev = path.first()

    for (i in 1 until path.size) {
        val point = path[i]
        val segment = metres(prev, point)
        val steps = maxOf(1, (segment / 8.0).toInt())
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val sample = LatLng(
                prev.latitude + (point.latitude - prev.latitude) * t,
                prev.longitude + (point.longitude - prev.longitude) * t,
            )
            phase += segment / steps
            val limit = if (drawing) dashM else gapM
            if (phase >= limit) {
                if (drawing) {
                    run.add(sample)
                    if (run.size >= 2) runs.add(run)
                    run = mutableListOf()
                } else {
                    run = mutableListOf(sample)
                }
                drawing = !drawing
                phase = 0.0
            } else if (drawing) {
                run.add(sample)
            }
        }
        prev = point
    }
    if (run.size >= 2) runs.add(run)
    return runs
}

/** Arrow positions on the route, roughly one every [spacingM], with the local bearing. */
private fun arrowsAlong(
    path: List<LatLng>,
    spacingM: Double = 650.0,
    maxArrows: Int = 80,
): List<Pair<LatLng, Float>> {
    if (path.size < 2) return emptyList()
    var cumulative = 0.0
    var next = spacingM / 2
    val out = mutableListOf<Pair<LatLng, Float>>()
    for (i in 1 until path.size) {
        val from = path[i - 1]
        val to = path[i]
        cumulative += metres(from, to)
        if (cumulative >= next && out.size < maxArrows) {
            out.add(to to bearing(from, to))
            next += spacingM
        }
    }
    return out
}

private fun metres(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat = Math.toRadians((a.latitude + b.latitude) / 2)
    val x = dLon * Math.cos(lat)
    return Math.hypot(dLat, x) * 6_371_000.0
}

private fun bearing(a: LatLng, b: LatLng): Float {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val y = Math.sin(dLon) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
    return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}

/** A chevron with a contrasting outline, readable under sun and without glare at night. */
private fun arrowBitmap(palette: MapPalette): BitmapDescriptor {
    val size = 26
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val chevron = android.graphics.Path().apply {
        moveTo(size * 0.5f, size * 0.10f)
        lineTo(size * 0.86f, size * 0.74f)
        lineTo(size * 0.5f, size * 0.56f)
        lineTo(size * 0.14f, size * 0.74f)
        close()
    }
    canvas.drawPath(
        chevron,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.arrowCasing
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
        },
    )
    canvas.drawPath(
        chevron,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.arrow
            style = Paint.Style.FILL
        },
    )
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** A name bubble, so a marker says where it is instead of being a bare pin. */
private fun labelBitmap(text: String, background: Int, density: Float): BitmapDescriptor {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        color = android.graphics.Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val padH = 9f * density
    val padV = 7f * density
    val width = (paint.measureText(text) + padH * 2).toInt().coerceAtLeast((28 * density).toInt())
    val height = (paint.textSize + padV * 2).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
        height / 2f,
        height / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
    )
    val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, padH, baseline, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun toGcj(point: Point): LatLng {
    val (lat, lon) = Gcj.wgs84ToGcj(point.lat, point.lon)
    return LatLng(lat, lon)
}
