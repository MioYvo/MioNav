package io.mionav.watch

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import io.mionav.proto.NavFrame
import io.mionav.proto.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single activity. No drawer, no nav graph: a HUD that stays on and redraws only when data changes.
 */
class HudActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var link: WatchLink
    private lateinit var hud: HudView
    private lateinit var heading: HeadingProvider
    private var tiles: TileSceneBuilder? = null

    private var nav: NavFrame? = null
    private var scene: Scene? = null
    private var roads: Map<Int, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        heading = HeadingProvider(this)
        runCatching {
            val file = java.io.File(filesDir, "tiles.mbtiles")
            if (file.exists()) tiles = TileSceneBuilder(io.mionav.pack.TileStore(file.absolutePath))
        }
        link = WatchLink(this)
        hud = HudView(this)
        setContentView(hud)
        hud.setOnClickListener { hud.night = !hud.night; hud.invalidate() }

        scope.launch {
            combine(link.nav, link.scene, link.roads) { n, s, r -> Triple(n, s, r) }
                .collect { (n, s, r) ->
                    nav = n
                    roads = r
                    n?.let { heading.onSpeed(it.speedKmh) }
                    // Local tiles win: they work without the phone and cost no BLE bandwidth.
                    scene = n?.let { frame -> buildLocal(frame) } ?: s
                    hud.invalidate()
                }
        }
        link.start()
    }

    override fun onResume() {
        super.onResume()
        heading.start()
        link.start()
    }

    override fun onPause() {
        heading.stop()
        super.onPause()
    }

    private fun buildLocal(frame: NavFrame): Scene? {
        val builder = tiles ?: return null
        val lat = frame.latE7 / 1e7
        val lon = frame.lonE7 / 1e7
        if (lat == 0.0 && lon == 0.0) return null
        return if (builder.shouldRebuild(lat, lon)) builder.build(lat, lon) else scene
    }

    override fun onDestroy() {
        link.close()
        scope.cancel()
        super.onDestroy()
    }

    private inner class HudView(context: Context) : View(context) {
        private val renderer = HudRenderer()
        var night = false

        override fun onDraw(canvas: Canvas) {
            renderer.palette = if (night) Palette.NIGHT else Palette.DAY
            val frame = nav ?: return drawWaiting(canvas)
            val road = roads[frame.roadId].orEmpty()
            val next = roads[frame.nextRoadId].orEmpty()
            // A moving bike gives a usable compass; standing still the phone's course is steadier.
            val bearing = if (heading.moving) heading.headingDeg else frame.bearingDeg100 / 100f
            renderer.draw(canvas, width, height, frame, road, next, bearingDeg = bearing, scene = scene)
        }

        private fun drawWaiting(canvas: Canvas) {
            canvas.drawColor(if (night) Color.BLACK else Color.WHITE)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (night) Color.WHITE else Color.BLACK
                textSize = width * 0.08f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(
                if (link.connected.value) "等待路线" else "连接手机中",
                width / 2f, height / 2f, paint,
            )
        }
    }
}
