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
            processEvent(json, sendAck)
        } catch (e: Exception) {
            handleException(e, seq, type, sendAck)
        }
    }

    private fun processEvent(
        json: JSONObject,
        sendAck: (ControlEventResult) -> Unit,
    ) {
        if (!ControlEventValidator.isValid(json)) {
            rejectEvent(json, "CONTROL_EVENT_REJECTED", sendAck)
            return
        }

        onViewerActivity()
        val service = serviceProvider()
        if (service == null) {
            rejectEvent(json, "ACCESSIBILITY_SERVICE_NOT_READY", sendAck)
            return
        }

        service.handleControlEvent(json) { result ->
            sendSequencedAck(result, sendAck)
        }
    }

    private fun rejectEvent(
        json: JSONObject,
        message: String,
        sendAck: (ControlEventResult) -> Unit,
    ) {
        sendSequencedAck(
            ControlEventResult(
                seq = json.controlSeq(),
                type = json.optString("type", "unknown"),
                applied = false,
                message = message,
            ),
            sendAck,
        )
    }

    private fun handleException(
        e: Exception,
        seq: Long?,
        type: String,
        sendAck: (ControlEventResult) -> Unit,
    ) {
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
