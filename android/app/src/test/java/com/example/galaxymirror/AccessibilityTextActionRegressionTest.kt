package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AccessibilityTextActionRegressionTest {
    @Test
    fun textInputUsesAccessibilityActionInsteadOfSealedNodeMutator() {
        val source = readServiceSource()

        assertFalse(
            "AccessibilityNodeInfo.setText() mutates sealed framework nodes and throws at runtime.",
            source.contains(".setText(nextText)")
        )
        assertTrue(source.contains(".performSetTextAction(edit.nextText)"))
        assertTrue(source.contains("performAction(AccessibilityNodeInfo.ACTION_SET_TEXT"))
    }

    private fun readServiceSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt"),
            Path.of("app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt")
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("GalaxyMirrorAccessibilityService.kt source not found")
        return path.toFile().readText()
    }
}
