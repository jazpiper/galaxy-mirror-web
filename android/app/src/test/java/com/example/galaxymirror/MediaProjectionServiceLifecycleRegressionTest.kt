package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MediaProjectionServiceLifecycleRegressionTest {
    @Test
    fun mediaProjectionStopDisposesCapturerPipeline() {
        val source = readServiceSource()

        assertFalse(
            "MediaProjection onStop must not keep a stopped ScreenCapturerAndroid cached.",
            Regex("""diagnosticReason = "ScreenCapturerAndroid callback",\s*stopCapturer = false,""")
                .containsMatchIn(source)
        )
        assertTrue(
            "MediaProjection onStop should force capturer disposal before any fresh grant.",
            Regex("""diagnosticReason = "ScreenCapturerAndroid callback",\s*stopCapturer = true,""")
                .containsMatchIn(source)
        )
    }

    @Test
    fun viewerReplacementAndSocketCloseUseCleanupPolicy() {
        val source = readServiceSource()

        assertTrue(source.contains("CleanupReason.VIEWER_REPLACED"))
        assertTrue(source.contains("CleanupReason.VIEWER_SOCKET_CLOSED"))
        assertFalse(
            "Viewer replacement must not keep active capture alive without a viewer.",
            source.contains("cleanupWebRTCResources(stopProjectionService = false, stopCapturer = false)")
        )
    }

    @Test
    fun missingPermissionOfferRequestsActivityPermissionFlow() {
        val source = readServiceSource()

        assertTrue(source.contains("fun onScreenCapturePermissionRequired() {}"))
        assertTrue(source.contains("screenCapturePermissionRequired"))
        assertTrue(source.contains("requestScreenCapturePermissionFromActivity("))
        assertTrue(source.contains("SCREEN_CAPTURE_REAUTH_REQUIRED"))
    }

    @Test
    fun manualDisconnectClearsPermissionRequestFlag() {
        val source = readServiceSource()

        assertTrue(
            "Disconnect after a denied permission request must not immediately relaunch the screen-share prompt.",
            source.contains(
                """
                mediaProjectionResultData = null
                        screenCapturePermissionRequired = false
                        isRunning = false
                """.trimIndent()
            )
        )
    }

    @Test
    fun screenAwakeEffectsAreReappliedWhenProjectionGrantStarts() {
        val source = readServiceSource()

        assertTrue(
            "Starting a fresh MediaProjection grant should reapply saved keep-awake and brightness options.",
            source.contains(
                """
                isRunning = true
                            applyScreenAwakeEffectsForCurrentState()
                """.trimIndent()
            )
        )
        assertTrue(
            "Screen-awake effects must update both the wake lock and brightness controller.",
            source.contains("private fun applyScreenAwakeEffectsForCurrentState()") &&
                source.contains("setKeepScreenAwake(screenAwakeSettings.shouldKeepScreenAwake(isMirroringActive()))") &&
                source.contains("applyBrightnessMinimizationForCurrentState()")
        )
    }

    private fun readServiceSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/galaxymirror/MediaProjectionService.kt"),
            Path.of("app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt")
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("MediaProjectionService.kt source not found")
        return path.toFile().readText()
    }
}
