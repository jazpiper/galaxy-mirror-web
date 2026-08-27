package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MainActivityTest {

    @Test
    fun verifyMainActivityStructureAndServiceBinding() {
        val source = readMainActivitySource().replace("\r\n", "\n")

        // Verify MainActivity inherits from ComponentActivity
        assertTrue(source.contains("class MainActivity : ComponentActivity()"))

        // Verify ServiceConnection implementation handles connect/disconnect
        assertTrue(source.contains("private val serviceConnection = object : ServiceConnection"))
        assertTrue(source.contains("override fun onServiceConnected("))
        assertTrue(source.contains("override fun onServiceDisconnected("))

        // Verify lifecycle repeatOnLifecycle observing serviceState
        assertTrue(source.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(source.contains("s.serviceState.collect"))
    }

    @Test
    fun verifyScreenCapturePermissionLauncher() {
        val source = readMainActivitySource().replace("\r\n", "\n")

        // Verify screen capture launcher handles Activity.RESULT_OK
        assertTrue(source.contains("screenCaptureLauncher = registerForActivityResult("))
        assertTrue(source.contains("result.resultCode == Activity.RESULT_OK"))
        assertTrue(source.contains("startMediaProjectionService("))
    }

    @Test
    fun verifySettingsNavigationIntents() {
        val source = readMainActivitySource().replace("\r\n", "\n")

        // Verify Settings intents are properly constructed
        assertTrue(source.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertTrue(source.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        assertTrue(source.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(source.contains("Settings.ACTION_MANAGE_WRITE_SETTINGS"))
    }

    private fun readMainActivitySource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/galaxymirror/MainActivity.kt"),
            Path.of("app/src/main/java/com/example/galaxymirror/MainActivity.kt")
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("MainActivity.kt source not found")
        return path.toFile().readText()
    }
}
