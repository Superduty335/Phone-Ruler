package com.siteruler.app

import android.app.Activity
import com.siteruler.app.MeasureController.Mode

/**
 * Who's measuring. Each trade shows the tools it uses: architects outline rooms for floor
 * plans; surveyors, engineers and landscapers shoot points and ground elevations for volumes.
 */
enum class Trade(
    val label: String,
    val modes: List<Mode>,
    /** What the Room tool is called; landscapers outline beds, lawns and patios. */
    val roomLabel: String,
    val askCeiling: Boolean,
    val showPlan: Boolean,
    val showVolumes: Boolean,
    /** Topo codes offered first, most common first. */
    val codes: List<String>,
) {
    ARCHITECTURE(
        "Architecture", listOf(Mode.DISTANCE, Mode.HEIGHT, Mode.ROOM, Mode.POINTS),
        "Room", askCeiling = true, showPlan = true, showVolumes = false,
        codes = listOf("FF", "GND", "TC", "EP"),
    ),
    SURVEYING(
        "Surveying", listOf(Mode.DISTANCE, Mode.HEIGHT, Mode.POINTS, Mode.TOPO),
        "Room", askCeiling = true, showPlan = false, showVolumes = true,
        codes = listOf("GND", "TOP", "TOE", "EP", "TC", "FL", "SHOT"),
    ),
    ENGINEERING(
        "Engineering", listOf(Mode.DISTANCE, Mode.HEIGHT, Mode.POINTS, Mode.TOPO),
        "Room", askCeiling = true, showPlan = false, showVolumes = true,
        codes = listOf("EG", "FG", "SG", "TOP", "TOE", "FL", "TC", "EP"),
    ),
    LANDSCAPING(
        "Landscaping", listOf(Mode.DISTANCE, Mode.ROOM, Mode.POINTS, Mode.TOPO),
        "Area", askCeiling = false, showPlan = true, showVolumes = true,
        codes = listOf("GND", "FG", "BED", "LAWN", "WALL", "PATIO", "TOP", "TOE"),
    );

    companion object {
        fun load(activity: Activity): Trade? {
            val name = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE).getString("trade", null)
            return entries.firstOrNull { it.name == name }
        }

        fun save(activity: Activity, trade: Trade) {
            activity.getSharedPreferences("settings", Activity.MODE_PRIVATE).edit().putString("trade", trade.name).apply()
        }
    }
}
