package com.example.galaxymirror

import org.json.JSONObject

data class ControlEventResult(
    val seq: Long?,
    val type: String,
    val applied: Boolean,
    val message: String,
) {
    fun toAckJson(): String =
        JSONObject()
            .put("type", "CONTROL_ACK")
            .put(
                "payload",
                JSONObject()
                    .put("seq", seq ?: JSONObject.NULL)
                    .put("eventType", type)
                    .put("applied", applied)
                    .put("message", message)
            )
            .toString()
}
