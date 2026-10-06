package com.siteruler.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A 2D point on a floor plan, in meters. */
data class Vec2(val x: Double, val y: Double)

enum class LineKind { DISTANCE, HEIGHT }

data class LineRecord(val name: String, val kind: LineKind, val meters: Double)

/**
 * A room outline in its own plan coordinates: corner 0 at the origin and the first
 * wall running along +X. AR sessions don't share a coordinate frame, so each room is
 * measured independently and then placed on the floor plan by rotating it about
 * corner 0 and moving it to (offsetX, offsetY).
 */
data class RoomRecord(
    val name: String,
    val corners: List<Vec2>,
    val heightMeters: Double?,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val rotationDeg: Double = 0.0,
) {
    val walls: List<Double>
        get() = corners.indices.map { i ->
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            hypot(b.x - a.x, b.y - a.y)
        }

    val perimeter: Double get() = walls.sum()

    val area: Double
        get() {
            var s = 0.0
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % corners.size]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2
        }

    /** Corners in floor-plan coordinates (meters, Y up). */
    val placed: List<Vec2>
        get() {
            val r = Math.toRadians(rotationDeg)
            val c = cos(r); val s = sin(r)
            return corners.map { Vec2(it.x * c - it.y * s + offsetX, it.x * s + it.y * c + offsetY) }
        }

    fun contains(p: Vec2): Boolean {
        val pts = placed
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}

class Job(var name: String = "Job") {
    val rooms = mutableListOf<RoomRecord>()
    val lines = mutableListOf<LineRecord>()

    val isEmpty: Boolean get() = rooms.isEmpty() && lines.isEmpty()

    /** Adds a newly measured room to the right of everything already on the plan. */
    fun addRoom(room: RoomRecord) {
        val right = rooms.flatMap { it.placed }.maxOfOrNull { it.x }
        val x = if (right == null) 0.0 else right + 1.0
        val minX = room.corners.minOf { it.x }
        val minY = room.corners.minOf { it.y }
        rooms.add(room.copy(offsetX = x - minX, offsetY = -minY))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("rooms", JSONArray().apply {
            rooms.forEach { r ->
                put(JSONObject().apply {
                    put("name", r.name)
                    put("heightMeters", r.heightMeters ?: JSONObject.NULL)
                    put("corners", JSONArray().apply {
                        r.corners.forEach { c -> put(JSONArray().put(c.x).put(c.y)) }
                    })
                    put("offsetX", r.offsetX)
                    put("offsetY", r.offsetY)
                    put("rotationDeg", r.rotationDeg)
                })
            }
        })
        put("lines", JSONArray().apply {
            lines.forEach { l ->
                put(JSONObject().put("name", l.name).put("kind", l.kind.name).put("meters", l.meters))
            }
        })
    }

    fun save(file: File) = file.writeText(toJson().toString(2))

    companion object {
        fun load(file: File): Job {
            val job = Job()
            if (!file.exists()) return job
            val o = JSONObject(file.readText())
            job.name = o.optString("name", "Job")
            val rooms = o.optJSONArray("rooms") ?: JSONArray()
            for (i in 0 until rooms.length()) {
                val r = rooms.getJSONObject(i)
                val cs = r.getJSONArray("corners")
                val corners = (0 until cs.length()).map {
                    val c = cs.getJSONArray(it)
                    Vec2(c.getDouble(0), c.getDouble(1))
                }
                val h = if (r.isNull("heightMeters")) null else r.getDouble("heightMeters")
                val room = RoomRecord(r.getString("name"), corners, h)
                if (r.has("offsetX")) {
                    job.rooms.add(
                        room.copy(
                            offsetX = r.getDouble("offsetX"),
                            offsetY = r.optDouble("offsetY", 0.0),
                            rotationDeg = r.optDouble("rotationDeg", 0.0),
                        )
                    )
                } else {
                    job.addRoom(room)
                }
            }
            val lines = o.optJSONArray("lines") ?: JSONArray()
            for (i in 0 until lines.length()) {
                val l = lines.getJSONObject(i)
                job.lines.add(
                    LineRecord(l.getString("name"), LineKind.valueOf(l.getString("kind")), l.getDouble("meters"))
                )
            }
            return job
        }
    }
}
