package com.example.galaxymirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class GalaxyMirrorAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GalaxyMirrorAccessibilityService? = null
        private const val TAG = "GalaxyMirrorA11y"

        fun isReadyForRemoteInput(): Boolean = instance != null
    }

    private var screenWidth = 0
    private var screenHeight = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textInputBuffer = RemoteTextInputBuffer()

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
     *   { "type": "text", "action": "commit", "text": "hello" }
     *   { "type": "text", "action": "deleteBackward", "count": 1 }
     */
    fun handleControlEvent(
        json: JSONObject,
        resultCallback: (ControlEventResult) -> Unit = {},
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleControlEvent(json, resultCallback) }
            return
        }

        val seq = json.controlSeq()
        val eventType = json.optString("type", "unknown")
        try {
            if (!ControlEventValidator.isValid(json)) {
                Log.w(TAG, "Rejected invalid control event: $json")
                resultCallback(ControlEventResult(seq, eventType, false, "CONTROL_EVENT_REJECTED"))
                return
            }
            CrashDiagnostics.recordEvent(this, "Accessibility control event accepted: ${json.optString("type")}.")

            when (val type = json.getString("type")) {
                "tap" -> {
                    textInputBuffer.invalidate()
                    val x = (json.getDouble("x") * screenWidth).toFloat()
                    val y = (json.getDouble("y") * screenHeight).toFloat()
                    CrashDiagnostics.recordEvent(this, "Accessibility tap requested.")
                    val applied = performTap(x, y)
                    resultCallback(ControlEventResult(seq, type, applied, "GESTURE_DISPATCH_REQUESTED"))
                }
                "swipe" -> {
                    textInputBuffer.invalidate()
                    val x1 = (json.getDouble("x1") * screenWidth).toFloat()
                    val y1 = (json.getDouble("y1") * screenHeight).toFloat()
                    val x2 = (json.getDouble("x2") * screenWidth).toFloat()
                    val y2 = (json.getDouble("y2") * screenHeight).toFloat()
                    val duration = if (json.has("duration")) json.getLong("duration") else 300L
                    CrashDiagnostics.recordEvent(this, "Accessibility swipe requested.")
                    val applied = performSwipe(x1, y1, x2, y2, duration)
                    resultCallback(ControlEventResult(seq, type, applied, "GESTURE_DISPATCH_REQUESTED"))
                }
                "key" -> {
                    textInputBuffer.invalidate()
                    val keyCode = json.getInt("keyCode")
                    CrashDiagnostics.recordEvent(this, "Accessibility key requested: $keyCode.")
                    val applied = handleKeyEvent(keyCode)
                    resultCallback(ControlEventResult(seq, type, applied, "KEY_APPLIED"))
                }
                "text" -> {
                    when (json.getString("action")) {
                        "commit" -> {
                            val text = json.getString("text")
                            CrashDiagnostics.recordEvent(this, "Accessibility text commit requested length=${text.length}.")
                            val applied = commitTextInput(text)
                            resultCallback(ControlEventResult(seq, type, applied, "TEXT_COMMIT_APPLIED"))
                        }
                        "deleteBackward" -> {
                            val count = json.getInt("count")
                            CrashDiagnostics.recordEvent(this, "Accessibility text delete requested count=$count.")
                            val applied = deleteTextBackward(count)
                            resultCallback(ControlEventResult(seq, type, applied, "TEXT_DELETE_APPLIED"))
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown control event type: $type")
                    resultCallback(ControlEventResult(seq, type, false, "UNKNOWN_CONTROL_EVENT"))
                }
            }
        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(filesDir, "accessibility control event", e)
            Log.e(TAG, "Error handling control event: ${e.message}", e)
            resultCallback(ControlEventResult(seq, eventType, false, "CONTROL_EVENT_EXCEPTION"))
        }
    }

    private fun performTap(x: Float, y: Float): Boolean {
        Log.d(TAG, "Performing tap at ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(strokeDescription).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
        CrashDiagnostics.recordEvent(this, "Accessibility tap dispatch requested=$dispatched.")
        return dispatched
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        Log.d(TAG, "Performing swipe from ($x1,$y1) to ($x2,$y2) in ${durationMs}ms")
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(strokeDescription).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Swipe completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)
        CrashDiagnostics.recordEvent(this, "Accessibility swipe dispatch requested=$dispatched.")
        return dispatched
    }

    private fun handleKeyEvent(keyCode: Int): Boolean {
        // Map common keycodes to global actions
        val applied = when (keyCode) {
            4 -> performGlobalAction(GLOBAL_ACTION_BACK)      // Android KEYCODE_BACK
            3 -> performGlobalAction(GLOBAL_ACTION_HOME)      // Android KEYCODE_HOME
            187 -> performGlobalAction(GLOBAL_ACTION_RECENTS) // KEYCODE_APP_SWITCH
            else -> {
                Log.w(TAG, "Unhandled keyCode: $keyCode")
                false
            }
        }
        CrashDiagnostics.recordEvent(this, "Accessibility key applied=$applied.")
        return applied
    }

    private fun commitTextInput(text: String): Boolean {
        val focusedNode = findTextInputTarget("commit") ?: return false

        val edit = textInputBuffer.planCommit(focusedNode.toRemoteTextSnapshot(), text)
        val applied = focusedNode.performSetTextAction(edit.nextText)
        if (applied) {
            focusedNode.performSetSelectionAction(edit.nextSelectionStart, edit.nextSelectionEnd)
            textInputBuffer.markApplied(edit)
        } else {
            textInputBuffer.invalidate()
        }
        CrashDiagnostics.recordEvent(this, "Accessibility text commit applied=$applied nextLength=${edit.nextText.length}.")
        Log.d(TAG, "Text commit applied=$applied length=${text.length}")
        return applied
    }

    private fun deleteTextBackward(count: Int): Boolean {
        val focusedNode = findTextInputTarget("delete") ?: return false

        val edit = textInputBuffer.planDelete(focusedNode.toRemoteTextSnapshot(), count)
        val applied = focusedNode.performSetTextAction(edit.nextText)
        if (applied) {
            focusedNode.performSetSelectionAction(edit.nextSelectionStart, edit.nextSelectionEnd)
            textInputBuffer.markApplied(edit)
        } else {
            textInputBuffer.invalidate()
        }
        CrashDiagnostics.recordEvent(this, "Accessibility text delete applied=$applied nextLength=${edit.nextText.length}.")
        Log.d(TAG, "Text delete applied=$applied count=$count")
        return applied
    }

    private fun findTextInputTarget(action: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            CrashDiagnostics.recordEvent(this, "Accessibility text $action failed: no active window root.")
            Log.w(TAG, "No active window root for text $action.")
            return null
        }

        val inputFocus = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = TextInputTargetSelector.findTarget(
            focused = inputFocus,
            root = rootNode,
            isEditable = { it.isEditable },
            isFocused = { it.isFocused },
            isEnabled = { it.isEnabled },
            childCount = { it.childCount },
            childAt = { node, index -> node.getChild(index) }
        )

        if (target == null) {
            CrashDiagnostics.recordEvent(
                this,
                "Accessibility text $action failed: no editable focused node. inputFocus=${inputFocus.describeForDiagnostics()}."
            )
            Log.w(TAG, "No editable focused node for text $action. inputFocus=${inputFocus.describeForDiagnostics()}")
            return null
        }

        if (target != inputFocus) {
            CrashDiagnostics.recordEvent(
                this,
                "Accessibility text $action using editable descendant. inputFocus=${inputFocus.describeForDiagnostics()} target=${target.describeForDiagnostics()}."
            )
        }

        return target
    }

    private fun AccessibilityNodeInfo.toRemoteTextSnapshot(): RemoteTextSnapshot {
        val text = this.text?.toString().orEmpty()
        val selection = selectionRange(text) ?: (text.length to text.length)
        return RemoteTextSnapshot(
            targetKey = textInputTargetKey(),
            text = text,
            selectionStart = selection.first,
            selectionEnd = selection.second,
        )
    }

    private fun AccessibilityNodeInfo.selectionRange(text: String): Pair<Int, Int>? {
        val selectionStart = textSelectionStart
        val selectionEnd = textSelectionEnd
        if (selectionStart < 0 || selectionEnd < 0) return null

        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        return start to end
    }

    private fun AccessibilityNodeInfo.textInputTargetKey(): String {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return listOf(
            packageName?.toString().orEmpty(),
            className?.toString().orEmpty(),
            viewIdResourceName.orEmpty(),
            bounds.toShortString(),
        ).joinToString("|")
    }

    private fun AccessibilityNodeInfo.performSetTextAction(text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun AccessibilityNodeInfo.performSetSelectionAction(start: Int, end: Int): Boolean {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    private fun JSONObject.controlSeq(): Long? =
        if (has("seq")) {
            optLong("seq")
        } else {
            null
        }

    private fun AccessibilityNodeInfo?.describeForDiagnostics(): String {
        if (this == null) return "null"
        return "class=${className ?: "unknown"}, package=${packageName ?: "unknown"}, editable=$isEditable, focused=$isFocused, enabled=$isEnabled"
    }
}
