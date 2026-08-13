package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertNull

class ControlEventDispatcherTest {
    @Test
    fun invalidJsonReturnsRejectedAck() {
        val results = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { FakeApplier() },
                onViewerActivity = {},
            )

        dispatcher.dispatch("""{"type":"tap","x":2,"y":0.5,"seq":8}""") { result ->
            results.add(result)
        }

        assertEquals(1, results.size)
        assertEquals(8L, results.first().seq)
        assertEquals(false, results.first().applied)
        assertEquals("CONTROL_EVENT_REJECTED", results.first().message)
    }

    @Test
    fun missingAccessibilityServiceReturnsNotReadyAck() {
        val results = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { null },
                onViewerActivity = {},
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4,"seq":3}""") { result ->
            results.add(result)
        }

        assertEquals(1, results.size)
        assertEquals(3L, results.first().seq)
        assertEquals(false, results.first().applied)
        assertEquals("ACCESSIBILITY_SERVICE_NOT_READY", results.first().message)
    }

    @Test
    fun validControlEventMarksActivityAndDelegatesToApplier() {
        val results = mutableListOf<ControlEventResult>()
        val fakeApplier = FakeApplier()
        var activityCount = 0
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { fakeApplier },
                onViewerActivity = { activityCount++ },
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4,"seq":12}""") { result ->
            results.add(result)
        }

        assertEquals(1, activityCount)
        assertEquals("key", fakeApplier.recordedJson?.optString("type"))
        assertEquals(1, results.size)
        assertEquals(12L, results.first().seq)
        assertEquals(true, results.first().applied)
        assertEquals("FAKE_APPLIED", results.first().message)
    }

    @Test
    fun validControlEventWithoutSeqDoesNotSendAckButStillDelegates() {
        val results = mutableListOf<ControlEventResult>()
        val fakeApplier = FakeApplier()
        var activityCount = 0
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { fakeApplier },
                onViewerActivity = { activityCount++ },
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4}""") { result ->
            results.add(result)
        }

        assertEquals(1, activityCount)
        assertEquals("key", fakeApplier.recordedJson?.optString("type"))
        assertEquals(0, results.size)
    }

    @Test
    fun invalidControlEventWithoutSeqDoesNotSendAck() {
        val results = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { FakeApplier() },
                onViewerActivity = {},
            )

        dispatcher.dispatch("""{"type":"tap","x":2,"y":0.5}""") { result ->
            results.add(result)
        }

        assertEquals(0, results.size)
    }

    @Test
    fun missingAccessibilityServiceWithoutSeqDoesNotSendAck() {
        val results = mutableListOf<ControlEventResult>()
        var activityCount = 0
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { null },
                onViewerActivity = { activityCount++ },
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4}""") { result ->
            results.add(result)
        }

        assertEquals(1, activityCount)
        assertEquals(0, results.size)
    }

    @Test
    fun malformedJsonDoesNotSendAck() {
        val results = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { FakeApplier() },
                onViewerActivity = {},
            )

        dispatcher.dispatch("not json") { result ->
            results.add(result)
        }

        assertEquals(0, results.size)
    }

    @Test
    fun exceptionDuringDispatchSendsExceptionAck() {
        val results = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = {
                    object : ControlEventApplier {
                        override fun handleControlEvent(
                            json: JSONObject,
                            resultCallback: (ControlEventResult) -> Unit,
                        ) {
                            throw RuntimeException("Test Exception")
                        }
                    }
                },
                onViewerActivity = {},
            )

        dispatcher.dispatch("{\"type\":\"key\",\"keyCode\":4,\"seq\":15}") { result ->
            results.add(result)
        }

        assertEquals(1, results.size)
        assertEquals(15L, results.first().seq)
        assertEquals(false, results.first().applied)
        assertEquals("CONTROL_EVENT_EXCEPTION", results.first().message)
    }

    @Test
    fun controlSeq_parsesValidSequence() {
        val json = JSONObject("""{"seq":123}""")
        assertEquals(123L, json.controlSeq())
    }

    @Test
    fun controlSeq_returnsNullWhenMissing() {
        val json = JSONObject("""{"type":"tap"}""")
        assertNull(json.controlSeq())
    }

    @Test
    fun controlSeq_returnsZeroForJsonNull() {
        val json = JSONObject("""{"seq":null}""")
        assertEquals(0L, json.controlSeq())
    }

    @Test
    fun controlSeq_parsesNumericString() {
        val json = JSONObject("""{"seq":"456"}""")
        assertEquals(456L, json.controlSeq())
    }

    @Test
    fun controlSeq_returnsZeroForNonNumericString() {
        val json = JSONObject("""{"seq":"not-a-number"}""")
        assertEquals(0L, json.controlSeq())
    }

    @Test
    fun controlSeq_parsesDoubleByTruncating() {
        val json = JSONObject("""{"seq":123.45}""")
        assertEquals(123L, json.controlSeq())
    }

    private class FakeApplier : ControlEventApplier {
        var recordedJson: JSONObject? = null

        override fun handleControlEvent(
            json: JSONObject,
            resultCallback: (ControlEventResult) -> Unit,
        ) {
            recordedJson = json
            resultCallback(
                ControlEventResult(
                    seq = json.controlSeq(),
                    type = json.optString("type", "unknown"),
                    applied = true,
                    message = "FAKE_APPLIED",
                )
            )
        }
    }
}
