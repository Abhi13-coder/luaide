package com.luaide.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Real screen overlays via WindowManager + TYPE_APPLICATION_OVERLAY (public API,
 * Android 8+; this is the same permission/API every floating-bubble app uses).
 * Requires the user to grant "Draw over other apps" once via Settings — Android
 * doesn't allow this silently, by design, and neither should we pretend to.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: TextView? = null

    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    /** Opens the real system settings screen to grant the permission — there is no programmatic bypass. */
    fun requestPermission() {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun show(text: String, x: Int = 100, y: Int = 300) {
        if (!hasPermission()) return
        if (overlayView == null) {
            val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            val view = TextView(context).apply {
                setBackgroundColor(Color.parseColor("#E6141210"))
                setTextColor(Color.parseColor("#E9E1CF"))
                setPadding(28, 20, 28, 20)
                textSize = 13f

                var lastX = 0f; var lastY = 0f; var startX = 0; var startY = 0
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.rawX; lastY = event.rawY
                            startX = params.x; startY = params.y
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = startX + (event.rawX - lastX).toInt()
                            params.y = startY + (event.rawY - lastY).toInt()
                            windowManager.updateViewLayout(v, params)
                            true
                        }
                        else -> false
                    }
                }
            }
            windowManager.addView(view, params)
            overlayView = view
        }
        overlayView?.text = text
    }

    fun hide() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }
}
