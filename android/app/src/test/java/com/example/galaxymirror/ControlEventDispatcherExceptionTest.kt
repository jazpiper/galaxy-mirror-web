package com.example.galaxymirror

import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Test

class ControlEventDispatcherExceptionTest {
    @Test
    fun exceptionDuringDispatchSendsAck() {
        val results = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { ExceptionApplier() },
                onViewerActivity = {},
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4,"seq":12}""") { result ->
            results.add(result)
        }

        assertEquals(1, results.size)
        assertEquals("CONTROL_EVENT_EXCEPTION", results.first().message)
        assertEquals(12L, results.first().seq)
    }

    private class ExceptionApplier : ControlEventApplier {
        override fun handleControlEvent(
            json: JSONObject,
            resultCallback: (ControlEventResult) -> Unit,
        ) {
            throw RuntimeException("Test exception")
        }
    }
}
