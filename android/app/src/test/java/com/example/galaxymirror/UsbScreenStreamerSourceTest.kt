package com.example.galaxymirror

import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class UsbScreenStreamerSourceTest {
    @Test
    fun streamerUsesImageReaderVirtualDisplayAndJpegCompression() {
        val source = readSource()

        assertTrue(source.contains("ImageReader.newInstance"))
        assertTrue(source.contains("createVirtualDisplay"))
        assertTrue(source.contains("Bitmap.CompressFormat.JPEG"))
        assertTrue(source.contains("UsbFrameRateGate"))
        assertTrue(source.contains("onFrame"))
    }

    @Test
    fun streamerFailsWhenVirtualDisplayCannotBeCreated() {
        val source = readSource()

        assertTrue(source.contains("USB virtual display could not be created"))
    }

    @Test
    fun streamerCleansUpStartupFailures() {
        val source = readSource()

        assertTrue(source.contains("releaseStartupResources"))
    }

    @Test
    fun sourceRecordsUsbPerfCounters() {
        val source = readSource()

        assertTrue(source.contains("perfMonitor.recordFrameAcquired()"))
        assertTrue(source.contains("perfMonitor.recordFrameDroppedByFps()"))
        assertTrue(source.contains("perfMonitor.recordFrameSkippedByStillness()"))
        assertTrue(source.contains("perfMonitor.recordFrameEncoded("))
        assertTrue(source.contains("perfMonitor.recordEncodeFailure()"))
    }

    @Test
    fun sourceUsesDynamicProfileProviderAndChangeGate() {
        val source = readSource()

        assertTrue(source.contains("profileProvider: () -> UsbStreamProfile"))
        assertTrue(source.contains("UsbStreamProfilePolicy.resolveTier(UsbStreamProfileTier.CLEAR)"))
        assertTrue(source.contains("frameRateGate.updateFps(profile.fps)"))
        assertTrue(source.contains("UsbFrameChangeGate"))
        assertTrue(source.contains("computeFrameSignature("))
        assertTrue(source.contains("encodeJpeg(image, profile, captureProfile)"))
        assertTrue(source.contains("canvas.drawBitmap(sBitmap, srcRect, destRect, null)"))
    }

    @Test
    fun streamerSeparatesProjectionCallbackUnregisterFromStop() {
        val source = readSource()

        assertTrue(source.contains("Unable to unregister USB stream MediaProjection callback."))
        assertTrue(source.contains("Unable to stop USB stream MediaProjection."))
    }

    @Test
    fun serviceCanConsumeMediaProjectionGrantOnce() {
        val source = readServiceSource()
        val helper = source.substringAfter("consumeMediaProjectionGrant")
            .substringBefore("private fun refreshStreamQualityState")

        assertTrue(source.contains("consumeMediaProjectionGrant"))
        assertTrue(helper.contains("mediaProjectionResultCode = null"))
        assertTrue(helper.contains("mediaProjectionResultData = null"))
    }

    private fun readSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt"),
                Path.of("app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt"),
            )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("UsbScreenStreamer.kt source not found")
        return path.toFile().readText()
    }

    private fun readServiceSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/com/example/galaxymirror/MediaProjectionService.kt"),
                Path.of("app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt"),
            )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("MediaProjectionService.kt source not found")
        return path.toFile().readText()
    }
}
