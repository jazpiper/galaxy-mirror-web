package com.example.galaxymirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class GalaxyMirrorAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GalaxyMirrorAccessibilityService? = null
        private const val TAG = "GalaxyMirrorA11y"
    }

    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        Log.d(TAG, "AccessibilityService connected. Screen: ${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Handle a JSON control event from the WebRTC DataChannel.
     * Expected format:
     *   { "type": "tap", "x": 0.5, "y": 0.3 }  (normalized 0.0-1.0)
     *   { "type": "swipe", "x1": 0.2, "y1": 0.8, "x2": 0.2, "y2": 0.2, "duration": 300 }
     *   { "type": "key", "keyCode": 4 }  (Android KeyEvent keycode)
     */
    fun handleControlEvent(json: JSONObject) {
        try {
            if (!ControlEventValidator.isValid(json)) {
                Log.w(TAG, "Rejected invalid control event: $json")
                return
            }

            when (val type = json.getString("type")) {
                "tap" -> {
                    val x = (json.getDouble("x") * screenWidth).toFloat()
                    val y = (json.getDouble("y") * screenHeight).toFloat()
                    performTap(x, y)
                }
                "swipe" -> {
                    val x1 = (json.getDouble("x1") * screenWidth).toFloat()
                    val y1 = (json.getDouble("y1") * screenHeight).toFloat()
                    val x2 = (json.getDouble("x2") * screenWidth).toFloat()
                    val y2 = (json.getDouble("y2") * screenHeight).toFloat()
                    val duration = if (json.has("duration")) json.getLong("duration") else 300L
                    performSwipe(x1, y1, x2, y2, duration)
                }
                "key" -> {
                    val keyCode = json.getInt("keyCode")
                    handleKeyEvent(keyCode)
                }
                else -> Log.w(TAG, "Unknown control event type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling control event: ${e.message}", e)
        }
    }

    private fun performTap(x: Float, y: Float) {
        Log.d(TAG, "Performing tap at ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(strokeDescription).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        Log.d(TAG, "Performing swipe from ($x1,$y1) to ($x2,$y2) in ${durationMs}ms")
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(strokeDescription).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Swipe completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)
    }

    private fun handleKeyEvent(keyCode: Int) {
        // Map common keycodes to global actions
        when (keyCode) {
            4 -> performGlobalAction(GLOBAL_ACTION_BACK)      // Android KEYCODE_BACK
            3 -> performGlobalAction(GLOBAL_ACTION_HOME)      // Android KEYCODE_HOME
            187 -> performGlobalAction(GLOBAL_ACTION_RECENTS) // KEYCODE_APP_SWITCH
            else -> Log.w(TAG, "Unhandled keyCode: $keyCode")
        }
    }
}
