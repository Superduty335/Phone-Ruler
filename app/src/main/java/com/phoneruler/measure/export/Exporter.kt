package com.phoneruler.measure.export

import com.phoneruler.measure.model.Format
import com.phoneruler.measure.model.Job
import com.phoneruler.measure.model.LineKind
import com.phoneruler.measure.model.Units
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

/** Writes a job as DXF (for CAD), CSV (for spreadsheets) and JSON (raw data). */
object Exporter {

    fun exportAll(job: Job, units: Units, dir: File): List<File> {
        dir.mkdirs()
        val base = job.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").ifBlank { "job" }
        val dxf = File(dir, "$base.dxf").apply { writeText(dxf(job, units)) }
        val csv = File(dir, "$base.csv").apply { writeText(csv(job, units)) }
        val json = File(dir, "$base.json").apply { writeText(job.toJson().toString(2)) }
        return listOf(dxf, csv, json)
    }

    fun csv(job: Job, units: Units): String = buildString {
        appendLine("type,name,item,meters,feet_inches,display")
        fun row(type: String, name: String, item: String, m: Double) {
            val ftIn = Format.length(m, Units.IMPERIAL)
            appendLine(
                listOf(type, name, item, "%.4f".format(Locale.US, m), ftIn, Format.length(m, units))
                    .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
            )
        }
        job.rooms.forEach { r ->
            r.walls.forEachIndexed { i, w -> row("wall", r.name, "Wall ${i + 1}", w) }
            row("perimeter", r.name, "Perimeter", r.perimeter)
            r.heightMeters?.let { row("height", r.name, "Ceiling height", it) }
            appendLine("\"area\",\"${r.name}\",\"Floor area (m2)\",\"%.4f\",\"\",\"%s\"".format(
                Locale.US, r.area, Format.area(r.area, units)))
        }
        job.lines.forEach { l ->
            row(if (l.kind == LineKind.HEIGHT) "height" else "distance", l.name, l.kind.name.lowercase(), l.meters)
        }
    }

    /**
     * AutoCAD R12 ASCII DXF. Imperial drawings use inches as the drawing unit, metric uses meters.
     * Rooms are placed as arranged on the in-app plan sketch.
     */
    fun dxf(job: Job, units: Units): String {
        val scale = if (units == Units.IMPERIAL) Format.inches(1.0) else 1.0
        val textH = if (units == Units.IMPERIAL) 4.0 else 0.1
        val gap = if (units == Units.IMPERIAL) 60.0 else 1.5
        val sb = StringBuilder()
        fun g(code: Int, value: Any) {
            sb.append(code).append('\n').append(
                if (value is Double) "%.4f".format(Locale.US, value) else value.toString()
            ).append('\n')
        }
        fun line(layer: String, x1: Double, y1: Double, x2: Double, y2: Double) {
            g(0, "LINE"); g(8, layer)
            g(10, x1); g(20, y1); g(30, 0.0)
            g(11, x2); g(21, y2); g(31, 0.0)
        }
        fun text(layer: String, x: Double, y: Double, h: Double, s: String, rotDeg: Double = 0.0) {
            g(0, "TEXT"); g(8, layer)
            g(10, x); g(20, y); g(30, 0.0)
            g(40, h); g(1, s); g(50, rotDeg)
            g(72, 1) // horizontally centered on the alignment point
            g(11, x); g(21, y); g(31, 0.0)
        }

        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$INSUNITS"); g(70, if (units == Units.IMPERIAL) 1 else 6)
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")

        for (room in job.rooms) {
            val pts = room.placed.map { (it.x * scale) to (it.y * scale) }
            if (pts.size < 3) continue
            // Signed area tells us winding, so dimension text can go on the outside of each wall.
            var signed = 0.0
            for (i in pts.indices) {
                val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
                signed += ax * by - bx * ay
            }
            val ccw = signed > 0
            room.walls.forEachIndexed { i, meters ->
                val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
                line("WALLS", ax, ay, bx, by)
                val dx = bx - ax; val dy = by - ay
                val len = hypot(dx, dy).takeIf { it > 0 } ?: return@forEachIndexed
                var nx = dy / len; var ny = -dx / len
                if (!ccw) { nx = -nx; ny = -ny }
                var angle = Math.toDegrees(atan2(dy, dx))
                if (angle > 90) angle -= 180
                if (angle <= -90) angle += 180
                text(
                    "DIMS", (ax + bx) / 2 + nx * textH * 1.5, (ay + by) / 2 + ny * textH * 1.5,
                    textH, Format.length(meters, units), angle
                )
            }
            val cx = pts.sumOf { it.first } / pts.size
            val cy = pts.sumOf { it.second } / pts.size
            text("ROOMS", cx, cy + textH, textH * 1.5, room.name)
            text("ROOMS", cx, cy - textH, textH, Format.area(room.area, units))
            room.heightMeters?.let { text("ROOMS", cx, cy - textH * 2.6, textH, "CLG " + Format.length(it, units)) }
        }

        // Point-to-point and height measurements as a notes list under the plans.
        val minY = job.rooms.flatMap { it.placed }.minOfOrNull { it.y * scale } ?: 0.0
        var y = minY - gap
        for (l in job.lines) {
            val label = if (l.kind == LineKind.HEIGHT) "Height" else "Distance"
            g(0, "TEXT"); g(8, "NOTES"); g(10, 0.0); g(20, y); g(30, 0.0); g(40, textH)
            g(1, "${l.name}: $label ${Format.length(l.meters, units)}")
            y -= textH * 1.8
        }

        g(0, "ENDSEC")
        g(0, "EOF")
        return sb.toString()
    }
}
