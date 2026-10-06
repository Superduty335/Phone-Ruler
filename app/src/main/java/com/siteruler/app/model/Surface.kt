package com.siteruler.app.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** A point on a surface: x = east, y = north, z = elevation (meters). */
data class P3(val x: Double, val y: Double, val z: Double)

/**
 * A triangulated surface (TIN): a Delaunay triangulation of shot points, the way survey
 * and civil software models the ground between shots.
 */
class Tin(val pts: List<P3>, val tris: List<IntArray>) {

    val area: Double get() = tris.sumOf { triArea(it) }

    fun triArea(t: IntArray): Double {
        val a = pts[t[0]]; val b = pts[t[1]]; val c = pts[t[2]]
        return abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2
    }

    /** Vertices on the outside edge of the surface. */
    fun boundary(): List<Int> {
        val count = HashMap<Long, Int>()
        for (t in tris) for (k in 0..2) {
            val key = edgeKey(t[k], t[(k + 1) % 3])
            count[key] = (count[key] ?: 0) + 1
        }
        return count.filterValues { it == 1 }.keys
            .flatMap { listOf((it shr 32).toInt(), (it and 0xffffffffL).toInt()) }
            .distinct()
    }

    /** Surface elevation at (x, y), or null outside the surface. */
    fun zAt(x: Double, y: Double): Double? {
        for (t in tris) {
            val a = pts[t[0]]; val b = pts[t[1]]; val c = pts[t[2]]
            val d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
            if (abs(d) < 1e-12) continue
            val l1 = ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / d
            val l2 = ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / d
            val l3 = 1 - l1 - l2
            if (l1 >= -1e-9 && l2 >= -1e-9 && l3 >= -1e-9) return l1 * a.z + l2 * b.z + l3 * c.z
        }
        return null
    }

    companion object {
        private fun edgeKey(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
            return (lo shl 32) or hi
        }

        /** Bowyer-Watson Delaunay triangulation. Points closer than 5 mm in plan are merged. */
        fun build(input: List<P3>): Tin {
            val pts = mutableListOf<P3>()
            for (p in input) if (pts.none { hypot(it.x - p.x, it.y - p.y) < 0.005 }) pts.add(p)
            val n = pts.size
            if (n < 3) return Tin(pts, emptyList())

            // Work around the centroid so grid-sized coordinates keep full precision.
            val ox = pts.sumOf { it.x } / n; val oy = pts.sumOf { it.y } / n
            val xs = DoubleArray(n + 3); val ys = DoubleArray(n + 3)
            for (i in 0 until n) { xs[i] = pts[i].x - ox; ys[i] = pts[i].y - oy }
            val span = max(xs.take(n).maxOf { abs(it) }, ys.take(n).maxOf { abs(it) }).coerceAtLeast(1.0) * 100
            xs[n] = -span; ys[n] = -span
            xs[n + 1] = span; ys[n + 1] = -span
            xs[n + 2] = 0.0; ys[n + 2] = span

            fun inCircle(t: IntArray, px: Double, py: Double): Boolean {
                val ax = xs[t[0]] - px; val ay = ys[t[0]] - py
                val bx = xs[t[1]] - px; val by = ys[t[1]] - py
                val cx = xs[t[2]] - px; val cy = ys[t[2]] - py
                val det = (ax * ax + ay * ay) * (bx * cy - cx * by) -
                    (bx * bx + by * by) * (ax * cy - cx * ay) +
                    (cx * cx + cy * cy) * (ax * by - bx * ay)
                val orient = (xs[t[1]] - xs[t[0]]) * (ys[t[2]] - ys[t[0]]) -
                    (xs[t[2]] - xs[t[0]]) * (ys[t[1]] - ys[t[0]])
                return if (orient > 0) det > 0 else det < 0
            }

            var tris = mutableListOf(intArrayOf(n, n + 1, n + 2))
            for (i in 0 until n) {
                val bad = tris.filter { inCircle(it, xs[i], ys[i]) }
                val edges = HashMap<Long, IntArray>()
                val seen = HashMap<Long, Int>()
                for (t in bad) for (k in 0..2) {
                    val a = t[k]; val b = t[(k + 1) % 3]
                    val key = edgeKey(a, b)
                    seen[key] = (seen[key] ?: 0) + 1
                    edges[key] = intArrayOf(a, b)
                }
                tris.removeAll(bad.toSet())
                for ((key, e) in edges) if (seen[key] == 1) tris.add(intArrayOf(e[0], e[1], i))
            }
            tris = tris.filter { t -> t.all { it < n } }.toMutableList()
            val tin = Tin(pts, tris)
            return Tin(pts, tris.filter { tin.triArea(it) > 1e-6 })
        }
    }
}

/** Cut is surface above the reference, fill is surface below it. Cubic meters and square meters. */
data class VolumeResult(val cut: Double, val fill: Double, val area: Double) {
    val net: Double get() = cut - fill
}

object Volumes {
    const val CY_PER_M3 = 1.307950619
    const val CF_PER_M3 = 35.31466672

    /**
     * Volume between the surface and a flat or sloped reference plane z = [ref](x, y).
     * Exact for a TIN: each triangle is split where it crosses the reference.
     */
    fun toPlane(tin: Tin, ref: (Double, Double) -> Double): VolumeResult {
        var cut = 0.0; var fill = 0.0
        for (t in tin.tris) {
            val a = tin.triArea(t)
            val h = DoubleArray(3) { val p = tin.pts[t[it]]; p.z - ref(p.x, p.y) }
            val above = positivePart(a, h)
            val net = a * (h[0] + h[1] + h[2]) / 3
            cut += above
            fill += above - net
        }
        return VolumeResult(cut, fill, tin.area)
    }

    /**
     * Volume of [top] above [bottom] where both surfaces exist (for example existing ground
     * against finished grade). Sampled on a fine grid.
     */
    fun between(top: Tin, bottom: Tin, cells: Int = 160): VolumeResult {
        if (top.tris.isEmpty() || bottom.tris.isEmpty()) return VolumeResult(0.0, 0.0, 0.0)
        val minX = max(top.pts.minOf { it.x }, bottom.pts.minOf { it.x })
        val maxX = minOf(top.pts.maxOf { it.x }, bottom.pts.maxOf { it.x })
        val minY = max(top.pts.minOf { it.y }, bottom.pts.minOf { it.y })
        val maxY = minOf(top.pts.maxOf { it.y }, bottom.pts.maxOf { it.y })
        if (maxX <= minX || maxY <= minY) return VolumeResult(0.0, 0.0, 0.0)
        val step = max(maxX - minX, maxY - minY) / cells
        val nx = ceil((maxX - minX) / step).toInt(); val ny = ceil((maxY - minY) / step).toInt()
        val cellArea = step * step
        var cut = 0.0; var fill = 0.0; var area = 0.0
        for (i in 0 until nx) for (j in 0 until ny) {
            val x = minX + (i + 0.5) * step; val y = minY + (j + 0.5) * step
            val zt = top.zAt(x, y) ?: continue
            val zb = bottom.zAt(x, y) ?: continue
            val d = zt - zb
            if (d > 0) cut += d * cellArea else fill -= d * cellArea
            area += cellArea
        }
        return VolumeResult(cut, fill, area)
    }

    /** Least-squares plane through the surface's outside edge: the base of a stockpile or the rim of a pit. */
    fun basePlane(tin: Tin): (Double, Double) -> Double {
        val b = tin.boundary().map { tin.pts[it] }
        if (b.isEmpty()) return { _, _ -> 0.0 }
        val ox = b.sumOf { it.x } / b.size; val oy = b.sumOf { it.y } / b.size
        val mz = b.sumOf { it.z } / b.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxz = 0.0; var syz = 0.0
        for (p in b) {
            val x = p.x - ox; val y = p.y - oy; val z = p.z - mz
            sxx += x * x; sxy += x * y; syy += y * y; sxz += x * z; syz += y * z
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-9) return { _, _ -> mz }
        val gx = (sxz * syy - syz * sxy) / det
        val gy = (syz * sxx - sxz * sxy) / det
        return { x, y -> mz + gx * (x - ox) + gy * (y - oy) }
    }

    /** Volume of the part of a linear prism (plan area [a], corner heights [h]) that lies above zero. */
    fun positivePart(a: Double, h: DoubleArray): Double {
        val s = h.sortedDescending()
        val h1 = s[0]; val h2 = s[1]; val h3 = s[2]
        return when {
            h3 >= 0 -> a * (h1 + h2 + h3) / 3
            h1 <= 0 -> 0.0
            h2 <= 0 -> a * h1 * h1 * h1 / (3 * (h1 - h2) * (h1 - h3))
            else -> a * (h1 + h2 + h3) / 3 + a * (-h3) * h3 * h3 / (3 * (h1 - h3) * (h2 - h3))
        }
    }
}
