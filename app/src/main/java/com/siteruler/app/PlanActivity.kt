package com.siteruler.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.siteruler.app.export.Share
import com.siteruler.app.model.Format
import com.siteruler.app.model.Job
import com.siteruler.app.model.Units
import com.siteruler.app.ui.PlanView
import java.io.File

/** The floor plan sketch: arrange measured rooms into a plan and export it. */
class PlanActivity : Activity() {

    private lateinit var plan: PlanView
    private lateinit var title: TextView
    private lateinit var job: Job
    private lateinit var jobFile: File
    private var units = Units.IMPERIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)
        plan = findViewById(R.id.plan)
        title = findViewById(R.id.planTitle)

        jobFile = File(filesDir, JOB_FILE)
        job = try { Job.load(jobFile) } catch (e: Exception) { Job() }
        units = loadUnits(this)

        plan.units = units
        plan.job = job
        plan.onChanged = { job.save(jobFile) }
        plan.onSelectionChanged = { updateTitle() }
        updateTitle()

        findViewById<TextView>(R.id.rotateLeft).setOnClickListener { plan.rotateSelected(90.0) }
        findViewById<TextView>(R.id.rotateRight).setOnClickListener { plan.rotateSelected(-90.0) }
        findViewById<TextView>(R.id.fit).setOnClickListener { plan.fit() }
        findViewById<TextView>(R.id.planExport).setOnClickListener { Share.export(this, job, units) }
    }

    private fun updateTitle() {
        val room = job.rooms.getOrNull(plan.selected)
        title.text = when {
            job.rooms.isEmpty() -> "No rooms yet. Measure rooms in Room mode and they'll appear here."
            room != null -> "${room.name}: ${Format.area(room.area, units)}. Drag to move, or rotate it below."
            else -> "${job.name}: ${job.rooms.size} rooms, ${Format.area(job.rooms.sumOf { it.area }, units)}. " +
                "Drag rooms into place; corners snap together."
        }
    }

    companion object {
        const val JOB_FILE = "job.json"

        fun loadUnits(activity: Activity): Units {
            val prefs = activity.getSharedPreferences("settings", MODE_PRIVATE)
            return if (prefs.getString("units", "IMPERIAL") == "METRIC") Units.METRIC else Units.IMPERIAL
        }
    }
}
