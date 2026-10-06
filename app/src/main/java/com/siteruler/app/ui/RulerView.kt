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

    enum class Edge { TOP, BOTTOM }

    var edge = Edge.TOP
        set(value) { field = value; invalidate() }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        markerA = markerA.coerceIn(0f, w.toFloat())
        markerB = if (markerB < 0) w * 0.6f else markerB.coerceIn(0f, w.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val top = edge == Edge.TOP
        val base = if (top) 0f else height.toFloat()
        val dir = if (top) 1f else -1f
        val bandTop = if (top) 0f else height - rulerHeight
        canvas.drawRect(0f, bandTop, width.toFloat(), bandTop + rulerHeight, bandPaint)

        // Ticks: 1/16" or 1 mm.
        val stepMm = if (units == Units.IMPERIAL) 25.4f / 16 else 1f
        var i = 0
        while (true) {
            val x = i * stepMm * pxPerMm
            if (x > width) break
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
            val len = rulerHeight * frac
            canvas.drawLine(x, base, x, base + dir * len, tickPaint)
            if (label != null && i > 0) {
                val y = base + dir * (len + numberPaint.textSize) + if (top) 0f else numberPaint.textSize * 0.7f
                canvas.drawText(label, x, y, numberPaint)
            }
            i++
        }
        val unitLabel = if (units == Units.IMPERIAL) "in" else "cm"
        canvas.drawText(unitLabel, width - 20 * density, base + dir * rulerHeight * 0.8f + if (top) 0f else numberPaint.textSize, numberPaint)

        // Span between the markers, and the markers themselves.
        val lo = minOf(markerA, markerB); val hi = maxOf(markerA, markerB)
        canvas.drawRect(lo, bandTop, hi, bandTop + rulerHeight, spanPaint)
        val reach = rulerHeight * 1.6f
        for (m in listOf(markerA, markerB)) canvas.drawLine(m, base, m, base + dir * reach, markerPaint)

        val cy = height / 2f
        canvas.drawText(reading(), width / 2f, cy, readingPaint)
        canvas.drawText("Drag the red lines to the ends of the object", width / 2f, cy + 30 * density, hintPaint)
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
        val x = e.x.coerceIn(0f, width.toFloat())
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
