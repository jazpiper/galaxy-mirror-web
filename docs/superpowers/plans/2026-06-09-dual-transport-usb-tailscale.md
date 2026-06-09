# Dual Transport USB And Tailscale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Galaxy Mirror Web에서 기존 Tailscale/WebRTC 연결과 USB/ADB 연결을 모두 지원하고, Android 앱과 Mac Viewer에서 선택적으로 연결할 수 있게 만든다.

**Architecture:** 기존 Tailscale 모드는 `/signaling` WebSocket + WebRTC video + DataChannel control 경로를 유지한다. USB 모드는 Mac에서 `adb forward tcp:8080 tcp:8080`을 건 뒤 `http://127.0.0.1:8080/?token=...&transport=usb`로 접속하고, `/usb/session` WebSocket에서 Android가 JPEG binary frame을 보내며 같은 WebSocket text frame으로 STATUS/CONTROL_ACK/control event를 주고받는다. 한 번에 하나의 viewer transport만 활성화하고, transport 전환은 기존 세션과 capture pipeline을 정리한 뒤 Android MediaProjection 재승인을 요구한다.

**Tech Stack:** Android Kotlin, Ktor CIO WebSocket, Android MediaProjection, ImageReader, VirtualDisplay, Bitmap JPEG encoding, Jetpack Compose Material3, Vanilla HTML/JavaScript, Node VM tests, JUnit4.

---

## Operating Model

- Tailscale mode:
  - Browser URL: `http://<Android MagicDNS>:8080/?token=<token>&transport=tailscale`
  - Signaling endpoint: `ws://<Android MagicDNS>:8080/signaling?token=<token>`
  - Video/control: current WebRTC video track and `control` DataChannel.
- USB mode:
  - Mac command before opening the viewer:

    ```bash
    adb forward --remove tcp:8080 || true
    adb forward tcp:8080 tcp:8080
    ```

  - Browser URL: `http://127.0.0.1:8080/?token=<token>&transport=usb`
  - Session endpoint: `ws://127.0.0.1:8080/usb/session?token=<token>`
  - Video/control: binary JPEG frames from Android to browser, JSON text control events from browser to Android, JSON text ACK/status from Android to browser.
- Session policy:
  - Exactly one active session is allowed across both transports.
  - Starting USB while Tailscale is active closes WebRTC resources and requests fresh MediaProjection approval.
  - Starting Tailscale while USB is active closes the USB WebSocket, stops the USB streamer, and follows the existing WebRTC permission path.
  - Remote input requires the existing Android AccessibilityService in both modes.

## File Structure

- Create: `android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt`
  - Defines wire names and labels for `TAILSCALE_WEBRTC` and `USB_JPEG`.
- Modify: `android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt`
  - Tracks the active transport along with the active session id.
- Create: `android/app/src/main/java/com/example/galaxymirror/ControlEventApplier.kt`
  - Interface implemented by `GalaxyMirrorAccessibilityService` so WebRTC and USB can share control dispatch.
- Create: `android/app/src/main/java/com/example/galaxymirror/ControlEventDispatcher.kt`
  - Parses, validates, applies, and ACKs control JSON for both transports.
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbStreamProfile.kt`
  - Maps existing stream quality modes to USB JPEG width/height/fps/jpegQuality.
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbFrameRateGate.kt`
  - Pure helper that throttles ImageReader frames to the selected USB FPS.
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt`
  - Owns USB ImageReader, VirtualDisplay, MediaProjection, JPEG encoding thread, and frame listener.
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - Stores `mediaProjectionResultCode`, exposes `/usb/session`, begins sessions with a transport, routes USB control events, and stops USB resources during cleanup.
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
  - Passes transport/session state to Compose and preserves the existing permission launcher path.
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
  - Shows both Tailscale URL and USB URL/ADB command in the Android UI.
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
  - Adds copy text for the two connection modes.
- Modify: `android/app/src/main/resources/files/index.html`
  - Adds transport buttons and a USB image surface beside the existing WebRTC video element.
- Modify: `android/app/src/main/resources/files/viewer.js`
  - Adds transport selection, USB WebSocket session handling, JPEG frame rendering, and transport-aware control sending.
- Modify: `docs/Protocols.md`
  - Documents USB session WebSocket frames and transport switching policy.
- Modify: `docs/Handoff.md`
  - Adds task status and manual USB smoke-check notes.
- Modify: `docs/Log.md`
  - Records the dual transport implementation summary.

---

### Task 1: Transport Domain And Session State

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`

- [x] **Step 1: Write the failing transport session tests**

Add these tests to `MirrorSessionStateTest`:

```kotlin
@Test
fun beginUsbSessionRecordsTransportAndClearsPendingOffer() {
    val state =
        MirrorSessionState()
            .beginSession(1, MirrorTransport.TAILSCALE_WEBRTC)
            .queueOffer(1)
            .beginSession(2, MirrorTransport.USB_JPEG)

    assertEquals(2, state.activeSessionId)
    assertEquals(MirrorTransport.USB_JPEG, state.activeTransport)
    assertFalse(state.hasPendingOffer)
    assertFalse(state.requiresScreenCaptureReauthorization)
}

@Test
fun endingUsbSessionClearsTransport() {
    val state =
        MirrorSessionState()
            .beginSession(9, MirrorTransport.USB_JPEG)
            .endSession(9)

    assertEquals(0, state.activeSessionId)
    assertNull(state.activeTransport)
}

@Test
fun activeCheckCanRequireTransportMatch() {
    val state = MirrorSessionState().beginSession(5, MirrorTransport.USB_JPEG)

    assertTrue(state.isActive(5, MirrorTransport.USB_JPEG))
    assertFalse(state.isActive(5, MirrorTransport.TAILSCALE_WEBRTC))
    assertFalse(state.isActive(4, MirrorTransport.USB_JPEG))
}
```

- [x] **Step 2: Run the focused test and verify it fails**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

Expected: FAIL because `MirrorTransport`, `activeTransport`, and the two-argument `beginSession`/`isActive` functions do not exist.

- [x] **Step 3: Add the transport enum**

Create `MirrorTransport.kt`:

```kotlin
package com.example.galaxymirror

enum class MirrorTransport(
    val wireValue: String,
    val koreanLabel: String,
) {
    TAILSCALE_WEBRTC("tailscale", "Tailscale"),
    USB_JPEG("usb", "USB");

    companion object {
        fun fromWireValue(value: String?): MirrorTransport? =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) }
    }
}
```

- [x] **Step 4: Update session state with active transport**

Replace `MirrorSessionState` with this shape, preserving the existing reauthorization behavior:

```kotlin
package com.example.galaxymirror

data class MirrorSessionState(
    val activeSessionId: Int = 0,
    val activeTransport: MirrorTransport? = null,
    val pendingOfferSessionId: Int? = null,
    val requiresScreenCaptureReauthorization: Boolean = false,
) {
    val hasPendingOffer: Boolean = pendingOfferSessionId != null

    fun beginSession(
        sessionId: Int,
        transport: MirrorTransport = MirrorTransport.TAILSCALE_WEBRTC,
    ): MirrorSessionState =
        copy(
            activeSessionId = sessionId,
            activeTransport = transport,
            pendingOfferSessionId = null,
            requiresScreenCaptureReauthorization = false,
        )

    fun endSession(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(activeSessionId = 0, activeTransport = null, pendingOfferSessionId = null)
        } else {
            this
        }

    fun queueOffer(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId && activeTransport == MirrorTransport.TAILSCALE_WEBRTC) {
            copy(pendingOfferSessionId = sessionId)
        } else {
            this
        }

    fun clearPendingOffer(): MirrorSessionState = copy(pendingOfferSessionId = null)

    fun projectionStopped(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(
                activeSessionId = 0,
                activeTransport = null,
                pendingOfferSessionId = null,
                requiresScreenCaptureReauthorization = true,
            )
        } else {
            this
        }

    fun isActive(sessionId: Int): Boolean = activeSessionId == sessionId

    fun isActive(sessionId: Int, transport: MirrorTransport): Boolean =
        activeSessionId == sessionId && activeTransport == transport
}
```

- [x] **Step 5: Run the focused test and verify it passes**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/MirrorTransport.kt \
  android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt \
  android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt
git commit -m "feat: 미러링 전송 방식 세션 상태 추가"
```

---

### Task 2: Shared Control Event Dispatcher

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/ControlEventApplier.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/ControlEventDispatcher.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/ControlEventDispatcherTest.kt`

- [x] **Step 1: Write dispatcher tests**

Create `ControlEventDispatcherTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.json.JSONObject
import org.junit.Test

class ControlEventDispatcherTest {
    @Test
    fun invalidJsonReturnsRejectedAck() {
        val result = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { FakeApplier() },
                onViewerActivity = {},
            )

        dispatcher.dispatch("""{"type":"tap","x":2,"y":0.5,"seq":8}""") { result += it }

        assertEquals(1, result.size)
        assertEquals(8L, result.single().seq)
        assertEquals(false, result.single().applied)
        assertEquals("CONTROL_EVENT_REJECTED", result.single().message)
    }

    @Test
    fun missingAccessibilityServiceReturnsNotReadyAck() {
        val result = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { null },
                onViewerActivity = {},
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4,"seq":3}""") { result += it }

        assertEquals(1, result.size)
        assertEquals(3L, result.single().seq)
        assertEquals("ACCESSIBILITY_SERVICE_NOT_READY", result.single().message)
    }

    @Test
    fun validControlEventMarksActivityAndDelegatesToApplier() {
        val applier = FakeApplier()
        var activityCount = 0
        val result = mutableListOf<ControlEventResult>()
        val dispatcher =
            ControlEventDispatcher(
                serviceProvider = { applier },
                onViewerActivity = { activityCount += 1 },
            )

        dispatcher.dispatch("""{"type":"key","keyCode":4,"seq":12}""") { result += it }

        assertEquals(1, activityCount)
        assertEquals("key", applier.lastJson?.getString("type"))
        assertEquals(12L, result.single().seq)
        assertEquals(true, result.single().applied)
        assertEquals("FAKE_APPLIED", result.single().message)
    }

    private class FakeApplier : ControlEventApplier {
        var lastJson: JSONObject? = null

        override fun handleControlEvent(
            json: JSONObject,
            resultCallback: (ControlEventResult) -> Unit,
        ) {
            lastJson = json
            resultCallback(
                ControlEventResult(
                    seq = json.controlSeq(),
                    type = json.optString("type", "unknown"),
                    applied = true,
                    message = "FAKE_APPLIED",
                ),
            )
        }
    }
}
```

- [x] **Step 2: Run the focused test and verify it fails**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ControlEventDispatcherTest --no-daemon
```

Expected: FAIL because `ControlEventApplier` and `ControlEventDispatcher` do not exist.

- [x] **Step 3: Add the applier interface**

Create `ControlEventApplier.kt`:

```kotlin
package com.example.galaxymirror

import org.json.JSONObject

interface ControlEventApplier {
    fun handleControlEvent(
        json: JSONObject,
        resultCallback: (ControlEventResult) -> Unit = {},
    )
}
```

- [x] **Step 4: Add the dispatcher**

Create `ControlEventDispatcher.kt`:

```kotlin
package com.example.galaxymirror

import android.util.Log
import org.json.JSONObject

class ControlEventDispatcher(
    private val serviceProvider: () -> ControlEventApplier?,
    private val onViewerActivity: () -> Unit,
) {
    fun dispatch(
        rawText: String,
        sendAck: (ControlEventResult) -> Unit,
    ) {
        try {
            val json = JSONObject(rawText)
            if (!ControlEventValidator.isValid(json)) {
                sendAck(
                    ControlEventResult(
                        seq = json.controlSeq(),
                        type = json.optString("type", "unknown"),
                        applied = false,
                        message = "CONTROL_EVENT_REJECTED",
                    ),
                )
                return
            }

            onViewerActivity()
            val service = serviceProvider()
            if (service == null) {
                sendAck(
                    ControlEventResult(
                        seq = json.controlSeq(),
                        type = json.optString("type", "unknown"),
                        applied = false,
                        message = "ACCESSIBILITY_SERVICE_NOT_READY",
                    ),
                )
                return
            }

            service.handleControlEvent(json) { result ->
                sendAck(result)
            }
        } catch (e: Exception) {
            Log.e("ControlEventDispatcher", "Control event dispatch failed: ${e.message}", e)
            sendAck(
                ControlEventResult(
                    seq = null,
                    type = "unknown",
                    applied = false,
                    message = "CONTROL_EVENT_EXCEPTION",
                ),
            )
        }
    }
}
```

- [x] **Step 5: Make the accessibility service implement the interface**

In `GalaxyMirrorAccessibilityService.kt`, change the class declaration:

```kotlin
class GalaxyMirrorAccessibilityService : AccessibilityService(), ControlEventApplier {
```

Change the existing `handleControlEvent` signature to:

```kotlin
override fun handleControlEvent(
    json: JSONObject,
    resultCallback: (ControlEventResult) -> Unit,
) {
```

- [x] **Step 6: Replace DataChannel control parsing with the dispatcher**

In `MediaProjectionService.kt`, add:

```kotlin
private val controlEventDispatcher =
    ControlEventDispatcher(
        serviceProvider = { GalaxyMirrorAccessibilityService.instance },
        onViewerActivity = { markViewerActivity() },
    )
```

Inside `DataChannel.Observer.onMessage`, replace the manual `JSONObject`/validator/service block with:

```kotlin
val bytes = ByteArray(buffer.data.remaining())
buffer.data.get(bytes)
val text = String(bytes, Charsets.UTF_8)
Log.d("WebRTC", "DataChannel message: $text")
controlEventDispatcher.dispatch(text) { result ->
    sendControlAck(dc, result)
}
```

- [x] **Step 7: Run dispatcher and existing control tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest \
  --tests com.example.galaxymirror.ControlEventDispatcherTest \
  --tests com.example.galaxymirror.ControlEventValidatorTest \
  --tests com.example.galaxymirror.ControlEventResultTest \
  --no-daemon
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/ControlEventApplier.kt \
  android/app/src/main/java/com/example/galaxymirror/ControlEventDispatcher.kt \
  android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt \
  android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt \
  android/app/src/test/java/com/example/galaxymirror/ControlEventDispatcherTest.kt
git commit -m "refactor: 원격 입력 처리 공통화"
```

---

### Task 3: USB Stream Profile And Frame Throttle

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbStreamProfile.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbFrameRateGate.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/UsbFrameRateGateTest.kt`

- [x] **Step 1: Write USB profile tests**

Create `UsbStreamProfileTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class UsbStreamProfileTest {
    @Test
    fun autoUsesStandardUsbProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO)

        assertEquals(720, profile.width)
        assertEquals(1600, profile.height)
        assertEquals(10, profile.fps)
        assertEquals(70, profile.jpegQuality)
    }

    @Test
    fun dataSaverUsesLowerUsbProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)

        assertEquals(540, profile.width)
        assertEquals(1200, profile.height)
        assertEquals(8, profile.fps)
        assertEquals(65, profile.jpegQuality)
    }

    @Test
    fun highUsesCappedUsbProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.HIGH)

        assertEquals(1080, profile.width)
        assertEquals(2400, profile.height)
        assertEquals(12, profile.fps)
        assertEquals(75, profile.jpegQuality)
    }
}
```

- [x] **Step 2: Write frame throttle tests**

Create `UsbFrameRateGateTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class UsbFrameRateGateTest {
    @Test
    fun acceptsFirstFrameImmediately() {
        val gate = UsbFrameRateGate(fps = 10)

        assertTrue(gate.shouldEmit(nowNanos = 1_000L))
    }

    @Test
    fun rejectsFramesBeforeInterval() {
        val gate = UsbFrameRateGate(fps = 10)

        assertTrue(gate.shouldEmit(nowNanos = 0L))
        assertFalse(gate.shouldEmit(nowNanos = 50_000_000L))
        assertTrue(gate.shouldEmit(nowNanos = 100_000_000L))
    }
}
```

- [x] **Step 3: Run focused tests and verify they fail**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest \
  --tests com.example.galaxymirror.UsbStreamProfileTest \
  --tests com.example.galaxymirror.UsbFrameRateGateTest \
  --no-daemon
```

Expected: FAIL because the USB profile and throttle classes do not exist.

- [x] **Step 4: Add USB stream profile policy**

Create `UsbStreamProfile.kt`:

```kotlin
package com.example.galaxymirror

data class UsbStreamProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
)

object UsbStreamProfilePolicy {
    fun resolve(selectedMode: StreamQualityMode): UsbStreamProfile =
        when (selectedMode) {
            StreamQualityMode.DATA_SAVER ->
                UsbStreamProfile(width = 540, height = 1200, fps = 8, jpegQuality = 65)
            StreamQualityMode.HIGH ->
                UsbStreamProfile(width = 1080, height = 2400, fps = 12, jpegQuality = 75)
            StreamQualityMode.AUTO,
            StreamQualityMode.STANDARD ->
                UsbStreamProfile(width = 720, height = 1600, fps = 10, jpegQuality = 70)
        }
}
```

- [x] **Step 5: Add frame rate gate**

Create `UsbFrameRateGate.kt`:

```kotlin
package com.example.galaxymirror

class UsbFrameRateGate(fps: Int) {
    private val intervalNanos: Long = 1_000_000_000L / fps.coerceAtLeast(1)
    private var lastEmitNanos: Long? = null

    fun shouldEmit(nowNanos: Long = System.nanoTime()): Boolean {
        val last = lastEmitNanos
        if (last == null || nowNanos - last >= intervalNanos) {
            lastEmitNanos = nowNanos
            return true
        }
        return false
    }
}
```

- [x] **Step 6: Run focused tests and verify they pass**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest \
  --tests com.example.galaxymirror.UsbStreamProfileTest \
  --tests com.example.galaxymirror.UsbFrameRateGateTest \
  --no-daemon
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbStreamProfile.kt \
  android/app/src/main/java/com/example/galaxymirror/UsbFrameRateGate.kt \
  android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt \
  android/app/src/test/java/com/example/galaxymirror/UsbFrameRateGateTest.kt
git commit -m "feat: USB 스트림 프로필 추가"
```

---

### Task 4: USB Screen JPEG Streamer

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/UsbScreenStreamerSourceTest.kt`

- [x] **Step 1: Write a source-level streamer regression test**

Create `UsbScreenStreamerSourceTest.kt`:

```kotlin
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

    private fun readSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt"),
                Path.of("app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt"),
            )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("UsbScreenStreamer.kt source not found")
        return Files.readString(path)
    }
}
```

- [x] **Step 2: Run the focused test and verify it fails**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbScreenStreamerSourceTest --no-daemon
```

Expected: FAIL because `UsbScreenStreamer.kt` does not exist.

- [x] **Step 3: Store the MediaProjection result code**

In `MediaProjectionService.kt`, add a field near `mediaProjectionResultData`:

```kotlin
var mediaProjectionResultCode: Int? = null
    private set
```

In `onStartCommand`, when `isValidStartData(resultCode, resultData != null)` is true, set both values:

```kotlin
mediaProjectionResultCode = resultCode
mediaProjectionResultData = resultData
```

Where the service clears `mediaProjectionResultData`, clear `mediaProjectionResultCode` in the same block:

```kotlin
mediaProjectionResultCode = null
mediaProjectionResultData = null
```

- [x] **Step 4: Add USB streamer skeleton**

Create `UsbScreenStreamer.kt` with this public API and lifecycle:

```kotlin
package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class UsbScreenStreamer(
    private val context: Context,
    private val onProjectionStopped: () -> Unit,
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var gate: UsbFrameRateGate = UsbFrameRateGate(10)
    private var jpegQuality: Int = 70
    private val running = AtomicBoolean(false)

    fun start(
        resultCode: Int,
        resultData: Intent,
        profile: UsbStreamProfile,
        onFrame: (ByteArray) -> Unit,
    ) {
        stop()
        jpegQuality = profile.jpegQuality
        gate = UsbFrameRateGate(profile.fps)
        handlerThread = HandlerThread("UsbScreenStreamer").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                    onProjectionStopped()
                }
            },
            handler,
        )

        imageReader =
            ImageReader.newInstance(
                profile.width,
                profile.height,
                PixelFormat.RGBA_8888,
                2,
            )
        imageReader?.setOnImageAvailableListener(
            { reader ->
                if (!running.get() || !gate.shouldEmit()) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    encodeJpeg(image)?.let(onFrame)
                } catch (e: Exception) {
                    Log.e("UsbScreenStreamer", "JPEG frame encode failed: ${e.message}", e)
                } finally {
                    image.close()
                }
            },
            handler,
        )

        running.set(true)
        virtualDisplay =
            mediaProjection?.createVirtualDisplay(
                "GalaxyMirrorUsb",
                profile.width,
                profile.height,
                displayDensityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler,
            )
    }

    fun stop() {
        running.set(false)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    fun isRunning(): Boolean = running.get()

    private fun displayDensityDpi(): Int {
        val metrics = context.resources.displayMetrics
        return if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
    }

    private fun encodeJpeg(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        val output = ByteArrayOutputStream()
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
        paddedBitmap.recycle()
        croppedBitmap.recycle()
        return output.toByteArray()
    }
}
```

- [x] **Step 5: Add a streamer field in the service**

In `MediaProjectionService.onCreate`, after stores are initialized, create:

```kotlin
private lateinit var usbScreenStreamer: UsbScreenStreamer
```

Initialize it in `onCreate`:

```kotlin
usbScreenStreamer =
    UsbScreenStreamer(applicationContext) {
        handleUsbProjectionStopped()
    }
```

Add the handler method:

```kotlin
private fun handleUsbProjectionStopped() {
    mainHandler.post {
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        screenCapturePermissionRequired = true
        isRunning = false
        updateWakeLock()
        applyBrightnessMinimizationForCurrentState()
        notifyStateChanged()
    }
}
```

- [x] **Step 6: Stop USB streamer during explicit disconnect and service destroy**

In `disconnectMirror()` and `onDestroy()`, add:

```kotlin
usbScreenStreamer.stop()
```

In `cleanupWebRTCResources`, do not stop the USB streamer. The caller that switches transports stops the previous active transport before starting the next one.

- [x] **Step 7: Run source test and compile**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbScreenStreamerSourceTest --no-daemon
./gradlew assembleDebug --no-daemon
```

Expected: PASS and BUILD SUCCESSFUL.

- [x] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt \
  android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt \
  android/app/src/test/java/com/example/galaxymirror/UsbScreenStreamerSourceTest.kt
git commit -m "feat: USB 화면 JPEG 스트리머 추가"
```

---

### Task 5: USB Session WebSocket Route

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`

- [x] **Step 1: Add session state tests for transport replacement**

Add this test to `MirrorSessionStateTest`:

```kotlin
@Test
fun startingTailscaleAfterUsbReplacesTransport() {
    val state =
        MirrorSessionState()
            .beginSession(10, MirrorTransport.USB_JPEG)
            .beginSession(11, MirrorTransport.TAILSCALE_WEBRTC)

    assertEquals(11, state.activeSessionId)
    assertEquals(MirrorTransport.TAILSCALE_WEBRTC, state.activeTransport)
}
```

- [x] **Step 2: Change session helpers to accept a transport**

In `MediaProjectionService.kt`, change:

```kotlin
private suspend fun beginViewerSession(): Int
```

to:

```kotlin
private suspend fun beginViewerSession(transport: MirrorTransport): Int
```

Inside it, replace:

```kotlin
mirrorSessionState = mirrorSessionState.beginSession(newSessionId)
```

with:

```kotlin
mirrorSessionState = mirrorSessionState.beginSession(newSessionId, transport)
```

For the existing `/signaling` route, call:

```kotlin
val sessionId = beginViewerSession(MirrorTransport.TAILSCALE_WEBRTC)
```

- [x] **Step 3: Stop the opposite transport when a session starts**

At the top of `beginViewerSession(transport)`, before assigning the new state, add:

```kotlin
when (transport) {
    MirrorTransport.TAILSCALE_WEBRTC -> usbScreenStreamer.stop()
    MirrorTransport.USB_JPEG -> cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
}
```

- [x] **Step 4: Add USB status JSON**

Add this helper in `MediaProjectionService.kt`:

```kotlin
private fun buildUsbStatusMessage(
    message: String,
    captureReady: Boolean = isRunning,
): String {
    return org.json.JSONObject().apply {
        put("type", "USB_STATUS")
        put("payload", org.json.JSONObject().apply {
            put("transport", MirrorTransport.USB_JPEG.wireValue)
            put("captureReady", captureReady)
            put("accessibilityReady", GalaxyMirrorAccessibilityService.isReadyForRemoteInput())
            put("streamQuality", org.json.JSONObject(UsbStreamProfileCodec.toStatusJson(streamQualityMode)))
            put("message", message)
        })
    }.toString()
}
```

- [x] **Step 5: Add USB WebSocket route**

Inside `startKtorServer` routing, after `/signaling`, add:

```kotlin
webSocket("/usb/session") {
    if (!isViewerAuthorized(call)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHORIZED_VIEWER"))
        return@webSocket
    }

    val sessionId = beginViewerSession(MirrorTransport.USB_JPEG)
    CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "USB session connected: sessionId=$sessionId.")

    try {
        if (mediaProjectionResultCode == null || mediaProjectionResultData == null) {
            outgoing.send(Frame.Text(buildUsbStatusMessage("WAITING_FOR_SCREEN_CAPTURE", captureReady = false)))
            requestScreenCapturePermissionFromActivity("USB session requested MediaProjection grant")
        }

        while (isActiveSession(sessionId, MirrorTransport.USB_JPEG) && mediaProjectionResultCode == null) {
            delay(500)
        }

        val resultCode = mediaProjectionResultCode
        val resultData = mediaProjectionResultData
        if (resultCode == null || resultData == null || !isActiveSession(sessionId, MirrorTransport.USB_JPEG)) {
            outgoing.send(Frame.Text(buildUsbStatusMessage("SCREEN_CAPTURE_REAUTH_REQUIRED", captureReady = false)))
            return@webSocket
        }

        val profile = UsbStreamProfilePolicy.resolve(streamQualityMode)
        outgoing.send(Frame.Text(buildUsbStatusMessage("USB_STREAM_STARTING", captureReady = true)))
        usbScreenStreamer.start(
            resultCode = resultCode,
            resultData = resultData,
            profile = profile,
        ) { frameBytes ->
            serviceScope.launch {
                if (isActiveSession(sessionId, MirrorTransport.USB_JPEG)) {
                    outgoing.send(Frame.Binary(fin = true, data = frameBytes))
                }
            }
        }
        outgoing.send(Frame.Text(buildUsbStatusMessage("USB_STREAMING", captureReady = true)))

        for (frame in incoming) {
            if (!isActiveSession(sessionId, MirrorTransport.USB_JPEG)) break
            if (frame is Frame.Text) {
                controlEventDispatcher.dispatch(frame.readText()) { result ->
                    serviceScope.launch {
                        outgoing.send(Frame.Text(result.toAckJson()))
                    }
                }
            }
        }
    } catch (e: Exception) {
        CrashDiagnostics.recordCaughtException(this@MediaProjectionService.filesDir, "USB session $sessionId", e)
        Log.e("KtorServer", "USB session error: ${e.message}", e)
    } finally {
        usbScreenStreamer.stop()
        endViewerSession(sessionId)
        CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "USB session ended: sessionId=$sessionId.")
    }
}
```

Also add:

```kotlin
private fun isActiveSession(sessionId: Int, transport: MirrorTransport): Boolean =
    mirrorSessionState.isActive(sessionId, transport)
```

- [x] **Step 6: Keep Tailscale signaling on the WebRTC transport**

In all WebRTC-only paths that queue offers or handle stale projection callbacks, keep using the existing one-argument `isActiveSession(sessionId)` or switch to:

```kotlin
isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)
```

Use the two-argument check inside `/signaling` message handling after `beginViewerSession(MirrorTransport.TAILSCALE_WEBRTC)` so a stale WebRTC callback cannot close an active USB session.

- [x] **Step 7: Run tests and assemble**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest \
  --tests com.example.galaxymirror.MirrorSessionStateTest \
  --tests com.example.galaxymirror.ControlEventDispatcherTest \
  --no-daemon
./gradlew assembleDebug --no-daemon
```

Expected: PASS and BUILD SUCCESSFUL.

- [x] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt \
  android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt
git commit -m "feat: USB 미러링 세션 WebSocket 추가"
```

---

### Task 6: Android UI For Two Connection Modes

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/ui/main/MainScreenContentTest.kt`

- [x] **Step 1: Write content tests**

Add these assertions to `MainScreenContentTest`:

```kotlin
@Test
fun viewerConnectionLinesShowTailscaleAndUsbOptions() {
    val token = "abc123"

    assertEquals(
        "Tailscale URL: http://<Android MagicDNS>:8080/?token=abc123&transport=tailscale",
        MainScreenContent.viewerTailscaleUrlLine(token),
    )
    assertEquals(
        "USB URL: http://127.0.0.1:8080/?token=abc123&transport=usb",
        MainScreenContent.viewerUsbUrlLine(token),
    )
    assertEquals(
        "Mac 터미널: adb forward tcp:8080 tcp:8080",
        MainScreenContent.viewerUsbForwardCommand,
    )
}
```

- [x] **Step 2: Run focused test and verify it fails**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ui.main.MainScreenContentTest --no-daemon
```

Expected: FAIL because the new content helpers do not exist.

- [x] **Step 3: Add content helpers**

In `MainScreenContent.kt`, add:

```kotlin
fun viewerTailscaleUrlLine(token: String): String =
    if (token.isBlank()) {
        "Tailscale URL: http://<Android MagicDNS>:8080/?token=<접속 토큰>&transport=tailscale"
    } else {
        "Tailscale URL: http://<Android MagicDNS>:8080/?token=$token&transport=tailscale"
    }

fun viewerUsbUrlLine(token: String): String =
    if (token.isBlank()) {
        "USB URL: http://127.0.0.1:8080/?token=<접속 토큰>&transport=usb"
    } else {
        "USB URL: http://127.0.0.1:8080/?token=$token&transport=usb"
    }

const val viewerUsbForwardCommand = "Mac 터미널: adb forward tcp:8080 tcp:8080"
const val viewerTransportHint = "Tailscale은 무선/원격 연결, USB는 adb forward가 켜진 Mac 직접 연결에 사용합니다."
```

- [x] **Step 4: Update the Mac connection info panel**

In `MainScreen.kt`, replace the single `viewerUrlLine` item with:

```kotlin
MainScreenContent.viewerTailscaleUrlLine(viewerAccessToken),
MainScreenContent.viewerUsbForwardCommand,
MainScreenContent.viewerUsbUrlLine(viewerAccessToken),
MainScreenContent.viewerTransportHint,
```

Keep `viewerTokenLine(viewerAccessToken)` above both URLs.

- [x] **Step 5: Run content tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ui.main.MainScreenContentTest --no-daemon
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt \
  android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt \
  android/app/src/test/java/com/example/galaxymirror/ui/main/MainScreenContentTest.kt
git commit -m "feat: Android 화면에 Tailscale USB 접속 주소 표시"
```

---

### Task 7: Mac Viewer Transport Selection And USB Rendering

**Files:**
- Modify: `android/app/src/main/resources/files/index.html`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Test: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [x] **Step 1: Add JS tests for initial transport selection**

In `viewer-keyboard.test.mjs`, add a test that loads the viewer with URL `http://127.0.0.1:8080/?token=abc&transport=usb` and asserts:

```js
assert.equal(vm.runInContext('selectedTransport', context), 'usb');
```

Add a second load with `http://phone.ts.net:8080/?token=abc` and assert:

```js
assert.equal(vm.runInContext('selectedTransport', context), 'tailscale');
```

- [x] **Step 2: Add JS tests for USB WebSocket and control send**

Add this test:

```js
await test('usb mode opens usb session and sends control over usb websocket', () => {
    const { context, webSockets } = loadViewer({ url: 'http://127.0.0.1:8080/?token=abc&transport=usb' });

    vm.runInContext('connectMirror();', context);
    assert.equal(webSockets[0].url, 'ws://127.0.0.1:8080/usb/session?token=abc');
    webSockets[0].onopen();

    vm.runInContext('sendControlPayload({ type: "key", keyCode: 4 });', context);

    assert.deepEqual(webSockets[0].sentMessages, [
        JSON.stringify({ type: 'key', keyCode: 4 }),
    ]);
});
```

Extend the fake WebSocket in the test harness with a `sentMessages` array if it does not already record sent text.

- [x] **Step 3: Add JS tests for binary frame rendering**

Add this test:

```js
await test('usb binary frame updates usb image and download usage', () => {
    const { context, document, webSockets } = loadViewer({ url: 'http://127.0.0.1:8080/?token=abc&transport=usb' });
    const usbFrame = document.getElementById('usbFrame');

    vm.runInContext('connectMirror();', context);
    webSockets[0].onopen();
    webSockets[0].onmessage({ data: new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'image/jpeg' }) });

    assert.match(usbFrame.src, /^blob:/);
    assert.equal(document.getElementById('downloadUsage').textContent, '0.00 MB');
});
```

- [x] **Step 4: Run JS tests and verify they fail**

Run:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: FAIL because `selectedTransport`, `connectMirror`, `usbFrame`, and USB socket handling do not exist.

- [x] **Step 5: Add transport controls to `index.html`**

Add this block near the existing status controls:

```html
<div class="transport-container">
    <div class="section-title">Connection</div>
    <div class="transport-actions" role="group" aria-label="Connection transport">
        <button id="transportTailscaleBtn" class="transport-btn" type="button">Tailscale</button>
        <button id="transportUsbBtn" class="transport-btn" type="button">USB</button>
    </div>
</div>
```

Inside `#videoContainer`, keep the existing video element and add:

```html
<img id="usbFrame" class="hidden" alt="USB mirrored Android screen">
```

Add CSS:

```css
.transport-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
}

.transport-btn {
    min-height: 38px;
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.03);
    color: var(--text-main);
    cursor: pointer;
    font-size: 12px;
    font-weight: 700;
}

.transport-btn.active {
    background: rgba(56, 189, 248, 0.15);
    border-color: rgba(56, 189, 248, 0.4);
}

#usbFrame {
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: block;
}

#usbFrame.hidden {
    display: none;
}
```

- [x] **Step 6: Add transport state in `viewer.js`**

Near the top of `viewer.js`, add:

```js
const usbFrame = document.getElementById('usbFrame');
const transportTailscaleBtn = document.getElementById('transportTailscaleBtn');
const transportUsbBtn = document.getElementById('transportUsbBtn');
let usbSocket = null;
let selectedTransport = initialTransport();
let lastUsbFrameUrl = null;

function initialTransport() {
    const params = new URLSearchParams(window.location.search);
    const requested = (params.get('transport') || '').toLowerCase();
    if (requested === 'usb' || requested === 'tailscale') return requested;
    return ['127.0.0.1', 'localhost', '::1'].includes(window.location.hostname) ? 'usb' : 'tailscale';
}
```

- [x] **Step 7: Add transport UI handlers**

Add:

```js
function renderTransportSelection() {
    transportTailscaleBtn?.classList.toggle('active', selectedTransport === 'tailscale');
    transportUsbBtn?.classList.toggle('active', selectedTransport === 'usb');
    if (remoteVideo) remoteVideo.classList.toggle('hidden', selectedTransport === 'usb');
    if (usbFrame) usbFrame.classList.toggle('hidden', selectedTransport !== 'usb');
}

function setTransport(transport) {
    if (selectedTransport === transport) return;
    selectedTransport = transport;
    disconnectCurrentTransport();
    renderTransportSelection();
    showStatusDetail(
        transport === 'usb'
            ? 'USB 모드는 adb forward 후 127.0.0.1 주소에서 연결합니다.'
            : 'Tailscale 모드는 Android MagicDNS 주소에서 WebRTC로 연결합니다.',
    );
}

transportTailscaleBtn?.addEventListener('click', () => setTransport('tailscale'));
transportUsbBtn?.addEventListener('click', () => setTransport('usb'));
```

Call `renderTransportSelection()` during viewer initialization.

- [x] **Step 8: Route connect and disconnect by transport**

Add:

```js
function connectMirror() {
    if (selectedTransport === 'usb') {
        connectUsbSession();
    } else {
        connectSignaling();
    }
}

function disconnectCurrentTransport() {
    if (usbSocket) {
        usbSocket.close();
        usbSocket = null;
    }
    cleanupPeerConnection();
    clearRemoteVideoFrame();
    clearUsbFrame();
}
```

Change the connect button handler from `connectSignaling` to `connectMirror`.

- [x] **Step 9: Add USB WebSocket session handling**

Add:

```js
function usbSessionUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const tokenQuery = viewerAccessToken ? `?token=${encodeURIComponent(viewerAccessToken)}` : '';
    return `${protocol}//${window.location.host}/usb/session${tokenQuery}`;
}

function connectUsbSession() {
    disconnectCurrentTransport();
    resetTextControlState();
    resetDataUsageStats();
    const wsUrl = usbSessionUrl();
    log(`USB session 연결 시도 중: ${wsUrl}`);
    usbSocket = new WebSocket(wsUrl);
    usbSocket.binaryType = 'blob';

    usbSocket.onopen = () => {
        wsIndicator.classList.add('online');
        wsStatus.innerText = 'Online';
        rtcStatus.innerText = 'USB 스트림 대기';
        controlStatus.innerText = 'USB';
        showStatusDetail('USB 연결이 열렸습니다. Android 화면 공유 승인을 기다립니다.');
    };

    usbSocket.onmessage = (event) => {
        if (typeof event.data === 'string') {
            handleUsbTextMessage(event.data);
            return;
        }
        renderUsbFrame(event.data);
    };

    usbSocket.onclose = () => {
        wsIndicator.classList.remove('online');
        wsStatus.innerText = 'Offline';
        rtcStatus.innerText = 'USB 연결 종료';
        controlStatus.innerText = '비활성';
    };

    usbSocket.onerror = () => {
        showStatusDetail('USB 연결 오류가 발생했습니다. adb forward 상태를 확인하세요.', 'warning');
    };
}
```

- [x] **Step 10: Add USB status, ACK, and frame rendering**

Add:

```js
function handleUsbTextMessage(text) {
    try {
        const message = JSON.parse(text);
        if (message.type === 'USB_STATUS') {
            const payload = message.payload || {};
            accessibilityReady = payload.accessibilityReady === true;
            accessibilityStatus.innerText = accessibilityReady ? '입력 가능' : '입력 권한 필요';
            renderStreamQualityStatus(payload.streamQuality || {});
            if (payload.message === 'USB_STREAMING') {
                rtcStatus.innerText = 'USB 스트리밍';
                showStatusDetail('USB 화면 전송 중입니다.', 'success');
            } else if (payload.message === 'WAITING_FOR_SCREEN_CAPTURE') {
                rtcStatus.innerText = '화면 공유 대기';
                showStatusDetail('Android 기기에서 화면 공유 권한을 승인하면 USB 미러링이 시작됩니다.', 'warning');
            }
            return;
        }
        if (message.type === 'CONTROL_ACK') {
            handleControlAck(message.payload || {});
        }
    } catch (error) {
        log(`USB 메시지 처리 실패: ${error.message}`);
    }
}

function renderUsbFrame(blob) {
    if (!usbFrame) return;
    if (lastUsbFrameUrl) URL.revokeObjectURL(lastUsbFrameUrl);
    lastUsbFrameUrl = URL.createObjectURL(blob);
    usbFrame.src = lastUsbFrameUrl;
    rtcStatus.innerText = 'USB 스트리밍';
    accumulatedNetworkBytes.received += blob.size || 0;
    updateDataUsageDisplay();
}

function clearUsbFrame() {
    if (lastUsbFrameUrl) {
        URL.revokeObjectURL(lastUsbFrameUrl);
        lastUsbFrameUrl = null;
    }
    if (usbFrame) usbFrame.removeAttribute('src');
}
```

- [x] **Step 11: Make control sending transport-aware**

Replace the first lines of `sendControlPayload(payload)` with:

```js
if (selectedTransport === 'usb') {
    if (!usbSocket || usbSocket.readyState !== WebSocket.OPEN) {
        log('USB 제어 채널이 아직 열리지 않았습니다.');
        return false;
    }
    usbSocket.send(JSON.stringify(payload));
    return true;
}
```

Keep the existing DataChannel path after this USB branch.

- [x] **Step 12: Run JS verification**

Run:

```bash
node --check android/app/src/main/resources/files/viewer.js
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: PASS.

- [x] **Step 13: Commit**

```bash
git add android/app/src/main/resources/files/index.html \
  android/app/src/main/resources/files/viewer.js \
  android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "feat: Mac Viewer USB 연결 모드 추가"
```

---

### Task 8: Protocol And Project Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/Protocols.md`
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`

- [x] **Step 1: Update README connection overview**

In `README.md`, add a subsection under setup:

````markdown
### USB 직접 연결

Tailscale 연결이 불안정하거나 같은 Mac에 USB로 직접 연결한 상태라면 ADB port forwarding으로
Android 내장 서버를 Mac localhost에 노출할 수 있습니다.

```bash
adb forward --remove tcp:8080 || true
adb forward tcp:8080 tcp:8080
```

그 뒤 Mac 브라우저에서 Android 앱 화면의 USB URL
`http://127.0.0.1:8080/?token=<token>&transport=usb`로 접속합니다.
USB 모드는 `/usb/session` WebSocket을 통해 JPEG 화면 frame과 원격 입력 JSON을 전송합니다.
````

- [x] **Step 2: Update Protocols with USB session frames**

In `docs/Protocols.md`, add:

````markdown
## 6. USB 연결 모드

USB 연결은 Mac에서 `adb forward tcp:8080 tcp:8080`을 실행한 뒤
`http://127.0.0.1:8080/?token=<token>&transport=usb`로 접속하는 모드입니다.
브라우저는 `ws://127.0.0.1:8080/usb/session?token=<token>` WebSocket을 열고,
Android Host는 같은 소켓으로 JPEG binary frame과 JSON text frame을 주고받습니다.

### 6.1 Android -> Browser text frame

```json
{
  "type": "USB_STATUS",
  "payload": {
    "transport": "usb",
    "captureReady": true,
    "accessibilityReady": true,
    "streamQuality": {
      "selectedMode": "AUTO",
      "selectedLabel": "자동",
      "effectiveMode": "STANDARD",
      "effectiveLabel": "표준",
      "width": 720,
      "height": 1600,
      "fps": 10,
      "jpegQuality": 70
    },
    "message": "USB_STREAMING"
  }
}
```

### 6.2 Android -> Browser binary frame

각 binary frame은 독립 JPEG 이미지입니다. Browser는 마지막 frame URL을 revoke하고 새 Blob URL을
`#usbFrame`에 표시합니다.

### 6.3 Browser -> Android text frame

USB 제어 입력은 WebRTC DataChannel과 같은 JSON shape을 사용합니다.

```json
{ "type": "tap", "x": 0.5, "y": 0.25, "seq": 42 }
```

Android Host는 기존 `CONTROL_ACK` JSON을 text frame으로 반환합니다.

### 6.4 전환 정책

Tailscale/WebRTC와 USB/JPEG는 동시에 활성화하지 않습니다. 새 transport 세션이 시작되면 기존
세션의 영상 capture와 제어 채널을 정리하고, Android MediaProjection 권한은 필요할 때 다시
요청합니다.
````

- [x] **Step 3: Update Handoff**

Add these checklist items to the active milestone:

```markdown
- [x] Tailscale/WebRTC와 USB/JPEG transport 선택 모델 정의
- [x] USB `/usb/session` WebSocket 기반 JPEG frame 및 control event 경로 구현
- [ ] 실제 Galaxy 단말에서 USB `adb forward` smoke test
- [ ] Tailscale/WebRTC와 USB 전환 반복 smoke test
```

- [x] **Step 4: Update Log**

Add an entry:

```markdown
### 2026-06-09

- Tailscale/WebRTC 기존 경로와 별도로 USB/ADB 직접 연결 모드 구현 계획을 반영했다.
- USB 모드는 `adb forward tcp:8080 tcp:8080` 후 `/usb/session` WebSocket에서 JPEG binary frame과 control JSON text frame을 교환한다.
- 두 transport는 동시에 활성화하지 않고, 전환 시 기존 capture/session을 정리한 뒤 MediaProjection 재승인을 받는 정책으로 정리했다.
```

- [ ] **Step 5: Commit**

```bash
git add README.md docs/Protocols.md docs/Handoff.md docs/Log.md \
  docs/superpowers/plans/2026-06-09-dual-transport-usb-tailscale.md
git commit -m "docs: Tailscale USB 연결 모드 문서화"
```

---

### Task 9: Full Verification And Device Smoke Test

**Files:**
- No source files created in this task.
- Uses: `android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Run JS verification**

Run:

```bash
node --check android/app/src/main/resources/files/viewer.js
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: PASS.

- [ ] **Step 2: Run Android unit tests and debug build**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest assembleDebug --no-daemon --no-watch-fs --max-workers=4
```

Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 3: Install and launch on Galaxy device**

Run:

```bash
adb devices -l
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.galaxymirror
adb shell am start -n com.example.galaxymirror/.MainActivity
```

Expected:
- `adb devices -l` shows the Galaxy device as `device`, not `offline` or `unauthorized`.
- `adb install -r` prints `Success`.
- Android Mirror launches and shows Tailscale URL, USB command, USB URL, and token.

- [ ] **Step 4: Verify USB server reachability**

Run:

```bash
adb forward --remove tcp:8080 || true
adb forward tcp:8080 tcp:8080
curl -sS --max-time 5 http://127.0.0.1:8080/status
```

Expected:

```text
Android Mirror Web Server is active. Port: 8080
```

- [ ] **Step 5: Smoke test USB mode in browser**

Open:

```text
http://127.0.0.1:8080/?token=<token>&transport=usb
```

Expected:
- USB transport button is active.
- Pressing connect opens `/usb/session`.
- Android screen share permission appears when no valid MediaProjection grant exists.
- After approval, the browser shows the Android screen through JPEG frames.
- Clicking the mirrored screen sends tap JSON over the USB WebSocket.
- If AccessibilityService is enabled, the Galaxy device receives the tap.

- [ ] **Step 6: Smoke test Tailscale mode still works**

Open:

```text
http://<Android MagicDNS>:8080/?token=<token>&transport=tailscale
```

Expected:
- Tailscale transport button is active.
- Pressing connect opens `/signaling`.
- WebRTC video reaches connected state.
- DataChannel control still sends tap/key/text events.

- [ ] **Step 7: Smoke test switching**

Run this sequence:

```text
1. Connect USB mode.
2. Disconnect or switch to Tailscale mode.
3. Approve screen capture if Android asks again.
4. Confirm Tailscale WebRTC stream starts.
5. Switch back to USB mode.
6. Approve screen capture if Android asks again.
7. Confirm USB JPEG stream starts.
```

Expected:
- No stale "연결 복원 중" overlay remains after transport switching.
- No old WebRTC DataChannel sends after USB is active.
- No USB binary frames continue after Tailscale is active.
- Android app does not crash.

- [ ] **Step 8: Check crash diagnostics**

Open:

```text
http://127.0.0.1:8080/debug/crash?token=<token>
```

Expected:
- No saved unhandled exception.
- Recent events include USB session connected, USB streaming, session ended, and Tailscale signaling events.

- [ ] **Step 9: Commit final verification note**

If smoke tests pass, update `docs/Handoff.md` and `docs/Log.md` with concrete device, date, and results:

```markdown
- 2026-06-09: Galaxy 실기기에서 USB `adb forward` 연결, Tailscale 연결, USB <-> Tailscale 전환을 smoke test로 확인했다.
```

Then commit:

```bash
git add docs/Handoff.md docs/Log.md
git commit -m "docs: USB Tailscale 실기기 검증 결과 기록"
```

---

## Risk Checklist

- MediaProjection result Intent is single-use on recent Android versions. The implementation must not try to reuse a token after stopping a WebRTC or USB capture pipeline.
- USB JPEG encoding can load CPU. The MVP caps USB FPS to 8-12 and uses JPEG quality 65-75.
- Browser Blob URLs must be revoked or USB mode will leak memory during long sessions.
- WebRTC and USB must not stream at the same time because that complicates MediaProjection ownership and doubles device load.
- ADB `offline` or `unauthorized` is an environment problem. USB mode cannot work until `adb devices -l` shows `device`.
- Remote input in both modes still depends on Android AccessibilityService being enabled.

## Self-Review

- Spec coverage: The plan covers transport choice, Android session ownership, USB screen frames, shared control events, viewer selection UI, Android displayed URLs, protocol docs, and real-device verification.
- Placeholder scan: No placeholder task remains; every task names concrete files, commands, and expected results.
- Type consistency: The plan consistently uses `MirrorTransport.TAILSCALE_WEBRTC`, `MirrorTransport.USB_JPEG`, `/signaling`, `/usb/session`, `USB_STATUS`, and existing `CONTROL_ACK`.
