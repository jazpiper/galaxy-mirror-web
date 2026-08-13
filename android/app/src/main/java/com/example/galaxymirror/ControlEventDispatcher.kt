package com.example.galaxymirror

import android.util.Log
import org.json.JSONObject

class ControlEventDispatcher(
    private val serviceProvider: () -> ControlEventApplier?,
    private val onViewerActivity: () -> Unit,
) {
    fun dispatch(
        rawText: String,
        sendAck: (ControlEventResult) -> Unit,
    ) {
        var seq: Long? = null
        var type = "unknown"
        try {
            val json = JSONObject(rawText)
            seq = json.controlSeq()
            type = json.optString("type", "unknown")
            if (!ControlEventValidator.isValid(json)) {
                sendSequencedAck(
                    ControlEventResult(
                        seq = seq,
                        type = type,
                        applied = false,
                        message = "CONTROL_EVENT_REJECTED",
                    ),
                    sendAck,
                )
                return
            }

            onViewerActivity()
            val service = serviceProvider()
            if (service == null) {
                sendSequencedAck(
                    ControlEventResult(
                        seq = seq,
                        type = type,
                        applied = false,
                        message = "ACCESSIBILITY_SERVICE_NOT_READY",
                    ),
                    sendAck,
                )
                return
            }

            service.handleControlEvent(json) { result ->
                sendSequencedAck(result, sendAck)
            }
        } catch (e: Exception) {
            logDispatchFailure(e)
            sendSequencedAck(
                ControlEventResult(
                    seq = seq,
                    type = type,
                    applied = false,
                    message = "CONTROL_EVENT_EXCEPTION",
                ),
                sendAck,
            )
        }
    }

    private fun sendSequencedAck(
        result: ControlEventResult,
        sendAck: (ControlEventResult) -> Unit,
    ) {
        if (result.seq != null) {
            sendAck(result)
        }
    }

    private fun logDispatchFailure(e: Exception) {
        try {
            Log.e("ControlEventDispatcher", "Control event dispatch failed: ${e.message}", e)
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in local JVM unit tests.
        }
    }
}

fun JSONObject.controlSeq(): Long? =
    if (has("seq")) {
        optLong("seq")
    } else {
        null
    }
