package com.siteruler.app.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where a point's position came from. AR today; a Bluetooth RTK receiver can add GNSS later. */
enum class PointSource { AR, GNSS }

/** Known grid coordinates for a control point, in meters. */
data class Known(val n: Double, val e: Double, val z: Double?)

/**
 * A shot point. [x], [y], [z] are the position in its setup's AR frame (meters, Y up), as
 * ARCore reports it. Every AR session is its own setup with its own frame; control points
 * tie a setup to real coordinates.
 */
data class SurveyPoint(
    val number: Int,
    val desc: String,
    val setup: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val known: Known? = null,
    val source: PointSource = PointSource.AR,
)

/** A point in grid coordinates (meters), ready for export. */
data class GridPoint(val number: Int, val desc: String, val n: Double, val e: Double, val z: Double, val setup: Int)

/** How a setup's AR frame was tied to the grid. */
data class SetupFit(
    val setup: Int,
    val controls: Int,
    /** Horizontal residual per control point number, meters. Empty with fewer than 2 controls. */
    val residuals: Map<Int, Double>,
    val rotationDeg: Double,
) {
    val assumed: Boolean get() = controls == 0
    val maxResidual: Double get() = residuals.values.maxOrNull() ?: 0.0
}

/**
 * Turns setup-local AR shots into grid coordinates.
 *
 * - 2+ control points: best-fit rotation and shift (no scale, so the residuals show the
 *   AR error honestly). Elevation shifts by the mean difference.
 * - 1 control point: shift only; grid north is the AR session's arbitrary heading.
 * - none: assumed coordinates, with the setup's first point at N 5000, E 5000, Z 100 ft
 *   (or m, depending on units).
 */
object Coords {
    const val ASSUMED_N = 5000.0
    const val ASSUMED_E = 5000.0
    const val ASSUMED_Z = 100.0

    private const val M_PER_FT = 0.3048

    fun compute(points: List<SurveyPoint>, units: Units): Pair<List<GridPoint>, List<SetupFit>> {
        val unit = if (units == Units.IMPERIAL) M_PER_FT else 1.0
        val out = mutableListOf<GridPoint>()
        val fits = mutableListOf<SetupFit>()
        for ((setup, pts) in points.groupBy { it.setup }.toSortedMap()) {
            // Local plan: east = x, north = -z (looking down, ARCore's -Z is "ahead").
            fun le(p: SurveyPoint) = p.x
            fun ln(p: SurveyPoint) = -p.z
            val ctl = pts.filter { it.known != null }
            var cosT = 1.0; var sinT = 0.0
            val tE: Double; val tN: Double; val tZ: Double
            val residuals = mutableMapOf<Int, Double>()
            when {
                ctl.size >= 2 -> {
                    val lcE = ctl.map(::le).average(); val lcN = ctl.map(::ln).average()
                    val gcE = ctl.map { it.known!!.e }.average(); val gcN = ctl.map { it.known!!.n }.average()
                    var dot = 0.0; var cross = 0.0
                    for (p in ctl) {
                        val ax = le(p) - lcE; val ay = ln(p) - lcN
                        val bx = p.known!!.e - gcE; val by = p.known.n - gcN
                        dot += ax * bx + ay * by
                        cross += ax * by - ay * bx
                    }
                    val t = atan2(cross, dot)
                    cosT = cos(t); sinT = sin(t)
                    tE = gcE - (cosT * lcE - sinT * lcN)
                    tN = gcN - (sinT * lcE + cosT * lcN)
                }
                ctl.size == 1 -> {
                    val c = ctl[0]
                    tE = c.known!!.e - le(c); tN = c.known.n - ln(c)
                }
                else -> {
                    val f = pts.minBy { it.number }
                    tE = ASSUMED_E * unit - le(f); tN = ASSUMED_N * unit - ln(f)
                }
            }
            val zCtl = ctl.filter { it.known!!.z != null }
            // Without elevation control, elevations are assumed from the setup's first point.
            tZ = if (zCtl.isNotEmpty()) zCtl.map { it.known!!.z!! - it.y }.average()
            else ASSUMED_Z * unit - pts.minBy { it.number }.y

            for (p in pts) {
                val e = cosT * le(p) - sinT * ln(p) + tE
                val n = sinT * le(p) + cosT * ln(p) + tN
                out.add(GridPoint(p.number, p.desc, n, e, p.y + tZ, setup))
                if (ctl.size >= 2 && p.known != null) residuals[p.number] = hypot(p.known.e - e, p.known.n - n)
            }
            fits.add(SetupFit(setup, ctl.size, residuals, Math.toDegrees(atan2(sinT, cosT))))
        }
        return out.sortedBy { it.number } to fits
    }
}
