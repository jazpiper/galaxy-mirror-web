package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.json.JSONObject
import org.junit.Test

class ControlEventValidatorTest {
    @Test
    fun controlChannel_acceptsOnlyExpectedLabel() {
        assertTrue(ControlEventValidator.isControlChannel("control"))
        assertFalse(ControlEventValidator.isControlChannel("debug"))
        assertFalse(ControlEventValidator.isControlChannel(null))
    }

    @Test
    fun isValid_acceptsSupportedControlEvents() {
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"tap","x":0.5,"y":0.25}""")))
        assertTrue(
            ControlEventValidator.isValid(
                JSONObject("""{"type":"swipe","x1":0.1,"y1":0.2,"x2":0.8,"y2":0.7,"duration":300}""")
            )
        )
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":4}""")))
    }

    @Test
    fun isValid_rejectsOutOfRangeTouchAndLongSwipe() {
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"tap","x":1.2,"y":0.25}""")))
        assertFalse(
            ControlEventValidator.isValid(
                JSONObject("""{"type":"swipe","x1":0.1,"y1":0.2,"x2":0.8,"y2":0.7,"duration":5000}""")
            )
        )
    }

    @Test
    fun isValid_rejectsUnsupportedKeyAndUnknownType() {
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":66}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"TOUCH_DOWN","x":0.5,"y":0.25}""")))
    }
}
