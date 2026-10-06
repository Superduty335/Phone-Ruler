package com.siteruler.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.siteruler.app.model.Format
import com.siteruler.app.model.Job
import com.siteruler.app.model.Units
import com.siteruler.app.model.Vec2
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draws the floor plan sketch onto any Canvas: the on-screen plan and the PDF export.
 * Plan coordinates are meters with Y up; screen = (x * scale + tx, -y * scale + ty).
 */
class PlanRenderer(private val density: Float) {

    private val roomFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 240) }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 193, 7) }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 30, 30)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.MITER
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 90, 160)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 30, 30)
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 90, 90)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    fun draw(canvas: Canvas, job: Job, units: Units, scale: Float, tx: Float, ty: Float, selected: Int = -1) {
        fun sx(p: Vec2) = (p.x * scale + tx).toFloat()
        fun sy(p: Vec2) = (-p.y * scale + ty).toFloat()

        job.rooms.forEachIndexed { index, room ->
            val pts = room.placed
            if (pts.size < 3) return@forEachIndexed
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
            path.close()
            canvas.drawPath(path, roomFill)
            if (index == selected) canvas.drawPath(path, selectedFill)
            canvas.drawPath(path, wallPaint)

            // Winding decides which side of each wall is outside, so dimensions sit outside the room.
            var signed = 0.0
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                signed += a.x * b.y - b.x * a.y
            }
            val outside = if (signed > 0) 1f else -1f
            room.walls.forEachIndexed { i, meters ->
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                val ax = sx(a); val ay = sy(a); val bx = sx(b); val by = sy(b)
                val len = hypot(bx - ax, by - ay)
                if (len < 1f) return@forEachIndexed
                // Screen Y is flipped, so the outward normal flips sign too.
                val nx = -(by - ay) / len * outside
                val ny = (bx - ax) / len * outside
                val off = dimPaint.textSize * 0.9f
                val mx = (ax + bx) / 2 + nx * off
                val my = (ay + by) / 2 + ny * off
                var angle = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
                if (angle > 90) angle -= 180
                if (angle <= -90) angle += 180
                canvas.save()
                canvas.rotate(angle, mx, my)
                canvas.drawText(Format.length(meters, units), mx, my + dimPaint.textSize / 3, dimPaint)
                canvas.restore()
            }

            val cx = pts.map { sx(it) }.average().toFloat()
            val cy = pts.map { sy(it) }.average().toFloat()
            canvas.drawText(room.name, cx, cy, namePaint)
            canvas.drawText(Format.area(room.area, units), cx, cy + infoPaint.textSize * 1.4f, infoPaint)
            room.heightMeters?.let {
                canvas.drawText("CLG " + Format.length(it, units), cx, cy + infoPaint.textSize * 2.7f, infoPaint)
            }
        }
    }

    /** Plan extents in meters, or null when there are no rooms. */
    fun bounds(job: Job): RectF? {
        val pts = job.rooms.flatMap { it.placed }
        if (pts.isEmpty()) return null
        return RectF(
            pts.minOf { it.x }.toFloat(), pts.minOf { it.y }.toFloat(),
            pts.maxOf { it.x }.toFloat(), pts.maxOf { it.y }.toFloat(),
        )
    }
}
