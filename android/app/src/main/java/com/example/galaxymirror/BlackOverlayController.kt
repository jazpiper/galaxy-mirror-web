package com.example.galaxymirror

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 스마트폰 실물 디스플레이에 전면 검은색 뷰(Full-screen Black View)를 배치하여
 * OLED 픽셀 전력을 절감하고 사생활 화면 노출을 차단하는 오버레이 컨트롤러.
 */
class BlackOverlayController(
    private val context: Context,
    private val onEmergencyDismiss: (() -> Unit)? = null
) {
    private var overlayView: View? = null
    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    companion object {
        private const val TAG = "BlackOverlayController"
    }

    @Suppress("ObsoleteSdkInt")
    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isShowing(): Boolean = overlayView != null

    @Synchronized
    fun showOverlay(): Boolean {
        if (!canDrawOverlays()) {
            Log.w(TAG, "Cannot show black overlay: SYSTEM_ALERT_WINDOW permission not granted.")
            return false
        }

        if (overlayView != null) {
            Log.d(TAG, "Black overlay is already showing.")
            return true
        }

        try {
            val view = View(context).apply {
                setBackgroundColor(Color.BLACK)
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        Log.i(TAG, "Emergency touch detected on black overlay. Dismissing overlay.")
                        v.performClick()
                        hideOverlay()
                        onEmergencyDismiss?.invoke()
                    }
                    true
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            )

            windowManager.addView(view, params)
            overlayView = view
            Log.i(TAG, "Black overlay shown successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error showing black overlay: ${e.message}", e)
            overlayView = null
            return false
        }
    }

    @Synchronized
    fun hideOverlay(): Boolean {
        val view = overlayView ?: return false
        try {
            windowManager.removeView(view)
            overlayView = null
            Log.i(TAG, "Black overlay hidden successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding black overlay: ${e.message}", e)
            overlayView = null
            return false
        }
    }
}
