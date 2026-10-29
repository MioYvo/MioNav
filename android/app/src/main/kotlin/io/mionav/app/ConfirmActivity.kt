package io.mionav.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
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

    companion object {
        /** Amap hands us the whole share body; only the link inside it matters. */
        fun takeSharedText(intent: Intent?, container: AppContainer) {
            if (intent?.action != Intent.ACTION_SEND) return
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            ShareLinkParser.parse(text)?.let { container.pendingTarget = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MioNavApp).container
        takeSharedText(intent, container)
        val target = container.pendingTarget

        setContent {
            MaterialTheme {
                var plan by remember { mutableStateOf(container.plan) }
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
                                    is ShareResolver.Result.Resolved ->
                                        container.nav.planRoute(resolved.point).also { container.destinationName = resolved.name }
                                    is ShareResolver.Result.Failed -> error(resolved.reason)
                                }
                            }
                        }
                        result.onSuccess { plan = it; selected = it.selectedId; container.plan = it }
                            .onFailure { error = it.message }
                    }
                    loading = false
                }

                Column(Modifier.fillMaxSize()) {
                    AmapMap(
                        candidates = plan?.candidates.orEmpty(),
                        selectedId = selected,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    Text(
                        text = error ?: if (loading) "正在算路…" else "选择路线后开始导航",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )

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

/** Thin MapView wrapper: lifecycle forwarding plus polyline drawing. */
@Composable
private fun AmapMap(candidates: List<RouteCandidate>, selectedId: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

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

    AndroidView(
        factory = { mapView },
        modifier = modifier.background(Color(0xFFF6F6F1)),
        update = { view ->
            val aMap: AMap = view.map ?: return@AndroidView
            aMap.clear()
            candidates.forEach { candidate ->
                val points = candidate.path.map { toGcj(it) }
                if (points.size < 2) return@forEach
                val selected = candidate.id == selectedId
                aMap.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .width(if (selected) 18f else 8f)
                        .color(if (selected) 0xFFE8452C.toInt() else 0xFF9AA1AC.toInt()),
                )
            }
            candidates.firstOrNull { it.id == selectedId }?.let { candidate ->
                if (candidate.path.size >= 2) {
                    val builder = LatLngBounds.Builder()
                    candidate.path.forEach { builder.include(toGcj(it)) }
                    aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96))
                }
            }
        },
    )
}

private fun toGcj(point: Point): LatLng {
    val (lat, lon) = Gcj.wgs84ToGcj(point.lat, point.lon)
    return LatLng(lat, lon)
}
