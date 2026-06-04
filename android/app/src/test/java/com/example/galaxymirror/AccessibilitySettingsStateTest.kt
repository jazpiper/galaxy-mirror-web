package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class AccessibilitySettingsStateTest {
    @Test
    fun containsEnabledService_matchesColonSeparatedComponentName() {
        val enabledServices =
            "com.other/.OtherService:com.example.galaxymirror/com.example.galaxymirror.GalaxyMirrorAccessibilityService"

        assertTrue(
            AccessibilitySettingsState.containsEnabledService(
                enabledServices,
                "com.example.galaxymirror/com.example.galaxymirror.GalaxyMirrorAccessibilityService"
            )
        )
    }

    @Test
    fun containsEnabledService_doesNotMatchPartialComponentName() {
        val enabledServices =
            "com.example.galaxymirror.debug/com.example.galaxymirror.GalaxyMirrorAccessibilityService"

        assertFalse(
            AccessibilitySettingsState.containsEnabledService(
                enabledServices,
                "com.example.galaxymirror/com.example.galaxymirror.GalaxyMirrorAccessibilityService"
            )
        )
        assertFalse(AccessibilitySettingsState.containsEnabledService(null, "component"))
    }
}
