package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.json.JSONObject
import org.junit.Test

class ControlEventApplierTest {

    private class FakeControlEventApplier : ControlEventApplier {
        var recordedJson: JSONObject? = null
        var callbackInvoked = false

        override fun handleControlEvent(
            json: JSONObject,
            resultCallback: (ControlEventResult) -> Unit,
        ) {
            recordedJson = json
            resultCallback(
                ControlEventResult(
                    seq = json.optLong("seq").takeIf { json.has("seq") },
                    type = json.optString("type", "unknown"),
                    applied = true,
                    message = "FAKE_APPLIED",
                )
            )
            callbackInvoked = true
        }
    }

    @Test
    fun handleControlEvent_withCallback_receivesResult() {
        val applier = FakeControlEventApplier()
        val json = JSONObject().put("type", "tap").put("seq", 42L)

        var receivedResult: ControlEventResult? = null

        applier.handleControlEvent(json) { result ->
            receivedResult = result
        }

        assertTrue(applier.callbackInvoked)
        assertEquals(json, applier.recordedJson)

        assertEquals(42L, receivedResult?.seq)
        assertEquals("tap", receivedResult?.type)
        assertEquals(true, receivedResult?.applied)
        assertEquals("FAKE_APPLIED", receivedResult?.message)
    }

    @Test
    fun handleControlEvent_withoutCallback_usesDefaultEmptyCallbackAndDoesNotCrash() {
        val applier = FakeControlEventApplier()
        val json = JSONObject().put("type", "swipe").put("seq", 100L)

        // This shouldn't crash when resultCallback is invoked internally by FakeControlEventApplier
        applier.handleControlEvent(json)

        assertTrue(applier.callbackInvoked)
        assertEquals(json, applier.recordedJson)
    }
}
