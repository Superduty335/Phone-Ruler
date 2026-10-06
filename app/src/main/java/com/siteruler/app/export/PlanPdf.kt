package com.siteruler.app.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.siteruler.app.model.Format
import com.siteruler.app.model.Job
import com.siteruler.app.model.Units
import com.siteruler.app.ui.PlanRenderer
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/** One-page landscape PDF of the floor plan sketch with a title block and scale bar. */
object PlanPdf {
    private const val PAGE_W = 792 // US Letter landscape, in points
    private const val PAGE_H = 612
    private const val MARGIN = 40f

    fun write(job: Job, units: Units, file: File) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val renderer = PlanRenderer(density = 0.75f)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; isFakeBoldText = true }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.DKGRAY }
        canvas.drawText(job.name, MARGIN, MARGIN, title)
        canvas.drawText(
            "As-built sketch · measured with SiteRuler · " + DateFormat.getDateInstance().format(Date()),
            MARGIN, MARGIN + 14f, small,
        )

        val b = renderer.bounds(job)
        if (b != null) {
            val top = MARGIN + 40f
            val bottom = PAGE_H - MARGIN - 30f
            val w = b.width().coerceAtLeast(1f)
            val h = b.height().coerceAtLeast(1f)
            val scale = min((PAGE_W - 2 * MARGIN) / w, (bottom - top) / h)
            val tx = PAGE_W / 2f - (b.left + w / 2) * scale
            val ty = (top + bottom) / 2f + (b.top + h / 2) * scale
            renderer.draw(canvas, job, units, scale, tx, ty)

            // Scale bar: 10 ft or 3 m.
            val barMeters = if (units == Units.IMPERIAL) 3.048 else 3.0
            val barLen = (barMeters * scale).toFloat()
            val y = PAGE_H - MARGIN
            val line = Paint().apply { strokeWidth = 2f; color = Color.BLACK }
            canvas.drawLine(MARGIN, y, MARGIN + barLen, y, line)
            canvas.drawLine(MARGIN, y - 4, MARGIN, y + 4, line)
            canvas.drawLine(MARGIN + barLen, y - 4, MARGIN + barLen, y + 4, line)
            canvas.drawText(Format.length(barMeters, units), MARGIN + barLen + 6f, y + 3f, small)
        }

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
