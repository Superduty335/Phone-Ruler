package com.siteruler.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.siteruler.app.model.Units
import com.siteruler.app.ui.RulerView

/**
 * On-screen ruler for small objects. Turn the phone sideways for a longer ruler.
 * Calibrate once against a credit card, because phones don't always report their exact pixel density.
 */
class RulerActivity : Activity() {

    private lateinit var ruler: RulerView
    private lateinit var hint: TextView
    private lateinit var normalRow: LinearLayout
    private lateinit var calibrateRow: LinearLayout
    private lateinit var controls: LinearLayout
    private var calibrating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val density = resources.displayMetrics.density

        ruler = RulerView(this, null)
        ruler.units = PlanActivity.loadUnits(this)
        val dm = resources.displayMetrics
        val reported = (dm.xdpi + dm.ydpi) / 2f / 25.4f
        ruler.pxPerMm = prefs.getFloat("rulerPxPerMm", reported)
        ruler.edge = if (prefs.getString("rulerEdge", "TOP") == "BOTTOM") RulerView.Edge.BOTTOM else RulerView.Edge.TOP

        fun button(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundResource(R.drawable.btn_bg)
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                marginStart = (3 * density).toInt(); marginEnd = (3 * density).toInt()
            }
            setOnClickListener { onClick(this) }
        }
        fun row() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }

        normalRow = row().apply {
            addView(button(edgeLabel()) { b ->
                ruler.edge = if (ruler.edge == RulerView.Edge.TOP) RulerView.Edge.BOTTOM else RulerView.Edge.TOP
                prefs.edit().putString("rulerEdge", ruler.edge.name).apply()
                b.text = edgeLabel()
                placeControls()
            })
            addView(button(unitsLabel()) { b ->
                ruler.units = if (ruler.units == Units.IMPERIAL) Units.METRIC else Units.IMPERIAL
                b.text = unitsLabel()
            })
            addView(button("Calibrate") { setCalibrating(true) })
            addView(button("Close") { finish() })
        }
        calibrateRow = row().apply {
            addView(button("−") { nudge(1 / 1.0025f) })
            addView(button("+") { nudge(1.0025f) })
            addView(button("Reset") {
                ruler.pxPerMm = reported
                ruler.setMarkersMm(0f, CARD_MM)
            })
            addView(button("Done") {
                prefs.edit().putFloat("rulerPxPerMm", ruler.pxPerMm).apply()
                setCalibrating(false)
            })
        }
        hint = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(normalRow)
            addView(calibrateRow)
        }
        val root = FrameLayout(this)
        root.addView(ruler, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2))
        setContentView(root)
        placeControls()
        setCalibrating(false)
    }

    /** Buttons go on the edge opposite the ruler, so they never cover it. */
    private fun placeControls() {
        val margin = (16 * resources.displayMetrics.density).toInt()
        controls.layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = if (ruler.edge == RulerView.Edge.TOP) Gravity.BOTTOM else Gravity.TOP
            topMargin = margin
            bottomMargin = margin
        }
    }

    private fun setCalibrating(on: Boolean) {
        calibrating = on
        normalRow.visibility = if (on) LinearLayout.GONE else LinearLayout.VISIBLE
        calibrateRow.visibility = if (on) LinearLayout.VISIBLE else LinearLayout.GONE
        hint.text = if (on) {
            "Lay a credit card's long edge on the ruler with its end at the left red line. " +
                "Tap − or + until the right red line meets the card's other end (85.6 mm, 3 3/8\")."
        } else {
            "Hold the object against the ruler. Turn the phone sideways for a longer ruler."
        }
        if (on) ruler.setMarkersMm(0f, CARD_MM)
    }

    private fun nudge(factor: Float) {
        ruler.pxPerMm *= factor
        ruler.setMarkersMm(0f, CARD_MM)
    }

    private fun edgeLabel() = if (ruler.edge == RulerView.Edge.TOP) "Ruler: top" else "Ruler: bottom"
    private fun unitsLabel() = if (ruler.units == Units.IMPERIAL) "Inches" else "mm"

    private companion object {
        const val CARD_MM = 85.6f // ISO/IEC 7810 ID-1 card width
    }
}
