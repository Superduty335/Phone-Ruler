package com.siteruler.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.siteruler.app.model.Tin
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The surface, shaded red where the ground is above the reference (cut) and blue where it's
 * below (fill). Starts as a plan, north up. Drag sideways or twist two fingers to rotate,
 * drag up or down to tilt into 3D, pinch to zoom, double-tap to go back to the plan.
 */
class TinView(context: Context) : View(context) {

    private var tin: Tin? = null
    /** Ground minus reference at each vertex, meters; NaN where there is no reference. */
    private var heights: DoubleArray = DoubleArray(0)
    var metersPerUnit = 0.3048
    var unitLabel = "ft"

    /** Rotation of the view about vertical, degrees; 0 = north up. */
    private var azimuth = 0.0
    /** Viewing angle above the horizon, degrees; 90 = straight down (plan). */
    private var tilt = 90.0
    private var zoom = 1.0

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(120, 60, 60, 60); strokeWidth = 1f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 12 * density }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 2 * density }

    fun show(tin: Tin, heights: DoubleArray) {
        this.tin = tin
        this.heights = heights
        invalidate()
    }

    fun resetView() {
        azimuth = 0.0; tilt = 90.0; zoom = 1.0
        invalidate()
    }

    // ---- Touch ----

    private var lastX = 0f
    private var lastY = 0f
    private var lastTwist: Double? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.3, 20.0)
            invalidate()
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { resetView(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; lastTwist = null }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> lastTwist = null
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val a = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble()))
                    lastTwist?.let { prev ->
                        var d = a - prev
                        if (d > 180) d -= 360
                        if (d < -180) d += 360
                        azimuth += d
                    }
                    lastTwist = a
                } else if (!scaleDetector.isInProgress) {
                    azimuth += (e.x - lastX) / density * 0.5
                    tilt = (tilt + (e.y - lastY) / density * 0.4).coerceIn(10.0, 90.0)
                }
                lastX = e.x; lastY = e.y
                invalidate()
            }
        }
        return true
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val tin = tin
        if (tin == null || tin.tris.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("Shoot at least 3 points in Topo mode", width / 2f, height / 2f, text)
            return
        }
        val cx = (tin.pts.minOf { it.x } + tin.pts.maxOf { it.x }) / 2
        val cy = (tin.pts.minOf { it.y } + tin.pts.maxOf { it.y }) / 2
        val cz = tin.pts.sumOf { it.z } / tin.pts.size
        val radius = tin.pts.maxOf { hypot(it.x - cx, it.y - cy) }.coerceAtLeast(0.05)
        val relief = tin.pts.maxOf { it.z } - tin.pts.minOf { it.z }
        // Exaggerate flat ground so slopes show when tilted: relief at least a fifth of the width.
        val exaggeration = if (relief < 1e-6) 1.0 else max(1.0, (radius * 0.4 / relief).roundToInt().toDouble()).coerceAtMost(50.0)
        val s = min(width, height) * 0.44 / radius * zoom
        val ca = cos(Math.toRadians(azimuth)); val sa = sin(Math.toRadians(azimuth))
        val ct = cos(Math.toRadians(tilt)); val st = sin(Math.toRadians(tilt))
        val ox = width / 2.0; val oy = height / 2.0

        // Orthographic view: rotate about vertical, then tilt toward the viewer.
        val n = tin.pts.size
        val px = FloatArray(n); val py = FloatArray(n); val depth = DoubleArray(n)
        for (i in 0 until n) {
            val p = tin.pts[i]
            val dx = p.x - cx; val dy = p.y - cy; val dz = (p.z - cz) * exaggeration
            val rx = dx * ca - dy * sa
            val ry = dx * sa + dy * ca
            px[i] = (ox + rx * s).toFloat()
            py[i] = (oy - (ry * st + dz * ct) * s).toFloat()
            depth[i] = -ry * ct + dz * st
        }

        val maxAbs = heights.filter { !it.isNaN() }.maxOfOrNull { abs(it) }?.coerceAtLeast(0.01) ?: 1.0
        val order = tin.tris.sortedBy { t -> t.sumOf { depth[it] } } // far triangles first
        for (t in order) {
            val h = t.map { heights.getOrElse(it) { Double.NaN } }
            val mean = h.average()
            var r: Int; var g: Int; var b: Int; var a: Int
            when {
                mean.isNaN() -> { r = 225; g = 225; b = 225; a = 255 }
                abs(mean) < 0.005 -> { r = 235; g = 235; b = 225; a = 255 }
                else -> {
                    val k = (abs(mean) / maxAbs).coerceIn(0.15, 1.0)
                    // Blend toward white instead of using transparency, so 3D faces stay solid.
                    val (cr, cg, cb) = if (mean > 0) Triple(211, 47, 47) else Triple(30, 136, 229)
                    val w = 1 - (0.25 + 0.75 * k)
                    r = (cr + (255 - cr) * w).toInt(); g = (cg + (255 - cg) * w).toInt(); b = (cb + (255 - cb) * w).toInt(); a = 255
                }
            }
            if (tilt < 89.5) {
                // Light from the upper left of the screen so slopes read in 3D.
                val p0 = tin.pts[t[0]]; val p1 = tin.pts[t[1]]; val p2 = tin.pts[t[2]]
                val ux = p1.x - p0.x; val uy = p1.y - p0.y; val uz = (p1.z - p0.z) * exaggeration
                val vx = p2.x - p0.x; val vy = p2.y - p0.y; val vz = (p2.z - p0.z) * exaggeration
                var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
                val len = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-12)
                val lx = -0.5 * ca - 0.5 * sa; val ly = 0.5 * sa - 0.5 * ca // light direction turns with the view
                val lambert = ((nx * lx + ny * ly + nz * 0.7) / len / 0.86).coerceIn(0.0, 1.0)
                val shade = 0.6 + 0.4 * lambert
                r = (r * shade).toInt(); g = (g * shade).toInt(); b = (b * shade).toInt()
            }
            fill.color = Color.argb(a, r, g, b)
            val path = Path().apply {
                moveTo(px[t[0]], py[t[0]]); lineTo(px[t[1]], py[t[1]]); lineTo(px[t[2]], py[t[2]]); close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
        }
        for (i in 0 until n) canvas.drawCircle(px[i], py[i], 2.5f * density, dot)

        // North arrow, turned with the view.
        val ax = width - 28 * density; val ay = 30 * density; val al = 14 * density
        val nxs = (-sa).toFloat(); val nys = (-ca * st).toFloat()
        canvas.drawLine(ax - nxs * al, ay - nys * al, ax + nxs * al, ay + nys * al, bar)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("N", ax + nxs * (al + 10 * density), ay + nys * (al + 10 * density) + 4 * density, text)

        // Scale bar (true for distances across the screen at any rotation).
        val unitsAcross = (width * 0.3) / s / metersPerUnit
        val nice = 10.0.pow(floor(log10(unitsAcross))).let { base -> listOf(5 * base, 2 * base, base).first { it <= unitsAcross } }
        val len = (nice * metersPerUnit * s).toFloat()
        val y = height - 14 * density
        canvas.drawLine(16 * density, y, 16 * density + len, y, bar)
        text.textAlign = Paint.Align.LEFT
        val label = if (nice >= 1) "%.0f %s".format(nice, unitLabel) else "%.1f %s".format(nice, unitLabel)
        canvas.drawText(label, 16 * density, y - 6 * density, text)

        text.textAlign = Paint.Align.LEFT
        val hint = if (tilt < 89.5 && exaggeration > 1) "Height ×${exaggeration.toInt()} · double-tap for plan"
        else if (tilt < 89.5 || abs(azimuth % 360) > 0.5) "Double-tap for plan, north up"
        else "Drag to rotate and tilt, pinch to zoom"
        canvas.drawText(hint, 12 * density, 20 * density, text)
    }
}
