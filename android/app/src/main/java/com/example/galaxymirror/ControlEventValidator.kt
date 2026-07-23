package com.example.galaxymirror

import org.json.JSONObject

object ControlEventValidator {
    private const val MIN_COORDINATE = 0.0
    private const val MAX_COORDINATE = 1.0
    private const val MIN_SWIPE_DURATION_MS = 1L
    private const val MAX_SWIPE_DURATION_MS = 1500L
    private const val MAX_TEXT_LENGTH = 128
    private const val MIN_DELETE_COUNT = 1
    private const val MAX_DELETE_COUNT = 64
    private const val CONTROL_CHANNEL_LABEL = "control"

    private val allowedKeyCodes = setOf(3, 4, 19, 20, 21, 22, 24, 25, 26, 66, 164, 187)

    fun isControlChannel(label: String?): Boolean = label == CONTROL_CHANNEL_LABEL

    fun isValid(json: JSONObject): Boolean {
        return when (json.optString("type", "")) {
            "tap" -> hasNormalizedCoordinates(json, "x", "y")
            "swipe" ->
                hasNormalizedCoordinates(json, "x1", "y1", "x2", "y2") &&
                    isDurationValid(json)
            "key" -> allowedKeyCodes.contains(json.optInt("keyCode", Int.MIN_VALUE))
            "text" -> isTextValid(json)
            "clipboard" -> isClipboardValid(json)
            "black_overlay" -> true
            "resize_display" -> isResizeValid(json)
            else -> false
        }
    }

    private fun isResizeValid(json: JSONObject): Boolean {
        // width/height are optional (handler applies phone-native defaults) and clamped downstream;
        // only reject values that are explicitly present but non-positive.
        val payload = if (json.has("payload")) json.optJSONObject("payload") ?: json else json
        val widthOk = !payload.has("width") || payload.optInt("width", -1) > 0
        val heightOk = !payload.has("height") || payload.optInt("height", -1) > 0
        return widthOk && heightOk
    }

    private fun hasNormalizedCoordinates(json: JSONObject, vararg keys: String): Boolean {
        return keys.all { key ->
            json.has(key) && json.optDouble(key, Double.NaN).let { value ->
                !value.isNaN() && value in MIN_COORDINATE..MAX_COORDINATE
            }
        }
    }

    private fun isDurationValid(json: JSONObject): Boolean {
        if (!json.has("duration")) return true
        val duration = json.optLong("duration", -1L)
        return duration in MIN_SWIPE_DURATION_MS..MAX_SWIPE_DURATION_MS
    }

    private fun isTextValid(json: JSONObject): Boolean {
        return when (json.optString("action", "")) {
            "commit" -> {
                if (!json.has("text")) return false
                val text = json.optString("text", "")
                text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH
            }
            "deleteBackward" -> {
                if (!json.has("count")) return false
                json.optInt("count", -1) in MIN_DELETE_COUNT..MAX_DELETE_COUNT
            }
            else -> false
        }
    }

    private fun isClipboardValid(json: JSONObject): Boolean {
        if (!json.has("text")) return false
        val text = json.optString("text", "")
        return text.length <= 8192
    }
}
