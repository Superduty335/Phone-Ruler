package com.phoneruler.measure

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.phoneruler.measure.export.Share
import com.phoneruler.measure.model.Format
import com.phoneruler.measure.model.Job
import com.phoneruler.measure.model.LineKind
import com.phoneruler.measure.model.LineRecord
import com.phoneruler.measure.model.RoomRecord
import com.phoneruler.measure.model.Units
import com.phoneruler.measure.model.Vec2
import com.phoneruler.measure.render.BackgroundRenderer
import com.phoneruler.measure.render.DisplayRotationHelper
import com.phoneruler.measure.render.LineRenderer
import com.phoneruler.measure.ui.OverlayView
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity(), GLSurfaceView.Renderer, MeasureController.Listener {

    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var liveText: TextView
    private lateinit var finishButton: TextView
    private lateinit var unitsButton: TextView
    private lateinit var modeTabs: Map<MeasureController.Mode, TextView>
    private lateinit var rotationHelper: DisplayRotationHelper

    private var session: Session? = null
    private var installRequested = false

    private val background = BackgroundRenderer()
    private val lineRenderer = LineRenderer()
    private val controller = MeasureController(this)

    /** UI-thread requests, run on the GL thread against the next camera frame. */
    private val pending = ConcurrentLinkedQueue<(Frame) -> Unit>()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private var viewW = 0
    private var viewH = 0
    private var textureBound = false

    @Volatile private var units = Units.IMPERIAL

    private lateinit var jobFile: File
    private lateinit var job: Job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status)
        liveText = findViewById(R.id.live)
        finishButton = findViewById(R.id.finish)
        unitsButton = findViewById(R.id.units)
        modeTabs = mapOf(
            MeasureController.Mode.DISTANCE to findViewById(R.id.modeDistance),
            MeasureController.Mode.HEIGHT to findViewById(R.id.modeHeight),
            MeasureController.Mode.ROOM to findViewById(R.id.modeRoom),
        )
        rotationHelper = DisplayRotationHelper(this)

        jobFile = File(filesDir, PlanActivity.JOB_FILE)
        job = loadJob()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        units = PlanActivity.loadUnits(this)
        unitsButton.text = unitsLabel()

        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        modeTabs.forEach { (mode, tab) ->
            tab.setOnClickListener {
                selectTab(mode)
                pending.add { controller.setMode(mode) }
            }
        }
        selectTab(MeasureController.Mode.DISTANCE)

        findViewById<TextView>(R.id.add).setOnClickListener {
            pending.add { frame ->
                val hit = if (frame.camera.trackingState == TrackingState.TRACKING) bestHit(frame) else null
                if (hit == null) toast("No surface under the crosshair yet. Move the phone slowly.")
                else controller.addPoint(hit)
            }
        }
        findViewById<TextView>(R.id.undo).setOnClickListener {
            pending.add { controller.undo()?.let(::toast) }
        }
        finishButton.setOnClickListener {
            pending.add { controller.closeOrSkip()?.let(::toast) }
        }
        findViewById<TextView>(R.id.clear).setOnClickListener {
            pending.add { controller.clear() }
        }
        unitsButton.setOnClickListener {
            units = if (units == Units.IMPERIAL) Units.METRIC else Units.IMPERIAL
            unitsButton.text = unitsLabel()
            prefs.edit().putString("units", units.name).apply()
        }
        findViewById<TextView>(R.id.job).setOnClickListener { showJob() }
        findViewById<TextView>(R.id.export).setOnClickListener { Share.export(this, job, units) }
        findViewById<TextView>(R.id.plan).setOnClickListener {
            startActivity(Intent(this, PlanActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        job = loadJob() // the plan screen may have moved rooms
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
                    return
                }
                session = Session(this).also { configure(it) }
            } catch (e: UnavailableException) {
                statusText.text = "ARCore isn't available on this phone: ${e.javaClass.simpleName}"
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            statusText.text = "Camera not available. Close other camera apps and reopen."
            session = null
            return
        }
        surface.onResume()
        rotationHelper.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            rotationHelper.onPause()
            surface.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is needed to measure", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun configure(session: Session) {
        val config = Config(session)
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        // Depth (from ToF sensors or motion stereo) gives hits on walls and ceilings with no detected plane.
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        }
        session.configure(config)
    }

    // ---- GL thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        lineRenderer.createOnGlThread()
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        rotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (!textureBound) {
            session.setCameraTextureName(background.textureId)
            textureBound = true
        }
        rotationHelper.updateSessionIfNeeded(session)

        val frame = try {
            session.update()
        } catch (e: Exception) {
            return
        }
        background.draw(frame)

        while (true) {
            val action = pending.poll() ?: break
            action(frame)
        }

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            val msg = trackingMessage(camera.trackingFailureReason)
            runOnUiThread {
                statusText.text = msg
                liveText.text = ""
                overlay.update(emptyList(), OverlayView.Aim.NONE)
            }
            return
        }

        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        drawPlanes(session)

        val hit = bestHit(frame)
        val aim = when {
            hit == null -> OverlayView.Aim.NONE
            hit.distance > MAX_GOOD_DISTANCE_M -> OverlayView.Aim.ROUGH
            hit.trackable is Point -> OverlayView.Aim.ROUGH
            else -> OverlayView.Aim.GOOD
        }
        val aimPoint = hit?.hitPose?.translation
        val snap = controller.render(lineRenderer, viewProj, viewW, viewH, aimPoint, units)

        val prompt = if (hit != null && hit.distance > MAX_GOOD_DISTANCE_M) {
            snap.prompt + "\nYou're %.1f m away. Get closer for better accuracy.".format(hit.distance)
        } else snap.prompt

        runOnUiThread {
            statusText.text = prompt
            liveText.text = snap.live ?: ""
            finishButton.text = snap.finishLabel
            overlay.update(snap.labels, aim)
        }
    }

    /** Surface hit under the crosshair: a detected plane, a depth hit, or (rougher) a feature point. */
    private fun bestHit(frame: Frame): HitResult? {
        if (viewW == 0) return null
        val hits = frame.hitTest(viewW / 2f, viewH / 2f)
        hits.firstOrNull { h ->
            val t = h.trackable
            (t is Plane && t.isPoseInPolygon(h.hitPose)) || t is DepthPoint
        }?.let { return it }
        return hits.firstOrNull { h ->
            val t = h.trackable
            t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }
    }

    private fun drawPlanes(session: Session) {
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val poly = plane.polygon
            poly.rewind()
            val n = poly.limit() / 2
            if (n < 3) continue
            val pose = plane.centerPose
            val xyz = FloatArray(n * 3)
            for (i in 0 until n) {
                val w = pose.transformPoint(floatArrayOf(poly.get(i * 2), 0f, poly.get(i * 2 + 1)))
                w.copyInto(xyz, i * 3, 0, 3)
            }
            lineRenderer.draw(GLES20.GL_LINE_LOOP, xyz, viewProj, PLANE_COLOR, 2f)
        }
    }

    private fun trackingMessage(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark to track. Turn on more lights."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at something with more texture or detail."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable."
        else -> "Move the phone slowly side to side to start tracking."
    }

    // ---- Measurement results (called on the GL thread) ----

    override fun onLineMeasured(kind: LineKind, meters: Double) {
        runOnUiThread {
            val prefix = if (kind == LineKind.HEIGHT) "Height" else "Line"
            val name = "$prefix ${job.lines.count { it.kind == kind } + 1}"
            job.lines.add(LineRecord(name, kind, meters))
            saveJob()
            toast("$name saved: ${Format.length(meters, units)}")
        }
    }

    override fun onRoomMeasured(corners: List<Vec2>, heightMeters: Double?) {
        runOnUiThread {
            val default = "Room ${job.rooms.size + 1}"
            val draft = RoomRecord(default, corners, heightMeters)
            val summary = buildString {
                append("${corners.size} walls, ${Format.area(draft.area, units)}")
                append("\nPerimeter ${Format.length(draft.perimeter, units)}")
                heightMeters?.let { append("\nCeiling ${Format.length(it, units)}") }
            }
            val input = EditText(this).apply { setText(default); selectAll() }
            var saved = false
            fun save() {
                if (saved) return
                saved = true
                val name = input.text.toString().trim().ifEmpty { default }
                job.addRoom(draft.copy(name = name))
                saveJob()
            }
            AlertDialog.Builder(this)
                .setTitle("Name this room")
                .setMessage(summary)
                .setView(input)
                .setPositiveButton("Save") { _, _ -> save() }
                .setOnDismissListener { save() }
                .show()
        }
    }

    // ---- Job, export ----

    private fun showJob() {
        val items = job.rooms.map { r ->
            "${r.name}: ${r.corners.size} walls, ${Format.area(r.area, units)}" +
                (r.heightMeters?.let { ", clg ${Format.length(it, units)}" } ?: "")
        } + job.lines.map { "${it.name}: ${Format.length(it.meters, units)}" }

        val builder = AlertDialog.Builder(this).setTitle(job.name)
        if (items.isEmpty()) builder.setMessage("Nothing measured yet.")
        else builder.setItems(items.toTypedArray()) { _, which -> confirmDelete(which) }
        builder
            .setPositiveButton("Close", null)
            .setNeutralButton("Rename job") { _, _ -> renameJob() }
            .setNegativeButton("New job") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Start a new job? Export first if you need the current measurements.")
                    .setPositiveButton("Start new") { _, _ ->
                        job = Job()
                        saveJob()
                        pending.add { controller.clear() }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun confirmDelete(index: Int) {
        val isRoom = index < job.rooms.size
        val name = if (isRoom) job.rooms[index].name else job.lines[index - job.rooms.size].name
        AlertDialog.Builder(this)
            .setMessage("Delete $name?")
            .setPositiveButton("Delete") { _, _ ->
                if (isRoom) job.rooms.removeAt(index) else job.lines.removeAt(index - job.rooms.size)
                saveJob()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameJob() {
        val input = EditText(this).apply { setText(job.name); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("Job name (address or client)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                job.name = input.text.toString().trim().ifEmpty { "Job" }
                saveJob()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadJob(): Job = try { Job.load(jobFile) } catch (e: Exception) { Job() }

    private fun saveJob() {
        try {
            job.save(jobFile)
        } catch (e: Exception) {
            toast("Couldn't save: ${e.message}")
        }
    }

    // ---- UI helpers ----

    private fun selectTab(mode: MeasureController.Mode) {
        modeTabs.forEach { (m, tab) ->
            val on = m == mode
            tab.setTextColor(if (on) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt())
            tab.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun unitsLabel() = if (units == Units.IMPERIAL) "ft-in" else "Metric"

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val MAX_GOOD_DISTANCE_M = 3.0f
        const val PLANE_COLOR = 0x66FFFFFF
    }
}
