package io.mionav.watch

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.mionav.hud.HudText
import io.mionav.hud.ManeuverKind
import io.mionav.proto.NavFlags
import io.mionav.proto.NavFrame
import io.mionav.proto.RoadClass
import io.mionav.proto.Scene
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure Canvas. No map SDK, no GLES: the watch only strokes polylines and draws text.
 *
 * Layout: turn card on top (28% of width), map window in the middle, status strip at the bottom.
 */
class HudRenderer(var palette: Palette = Palette.DAY) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val path = Path()

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        nav: NavFrame,
        road: String,
        nextRoad: String,
        bearingDeg: Float,
        scene: Scene?,
    ) {
        canvas.drawColor(palette.background)

        val cardH = (width * 0.28f).toInt()
        val statusH = (width * 0.16f).toInt()
        val mapRect = RectF(0f, cardH.toFloat(), width.toFloat(), (height - statusH).toFloat())

        scene?.let { drawScene(canvas, mapRect, it, bearingDeg) }
        drawTurnCard(canvas, width, cardH, nav, road, nextRoad)
        drawStatus(canvas, width, height, statusH, nav)
    }

    private fun drawTurnCard(canvas: Canvas, width: Int, height: Int, nav: NavFrame, road: String, nextRoad: String) {
        fill.color = palette.card
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val iconSize = height * 0.42f
        drawArrow(canvas, width * 0.18f, height / 2f, iconSize, io.mionav.hud.IconMapper.map(nav.iconType))

        textPaint.color = palette.text
        textPaint.textSize = height * 0.42f
        textPaint.isFakeBoldText = true
        canvas.drawText(HudText.distance(nav.stepDistM), width * 0.34f, height * 0.52f, textPaint)

        textPaint.isFakeBoldText = false
        textPaint.textSize = height * 0.22f
        textPaint.color = palette.textDim
        val label = road.ifBlank { nextRoad.ifBlank { "" } }
        if (label.isNotEmpty()) {
            canvas.drawText(label, width * 0.34f, height * 0.84f, textPaint)
        }
    }

    private fun drawStatus(canvas: Canvas, width: Int, height: Int, statusH: Int, nav: NavFrame) {
        val top = height - statusH
        fill.color = palette.card
        canvas.drawRect(0f, top.toFloat(), width.toFloat(), height.toFloat(), fill)

        val midY = top + statusH * 0.62f
        textPaint.color = palette.text
        textPaint.textSize = statusH * 0.44f
        textPaint.isFakeBoldText = false
        canvas.drawText(
            HudText.remaining(nav.remainDist10m * 10, nav.remainTimeMin),
            width * 0.04f, midY, textPaint,
        )

        // Speed limit roundel on the right.
        val limit = HudText.limit(nav.limitKmh)
        if (limit.isNotEmpty()) {
            val r = statusH * 0.30f
            val cx = width - r - statusH * 0.12f
            val cy = top + statusH / 2f
            stroke.color = palette.accent
            stroke.strokeWidth = r * 0.18f
            canvas.drawCircle(cx, cy, r, stroke)
            textPaint.color = palette.text
            textPaint.textSize = r * 1.05f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isFakeBoldText = true
            canvas.drawText(limit, cx, cy + r * 0.38f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        // Connection / GPS warning replaces the service area slot when something is wrong.
        val warn = when {
            nav.flags and NavFlags.GPS_WEAK != 0 -> "GPS 弱"
            nav.flags and NavFlags.RECALCULATING != 0 -> "重算中"
            nav.flags and NavFlags.ARRIVED != 0 -> "已到达"
            else -> HudText.serviceArea(nav.svcDist100m * 100, nav.flags and NavFlags.HAS_SERVICE_AREA != 0)
        }
        if (!warn.isNullOrEmpty()) {
            textPaint.color = palette.warn
            textPaint.textSize = statusH * 0.38f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(warn, width * 0.62f, midY, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawScene(canvas: Canvas, rect: RectF, scene: Scene, bearingDeg: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        // Metres per pixel: the scene radius fills half of the shorter side.
        val scale = (min(rect.width(), rect.height()) / 2f) / scene.radiusM.coerceAtLeast(1)

        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(cx, cy)
        canvas.rotate(-bearingDeg)

        val order = listOf(RoadClass.WATER, RoadClass.WATERWAY, RoadClass.MINOR, RoadClass.SECONDARY, RoadClass.MAJOR, RoadClass.ROUTE)
        for (cls in order) {
            for (line in scene.lines) {
                if (line.cls != cls) continue
                path.reset()
                for (i in 0 until line.points) {
                    val x = line.xs[i] * scale
                    val y = -line.ys[i] * scale
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                when (cls) {
                    RoadClass.WATER -> {
                        fill.color = palette.water
                        canvas.drawPath(path, fill)
                    }
                    RoadClass.ROUTE -> {
                        stroke.color = palette.routeCasing
                        stroke.strokeWidth = 10f
                        canvas.drawPath(path, stroke)
                        stroke.color = palette.route
                        stroke.strokeWidth = 6f
                        canvas.drawPath(path, stroke)
                    }
                    else -> {
                        val major = cls == RoadClass.MAJOR || cls == RoadClass.SECONDARY
                        stroke.color = if (major) palette.roadMajorCasing else palette.roadMinorCasing
                        stroke.strokeWidth = if (major) 7f else 5f
                        canvas.drawPath(path, stroke)
                        stroke.color = if (major) palette.roadMajor else palette.roadMinor
                        stroke.strokeWidth = if (major) 4.5f else 3f
                        canvas.drawPath(path, stroke)
                    }
                }
            }
        }

        // Own position: a fixed chevron at the centre, since the map is heading-up.
        path.reset()
        path.moveTo(0f, -14f)
        path.lineTo(10f, 12f)
        path.lineTo(0f, 6f)
        path.lineTo(-10f, 12f)
        path.close()
        fill.color = palette.route
        canvas.drawPath(path, fill)

        canvas.restore()
    }

    /** Simple stroked glyphs; no icon font, no bitmaps. */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, kind: ManeuverKind) {
        val h = size / 2f
        stroke.color = palette.text
        stroke.strokeWidth = size * 0.14f
        path.reset()
        when (kind) {
            ManeuverKind.LEFT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SHARP_LEFT, ManeuverKind.FORK_LEFT -> {
                path.moveTo(cx, cy + h)
                path.lineTo(cx, cy)
                path.lineTo(cx - h * 0.9f, cy)
            }
            ManeuverKind.RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT, ManeuverKind.FORK_RIGHT -> {
                path.moveTo(cx, cy + h)
                path.lineTo(cx, cy)
                path.lineTo(cx + h * 0.9f, cy)
            }
            ManeuverKind.ROUNDABOUT -> {
                canvas.drawCircle(cx, cy, h * 0.8f, stroke)
                path.moveTo(cx, cy + h)
                path.lineTo(cx, cy - h * 0.2f)
            }
            else -> {
                path.moveTo(cx, cy + h)
                path.lineTo(cx, cy - h * 0.9f)
            }
        }
        canvas.drawPath(path, stroke)

        // Arrow head, pointing where the stroke ended.
        val (hx, hy) = when (kind) {
            ManeuverKind.LEFT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SHARP_LEFT, ManeuverKind.FORK_LEFT -> cx - h * 0.9f to cy
            ManeuverKind.RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT, ManeuverKind.FORK_RIGHT -> cx + h * 0.9f to cy
            else -> cx to cy - h * 0.9f
        }
        val angle = when (kind) {
            ManeuverKind.LEFT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SHARP_LEFT, ManeuverKind.FORK_LEFT -> Math.PI
            ManeuverKind.RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT, ManeuverKind.FORK_RIGHT -> 0.0
            else -> -Math.PI / 2
        }
        val head = size * 0.22f
        path.reset()
        path.moveTo(hx, hy)
        path.lineTo(
            hx - (head * cos(angle - 0.5)).toFloat(),
            hy - (head * sin(angle - 0.5)).toFloat(),
        )
        path.lineTo(
            hx - (head * cos(angle + 0.5)).toFloat(),
            hy - (head * sin(angle + 0.5)).toFloat(),
        )
        path.close()
        fill.color = palette.text
        canvas.drawPath(path, fill)
    }
}
