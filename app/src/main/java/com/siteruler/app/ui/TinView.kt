package com.siteruler.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.siteruler.app.model.Tin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Plan view of a surface: each triangle is shaded red where the ground is above the
 * reference (cut) and blue where it's below (fill), deeper colors for bigger differences.
 */
class TinView(context: Context) : View(context) {

    private var tin: Tin? = null
    /** Ground minus reference at each vertex, meters; NaN where there is no reference. */
    private var heights: DoubleArray = DoubleArray(0)
    var metersPerUnit = 0.3048
    var unitLabel = "ft"

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(120, 60, 60, 60); strokeWidth = 1f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 12 * density }
    private val bar = Paint().apply { color = Color.BLACK; strokeWidth = 2 * density }

    fun show(tin: Tin, heights: DoubleArray) {
        this.tin = tin
        this.heights = heights
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val tin = tin
        if (tin == null || tin.tris.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("Shoot at least 3 points in Topo mode", width / 2f, height / 2f, text)
            return
        }
        val pad = 28 * density
        val minX = tin.pts.minOf { it.x }; val maxX = tin.pts.maxOf { it.x }
        val minY = tin.pts.minOf { it.y }; val maxY = tin.pts.maxOf { it.y }
        val s = min((width - 2 * pad) / max(maxX - minX, 0.01), (height - 2 * pad) / max(maxY - minY, 0.01))
        val ox = (width - (maxX - minX) * s) / 2; val oy = (height + (maxY - minY) * s) / 2
        fun sx(x: Double) = (ox + (x - minX) * s).toFloat()
        fun sy(y: Double) = (oy - (y - minY) * s).toFloat()

        val maxAbs = heights.filter { !it.isNaN() }.maxOfOrNull { abs(it) }?.coerceAtLeast(0.01) ?: 1.0
        for (t in tin.tris) {
            val h = t.map { heights.getOrElse(it) { Double.NaN } }
            val mean = h.average()
            fill.color = when {
                mean.isNaN() -> Color.rgb(230, 230, 230)
                abs(mean) < 0.005 -> Color.rgb(235, 235, 225)
                else -> {
                    val k = (abs(mean) / maxAbs).coerceIn(0.15, 1.0)
                    val a = (60 + 170 * k).toInt()
                    if (mean > 0) Color.argb(a, 211, 47, 47) else Color.argb(a, 30, 136, 229)
                }
            }
            val path = Path().apply {
                val a = tin.pts[t[0]]; val b = tin.pts[t[1]]; val c = tin.pts[t[2]]
                moveTo(sx(a.x), sy(a.y)); lineTo(sx(b.x), sy(b.y)); lineTo(sx(c.x), sy(c.y)); close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
        }
        for (p in tin.pts) canvas.drawCircle(sx(p.x), sy(p.y), 2.5f * density, dot)

        // North arrow and a round-number scale bar.
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("N ↑", width - 24 * density, 22 * density, text)
        val unitsAcross = (width * 0.3) / s / metersPerUnit
        val nice = 10.0.pow(floor(log10(unitsAcross))).let { b ->
            listOf(5 * b, 2 * b, b).first { it <= unitsAcross }
        }
        val len = (nice * metersPerUnit * s).toFloat()
        val y = height - 14 * density
        canvas.drawLine(16 * density, y, 16 * density + len, y, bar)
        text.textAlign = Paint.Align.LEFT
        val label = if (nice >= 1) "%.0f %s".format(nice, unitLabel) else "%.1f %s".format(nice, unitLabel)
        canvas.drawText(label, 16 * density, y - 6 * density, text)
    }
}
