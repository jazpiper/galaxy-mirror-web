# USB H264 WebCodecs Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** USB 미러링의 기본 전송 경로를 Android 하드웨어 H.264 인코더와 Chrome WebCodecs 디코더로 바꿔 8fps JPEG보다 선명하고 부드럽게 만들고, 발열을 낮춘다.

**Architecture:** 기존 `/usb/session` WebSocket은 유지하되 `codec=h264` 협상을 추가한다. Android는 `MediaProjection -> VirtualDisplay Surface -> MediaCodec H.264`로 인코딩하고, Chrome은 `VideoDecoder`로 받은 chunk를 디코딩해 기존 `usbCanvas`에 그린다. 현재 JPEG 경로는 encoder 또는 WebCodecs 실패 시의 로컬 fallback으로만 남긴다.

**Tech Stack:** Android Kotlin, MediaProjection, VirtualDisplay, MediaCodec AVC encoder, Ktor WebSocket, Chrome WebCodecs `VideoDecoder`, JavaScript canvas, Gradle unit tests, Node JS syntax/tests, real-device `adb forward`.

---

## Current Context

- 현재 USB 경로는 `android/app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt`에서 `ImageReader` RGBA frame을 `Bitmap`으로 복사하고 `Bitmap.CompressFormat.JPEG`로 압축한 뒤 binary WebSocket frame으로 보낸다.
- Chrome UI는 `android/app/src/main/resources/files/viewer.js`의 `connectUsbSession()`에서 `/usb/session`을 열고, binary payload를 `renderUsbFrame(blob)`으로 넘겨 `createImageBitmap(blob)`으로 그린다.
- USB profile은 `android/app/src/main/java/com/example/galaxymirror/UsbStreamProfile.kt` 기준 `COOL 360x800 4fps q50`, `BALANCED 540x1200 8fps q60`, `CLEAR 720x1600 10fps q68`이다.
- 사용 환경은 Chrome-only, USB-only가 주 사용 경로다. 따라서 WebCodecs를 1순위로 쓰고 MSE, Safari, Firefox 호환 경로는 만들지 않는다.

## Target UX And Performance

- 기본 USB 접속 URL은 계속 `http://127.0.0.1:8080/?transport=usb`이다.
- Chrome이 WebCodecs를 지원하면 viewer가 자동으로 `ws://127.0.0.1:8080/usb/session?codec=h264`를 연다.
- Android H.264 encoder 시작에 실패하거나 Chrome `VideoDecoder.isConfigSupported()`가 실패하면 같은 버튼 흐름에서 JPEG fallback으로 재연결한다.
- 목표 기본값은 `BALANCED_H264 720x1600 24fps 3Mbps`이다.
- 실제 기기 smoke 기준은 5분 이상 지속 스트리밍, `/debug/perf` delivered FPS 20 이상, decoder queue 2 이하 유지, 조작 입력 정상, 토큰 없는 URL 유지다.

## File Structure

- Create `android/app/src/main/java/com/example/galaxymirror/UsbVideoCodec.kt`
  - USB session codec negotiation enum을 담당한다.
- Create `android/app/src/main/java/com/example/galaxymirror/UsbH264StreamProfile.kt`
  - H.264 전용 width, height, fps, bitrate, keyframe interval profile을 담당한다.
- Create `android/app/src/main/java/com/example/galaxymirror/UsbH264Packet.kt`
  - WebSocket binary packet header, keyframe flag, timestamp, payload encode/decode helper를 담당한다.
- Create `android/app/src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt`
  - `MediaCodec` input surface를 `VirtualDisplay`에 연결하고 encoded chunk를 drain한다.
- Modify `android/app/src/main/java/com/example/galaxymirror/UsbPerfMonitor.kt`
  - codec, bitrate, decoder/backpressure 관련 counters를 status JSON에 포함한다.
- Modify `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - `/usb/session?codec=h264` negotiation, H.264 streamer start/stop, JPEG fallback, `USB_VIDEO_CONFIG` text frame 전송을 담당한다.
- Modify `android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt`
  - transport ownership에서 H.264 USB session을 구분해야 하면 `USB_H264`를 추가한다. UI의 transport query는 계속 `usb`다.
- Modify `android/app/src/main/resources/files/viewer.js`
  - Chrome WebCodecs feature detection, H.264 WebSocket URL, `USB_VIDEO_CONFIG`, binary chunk decode, canvas rendering, JPEG fallback을 담당한다.
- Modify `android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt`
  - H.264 profile ladder를 검증한다.
- Create `android/app/src/test/java/com/example/galaxymirror/UsbH264PacketTest.kt`
  - packet header round-trip과 keyframe/timestamp decode를 검증한다.
- Create `android/app/src/test/java/com/example/galaxymirror/UsbH264ScreenStreamerSourceTest.kt`
  - `MediaCodec`, `COLOR_FormatSurface`, `createInputSurface`, `BUFFER_FLAG_KEY_FRAME`, `BUFFER_FLAG_CODEC_CONFIG` 사용을 source-level로 검증한다.
- Modify `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`
  - USB H.264 session ownership과 cleanup guard를 검증한다.
- Modify `android/app/src/test/js/viewer-keyboard.test.mjs`
  - `codec=h264` URL, `VideoDecoder` decode, fallback, UI status를 검증한다.
- Modify `docs/Protocols.md`
  - `/usb/session?codec=h264`, `USB_VIDEO_CONFIG`, H.264 binary frame header를 문서화한다.

## Protocol Decisions

### USB codec negotiation

Browser opens one of:

```text
ws://127.0.0.1:8080/usb/session?codec=h264
ws://127.0.0.1:8080/usb/session?codec=jpeg
```

Server behavior:

```kotlin
val requestedCodec = call.request.queryParameters["codec"]
val usbCodec = UsbVideoCodec.fromWireValue(requestedCodec)
```

`null`, unknown, or unsupported values resolve to `UsbVideoCodec.JPEG` only when H.264 cannot be started. Chrome viewer should request `h264` first.

### Text config message

Android sends this before encoded H.264 chunks:

```json
{
  "type": "USB_VIDEO_CONFIG",
  "payload": {
    "codec": "h264",
    "mime": "video/avc",
    "chunkFormat": "annexb",
    "codecString": "avc1.42E01F",
    "width": 720,
    "height": 1600,
    "fps": 24,
    "bitrateBps": 3000000,
    "keyFrameIntervalSeconds": 1
  }
}
```

### Binary H.264 packet

Each WebSocket binary message is one encoded sample:

```text
0..3   magic ASCII "GH26"
4      version, currently 1
5      codec, 1 = h264
6      flags, bit0 keyframe, bit1 codecConfig
7      reserved, 0
8..15  presentation timestamp micros, signed big-endian Int64
16..n  encoded Annex B H.264 bytes
```

Chrome builds:

```js
new EncodedVideoChunk({
    type: packet.keyFrame ? 'key' : 'delta',
    timestamp: packet.timestampUs,
    data: packet.payload,
});
```

## Task 1: Define USB Codec And H.264 Profiles

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbVideoCodec.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbH264StreamProfile.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt`

- [ ] **Step 1: Add failing tests for codec parsing and profile ladder**

Append to `android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt`:

```kotlin
@Test
fun usbVideoCodecParsesWireValuesAndDefaultsToH264ForChrome() {
    assertEquals(UsbVideoCodec.H264, UsbVideoCodec.fromWireValue("h264"))
    assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("jpeg"))
    assertEquals(UsbVideoCodec.H264, UsbVideoCodec.preferredForChrome())
    assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("unknown"))
}

@Test
fun h264ProfilesPreferHardwareFriendlyFpsAndBitrate() {
    val balanced = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.AUTO)
    val cool = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)
    val clear = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.HIGH)

    assertEquals(UsbH264StreamProfileTier.BALANCED, balanced.tier)
    assertEquals(720, balanced.width)
    assertEquals(1600, balanced.height)
    assertEquals(24, balanced.fps)
    assertEquals(3_000_000, balanced.bitrateBps)
    assertEquals(1, balanced.keyFrameIntervalSeconds)

    assertEquals(540, cool.width)
    assertEquals(1200, cool.height)
    assertEquals(18, cool.fps)
    assertEquals(1_800_000, cool.bitrateBps)

    assertEquals(1080, clear.width)
    assertEquals(2400, clear.height)
    assertEquals(30, clear.fps)
    assertEquals(6_000_000, clear.bitrateBps)
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbStreamProfileTest --no-daemon
```

Expected: compile failure mentioning unresolved `UsbVideoCodec`, `UsbH264StreamProfilePolicy`, or `UsbH264StreamProfileTier`.

- [ ] **Step 3: Create `UsbVideoCodec.kt`**

```kotlin
package com.example.galaxymirror

enum class UsbVideoCodec(
    val wireValue: String,
    val koreanLabel: String,
) {
    H264("h264", "H.264"),
    JPEG("jpeg", "JPEG");

    companion object {
        fun preferredForChrome(): UsbVideoCodec = H264

        fun fromWireValue(value: String?): UsbVideoCodec =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: JPEG
    }
}
```

- [ ] **Step 4: Create `UsbH264StreamProfile.kt`**

```kotlin
package com.example.galaxymirror

import org.json.JSONObject

data class UsbH264StreamProfile(
    val tier: UsbH264StreamProfileTier,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val keyFrameIntervalSeconds: Int = 1,
    val mime: String = "video/avc",
    val policy: String = "hardware-h264",
)

enum class UsbH264StreamProfileTier {
    COOL,
    BALANCED,
    CLEAR,
}

object UsbH264StreamProfilePolicy {
    fun resolve(selectedMode: StreamQualityMode): UsbH264StreamProfile =
        when (selectedMode) {
            StreamQualityMode.DATA_SAVER -> resolveTier(UsbH264StreamProfileTier.COOL)
            StreamQualityMode.HIGH -> resolveTier(UsbH264StreamProfileTier.CLEAR)
            StreamQualityMode.AUTO,
            StreamQualityMode.STANDARD -> resolveTier(UsbH264StreamProfileTier.BALANCED)
        }

    fun resolveTier(tier: UsbH264StreamProfileTier): UsbH264StreamProfile =
        when (tier) {
            UsbH264StreamProfileTier.COOL ->
                UsbH264StreamProfile(
                    tier = tier,
                    width = 540,
                    height = 1200,
                    fps = 18,
                    bitrateBps = 1_800_000,
                )
            UsbH264StreamProfileTier.BALANCED ->
                UsbH264StreamProfile(
                    tier = tier,
                    width = 720,
                    height = 1600,
                    fps = 24,
                    bitrateBps = 3_000_000,
                )
            UsbH264StreamProfileTier.CLEAR ->
                UsbH264StreamProfile(
                    tier = tier,
                    width = 1080,
                    height = 2400,
                    fps = 30,
                    bitrateBps = 6_000_000,
                )
        }
}

object UsbH264StreamProfileCodec {
    fun toStatusJson(
        selectedMode: StreamQualityMode,
        profile: UsbH264StreamProfile = UsbH264StreamProfilePolicy.resolve(selectedMode),
    ): String =
        JSONObject()
            .put("codec", UsbVideoCodec.H264.wireValue)
            .put("selectedMode", selectedMode.wireValue)
            .put("selectedLabel", selectedMode.koreanLabel)
            .put("effectiveTier", profile.tier.name)
            .put("effectiveWidth", profile.width)
            .put("effectiveHeight", profile.height)
            .put("effectiveFps", profile.fps)
            .put("width", profile.width)
            .put("height", profile.height)
            .put("fps", profile.fps)
            .put("bitrateBps", profile.bitrateBps)
            .put("keyFrameIntervalSeconds", profile.keyFrameIntervalSeconds)
            .put("mime", profile.mime)
            .put("policy", profile.policy)
            .toString()
}
```

- [ ] **Step 5: Run profile tests**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbStreamProfileTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbVideoCodec.kt android/app/src/main/java/com/example/galaxymirror/UsbH264StreamProfile.kt android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt
git commit -m "feat: USB H264 프로필 정의"
```

## Task 2: Add H.264 Packet Framing

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbH264Packet.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/UsbH264PacketTest.kt`

- [ ] **Step 1: Write packet tests**

Create `android/app/src/test/java/com/example/galaxymirror/UsbH264PacketTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class UsbH264PacketTest {
    @Test
    fun packetRoundTripsKeyFrameAndTimestamp() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0x02)
        val packet = UsbH264Packet.encode(
            payload = payload,
            presentationTimeUs = 123_456_789L,
            keyFrame = true,
            codecConfig = false,
        )

        val decoded = UsbH264Packet.decode(packet)

        assertEquals(1, decoded.version)
        assertEquals(123_456_789L, decoded.presentationTimeUs)
        assertTrue(decoded.keyFrame)
        assertFalse(decoded.codecConfig)
        assertEquals(payload.toList(), decoded.payload.toList())
    }

    @Test
    fun packetRejectsInvalidMagic() {
        val invalid = ByteArray(20)
        invalid[0] = 'B'.code.toByte()

        try {
            UsbH264Packet.decode(invalid)
            throw AssertionError("decode should reject invalid packet magic")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GH26"))
        }
    }
}
```

- [ ] **Step 2: Run packet tests and verify they fail**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbH264PacketTest --no-daemon
```

Expected: compile failure mentioning unresolved `UsbH264Packet`.

- [ ] **Step 3: Create packet helper**

Create `android/app/src/main/java/com/example/galaxymirror/UsbH264Packet.kt`:

```kotlin
package com.example.galaxymirror

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class UsbH264DecodedPacket(
    val version: Int,
    val presentationTimeUs: Long,
    val keyFrame: Boolean,
    val codecConfig: Boolean,
    val payload: ByteArray,
)

object UsbH264Packet {
    private val magic = byteArrayOf('G'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte(), '6'.code.toByte())
    private const val headerSize = 16
    private const val version = 1
    private const val codecH264 = 1
    private const val flagKeyFrame = 1
    private const val flagCodecConfig = 1 shl 1

    fun encode(
        payload: ByteArray,
        presentationTimeUs: Long,
        keyFrame: Boolean,
        codecConfig: Boolean,
    ): ByteArray {
        val output = ByteArray(headerSize + payload.size)
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(version.toByte())
        buffer.put(codecH264.toByte())
        var flags = 0
        if (keyFrame) flags = flags or flagKeyFrame
        if (codecConfig) flags = flags or flagCodecConfig
        buffer.put(flags.toByte())
        buffer.put(0)
        buffer.putLong(presentationTimeUs)
        buffer.put(payload)
        return output
    }

    fun decode(packet: ByteArray): UsbH264DecodedPacket {
        require(packet.size >= headerSize) { "USB H.264 packet is shorter than $headerSize bytes." }
        require(packet[0] == magic[0] && packet[1] == magic[1] && packet[2] == magic[2] && packet[3] == magic[3]) {
            "USB H.264 packet magic must be GH26."
        }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val decodedVersion = buffer.get().toInt() and 0xff
        val codec = buffer.get().toInt() and 0xff
        require(codec == codecH264) { "USB packet codec must be H.264." }
        val flags = buffer.get().toInt() and 0xff
        buffer.get()
        val presentationTimeUs = buffer.long
        val payload = packet.copyOfRange(headerSize, packet.size)
        return UsbH264DecodedPacket(
            version = decodedVersion,
            presentationTimeUs = presentationTimeUs,
            keyFrame = flags and flagKeyFrame != 0,
            codecConfig = flags and flagCodecConfig != 0,
            payload = payload,
        )
    }
}
```

- [ ] **Step 4: Run packet tests**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbH264PacketTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbH264Packet.kt android/app/src/test/java/com/example/galaxymirror/UsbH264PacketTest.kt
git commit -m "feat: USB H264 패킷 포맷 추가"
```

## Task 3: Implement Android H.264 Streamer

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/UsbH264ScreenStreamerSourceTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/UsbPerfMonitor.kt`

- [ ] **Step 1: Add source test for MediaCodec path**

Create `android/app/src/test/java/com/example/galaxymirror/UsbH264ScreenStreamerSourceTest.kt`:

```kotlin
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
    fun streamerStopsProjectionAndEncoderResources() {
        val source = readSource()

        assertTrue(source.contains("encoder.stop()"))
        assertTrue(source.contains("encoder.release()"))
        assertTrue(source.contains("inputSurface.release()"))
        assertTrue(source.contains("virtualDisplay.release()"))
        assertTrue(source.contains("mediaProjection.stop()"))
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
```

- [ ] **Step 2: Run source test and verify it fails**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbH264ScreenStreamerSourceTest --no-daemon
```

Expected: failure because `UsbH264ScreenStreamer.kt` does not exist.

- [ ] **Step 3: Implement `UsbH264ScreenStreamer.kt`**

Create the streamer with this public shape:

```kotlin
package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import org.json.JSONObject
import java.nio.ByteBuffer

class UsbH264ScreenStreamer(
    private val context: Context,
    private val onProjectionStopped: () -> Unit,
) {
    private val stateLock = Any()
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    @Volatile private var running = false
    @Volatile private var stopping = false

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun start(
        resultCode: Int,
        resultData: Intent,
        profileProvider: () -> UsbH264StreamProfile,
        perfMonitor: UsbPerfMonitor,
        onVideoConfig: (String) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        stop()
        val profile = profileProvider()
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection grant could not be created for USB H.264 stream.")
        val streamThread = HandlerThread("UsbH264ScreenStreamer").apply { start() }
        val streamHandler = Handler(streamThread.looper)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, profile.width, profile.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, profile.keyFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        val callback =
            object : MediaProjection.Callback() {
                override fun onStop() {
                    handleProjectionStopped()
                }
            }
        projection.registerCallback(callback, streamHandler)
        val display = projection.createVirtualDisplay(
            "GalaxyMirrorUsbH264ScreenStreamer",
            profile.width,
            profile.height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            streamHandler,
        ) ?: throw IllegalStateException("USB H.264 virtual display could not be created.")

        synchronized(stateLock) {
            handlerThread = streamThread
            handler = streamHandler
            mediaProjection = projection
            projectionCallback = callback
            virtualDisplay = display
            encoder = codec
            inputSurface = surface
            running = true
            stopping = false
        }

        onVideoConfig(buildVideoConfig(profile, "avc1.42E01F"))
        codec.start()
        streamHandler.post { drainEncoder(profileProvider, perfMonitor, onChunk) }
    }

    fun stop() {
        releaseResources(stopProjection = true)
    }

    fun isRunning(): Boolean = running

    private fun drainEncoder(
        profileProvider: () -> UsbH264StreamProfile,
        perfMonitor: UsbPerfMonitor,
        onChunk: (ByteArray) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val codec = encoder ?: break
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val startedAt = SystemClock.elapsedRealtime()
                    val encoded = copyEncodedBytes(outputBuffer, bufferInfo)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val packet = UsbH264Packet.encode(
                        payload = encoded,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        keyFrame = isKeyFrame,
                        codecConfig = isConfig,
                    )
                    onChunk(packet)
                    perfMonitor.recordFrameEncoded(
                        bytes = packet.size,
                        encodeMillis = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                codec.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    private fun copyEncodedBytes(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ): ByteArray {
        outputBuffer.position(bufferInfo.offset)
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
        val encoded = ByteArray(bufferInfo.size)
        outputBuffer.get(encoded)
        return encoded
    }

    private fun buildVideoConfig(
        profile: UsbH264StreamProfile,
        codecString: String,
    ): String =
        JSONObject()
            .put("type", "USB_VIDEO_CONFIG")
            .put(
                "payload",
                JSONObject()
                    .put("codec", UsbVideoCodec.H264.wireValue)
                    .put("mime", profile.mime)
                    .put("chunkFormat", "annexb")
                    .put("codecString", codecString)
                    .put("width", profile.width)
                    .put("height", profile.height)
                    .put("fps", profile.fps)
                    .put("bitrateBps", profile.bitrateBps)
                    .put("keyFrameIntervalSeconds", profile.keyFrameIntervalSeconds),
            )
            .toString()

    private fun handleProjectionStopped() {
        val shouldNotify =
            synchronized(stateLock) {
                if (stopping) false else {
                    stopping = true
                    running
                }
            }
        releaseResources(stopProjection = false)
        if (shouldNotify) onProjectionStopped()
    }

    private fun releaseResources(stopProjection: Boolean) {
        val resources =
            synchronized(stateLock) {
                running = false
                val snapshot = listOf(encoder, inputSurface, virtualDisplay, mediaProjection, projectionCallback, handlerThread)
                encoder = null
                inputSurface = null
                virtualDisplay = null
                mediaProjection = null
                projectionCallback = null
                handlerThread = null
                handler = null
                snapshot
            }
        val releasedEncoder = resources[0] as? MediaCodec
        val releasedSurface = resources[1] as? Surface
        val releasedDisplay = resources[2] as? VirtualDisplay
        val releasedProjection = resources[3] as? MediaProjection
        val releasedCallback = resources[4] as? MediaProjection.Callback
        val releasedThread = resources[5] as? HandlerThread

        try { releasedEncoder?.stop() } catch (_: Exception) {}
        try { releasedEncoder?.release() } catch (_: Exception) {}
        try { releasedSurface?.release() } catch (_: Exception) {}
        try { releasedDisplay?.release() } catch (_: Exception) {}
        try {
            if (releasedProjection != null && releasedCallback != null) {
                releasedProjection.unregisterCallback(releasedCallback)
            }
        } catch (_: Exception) {}
        try {
            if (stopProjection) releasedProjection?.stop()
        } catch (_: Exception) {}
        releasedThread?.quitSafely()
    }
}
```

- [ ] **Step 4: Run streamer source test**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbH264ScreenStreamerSourceTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run full compile-oriented unit suite**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt android/app/src/main/java/com/example/galaxymirror/UsbPerfMonitor.kt android/app/src/test/java/com/example/galaxymirror/UsbH264ScreenStreamerSourceTest.kt
git commit -m "feat: USB H264 스트리머 추가"
```

## Task 4: Wire H.264 Into `/usb/session`

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`

- [ ] **Step 1: Add failing session ownership tests**

Append to `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`:

```kotlin
@Test
fun h264UsbSessionUsesDedicatedTransportOwnership() {
    val state = MirrorSessionState().beginSession(22, MirrorTransport.USB_H264)

    assertTrue(state.isActive(22, MirrorTransport.USB_H264))
    assertFalse(state.isActive(22, MirrorTransport.USB_JPEG))
}

@Test
fun serviceRoutesUsbSessionCodecQueryToH264Streamer() {
    val source = readMediaProjectionServiceSource()

    assertTrue(source.contains("call.request.queryParameters[\"codec\"]"))
    assertTrue(source.contains("UsbVideoCodec.fromWireValue"))
    assertTrue(source.contains("UsbH264ScreenStreamer"))
    assertTrue(source.contains("USB_VIDEO_CONFIG"))
    assertTrue(source.contains("MirrorTransport.USB_H264"))
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

Expected: compile failure for unresolved `MirrorTransport.USB_H264` or assertion failure for missing service routing.

- [ ] **Step 3: Add transport enum**

Modify `android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt`:

```kotlin
enum class MirrorTransport(
    val wireValue: String,
    val koreanLabel: String,
) {
    TAILSCALE_WEBRTC("tailscale", "Tailscale"),
    USB_JPEG("usb_jpeg", "USB JPEG"),
    USB_H264("usb_h264", "USB H.264");
}
```

- [ ] **Step 4: Add service fields and factory**

In `MediaProjectionService.kt`, add fields near the existing `usbScreenStreamer` field:

```kotlin
private var usbH264ScreenStreamer: UsbH264ScreenStreamer? = null
@Volatile private var lastUsbCodec: UsbVideoCodec = UsbVideoCodec.JPEG
@Volatile private var lastUsbH264Profile = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.AUTO)
```

Add factory near `createUsbScreenStreamer`:

```kotlin
private fun createUsbH264ScreenStreamer(sessionId: Int): UsbH264ScreenStreamer =
    UsbH264ScreenStreamer(applicationContext) {
        serviceScope.launch {
            mirrorSessionState = mirrorSessionState.projectionStopped(sessionId)
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }
```

- [ ] **Step 5: Route `/usb/session` by codec**

Inside the existing `webSocket("/usb/session")` block, read the query before beginning the viewer session:

```kotlin
val requestedCodec = UsbVideoCodec.fromWireValue(call.request.queryParameters["codec"])
val activeTransport =
    when (requestedCodec) {
        UsbVideoCodec.H264 -> MirrorTransport.USB_H264
        UsbVideoCodec.JPEG -> MirrorTransport.USB_JPEG
    }
val sessionId = beginViewerSession(activeTransport)
lastUsbCodec = requestedCodec
```

Start the H.264 streamer when `requestedCodec == UsbVideoCodec.H264`:

```kotlin
val h264Streamer = createUsbH264ScreenStreamer(sessionId)
usbH264ScreenStreamer = h264Streamer
h264Streamer.start(
    resultCode = grant.resultCode,
    resultData = grant.resultData,
    profileProvider = {
        UsbH264StreamProfilePolicy.resolve(streamQualityMode).also {
            lastUsbH264Profile = it
        }
    },
    perfMonitor = usbPerfMonitor,
    onVideoConfig = { configJson ->
        outgoing.trySend(Frame.Text(configJson))
    },
    onChunk = { chunkBytes ->
        outgoing.trySend(Frame.Binary(fin = true, data = chunkBytes))
    },
)
```

Keep the current JPEG branch unchanged except for setting `lastUsbCodec = UsbVideoCodec.JPEG`.

- [ ] **Step 6: Include codec in USB status**

In `buildUsbStatusMessage`, choose status JSON by `lastUsbCodec`:

```kotlin
val streamQualityJson =
    if (lastUsbCodec == UsbVideoCodec.H264) {
        JSONObject(UsbH264StreamProfileCodec.toStatusJson(streamQualityMode, lastUsbH264Profile))
    } else {
        JSONObject(UsbStreamProfileCodec.toStatusJson(streamQualityMode))
            .put("codec", UsbVideoCodec.JPEG.wireValue)
            .put("effectiveTier", lastUsbProfile.tier.name)
            .put("effectiveWidth", lastUsbProfile.width)
            .put("effectiveHeight", lastUsbProfile.height)
            .put("effectiveFps", lastUsbProfile.fps)
            .put("width", lastUsbProfile.width)
            .put("height", lastUsbProfile.height)
            .put("fps", lastUsbProfile.fps)
            .put("jpegQuality", lastUsbProfile.jpegQuality)
            .put("policy", lastUsbProfile.policy)
    }
```

Set `transportWireValue` from the active codec:

```kotlin
val transportWireValue =
    if (lastUsbCodec == UsbVideoCodec.H264) {
        MirrorTransport.USB_H264.wireValue
    } else {
        MirrorTransport.USB_JPEG.wireValue
    }
```

- [ ] **Step 7: Stop both USB streamers during replacement and cleanup**

Where existing cleanup checks `MirrorTransport.USB_JPEG`, add H.264:

```kotlin
when (previousTransport) {
    MirrorTransport.USB_JPEG -> usbScreenStreamer.stop()
    MirrorTransport.USB_H264 -> usbH264ScreenStreamer?.stop()
    MirrorTransport.TAILSCALE_WEBRTC,
    null -> Unit
}
```

In USB session `finally`, stop only the active codec-owned streamer:

```kotlin
when (activeTransport) {
    MirrorTransport.USB_JPEG ->
        if (isActiveSession(sessionId, MirrorTransport.USB_JPEG)) usbScreenStreamer.stop()
    MirrorTransport.USB_H264 ->
        if (isActiveSession(sessionId, MirrorTransport.USB_H264)) usbH264ScreenStreamer?.stop()
    MirrorTransport.TAILSCALE_WEBRTC -> Unit
}
```

- [ ] **Step 8: Run session tests**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt
git commit -m "feat: USB 세션에 H264 협상 연결"
```

## Task 5: Add Chrome WebCodecs Decoder

**Files:**
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Add JS tests for codec URL and decoder path**

Append tests to `android/app/src/test/js/viewer-keyboard.test.mjs` using the existing WebSocket and DOM stubs:

```js
await test('USB session requests H264 codec when Chrome WebCodecs is available', async () => {
    window.VideoDecoder = function VideoDecoder() {};
    window.VideoDecoder.isConfigSupported = async () => ({ supported: true });
    window.EncodedVideoChunk = function EncodedVideoChunk() {};

    selectedTransport = 'usb';
    connectMirror();

    assert.ok(webSocketInstances[0].url.includes('/usb/session?codec=h264'));
});

await test('USB video config creates WebCodecs decoder and binary chunks decode', async () => {
    const decodedChunks = [];
    window.EncodedVideoChunk = function EncodedVideoChunk(init) {
        this.init = init;
    };
    window.VideoDecoder = class VideoDecoder {
        static async isConfigSupported() {
            return { supported: true };
        }
        constructor(init) {
            this.init = init;
            this.decodeQueueSize = 0;
        }
        configure(config) {
            this.config = config;
        }
        decode(chunk) {
            decodedChunks.push(chunk.init);
        }
        close() {}
    };

    selectedTransport = 'usb';
    connectMirror();
    webSocketInstances[0].onmessage({
        data: JSON.stringify({
            type: 'USB_VIDEO_CONFIG',
            payload: {
                codec: 'h264',
                codecString: 'avc1.42E01F',
                width: 720,
                height: 1600,
                fps: 24,
            },
        }),
    });

    const packet = new Uint8Array(20);
    packet[0] = 'G'.charCodeAt(0);
    packet[1] = 'H'.charCodeAt(0);
    packet[2] = '2'.charCodeAt(0);
    packet[3] = '6'.charCodeAt(0);
    packet[4] = 1;
    packet[5] = 1;
    packet[6] = 1;
    packet[15] = 7;
    packet[16] = 0;
    packet[17] = 0;
    packet[18] = 0;
    packet[19] = 1;
    webSocketInstances[0].onmessage({ data: packet.buffer });

    assert.equal(decodedChunks.length, 1);
    assert.equal(decodedChunks[0].type, 'key');
    assert.equal(decodedChunks[0].timestamp, 7);
});
```

- [ ] **Step 2: Run JS tests and verify they fail**

Run:

```bash
node android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: failure because `/usb/session` does not append `codec=h264` and `USB_VIDEO_CONFIG` is ignored.

- [ ] **Step 3: Add decoder state and feature detection**

In `viewer.js`, add near other USB state variables:

```js
let usbVideoDecoder = null;
let usbVideoConfig = null;
let usbCodecMode = 'jpeg';
let usbDroppedDeltaFrames = 0;

function supportsUsbH264WebCodecs() {
    return typeof window.VideoDecoder !== 'undefined' &&
        typeof window.EncodedVideoChunk !== 'undefined';
}
```

Update `usbSessionUrl()`:

```js
function usbSessionUrl(codecOverride) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const codec = codecOverride || (supportsUsbH264WebCodecs() ? 'h264' : 'jpeg');
    return `${protocol}//${window.location.host}/usb/session?codec=${encodeURIComponent(codec)}`;
}
```

In `connectUsbSession()` before opening the socket:

```js
usbCodecMode = supportsUsbH264WebCodecs() ? 'h264' : 'jpeg';
const wsUrl = usbSessionUrl(usbCodecMode);
```

Set binary type for H.264:

```js
sessionSocket.binaryType = usbCodecMode === 'h264' ? 'arraybuffer' : 'blob';
```

- [ ] **Step 4: Handle `USB_VIDEO_CONFIG`**

In `handleUsbTextMessage(text)`, before `CONTROL_ACK`:

```js
if (message.type === 'USB_VIDEO_CONFIG') {
    configureUsbVideoDecoder(message.payload || {});
    return;
}
```

Add:

```js
async function configureUsbVideoDecoder(payload) {
    closeUsbVideoDecoder();
    usbVideoConfig = payload;
    const config = {
        codec: payload.codecString || 'avc1.42E01F',
        codedWidth: payload.width,
        codedHeight: payload.height,
        optimizeForLatency: true,
    };
    const support = await window.VideoDecoder.isConfigSupported(config);
    if (!support.supported) {
        log('Chrome WebCodecs H.264 config unsupported; reconnecting with JPEG.');
        reconnectUsbAsJpeg();
        return;
    }
    usbVideoDecoder = new window.VideoDecoder({
        output: drawUsbVideoFrame,
        error: (error) => {
            log(`USB H.264 decoder error: ${error.message}`);
            reconnectUsbAsJpeg();
        },
    });
    usbVideoDecoder.configure(config);
    rtcStatus.innerText = 'USB H.264 준비';
}

function closeUsbVideoDecoder() {
    if (usbVideoDecoder) {
        try {
            usbVideoDecoder.close();
        } catch (error) {
            log(`USB H.264 decoder close failed: ${error.message}`);
        }
    }
    usbVideoDecoder = null;
    usbVideoConfig = null;
    usbDroppedDeltaFrames = 0;
}

function reconnectUsbAsJpeg() {
    if (usbCodecMode === 'jpeg') return;
    usbCodecMode = 'jpeg';
    closeUsbVideoDecoder();
    connectUsbSession();
}
```

- [ ] **Step 5: Parse and decode H.264 binary chunks**

In `sessionSocket.onmessage`, replace the binary branch:

```js
if (usbCodecMode === 'h264') {
    renderUsbH264Chunk(event.data);
} else {
    renderUsbFrame(event.data);
}
```

Add:

```js
function parseUsbH264Packet(data) {
    const bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer);
    if (bytes.length < 16) throw new Error('USB H.264 packet too short');
    if (bytes[0] !== 71 || bytes[1] !== 72 || bytes[2] !== 50 || bytes[3] !== 54) {
        throw new Error('USB H.264 packet magic must be GH26');
    }
    const flags = bytes[6];
    const timestampView = new DataView(bytes.buffer, bytes.byteOffset + 8, 8);
    const timestampUs = Number(timestampView.getBigInt64(0, false));
    return {
        keyFrame: (flags & 1) !== 0,
        codecConfig: (flags & 2) !== 0,
        timestampUs,
        payload: bytes.slice(16),
    };
}

function renderUsbH264Chunk(data) {
    if (!usbVideoDecoder || selectedTransport !== 'usb') return;
    try {
        const packet = parseUsbH264Packet(data);
        if (!packet.keyFrame && usbVideoDecoder.decodeQueueSize > 2) {
            usbDroppedDeltaFrames += 1;
            return;
        }
        const chunk = new window.EncodedVideoChunk({
            type: packet.keyFrame ? 'key' : 'delta',
            timestamp: packet.timestampUs,
            data: packet.payload,
        });
        accumulatedNetworkBytes.received += packet.payload.byteLength;
        updateDataUsageDisplay();
        usbVideoDecoder.decode(chunk);
        rtcStatus.innerText = 'USB H.264 스트리밍';
    } catch (error) {
        log(`USB H.264 chunk decode failed: ${error.message}`);
    }
}
```

- [ ] **Step 6: Draw decoded `VideoFrame` to canvas**

Add:

```js
function drawUsbVideoFrame(frame) {
    if (!usbCanvas || selectedTransport !== 'usb') {
        frame.close();
        return;
    }
    hideConnectionPlaceholder();
    usbCanvas.classList.remove('hidden');
    usbFrame?.classList.add('hidden');
    remoteVideo?.classList.add('hidden');

    if (usbCanvas.width !== frame.codedWidth || usbCanvas.height !== frame.codedHeight) {
        usbCanvas.width = frame.codedWidth;
        usbCanvas.height = frame.codedHeight;
        const videoContainer = document.getElementById('videoContainer');
        if (videoContainer) {
            videoContainer.style.aspectRatio = `${frame.codedWidth} / ${frame.codedHeight}`;
        }
    }

    const ctx = usbCanvas.getContext('2d');
    ctx.drawImage(frame, 0, 0);
    frame.close();
}
```

In `sessionSocket.onclose`, call:

```js
closeUsbVideoDecoder();
```

- [ ] **Step 7: Run JS syntax and tests**

Run:

```bash
node --check android/app/src/main/resources/files/viewer.js
node android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: both commands pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/resources/files/viewer.js android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "feat: Chrome WebCodecs USB 디코더 추가"
```

## Task 6: Update Status UI And Perf JSON

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/UsbPerfMonitor.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Add JS status rendering test**

Append to `viewer-keyboard.test.mjs`:

```js
await test('renders USB H264 codec bitrate and fps in stream quality status', () => {
    renderStreamQualityStatus({
        codec: 'h264',
        effectiveTier: 'BALANCED',
        width: 720,
        height: 1600,
        fps: 24,
        bitrateBps: 3000000,
    });

    assert.ok(streamQualityStatus.innerText.includes('H.264'));
    assert.ok(streamQualityStatus.innerText.includes('720x1600'));
    assert.ok(streamQualityStatus.innerText.includes('24fps'));
    assert.ok(streamQualityStatus.innerText.includes('3.0Mbps'));
});
```

- [ ] **Step 2: Run JS test and verify it fails**

Run:

```bash
node android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: failure because status text does not include H.264 bitrate.

- [ ] **Step 3: Update stream quality formatting**

In `viewer.js`, update `renderStreamQualityStatus(profile)` so H.264 displays compactly:

```js
const codecLabel = profile.codec === 'h264' ? 'H.264' : 'JPEG';
const bitrateLabel = profile.bitrateBps
    ? ` ${(profile.bitrateBps / 1000000).toFixed(1)}Mbps`
    : '';
streamQualityStatus.innerText =
    `${codecLabel} ${profile.width}x${profile.height} ${profile.fps}fps${bitrateLabel}`;
```

Keep the existing tier/mode labels if they are currently rendered elsewhere, but avoid long single-line text inside the small left panel. Prefer two short lines if necessary:

```js
streamQualityStatus.innerHTML =
    `<span>${codecLabel} ${profile.width}x${profile.height}</span><span>${profile.fps}fps${bitrateLabel}</span>`;
```

- [ ] **Step 4: Add perf fields**

In `UsbPerfMonitor.kt`, add snapshot fields:

```kotlin
val codec: String = lastCodec,
val bitrateBps: Int? = lastBitrateBps,
val decoderDroppedFrames: Long = decoderDroppedFrames,
```

Add a method for MediaProjectionService to update codec state:

```kotlin
fun recordCodec(
    codec: UsbVideoCodec,
    bitrateBps: Int?,
) {
    lastCodec = codec.wireValue
    lastBitrateBps = bitrateBps
}
```

Call it after resolving USB profile:

```kotlin
usbPerfMonitor.recordCodec(UsbVideoCodec.H264, lastUsbH264Profile.bitrateBps)
```

and for JPEG:

```kotlin
usbPerfMonitor.recordCodec(UsbVideoCodec.JPEG, null)
```

- [ ] **Step 5: Run JS and unit tests**

Run:

```bash
node --check android/app/src/main/resources/files/viewer.js
node android/app/src/test/js/viewer-keyboard.test.mjs
cd android && ./gradlew app:testDebugUnitTest --no-daemon
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbPerfMonitor.kt android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt android/app/src/main/resources/files/viewer.js android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "feat: USB H264 상태 표시 개선"
```

## Task 7: Real Device Verification And Fallback Hardening

**Files:**
- Modify only if verification exposes a bug:
  - `android/app/src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - `android/app/src/main/resources/files/viewer.js`

- [ ] **Step 1: Build and install**

Run:

```bash
cd android && ./gradlew app:testDebugUnitTest app:assembleDebug app:installDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL` and install succeeds on the connected device.

- [ ] **Step 2: Start app and USB forward**

Run:

```bash
adb shell am start -n com.example.galaxymirror/.MainActivity
adb forward tcp:8080 tcp:8080
```

Expected: app opens on Android and `adb forward` exits 0.

- [ ] **Step 3: Verify tokenless local UI still loads**

Run:

```bash
curl -fsS http://127.0.0.1:8080/?transport=usb | head -n 5
curl -fsS http://127.0.0.1:8080/viewer.js | rg "token|VideoDecoder|codec=h264"
```

Expected: HTML returns, `viewer.js` contains `VideoDecoder` and `codec=h264`, and has no required URL token check.

- [ ] **Step 4: Smoke in Chrome**

Open:

```text
http://127.0.0.1:8080/?transport=usb
```

Expected:

- Button connects without token.
- Android screen capture approval starts stream.
- Chrome DevTools console has no `USB H.264 decoder error`.
- First H.264 payload starts with a valid Annex B NAL representation for Chrome on this device. If Chrome rejects the chunk, inspect the first 8 bytes in DevTools; the implementation should convert length-prefixed NAL units to `00 00 00 01` start-code NAL units before `UsbH264Packet.encode`.
- Left status shows `H.264 720x1600 24fps 3.0Mbps` or the active thermal-clamped equivalent.
- Canvas updates smoothly and does not leave the last frame visible after disconnect.
- Touch, swipe, keyboard input still work.

- [ ] **Step 5: Verify perf endpoint**

Run while streaming:

```bash
curl -fsS http://127.0.0.1:8080/debug/perf
```

Expected JSON includes:

```json
{
  "codec": "h264",
  "bitrateBps": 3000000,
  "profile": {
    "fps": 24
  }
}
```

Exact counter names may follow the current `UsbPerfMonitor` JSON shape, but the endpoint must clearly expose codec, fps, bitrate, emitted bytes, encode timing, and thermal status.

- [ ] **Step 6: Test fallback**

Temporarily force WebCodecs unsupported in Chrome DevTools console:

```js
delete window.VideoDecoder
```

Reload:

```text
http://127.0.0.1:8080/?transport=usb
```

Expected:

- WebSocket URL uses `codec=jpeg`.
- Existing JPEG path still streams.
- UI status says `JPEG`.

- [ ] **Step 7: Commit verification fixes**

Only if code changed during this task:

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbH264ScreenStreamer.kt android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt android/app/src/main/resources/files/viewer.js
git commit -m "fix: USB H264 실기기 검증 이슈 수정"
```

## Task 8: Document Protocol And Operational Notes

**Files:**
- Modify: `docs/Protocols.md`
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`

- [ ] **Step 1: Update protocol doc**

Add to `docs/Protocols.md`:

```markdown
## USB H.264 Session

Chrome viewer opens `ws://127.0.0.1:8080/usb/session?codec=h264` when WebCodecs is available.
The Android app sends `USB_VIDEO_CONFIG` before H.264 binary chunks.

Binary chunks use a 16-byte header:

| Offset | Value |
| --- | --- |
| 0..3 | ASCII `GH26` |
| 4 | version, currently `1` |
| 5 | codec, `1` means H.264 |
| 6 | flags, bit0 keyframe, bit1 codec config |
| 7 | reserved |
| 8..15 | presentation timestamp in microseconds, big-endian signed Int64 |
| 16..n | Annex B H.264 payload verified against Chrome WebCodecs |

JPEG remains available through `ws://127.0.0.1:8080/usb/session?codec=jpeg` as a local fallback.
```

- [ ] **Step 2: Update handoff**

Add to `docs/Handoff.md`:

```markdown
## USB H.264 Phase 2

- Default Chrome USB path prefers H.264 via `?codec=h264`.
- JPEG path remains as fallback for encoder or WebCodecs failure.
- Real-device verification command set:
  - `adb forward tcp:8080 tcp:8080`
  - `http://127.0.0.1:8080/?transport=usb`
  - `curl -fsS http://127.0.0.1:8080/debug/perf`
```

- [ ] **Step 3: Update log**

Add to `docs/Log.md`:

```markdown
## 2026-07-06 USB H.264 Phase 2 Plan

- Recommended Chrome-only path: Android `MediaCodec` H.264 encoder over USB WebSocket, Chrome WebCodecs decoder.
- Target default: 720x1600, 24fps, 3Mbps.
- Fallback: current JPEG transport through `codec=jpeg`.
```

- [ ] **Step 4: Commit docs**

```bash
git add docs/Protocols.md docs/Handoff.md docs/Log.md
git commit -m "docs: USB H264 프로토콜 문서화"
```

## Final Verification Checklist

- [ ] `node --check android/app/src/main/resources/files/viewer.js`
- [ ] `node android/app/src/test/js/viewer-keyboard.test.mjs`
- [ ] `cd android && ./gradlew app:testDebugUnitTest app:assembleDebug app:installDebug --no-daemon`
- [ ] `adb forward tcp:8080 tcp:8080`
- [ ] Chrome opens `http://127.0.0.1:8080/?transport=usb`
- [ ] Chrome DevTools network shows `/usb/session?codec=h264`
- [ ] `/debug/perf` shows `codec=h264`, bitrate, fps, thermal state
- [ ] Disconnect clears the last frame and shows disconnected copy
- [ ] JPEG fallback works with `?codec=jpeg`

## Self-Review Notes

- Spec coverage: Chrome-only requirement is covered by WebCodecs-first negotiation and no MSE/Safari/Firefox work. USB H.264 path is covered by Android streamer, service routing, browser decoder, perf/status, real-device verification, and protocol docs.
- Placeholder scan: The plan avoids deferred implementation markers and includes concrete file paths, code shapes, commands, and expected outputs.
- Type consistency: `UsbVideoCodec.H264/JPEG`, `UsbH264StreamProfilePolicy`, `UsbH264Packet`, `UsbH264ScreenStreamer`, `MirrorTransport.USB_H264`, `USB_VIDEO_CONFIG`, and `codec=h264` are used consistently across tasks.
