package com.siteruler.app

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.TrackingState
import com.siteruler.app.model.Format
import com.siteruler.app.model.LineKind
import com.siteruler.app.model.Units
import com.siteruler.app.model.Vec2
import com.siteruler.app.render.LineRenderer
import com.siteruler.app.ui.OverlayView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measurement state for the live AR session. Every method runs on the GL thread, because
 * anchors and hit results belong to the ARCore frame being rendered.
 */
class MeasureController(private val listener: Listener) {

    interface Listener {
        fun onLineMeasured(kind: LineKind, meters: Double)
        fun onRoomMeasured(corners: List<Vec2>, heightMeters: Double?)
    }

    enum class Mode { DISTANCE, HEIGHT, ROOM }

    data class Snapshot(
        val labels: List<OverlayView.Label>,
        val live: String?,
        val prompt: String,
        val finishLabel: String,
    )

    private class LiveLine(val a: Anchor, val b: Anchor, val kind: LineKind)
    private class LiveRoom(val corners: List<Anchor>, var ceilingY: Float? = null)

    var mode = Mode.DISTANCE
        private set

    private val active = mutableListOf<Anchor>()
    private val lines = mutableListOf<LiveLine>()
    private val rooms = mutableListOf<LiveRoom>()
    /** A closed room outline waiting for its ceiling height (or a skip). */
    private var pendingRoom: LiveRoom? = null

    fun setMode(m: Mode) {
        if (m == mode) return
        active.forEach { it.detach() }
        active.clear()
        pendingRoom?.let { finishRoom(it) }
        mode = m
    }

    fun addPoint(hit: HitResult) {
        pendingRoom?.let { room ->
            room.ceilingY = hit.hitPose.ty()
            finishRoom(room)
            return
        }
        active.add(hit.createAnchor())
        if (mode != Mode.ROOM && active.size == 2) {
            val kind = if (mode == Mode.HEIGHT) LineKind.HEIGHT else LineKind.DISTANCE
            val line = LiveLine(active[0], active[1], kind)
            lines.add(line)
            active.clear()
            listener.onLineMeasured(kind, measure(line.a.pos(), line.b.pos(), kind))
        }
    }

    /** "Close room" while outlining, "Skip height" while waiting for the ceiling. */
    fun closeOrSkip(): String? {
        pendingRoom?.let { finishRoom(it); return null }
        if (mode != Mode.ROOM) return "Switch to Room mode to outline a room"
        if (active.size < 3) return "A room needs at least 3 corners"
        pendingRoom = LiveRoom(active.toList())
        active.clear()
        return null
    }

    fun undo(): String? {
        pendingRoom?.let {
            active.addAll(it.corners)
            pendingRoom = null
            return null
        }
        val last = active.removeLastOrNull() ?: return "Nothing to undo here. Delete saved items from Job."
        last.detach()
        return null
    }

    fun clear() {
        active.forEach { it.detach() }
        lines.forEach { it.a.detach(); it.b.detach() }
        rooms.forEach { r -> r.corners.forEach { it.detach() } }
        pendingRoom?.corners?.forEach { it.detach() }
        active.clear(); lines.clear(); rooms.clear(); pendingRoom = null
    }

    private fun finishRoom(room: LiveRoom) {
        pendingRoom = null
        rooms.add(room)
        val floorY = floorY(room.corners)
        val height = room.ceilingY?.let { abs(it - floorY).toDouble() }
        listener.onRoomMeasured(planCorners(room.corners), height)
    }

    /** Draws everything and returns the 2D labels and text for the UI. */
    fun render(
        renderer: LineRenderer, viewProj: FloatArray, viewW: Int, viewH: Int,
        aim: FloatArray?, units: Units,
    ): Snapshot {
        val labels = mutableListOf<OverlayView.Label>()
        fun label(p: FloatArray, text: String) {
            project(p, viewProj, viewW, viewH)?.let { (x, y) -> labels.add(OverlayView.Label(x, y, text)) }
        }

        // Finished point-to-point and height lines.
        for (l in lines) {
            if (!l.a.isLive() || !l.b.isLive()) continue
            val a = l.a.pos()
            val b = if (l.kind == LineKind.HEIGHT) floatArrayOf(a[0], l.b.pos()[1], a[2]) else l.b.pos()
            renderer.draw(GLES20.GL_LINES, a + b, viewProj, LINE_COLOR, 6f)
            renderer.draw(GLES20.GL_POINTS, a + b, viewProj, POINT_COLOR, 18f)
            label(mid(a, b), Format.length(measure(l.a.pos(), l.b.pos(), l.kind), units))
        }

        // Finished rooms, plus the one waiting for a ceiling height.
        for (room in rooms + listOfNotNull(pendingRoom)) {
            if (room.corners.any { !it.isLive() }) continue
            val y = floorY(room.corners)
            val pts = room.corners.map { val p = it.pos(); floatArrayOf(p[0], y, p[2]) }
            renderer.draw(GLES20.GL_LINE_LOOP, pts.flatten(), viewProj, ROOM_COLOR, 6f)
            renderer.draw(GLES20.GL_POINTS, pts.flatten(), viewProj, POINT_COLOR, 18f)
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                label(mid(a, b), Format.length(horizontal(a, b), units))
            }
            room.ceilingY?.let { cy ->
                val base = pts[0]
                val top = floatArrayOf(base[0], cy, base[2])
                renderer.draw(GLES20.GL_LINES, base + top, viewProj, LINE_COLOR, 6f)
                label(mid(base, top), "CLG " + Format.length(abs(cy - y).toDouble(), units))
            }
        }

        // In-progress points and the rubber-band line to the reticle.
        val activePts = active.filter { it.isLive() }.map { it.pos() }
        if (activePts.isNotEmpty()) {
            val flat = if (mode == Mode.ROOM) {
                val y = activePts[0][1]
                activePts.map { floatArrayOf(it[0], y, it[2]) }
            } else activePts
            renderer.draw(GLES20.GL_LINE_STRIP, flat.flatten(), viewProj, ROOM_COLOR, 6f)
            renderer.draw(GLES20.GL_POINTS, flat.flatten(), viewProj, POINT_COLOR, 18f)
            for (i in 0 until flat.size - 1) {
                label(mid(flat[i], flat[i + 1]), Format.length(horizontal(flat[i], flat[i + 1]), units))
            }
        }

        var live: String? = null
        if (aim != null) {
            val pending = pendingRoom
            if (pending != null && pending.corners.all { it.isLive() }) {
                val y = floorY(pending.corners)
                val c = pending.corners[0].pos()
                val base = floatArrayOf(c[0], y, c[2])
                val top = floatArrayOf(c[0], aim[1], c[2])
                renderer.draw(GLES20.GL_LINES, base + top, viewProj, PREVIEW_COLOR, 4f)
                live = "Height " + Format.length(abs(aim[1] - y).toDouble(), units)
            } else if (activePts.isNotEmpty()) {
                val last = activePts.last()
                val (from, to, meters) = when (mode) {
                    Mode.DISTANCE -> Triple(last, aim, measure(last, aim, LineKind.DISTANCE))
                    Mode.HEIGHT -> Triple(last, floatArrayOf(last[0], aim[1], last[2]), measure(last, aim, LineKind.HEIGHT))
                    Mode.ROOM -> {
                        val y = activePts[0][1]
                        val a = floatArrayOf(last[0], y, last[2])
                        val b = floatArrayOf(aim[0], y, aim[2])
                        Triple(a, b, horizontal(a, b))
                    }
                }
                renderer.draw(GLES20.GL_LINES, from + to, viewProj, PREVIEW_COLOR, 4f)
                live = Format.length(meters, units)
            }
        }

        return Snapshot(labels, live, prompt(), if (pendingRoom != null) "Skip height" else "Close room")
    }

    private fun prompt(): String {
        if (pendingRoom != null) return "Aim where a wall meets the ceiling and tap +, or tap Skip height."
        val n = active.size
        return when (mode) {
            Mode.DISTANCE -> if (n == 0) "Aim at the start point and tap +." else "Aim at the end point and tap +."
            Mode.HEIGHT -> if (n == 0) "Aim at the floor and tap +." else "Aim at the top point and tap +. Only the vertical part counts."
            Mode.ROOM -> when {
                n == 0 -> "Aim at the floor in a corner and tap +. Work your way around the room."
                n < 3 -> "Aim at the next corner and tap +. ($n so far)"
                else -> "Next corner, or tap Close room. ($n corners)"
            }
        }
    }

    companion object {
        private const val LINE_COLOR = 0xFFFFC107.toInt()
        private const val ROOM_COLOR = 0xFF40C4FF.toInt()
        private const val POINT_COLOR = 0xFFFFFFFF.toInt()
        private const val PREVIEW_COLOR = 0xCCFFFFFF.toInt()

        private fun Anchor.isLive() = trackingState != TrackingState.STOPPED
        private fun Anchor.pos(): FloatArray = pose.translation

        private fun List<FloatArray>.flatten(): FloatArray {
            val out = FloatArray(size * 3)
            forEachIndexed { i, p -> p.copyInto(out, i * 3, 0, 3) }
            return out
        }

        private fun mid(a: FloatArray, b: FloatArray) =
            floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)

        private fun horizontal(a: FloatArray, b: FloatArray) = hypot(b[0] - a[0], b[2] - a[2]).toDouble()

        private fun measure(a: FloatArray, b: FloatArray, kind: LineKind): Double = when (kind) {
            LineKind.HEIGHT -> abs(b[1] - a[1]).toDouble()
            LineKind.DISTANCE -> {
                val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
                sqrt(dx * dx + dy * dy + dz * dz).toDouble()
            }
        }

        private fun floorY(corners: List<Anchor>) = corners.map { it.pos()[1] }.average().toFloat()

        /** Plan view (looking down, ARCore's -Z is "up" on paper), corner 0 at origin, first wall on +X. */
        private fun planCorners(corners: List<Anchor>): List<Vec2> {
            val raw = corners.map { val p = it.pos(); Vec2(p[0].toDouble(), -p[2].toDouble()) }
            val o = raw[0]
            val angle = atan2(raw[1].y - o.y, raw[1].x - o.x)
            val c = cos(-angle); val s = sin(-angle)
            return raw.map {
                val x = it.x - o.x; val y = it.y - o.y
                Vec2(x * c - y * s, x * s + y * c)
            }
        }

        private fun project(p: FloatArray, vp: FloatArray, w: Int, h: Int): Pair<Float, Float>? {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, vp, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
            if (out[3] <= 0f) return null
            val x = (out[0] / out[3] + 1f) / 2f * w
            val y = (1f - out[1] / out[3]) / 2f * h
            return x to y
        }
    }
}
