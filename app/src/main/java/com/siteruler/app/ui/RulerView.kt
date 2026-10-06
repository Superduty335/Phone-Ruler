package com.siteruler.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.siteruler.app.model.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A true-scale ruler drawn along the top or bottom edge of the screen. Two markers can be
 * dragged to the ends of an object; the distance between them is the reading.
 */
class RulerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Edge {
        TOP, RIGHT, BOTTOM, LEFT;

        /** Left and right run the ruler down the long side of the phone. */
        val vertical: Boolean get() = this == LEFT || this == RIGHT
    }

    var edge = Edge.TOP
        set(value) { field = value; if (width > 0) resetMarkers() }
    var units = Units.IMPERIAL
        set(value) { field = value; invalidate() }
    /** Physical scale. Starts from the screen's reported density; calibration replaces it. */
    var pxPerMm = 1f
        set(value) { field = value; invalidate() }

    /** Marker positions in pixels from the left edge. */
    private var markerA = 0f
    private var markerB = -1f
    private var dragging = 0

    private val density = resources.displayMetrics.density
    private val rulerHeight = 110 * density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 1.2f * density }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 13 * density; textAlign = Paint.Align.CENTER
    }
    private val bandPaint = Paint().apply { color = Color.rgb(255, 213, 79) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 47, 47); strokeWidth = 2 * density }
    private val spanPaint = Paint().apply { color = Color.argb(60, 211, 47, 47) }
    private val readingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 44 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 14 * density; textAlign = Paint.Align.CENTER
    }

    val readingMm: Float get() = abs(markerB - markerA) / pxPerMm

    fun setMarkersMm(aMm: Float, bMm: Float) {
        markerA = aMm * pxPerMm
        markerB = bMm * pxPerMm
        invalidate()
    }

    private val length: Float get() = (if (edge.vertical) height else width).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMarkers()
    }

    fun resetMarkers() {
        markerA = 0f
        markerB = length * 0.6f
        invalidate()
    }

    /** Ruler coordinates (u along the ruler from zero, v in from the edge) to screen. */
    private fun sx(u: Float, v: Float) = when (edge) {
        Edge.TOP, Edge.BOTTOM -> u
        Edge.LEFT -> v
        Edge.RIGHT -> width - v
    }

    private fun sy(u: Float, v: Float) = when (edge) {
        Edge.TOP -> v
        Edge.BOTTOM -> height - v
        Edge.LEFT, Edge.RIGHT -> u
    }

    private fun line(canvas: Canvas, u0: Float, v0: Float, u1: Float, v1: Float, paint: Paint) =
        canvas.drawLine(sx(u0, v0), sy(u0, v0), sx(u1, v1), sy(u1, v1), paint)

    private fun band(canvas: Canvas, u0: Float, u1: Float, paint: Paint) {
        val x0 = sx(u0, 0f); val y0 = sy(u0, 0f); val x1 = sx(u1, rulerHeight); val y1 = sy(u1, rulerHeight)
        canvas.drawRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), paint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val len = length
        band(canvas, 0f, len, bandPaint)

        // Ticks: 1/16" or 1 mm.
        val stepMm = if (units == Units.IMPERIAL) 25.4f / 16 else 1f
        var i = 0
        while (true) {
            val u = i * stepMm * pxPerMm
            if (u > len) break
            val (frac, label) = if (units == Units.IMPERIAL) {
                when {
                    i % 16 == 0 -> 0.55f to (i / 16).toString()
                    i % 8 == 0 -> 0.40f to null
                    i % 4 == 0 -> 0.30f to null
                    i % 2 == 0 -> 0.20f to null
                    else -> 0.12f to null
                }
            } else {
                when {
                    i % 10 == 0 -> 0.55f to (i / 10).toString()
                    i % 5 == 0 -> 0.35f to null
                    else -> 0.18f to null
                }
            }
            val tick = rulerHeight * frac
            line(canvas, u, 0f, u, tick, tickPaint)
            if (label != null && i > 0) {
                val v = tick + numberPaint.textSize
                canvas.drawText(label, sx(u, v), sy(u, v) + numberPaint.textSize / 3, numberPaint)
            }
            i++
        }
        val unitLabel = if (units == Units.IMPERIAL) "in" else "cm"
        val lu = len - 20 * density; val lv = rulerHeight * 0.8f
        canvas.drawText(unitLabel, sx(lu, lv), sy(lu, lv) + numberPaint.textSize / 3, numberPaint)

        // Span between the markers, and the markers themselves.
        band(canvas, minOf(markerA, markerB), maxOf(markerA, markerB), spanPaint)
        for (m in listOf(markerA, markerB)) line(canvas, m, 0f, m, rulerHeight * 1.5f, markerPaint)

        // Reading, kept clear of a side ruler.
        val side = if (edge.vertical) rulerHeight * 1.6f else 0f
        val cx = when (edge) {
            Edge.LEFT -> side + (width - side) / 2f
            Edge.RIGHT -> (width - side) / 2f
            else -> width / 2f
        }
        val cy = if (edge.vertical) height * 0.4f else height / 2f
        canvas.drawText(reading(), cx, cy, readingPaint)
        canvas.drawText("Drag the red lines to the", cx, cy + 30 * density, hintPaint)
        canvas.drawText("ends of the object", cx, cy + 50 * density, hintPaint)
    }

    private fun reading(): String {
        val mm = readingMm
        return if (units == Units.IMPERIAL) {
            val sixteenths = (mm / 25.4f * 16).roundToInt()
            val whole = sixteenths / 16
            var n = sixteenths % 16
            var d = 16
            while (n != 0 && n % 2 == 0) { n /= 2; d /= 2 }
            val frac = if (n == 0) "" else " $n/$d"
            "$whole$frac\""
        } else {
            "%.1f mm".format(mm)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (if (edge.vertical) e.y else e.x).coerceIn(0f, length)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (abs(x - markerA) <= abs(x - markerB)) 1 else 2
                move(x)
            }
            MotionEvent.ACTION_MOVE -> move(x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
        }
        return true
    }

    private fun move(x: Float) {
        if (dragging == 1) markerA = x else if (dragging == 2) markerB = x
        invalidate()
    }
}
