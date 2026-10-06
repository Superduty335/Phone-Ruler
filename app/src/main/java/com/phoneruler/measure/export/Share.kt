package com.phoneruler.measure.export

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.phoneruler.measure.model.Job
import com.phoneruler.measure.model.Units
import java.io.File

/** Writes the PDF sketch, DXF, CSV and JSON for a job and opens the Android share sheet. */
object Share {
    fun export(activity: Activity, job: Job, units: Units) {
        if (job.isEmpty) {
            Toast.makeText(activity, "Nothing to export yet", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(activity.cacheDir, "exports")
        dir.deleteRecursively()
        val files = Exporter.exportAll(job, units, dir).toMutableList()
        if (job.rooms.isNotEmpty()) {
            val pdf = File(dir, files.first().nameWithoutExtension + ".pdf")
            PlanPdf.write(job, units, pdf)
            files.add(0, pdf)
        }
        val authority = activity.packageName + ".fileprovider"
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(activity, authority, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "${job.name} measurements")
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Send plan (PDF, DXF, CSV)"))
    }
}
