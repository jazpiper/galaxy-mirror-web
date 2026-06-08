package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":"hello"}""")))
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":"한글 입력"}""")))
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":"\n"}""")))
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":1}""")))
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":64}""")))
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
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":99}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"TOUCH_DOWN","x":0.5,"y":0.25}""")))
    }

    @Test
    fun isValid_rejectsInvalidTextEvents() {
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":""}""")))
        assertFalse(
            ControlEventValidator.isValid(
                JSONObject("""{"type":"text","action":"commit","text":${JSONObject.quote("a".repeat(129))}}""")
            )
        )
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","keyCode":66}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":0}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":65}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward"}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","text":"hello"}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"replace","text":"hello"}""")))
    }

    @Test
    fun isValid_acceptsNewVolumeMuteAndPowerKeyPayloads() {
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":24}"""))) // Volume Up
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":25}"""))) // Volume Down
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":164}"""))) // Volume Mute
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":26}"""))) // Lock Screen
    }

    @Test
    fun isValid_acceptsAndValidatesClipboardEvents() {
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"clipboard","text":"hello clipboard"}""")))
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"clipboard","text":""}""")))
        assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"clipboard"}""")))
        assertFalse(
            ControlEventValidator.isValid(
                JSONObject("""{"type":"clipboard","text":${JSONObject.quote("a".repeat(8193))}}""")
            )
        )
    }

    @Test
    fun outboundClipboardListenerAllowsEmptyTextButSkipsNullText() {
        val source = readAccessibilityServiceSource()

        assertTrue(
            "Clipboard listener should coerce clip text and keep null as the only missing-text value.",
            source.contains("val text = clip.getItemAt(0).coerceToText(this)?.toString()")
        )
        assertTrue(
            "Empty clipboard strings should propagate as clear commands unless they are self-injected repeats.",
            source.contains("if (text != null && text != lastInjectedClipboardText)")
        )
        assertFalse(
            "Empty clipboard text must not be filtered out by isNullOrEmpty().",
            source.contains("!text.isNullOrEmpty()")
        )
    }

    private fun readAccessibilityServiceSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt"),
            Path.of("app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt")
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("GalaxyMirrorAccessibilityService.kt source not found")
        return path.toFile().readText()
    }
}
