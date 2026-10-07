package com.siteruler.app.model

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Pole calibration by pivoting: with the tip held on one spot and the pole tilted around,
 * every phone pose must put the tip at the same place. Solving for that gives the tip's
 * offset from the camera in the phone's own frame, whatever the mount.
 */
object Pivot {

    /** [tip] in the phone frame and [pivot] in the world, meters; [rms] is the fit, [tiltDeg] the tilt range used. */
    data class Result(val tip: DoubleArray, val pivot: DoubleArray, val rms: Double, val tiltDeg: Double) {
        val length: Double get() = sqrt(tip.sumOf { it * it })
    }

    /** Each sample is a column-major 4x4 pose matrix (rotation plus camera position), as Pose.toMatrix gives. */
    fun solve(samples: List<FloatArray>): Result? {
        if (samples.size < 10) return null
        // Unknowns x = (t, c): R t - c = -p for every sample. Normal equations, 6 x 6.
        val ata = Array(6) { DoubleArray(6) }
        val atb = DoubleArray(6)
        for (m in samples) for (i in 0..2) {
            val row = DoubleArray(6)
            for (j in 0..2) row[j] = m[j * 4 + i].toDouble()
            row[3 + i] = -1.0
            val b = -m[12 + i].toDouble()
            for (r in 0..5) {
                atb[r] += row[r] * b
                for (c in 0..5) ata[r][c] += row[r] * row[c]
            }
        }
        val x = gauss(ata, atb) ?: return null
        val t = x.copyOfRange(0, 3); val c = x.copyOfRange(3, 6)
        var sq = 0.0
        for (m in samples) for (i in 0..2) {
            var w = m[12 + i].toDouble()
            for (j in 0..2) w += m[j * 4 + i] * t[j]
            sq += (w - c[i]) * (w - c[i])
        }
        // Tilt range: the widest angle between the pole's direction in any two samples.
        val len = sqrt(t.sumOf { it * it }).takeIf { it > 1e-6 } ?: return null
        val dirs = samples.map { m -> DoubleArray(3) { i -> (0..2).sumOf { j -> m[j * 4 + i] * t[j] } / len } }
        var maxAngle = 0.0
        for (a in dirs.indices step 3) for (b in a + 1 until dirs.size step 3) {
            val dot = (0..2).sumOf { dirs[a][it] * dirs[b][it] }.coerceIn(-1.0, 1.0)
            maxAngle = maxOf(maxAngle, Math.toDegrees(acos(dot)))
        }
        return Result(t, c, sqrt(sq / samples.size), maxAngle)
    }

    private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            val p = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[p][col]) < 1e-9) return null
            m[col] = m[p].also { m[p] = m[col] }
            v[col] = v[p].also { v[p] = v[col] }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                for (c in col until n) m[r][c] -= f * m[col][c]
                v[r] -= f * v[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (v[r] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
        return x
    }
}
