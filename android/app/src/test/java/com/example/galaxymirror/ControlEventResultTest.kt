package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.json.JSONObject
import org.junit.Test

class ControlEventResultTest {
    @Test
    fun ackJsonContainsSeqAppliedAndMessage() {
        val json =
            JSONObject(
                ControlEventResult(
                    seq = 42,
                    type = "text",
                    applied = true,
                    message = "TEXT_COMMIT_APPLIED",
                ).toAckJson()
            )

        assertEquals("CONTROL_ACK", json.getString("type"))
        assertEquals(42, json.getJSONObject("payload").getLong("seq"))
        assertEquals("text", json.getJSONObject("payload").getString("eventType"))
        assertEquals(true, json.getJSONObject("payload").getBoolean("applied"))
        assertEquals("TEXT_COMMIT_APPLIED", json.getJSONObject("payload").getString("message"))
    }
}
