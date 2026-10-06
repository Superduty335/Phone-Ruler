package com.siteruler.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.siteruler.app.export.Exporter
import com.siteruler.app.export.Share
import com.siteruler.app.model.Coords
import com.siteruler.app.model.Format
import com.siteruler.app.model.GridPoint
import com.siteruler.app.model.Job
import com.siteruler.app.model.P3
import com.siteruler.app.model.Tin
import com.siteruler.app.model.Units
import com.siteruler.app.model.VolumeResult
import com.siteruler.app.model.Volumes
import com.siteruler.app.ui.TinView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Earthwork volumes from shot elevations. The ground is a TIN through the chosen points,
 * measured against a design grade, the plane through its own edge (a stockpile or pit),
 * or a second surface (existing against finished grade).
 */
class VolumeActivity : Activity() {

    private enum class Ref(val label: String) { GRADE("Grade"), BASE("Stockpile"), SURFACE("Surface") }

    private lateinit var job: Job
    private var units = Units.IMPERIAL
    private lateinit var grid: List<GridPoint>
    private lateinit var tinView: TinView
    private lateinit var resultText: TextView
    private lateinit var groundButton: TextView
    private lateinit var gradeField: EditText
    private lateinit var compareButton: TextView
    private lateinit var refTabs: Map<Ref, TextView>

    private var ref = Ref.GRADE
    private var groundCodes = setOf<String>()
    private var compareCodes = setOf<String>()
    private var lastReport = ""

    private val codes: List<String> get() = grid.map { it.desc }.distinct().sorted()
    private val f: Double get() = if (units == Units.IMPERIAL) 1 / 0.3048 else 1.0
    private val unitLabel: String get() = if (units == Units.IMPERIAL) "ft" else "m"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = try { Job.load(File(filesDir, PlanActivity.JOB_FILE)) } catch (e: Exception) { Job() }
        units = PlanActivity.loadUnits(this)
        grid = Coords.compute(job.points, units).first
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // Engineers shoot EG and FG; compare them by default when both exist.
        compareCodes = codes.filter { it in setOf("FG", "SG", "DESIGN") }.toSet()
        groundCodes = codes.toSet() - compareCodes
        if (compareCodes.isNotEmpty() && groundCodes.isNotEmpty()) ref = Ref.SURFACE

        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.btn_bg)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
            setOnClickListener { onClick() }
        }
        fun row() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }

        tinView = TinView(this).apply {
            metersPerUnit = 1 / f
            unitLabel = this@VolumeActivity.unitLabel
        }
        resultText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        groundButton = button("") { pickCodes("Ground surface points", groundCodes) { groundCodes = it; update() } }
        refTabs = Ref.entries.associateWith { r -> button(r.label) { ref = r; update() } }
        gradeField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Design grade elevation ($unitLabel)"
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            val zs = groundPoints().map { it.z * f }
            if (zs.isNotEmpty()) setText("%.2f".format(Locale.US, zs.average()))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = update()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        compareButton = button("") { pickCodes("Compare against these points", compareCodes) { compareCodes = it; update() } }
            .apply { layoutParams = LinearLayout.LayoutParams(-1, dp(44)) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(32, 32, 32))
            setPadding(dp(10), dp(4), dp(10), dp(10))
            addView(row().apply { addView(groundButton) })
            addView(row().apply { refTabs.values.forEach { addView(it) } })
            addView(gradeField)
            addView(compareButton)
            addView(resultText)
            addView(row().apply {
                addView(button("Share") { share() })
                addView(button("Copy") { copy() })
                addView(button("Close") { finish() })
            })
        }
        val title = TextView(this).apply {
            text = "${job.name}: volumes"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(32, 32, 32))
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(tinView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(panel)
        })
        update()
    }

    private fun groundPoints() = grid.filter { it.desc in groundCodes }
    private fun comparePoints() = grid.filter { it.desc in compareCodes }

    private fun codeLabel(set: Set<String>) = when {
        set.isEmpty() -> "none"
        set.size == codes.size -> "all points"
        else -> set.joinToString(", ") { it.ifEmpty { "(no code)" } }
    }

    private fun pickCodes(title: String, current: Set<String>, done: (Set<String>) -> Unit) {
        if (codes.isEmpty()) return
        val checked = codes.map { it in current }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(
                codes.map { c -> "${c.ifEmpty { "(no code)" }}  (${grid.count { it.desc == c }})" }.toTypedArray(),
                checked,
            ) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ -> done(codes.filterIndexed { i, _ -> checked[i] }.toSet()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun update() {
        refTabs.forEach { (r, tab) ->
            tab.setTextColor(if (r == ref) 0xFFFFC107.toInt() else Color.WHITE)
            tab.setTypeface(null, if (r == ref) Typeface.BOLD else Typeface.NORMAL)
        }
        gradeField.visibility = if (ref == Ref.GRADE) View.VISIBLE else View.GONE
        compareButton.visibility = if (ref == Ref.SURFACE) View.VISIBLE else View.GONE
        compareButton.text = "Compare to: ${codeLabel(compareCodes)}  ▾"

        val ground = groundPoints()
        val tin = Tin.build(ground.map { P3(it.e, it.n, it.z) })
        groundButton.text = "Ground: ${codeLabel(groundCodes)} (${ground.size})  ▾"
        if (tin.tris.isEmpty()) {
            tinView.show(tin, DoubleArray(0))
            resultText.text = if (grid.isEmpty()) {
                "No points yet. Use Topo mode: tap + on the ground at every high spot, low spot and change in slope, " +
                    "and around the edge."
            } else "Pick at least 3 ground points that aren't all in a line."
            lastReport = ""
            return
        }

        val result: VolumeResult
        val heights: DoubleArray
        val refText: String
        when (ref) {
            Ref.GRADE -> {
                val grade = gradeField.text.toString().toDoubleOrNull()?.div(f)
                if (grade == null) {
                    tinView.show(tin, DoubleArray(tin.pts.size) { Double.NaN })
                    resultText.text = "Enter the design grade elevation."
                    lastReport = ""
                    return
                }
                result = Volumes.toPlane(tin) { _, _ -> grade }
                heights = DoubleArray(tin.pts.size) { tin.pts[it].z - grade }
                refText = "flat grade at " + Format.elevation(grade, units)
            }
            Ref.BASE -> {
                val base = Volumes.basePlane(tin)
                result = Volumes.toPlane(tin, base)
                heights = DoubleArray(tin.pts.size) { tin.pts[it].z - base(tin.pts[it].x, tin.pts[it].y) }
                refText = "plane through the edge points (stockpile base or pit rim)"
            }
            Ref.SURFACE -> {
                val other = Tin.build(comparePoints().map { P3(it.e, it.n, it.z) })
                if (other.tris.isEmpty()) {
                    tinView.show(tin, DoubleArray(tin.pts.size) { Double.NaN })
                    resultText.text = "Pick at least 3 points to compare against (for example FG for finished grade)."
                    lastReport = ""
                    return
                }
                result = Volumes.between(tin, other)
                heights = DoubleArray(tin.pts.size) { i ->
                    other.zAt(tin.pts[i].x, tin.pts[i].y)?.let { tin.pts[i].z - it } ?: Double.NaN
                }
                refText = "surface from ${codeLabel(compareCodes)} (${comparePoints().size} points)"
            }
        }
        tinView.show(tin, heights)

        val zs = ground.map { it.z }
        val summary = when (ref) {
            Ref.BASE -> buildString {
                append("Stockpile ${vol(result.cut)} above its base")
                if (result.fill > 0.001) append("\nBelow the base (pit) ${vol(result.fill)}")
            }
            else -> "Cut ${vol(result.cut)}   Fill ${vol(result.fill)}\n" +
                "Net ${vol(kotlin.math.abs(result.net))} " + (if (result.net >= 0) "cut (export)" else "fill (import)")
        }
        resultText.text = summary + "\nArea ${Format.area(result.area, units)} · ground " +
            "${Format.elevation(zs.min(), units)} to ${Format.elevation(zs.max(), units)}"

        lastReport = buildString {
            appendLine("SiteRuler volume report")
            appendLine("Job: ${job.name}")
            appendLine("Date: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine("Ground surface: ${codeLabel(groundCodes)}, ${ground.size} points, ${tin.tris.size} triangles")
            appendLine("Measured against: $refText")
            appendLine()
            appendLine(summary)
            appendLine("Area: ${Format.area(result.area, units)}")
            appendLine("Ground elevations: ${Format.elevation(zs.min(), units)} to ${Format.elevation(zs.max(), units)}")
            appendLine()
            appendLine("Cut is ground above the reference, fill is ground below it.")
            appendLine("Elevations come from the phone's AR tracking (about 1 cm per meter walked, more on large sites).")
            appendLine("Tie long sites to control points and treat volumes as estimates.")
        }
    }

    private fun vol(m3: Double): String = if (units == Units.IMPERIAL) {
        "%.1f CY (%.0f cu ft)".format(m3 * Volumes.CY_PER_M3, m3 * Volumes.CF_PER_M3)
    } else "%.2f cu m".format(m3)

    private fun share() {
        if (lastReport.isEmpty()) return
        val dir = File(cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        val base = job.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").ifBlank { "job" }
        val ground = groundPoints().map { P3(it.e, it.n, it.z) }
        val files = mutableListOf(
            File(dir, "${base}_volume.txt").apply { writeText(lastReport) },
            File(dir, "${base}_surface.dxf").apply { writeText(Exporter.tinDxf(Tin.build(ground), units)) },
            File(dir, "${base}_points.csv").apply { writeText(Exporter.pnezd(job, units)) },
        )
        Share.send(this, files, "${job.name} volumes", "Send volume report")
    }

    private fun copy() {
        if (lastReport.isEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SiteRuler volumes", lastReport))
        android.widget.Toast.makeText(this, "Volume report copied", android.widget.Toast.LENGTH_SHORT).show()
    }
}
