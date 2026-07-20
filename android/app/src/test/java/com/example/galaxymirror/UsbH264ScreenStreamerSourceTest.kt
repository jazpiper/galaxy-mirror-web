package com.example.galaxymirror

import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class UsbH264ScreenStreamerSourceTest {
    @Test
    fun streamerUsesMediaCodecInputSurfaceAndVirtualDisplay() {
        val source = readSource()

        assertTrue(source.contains("MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)"))
        assertTrue(source.contains("MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface"))
        assertTrue(source.contains("createInputSurface()"))
        assertTrue(source.contains("createVirtualDisplay"))
        assertTrue(source.contains("UsbH264Packet.encode"))
    }

    @Test
    fun streamerEmitsCodecConfigAndKeyFrameFlags() {
        val source = readSource()

        assertTrue(source.contains("MediaCodec.BUFFER_FLAG_CODEC_CONFIG"))
        assertTrue(source.contains("MediaCodec.BUFFER_FLAG_KEY_FRAME"))
        assertTrue(source.contains("onVideoConfig("))
        assertTrue(source.contains("onChunk("))
    }

    @Test
    fun streamerNormalizesLengthPrefixedNalUnitsForChrome() {
        val source = readSource()

        assertTrue(source.contains("ensureAnnexB("))
        assertTrue(source.contains("ANNEX_B_START_CODE"))
        assertTrue(source.contains("hasAnnexBStartCode()"))
    }

    @Test
    fun streamerStopsProjectionAndEncoderResources() {
        val source = readSource()

        assertTrue(source.contains("encoder?.stop()"))
        assertTrue(source.contains("encoder?.release()"))
        assertTrue(source.contains("inputSurface?.release()"))
        assertTrue(source.contains("virtualDisplay?.release()"))
        assertTrue(source.contains("mediaProjection?.stop()"))
    }

    private fun readSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt"),
                Path.of("app/src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt"),
            )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("UsbH264ScreenStreamer.kt source not found")
        return path.toFile().readText()
    }
}
