package com.phoneruler.measure.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.phoneruler.measure.model.Job
import com.phoneruler.measure.model.Units
import com.phoneruler.measure.model.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Interactive floor plan. Drag a room to move it (it snaps corner to corner onto other rooms),
 * drag empty space to pan, pinch to zoom.
 */
class PlanView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var job = Job()
        set(value) { field = value; selected = -1; fitted = false; invalidate() }
    var units = Units.IMPERIAL
        set(value) { field = value; invalidate() }

    /** Called after a room is moved or rotated, so the job can be saved. */
    var onChanged: (() -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    var selected = -1
        private set

    private val density = resources.displayMetrics.density
    private val renderer = PlanRenderer(density)
    private var scale = 100f // pixels per meter
    private var tx = 0f
    private var ty = 0f
    private var fitted = false

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var moved = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val newScale = (scale * f).coerceIn(10f, 2000f)
            val k = newScale / scale
            tx = d.focusX - (d.focusX - tx) * k
            ty = d.focusY - (d.focusY - ty) * k
            scale = newScale
            invalidate()
            return true
        }
    })

    fun fit() {
        val b = renderer.bounds(job)
        if (b == null || width == 0) return
        val margin = 48 * density
        val w = (b.width()).coerceAtLeast(1f)
        val h = (b.height()).coerceAtLeast(1f)
        scale = min((width - 2 * margin) / w, (height - 2 * margin) / h)
        tx = width / 2f - (b.left + w / 2) * scale
        ty = height / 2f + (b.top + h / 2) * scale
        fitted = true
        invalidate()
    }

    fun rotateSelected(degrees: Double) {
        val room = job.rooms.getOrNull(selected) ?: return
        val before = centroid(room.placed)
        val turned = room.copy(rotationDeg = (room.rotationDeg + degrees) % 360)
        val after = centroid(turned.placed)
        job.rooms[selected] = turned.copy(
            offsetX = turned.offsetX + before.x - after.x,
            offsetY = turned.offsetY + before.y - after.y,
        )
        invalidate()
        onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted) fit()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        if (!fitted) fit()
        renderer.draw(canvas, job, units, scale, tx, ty, selected)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; moved = false
                val p = toPlan(e.x, e.y)
                selected = job.rooms.indexOfLast { it.contains(p) }
                dragging = selected >= 0
                onSelectionChanged?.invoke(selected)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || e.pointerCount > 1) {
                    lastX = e.x; lastY = e.y
                    return true
                }
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (abs(dx) + abs(dy) > 0) moved = true
                val room = job.rooms.getOrNull(selected)
                if (dragging && room != null) {
                    job.rooms[selected] = room.copy(offsetX = room.offsetX + dx / scale, offsetY = room.offsetY - dy / scale)
                } else {
                    tx += dx; ty += dy
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && moved) {
                    snap(selected)
                    onChanged?.invoke()
                }
                dragging = false
                invalidate()
            }
        }
        return true
    }

    /** Moves a room so its nearest corner lands exactly on another room's corner, if one is close. */
    private fun snap(index: Int) {
        val room = job.rooms.getOrNull(index) ?: return
        val threshold = 20 * density / scale
        var best: Pair<Double, Vec2>? = null
        val others = job.rooms.filterIndexed { i, _ -> i != index }.flatMap { it.placed }
        for (v in room.placed) for (w in others) {
            val d = hypot(w.x - v.x, w.y - v.y)
            if (d < threshold && (best == null || d < best.first)) best = d to Vec2(w.x - v.x, w.y - v.y)
        }
        best?.second?.let { shift ->
            job.rooms[index] = room.copy(offsetX = room.offsetX + shift.x, offsetY = room.offsetY + shift.y)
        }
    }

    private fun toPlan(x: Float, y: Float) = Vec2(((x - tx) / scale).toDouble(), (-(y - ty) / scale).toDouble())

    private fun centroid(pts: List<Vec2>) = Vec2(pts.map { it.x }.average(), pts.map { it.y }.average())
}
