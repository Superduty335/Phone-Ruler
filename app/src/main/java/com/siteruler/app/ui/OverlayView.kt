package com.siteruler.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 2D layer over the camera: the aiming reticle and measurement labels. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class Label(val x: Float, val y: Float, val text: String)

    enum class Aim { NONE, ROUGH, GOOD }

    private val density = resources.displayMetrics.density
    private var labels: List<Label> = emptyList()
    private var aim = Aim.NONE

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    init {
        // Let touches fall through to the buttons and surface below.
        isClickable = false
    }

    fun update(labels: List<Label>, aim: Aim) {
        this.labels = labels
        this.aim = aim
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (l in labels) {
            val w = textPaint.measureText(l.text) / 2 + 8 * density
            val h = textPaint.textSize / 2 + 6 * density
            rect.set(l.x - w, l.y - h, l.x + w, l.y + h)
            canvas.drawRoundRect(rect, 8 * density, 8 * density, bgPaint)
            canvas.drawText(l.text, l.x, l.y + textPaint.textSize / 3, textPaint)
        }

        val cx = width / 2f
        val cy = height / 2f
        reticlePaint.color = when (aim) {
            Aim.GOOD -> Color.rgb(76, 217, 100)
            Aim.ROUGH -> Color.rgb(255, 193, 7)
            Aim.NONE -> Color.rgb(255, 82, 82)
        }
        val r = 18 * density
        canvas.drawCircle(cx, cy, r, reticlePaint)
        canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, reticlePaint)
        canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, reticlePaint)
    }
}
