package com.siteruler.app.render

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import com.google.ar.core.Session

/** Tells ARCore the screen size and rotation whenever they change. */
class DisplayRotationHelper(private val activity: Activity) : DisplayManager.DisplayListener {
    @Volatile private var changed = false
    private var width = 0
    private var height = 0
    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun onResume() = displayManager.registerDisplayListener(this, null)
    fun onPause() = displayManager.unregisterDisplayListener(this)

    fun onSurfaceChanged(w: Int, h: Int) {
        width = w
        height = h
        changed = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (!changed) return
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        session.setDisplayGeometry(rotation, width, height)
        changed = false
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) { changed = true }
}
