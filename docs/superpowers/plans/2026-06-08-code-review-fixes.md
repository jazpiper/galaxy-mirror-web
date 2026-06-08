# Code Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every actionable issue recorded in `docs/CodeReview-2026-06-08.md` and leave tests, docs, and CI aligned with the corrected runtime behavior.

**Architecture:** Use a conservative privacy-first MediaProjection policy: if the viewer disconnects or is replaced, screen capture is torn down and the next session requests a fresh Android screen-share grant. Keep WebRTC/Ktor in `MediaProjectionService`, but add a service-to-activity permission request signal so pending offers can resume when the Android activity is bound. Browser fixes stay in the existing static `viewer.js`/`viewer-keyboard.test.mjs` stack without adding a frontend framework.

**Tech Stack:** Kotlin 2.x, Android SDK 36, Jetpack Compose, Ktor CIO, WebRTC Android, JUnit4 JVM tests, Node.js `node --test`, GitHub Actions.

## Execution Status

- [x] MediaProjection lifecycle, permission request, stopped-capturer disposal, and viewer close/replacement cleanup implemented.
- [x] Hardware volume/mute routing changed from invalid raw Accessibility global action IDs to `AudioManager`.
- [x] `CONTROL_ACK` serialization restored to structured `JSONObject` escaping.
- [x] Clipboard empty-string sync and HTTP-origin browser clipboard fallback implemented.
- [x] Viewer manual refresh, auto reconnect after socket close, and MediaRecorder fallback/reset behavior implemented.
- [x] Protocol docs, handoff notes, development log, Dashboard, CI JS test step, and trailing whitespace cleanup updated.
- [x] Targeted Android regression tests, full JVM unit tests, and Viewer Node tests passed locally.
- [x] `app:lintDebug`, `assembleDebug`, and final `git diff --check` passed locally.
- [ ] Physical Android smoke test completed on an actual Galaxy device.

---

## Review Source

- Primary review document: `docs/CodeReview-2026-06-08.md`
- Review commit range: `7ee4930ff5ccfe89f39394326707fb7de6191e76^..HEAD`
- Required verification commands:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon
./gradlew app:lintDebug --no-daemon
./gradlew assembleDebug --no-daemon
cd ..
node --test android/app/src/test/js/viewer-keyboard.test.mjs
git diff --check
```

## File Structure

- Modify `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - Owns Ktor hosting, signaling session lifecycle, MediaProjection/WebRTC cleanup, pending offer resumption, and service-to-activity permission request state.
- Modify `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
  - Owns Android screen-capture permission launcher and responds to service permission request callbacks.
- Modify `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
  - Owns remote key injection and Android-to-Mac clipboard events.
- Create `android/app/src/main/java/com/example/galaxymirror/HardwareKeyAction.kt`
  - Pure Kotlin keycode-to-action mapping so hardware key behavior can be unit-tested without instantiating `AccessibilityService`.
- Modify `android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt`
  - Owns structured `CONTROL_ACK` JSON serialization.
- Modify `android/app/src/main/resources/files/viewer.js`
  - Owns reconnect behavior, clipboard feature detection/fallback, recording state, and recording API guards.
- Modify `android/app/src/test/js/viewer-keyboard.test.mjs`
  - Owns browser behavior regression tests and fake browser runtime improvements.
- Create `android/app/src/test/java/com/example/galaxymirror/MediaProjectionServiceLifecycleRegressionTest.kt`
  - Source-level JVM regression tests for service lifecycle code that cannot be run directly on plain JVM.
- Create `android/app/src/test/java/com/example/galaxymirror/HardwareKeyActionTest.kt`
  - Pure mapping tests for hardware key action routing.
- Modify `android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt`
  - ACK escaping regression tests.
- Modify `.github/workflows/android-build.yml`
  - Runs viewer JS tests in CI.
- Modify `docs/Protocols.md`, `docs/Handoff.md`, `docs/Log.md`, and `docs/Dashboard.md`
  - Align Milestone 6 and MediaProjection policy docs with the corrected code.

---

### Task 1: MediaProjection Lifecycle And Permission Request Repair

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/MediaProjectionServiceLifecycleRegressionTest.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/SignalingStateTest.kt`

- [ ] **Step 1: Write failing service lifecycle regression tests**

Create `android/app/src/test/java/com/example/galaxymirror/MediaProjectionServiceLifecycleRegressionTest.kt`:

```kotlin
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
            source.contains(
                """
                diagnosticReason = "ScreenCapturerAndroid callback",
                            stopCapturer = false,
                """.trimIndent()
            )
        )
        assertTrue(
            "MediaProjection onStop should force capturer disposal before any fresh grant.",
            source.contains(
                """
                diagnosticReason = "ScreenCapturerAndroid callback",
                            stopCapturer = true,
                """.trimIndent()
            )
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.MediaProjectionServiceLifecycleRegressionTest
```

Expected: FAIL. Current source still contains `stopCapturer = false`, does not expose `onScreenCapturePermissionRequired`, and does not route viewer lifecycle cleanup through `CleanupReason`.

- [ ] **Step 3: Add service-to-activity permission request state**

In `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`, replace the listener interface and add the request flag near the current listener state:

```kotlin
    interface StateListener {
        fun onStateChanged()
        fun onScreenCapturePermissionRequired() {}
    }
    private val listeners = mutableListOf<StateListener>()

    var screenCapturePermissionRequired = false
        private set
```

Add these helper methods below `notifyStateChanged()`:

```kotlin
    private fun notifyScreenCapturePermissionRequired() {
        val targets = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            targets.forEach { it.onScreenCapturePermissionRequired() }
        }
    }

    private fun requestScreenCapturePermissionFromActivity(reason: String) {
        screenCapturePermissionRequired = true
        CrashDiagnostics.recordEvent(this, "Screen capture permission request required: $reason.")
        notifyScreenCapturePermissionRequired()
        notifyStateChanged()
    }
```

In `onStartCommand`, inside the valid start-data branch and before assigning `mediaProjectionResultData`, add cleanup of stale capture resources and clear the request flag:

```kotlin
            cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
            mediaProjectionResultData = resultData
            screenCapturePermissionRequired = false
            isRunning = true
```

The branch should still call `startForeground(...)`, `updateWakeLock()`, `resumePendingOfferIfReady()`, and `notifyStateChanged()`.

- [ ] **Step 4: Route missing-permission offers to the activity permission flow**

In `handleSignalingMessage`, replace the `QUEUE_AND_REQUEST_PERMISSION` branch with:

```kotlin
                            SignalingDecision.QUEUE_AND_REQUEST_PERMISSION -> {
                                queuePendingOffer(sessionId, sdpDescription, sendResponse)
                                requestScreenCapturePermissionFromActivity("Offer received without active MediaProjection grant")
                                sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                            }
```

In `initializeWebRTC`, replace the `readiness != ProjectionReadiness.READY` block with:

```kotlin
        if (readiness != ProjectionReadiness.READY) {
            CrashDiagnostics.recordEvent(this, "Capture not ready; deferring offer for sessionId=$sessionId.")
            queuePendingOffer(sessionId, remoteSdp, sendResponse)
            if (readiness == ProjectionReadiness.MISSING_PERMISSION) {
                requestScreenCapturePermissionFromActivity("Negotiation attempted without active MediaProjection grant")
            }
            sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
            return
        }
```

- [ ] **Step 5: Make MediaProjection stop and startCapture failures dispose capture resources**

In the `ScreenCapturerAndroid` callback inside `initializeWebRTC`, change the `stopCapturer` argument to `true`:

```kotlin
                    override fun onStop() {
                        CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "MediaProjection stopped inside service.")
                        handleScreenCaptureReauthorizationRequired(
                            sessionId = sessionId,
                            sendResponse = sendResponse,
                            diagnosticReason = "ScreenCapturerAndroid callback",
                            stopCapturer = true,
                        )
                    }
```

Replace the direct `startCapture(...)` call with a local guarded call:

```kotlin
                try {
                    videoCapturer?.startCapture(streamProfile.width, streamProfile.height, streamProfile.fps)
                } catch (e: Exception) {
                    CrashDiagnostics.recordCaughtException(filesDir, "ScreenCapturerAndroid.startCapture", e)
                    handleScreenCaptureReauthorizationRequired(
                        sessionId = sessionId,
                        sendResponse = sendResponse,
                        diagnosticReason = "ScreenCapturerAndroid.startCapture failure",
                        stopCapturer = true,
                    )
                    return
                }
                videoTrack = peerConnectionFactory?.createVideoTrack("video_track_id", videoSource)
```

In `handleScreenCaptureReauthorizationRequired`, after `mediaProjectionResultData = null`, add:

```kotlin
            screenCapturePermissionRequired = true
```

After `cleanupWebRTCResources(...)`, add:

```kotlin
            requestScreenCapturePermissionFromActivity(diagnosticReason)
```

- [ ] **Step 6: Enforce privacy-first cleanup on viewer replacement and close**

Add this helper below `disconnectMirror()`:

```kotlin
    private fun stopProjectionCaptureForPolicy(reason: CleanupReason) {
        if (!CleanupPolicy.shouldStopProjection(reason)) return
        CrashDiagnostics.recordEvent(this, "Stopping projection capture for cleanup reason: $reason.")
        stopForeground(true)
        cleanupWebRTCResources(stopProjectionService = true, stopCapturer = true)
        isRunning = false
        screenCapturePermissionRequired = false
        updateWakeLock()
        applyBrightnessMinimizationForCurrentState()
    }
```

In `beginViewerSession()`, replace the current replacement cleanup block with:

```kotlin
            if (replacingSessionId != 0) {
                CrashDiagnostics.recordEvent(this@MediaProjectionService, "Replacing active viewer session: $replacingSessionId -> $sessionId.")
                Log.w("WebRTC", "Replacing active viewer session: $replacingSessionId -> $sessionId")
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_REPLACED)
            }
```

In `endViewerSession(sessionId)`, replace the method body with:

```kotlin
    private suspend fun endViewerSession(sessionId: Int) {
        withContext(Dispatchers.Main) {
            val shouldStopProjection = synchronized(sessionLock) {
                if (isActiveSession(sessionId)) {
                    CrashDiagnostics.recordEvent(this@MediaProjectionService, "Ending viewer session: $sessionId.")
                    mirrorSessionState = mirrorSessionState.endSession(sessionId)
                    activeSessionId = mirrorSessionState.activeSessionId
                    pendingOffer = null
                    true
                } else {
                    false
                }
            }

            if (shouldStopProjection) {
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_SOCKET_CLOSED)
                notifyStateChanged()
            }
        }
    }
```

- [ ] **Step 7: Make MainActivity consume permission request callbacks**

In `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`, update `serviceStateListener`:

```kotlin
    private val serviceStateListener = object : MediaProjectionService.StateListener {
        override fun onStateChanged() {
            val s = mediaProjectionService ?: return
            streamQualityMode = s.streamQualityMode
            streamQualityNetwork = s.streamQualityNetwork
            streamQualityProfile = s.streamQualityProfile
            viewerAccessToken = s.viewerAccessToken
            mirrorSessionState = s.mirrorSessionState
            activeSessionId = s.activeSessionId
            screenAwakeSettings = s.screenAwakeSettings
            accessibilityEnabled = AccessibilitySettingsState.isGalaxyMirrorServiceEnabled(this@MainActivity)
            canWriteSystemSettings = s.screenBrightnessController.canWriteSystemSettings()
            applyScreenAwakeWindowFlag()
            if (s.screenCapturePermissionRequired && !screenCaptureRequestInFlight) {
                requestScreenCapturePermission()
            }
        }

        override fun onScreenCapturePermissionRequired() {
            requestScreenCapturePermission()
        }
    }
```

- [ ] **Step 8: Run lifecycle tests and full JVM tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.MediaProjectionServiceLifecycleRegressionTest
./gradlew app:testDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit lifecycle repair**

Run:

```bash
git add android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/test/java/com/example/galaxymirror/MediaProjectionServiceLifecycleRegressionTest.kt
git commit -m "fix: repair projection lifecycle and permission requests"
```

---

### Task 2: Hardware Key Injection Repair

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/HardwareKeyAction.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/HardwareKeyActionTest.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt`

- [ ] **Step 1: Write failing hardware key action tests**

Create `android/app/src/test/java/com/example/galaxymirror/HardwareKeyActionTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class HardwareKeyActionTest {
    @Test
    fun mapsVolumeAndLockKeycodesToSemanticActions() {
        assertEquals(HardwareKeyAction.VolumeUp, HardwareKeyAction.fromKeyCode(24))
        assertEquals(HardwareKeyAction.VolumeDown, HardwareKeyAction.fromKeyCode(25))
        assertEquals(HardwareKeyAction.ToggleMute, HardwareKeyAction.fromKeyCode(164))
        assertEquals(HardwareKeyAction.LockScreen, HardwareKeyAction.fromKeyCode(26))
    }

    @Test
    fun unsupportedKeycodesDoNotMapToHardwareActions() {
        assertNull(HardwareKeyAction.fromKeyCode(4))
        assertNull(HardwareKeyAction.fromKeyCode(187))
        assertNull(HardwareKeyAction.fromKeyCode(99))
    }
}
```

- [ ] **Step 2: Run hardware key tests to verify they fail**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.HardwareKeyActionTest
```

Expected: FAIL with unresolved reference `HardwareKeyAction`.

- [ ] **Step 3: Add pure hardware key action mapper**

Create `android/app/src/main/java/com/example/galaxymirror/HardwareKeyAction.kt`:

```kotlin
package com.example.galaxymirror

sealed class HardwareKeyAction {
    data object VolumeUp : HardwareKeyAction()
    data object VolumeDown : HardwareKeyAction()
    data object ToggleMute : HardwareKeyAction()
    data object LockScreen : HardwareKeyAction()

    companion object {
        fun fromKeyCode(keyCode: Int): HardwareKeyAction? =
            when (keyCode) {
                24 -> VolumeUp
                25 -> VolumeDown
                164 -> ToggleMute
                26 -> LockScreen
                else -> null
            }
    }
}
```

- [ ] **Step 4: Replace wrong raw global action IDs with AudioManager volume actions**

In `GalaxyMirrorAccessibilityService.kt`, add imports:

```kotlin
import android.content.Context
import android.media.AudioManager
```

Replace the volume and power part of `handleKeyEvent` with:

```kotlin
            24, 25, 164, 26 -> handleHardwareKeyAction(keyCode)
```

Add this method below `handleKeyEvent`:

```kotlin
    private fun handleHardwareKeyAction(keyCode: Int): Boolean {
        return when (HardwareKeyAction.fromKeyCode(keyCode)) {
            HardwareKeyAction.VolumeUp -> adjustMusicVolume(AudioManager.ADJUST_RAISE)
            HardwareKeyAction.VolumeDown -> adjustMusicVolume(AudioManager.ADJUST_LOWER)
            HardwareKeyAction.ToggleMute -> adjustMusicVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            HardwareKeyAction.LockScreen -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    false
                }
            }
            null -> false
        }
    }

    private fun adjustMusicVolume(direction: Int): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        return true
    }
```

Remove these wrong calls entirely:

```kotlin
performGlobalAction(10)
performGlobalAction(11)
performGlobalAction(12)
```

- [ ] **Step 5: Keep validator tests focused on payload acceptance**

In `ControlEventValidatorTest.kt`, rename the test method:

```kotlin
    @Test
    fun isValid_acceptsNewVolumeMuteAndPowerKeyPayloads() {
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":24}"""))) // Volume Up
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":25}"""))) // Volume Down
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":164}"""))) // Volume Mute
        assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"key","keyCode":26}"""))) // Lock Screen
    }
```

- [ ] **Step 6: Run hardware key and validator tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.HardwareKeyActionTest
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.ControlEventValidatorTest
```

Expected: PASS.

- [ ] **Step 7: Commit hardware key repair**

Run:

```bash
git add android/app/src/main/java/com/example/galaxymirror/HardwareKeyAction.kt \
  android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt \
  android/app/src/test/java/com/example/galaxymirror/HardwareKeyActionTest.kt \
  android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt
git commit -m "fix: route remote volume keys through audio manager"
```

---

### Task 3: CONTROL_ACK JSON Escaping Repair

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt`

- [ ] **Step 1: Add failing JSON escaping tests**

Append this test to `ControlEventResultTest.kt`:

```kotlin
    @Test
    fun ackJsonEscapesRemoteControlledStrings() {
        val json =
            JSONObject(
                ControlEventResult(
                    seq = 7,
                    type = """bad"type\with
newline""",
                    applied = false,
                    message = """CONTROL_EVENT_REJECTED "quoted"""",
                ).toAckJson()
            )

        val payload = json.getJSONObject("payload")
        assertEquals(7, payload.getLong("seq"))
        assertEquals(
            """bad"type\with
newline""",
            payload.getString("eventType")
        )
        assertEquals(false, payload.getBoolean("applied"))
        assertEquals("""CONTROL_EVENT_REJECTED "quoted"""", payload.getString("message"))
    }
```

- [ ] **Step 2: Run ACK tests to verify they fail**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.ControlEventResultTest
```

Expected: FAIL with a JSON parse error from the raw interpolated string.

- [ ] **Step 3: Restore structured JSON serialization**

Replace `ControlEventResult.kt` with:

```kotlin
package com.example.galaxymirror

import org.json.JSONObject

data class ControlEventResult(
    val seq: Long?,
    val type: String,
    val applied: Boolean,
    val message: String,
) {
    fun toAckJson(): String =
        JSONObject()
            .put("type", "CONTROL_ACK")
            .put(
                "payload",
                JSONObject()
                    .put("seq", seq)
                    .put("eventType", type)
                    .put("applied", applied)
                    .put("message", message)
            )
            .toString()
}
```

- [ ] **Step 4: Run ACK tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.ControlEventResultTest
```

Expected: PASS.

- [ ] **Step 5: Commit ACK serialization repair**

Run:

```bash
git add android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt \
  android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt
git commit -m "fix: restore escaped control ack serialization"
```

---

### Task 4: Clipboard Clear Semantics And Browser Clipboard Fallback

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Add failing JS clipboard tests and fix duplicate timer draining**

In `viewer-keyboard.test.mjs`, replace `FakeClock.tick(ms)` with:

```javascript
    tick(ms) {
        let remaining = ms;
        while (true) {
            this.tasks.sort((a, b) => a.remaining - b.remaining);
            const next = this.tasks[0];
            if (!next || next.remaining > remaining) break;

            const elapsed = next.remaining;
            for (const task of this.tasks) {
                task.remaining -= elapsed;
            }
            remaining -= elapsed;

            const due = this.tasks.filter(task => task.remaining <= 0);
            this.tasks = this.tasks.filter(task => task.remaining > 0);
            for (const task of due) {
                task.callback();
            }
        }

        for (const task of this.tasks) {
            task.remaining -= remaining;
        }
    }
```

Change the `loadViewer` signature and context setup:

```javascript
function loadViewer(options = {}) {
```

Inside the context object, add:

```javascript
        navigator: options.navigator || {},
        MediaRecorder: options.MediaRecorder,
```

After `context.window.document = contextDocument;`, add:

```javascript
    context.window.navigator = context.navigator;
    context.window.MediaRecorder = context.MediaRecorder;
```

Replace the current clipboard test with:

```javascript
await test('copy event sends clipboard payload through dataChannel once', async () => {
    const { context, messages, clock } = loadViewer({
        navigator: {
            clipboard: {
                readText: async () => 'copied-from-mac',
                writeText: async () => {}
            }
        }
    });

    const copyEvent = { type: 'copy', preventDefault() {} };
    context.document.dispatchEvent(copyEvent);

    clock.runAll();
    await flushAsyncWork();

    assert.deepEqual(messages, [
        { type: 'clipboard', text: 'copied-from-mac' }
    ]);
});

await test('copy event propagates empty clipboard text as clear command', async () => {
    const { context, messages, clock } = loadViewer({
        navigator: {
            clipboard: {
                readText: async () => '',
                writeText: async () => {}
            }
        }
    });

    context.document.dispatchEvent({ type: 'copy', preventDefault() {} });

    clock.runAll();
    await flushAsyncWork();

    assert.deepEqual(messages, [
        { type: 'clipboard', text: '' }
    ]);
});

await test('received clipboard text uses manual fallback when Clipboard API is unavailable', async () => {
    const { context, document } = loadViewer({ navigator: {} });

    vm.runInContext(
        'dataChannel.onmessage({ data: JSON.stringify({ type: "clipboard", text: "from-android" }) });',
        context
    );

    const toastContainer = document.getElementById('toastContainer');
    assert.equal(toastContainer.children.length, 1);
    assert.match(toastContainer.children[0].textContent, /클립보드 수신/);
});
```

- [ ] **Step 2: Run JS tests to verify clipboard tests fail**

Run:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: FAIL because `navigator` is unavailable at load time, empty clipboard text is skipped, and the fallback path still assumes Clipboard API.

- [ ] **Step 3: Update Android outbound clipboard to allow empty strings**

In `GalaxyMirrorAccessibilityService.kt`, replace the clipboard listener text gate:

```kotlin
                val text = clip.getItemAt(0).coerceToText(this)?.toString()
                if (text != null && text != lastInjectedClipboardText) {
```

Keep the existing `JSONObject().put("type", "clipboard").put("text", text)` send path.

- [ ] **Step 4: Add viewer clipboard helpers**

In `viewer.js`, add these helpers above `setupDataChannelHandlers(channel)`:

```javascript
function hasClipboardWriteApi() {
    return Boolean(navigator?.clipboard && typeof navigator.clipboard.writeText === 'function');
}

function hasClipboardReadApi() {
    return Boolean(navigator?.clipboard && typeof navigator.clipboard.readText === 'function');
}

function showManualClipboardFallback(text) {
    const toast = showGlowToast("클립보드 수신 (클릭하여 복사)");
    if (!toast) return;
    toast.style.pointerEvents = 'auto';
    toast.style.cursor = 'pointer';
    toast.onclick = () => {
        const textArea = document.createElement('textarea');
        textArea.value = text;
        textArea.setAttribute('readonly', 'readonly');
        textArea.style.position = 'fixed';
        textArea.style.left = '-9999px';
        document.body?.appendChild?.(textArea);
        textArea.focus();
        textArea.select?.();
        try {
            document.execCommand?.('copy');
            showGlowToast("복사 완료!");
        } catch (error) {
            log(`수동 클립보드 복사 실패: ${error.message}`);
        } finally {
            textArea.remove?.();
        }
    };
}

async function writeClipboardFromAndroid(text) {
    if (!hasClipboardWriteApi()) {
        showManualClipboardFallback(text);
        return;
    }
    try {
        await navigator.clipboard.writeText(text);
        showGlowToast(text === "" ? "갤럭시 클립보드 비우기와 동기화되었습니다." : "갤럭시 클립보드와 동기화되었습니다.");
    } catch (error) {
        log(`브라우저 클립보드 쓰기 실패(보안 제약): ${error.message}`);
        showManualClipboardFallback(text);
    }
}

async function readClipboardForAndroid() {
    if (!hasClipboardReadApi()) {
        log("브라우저 클립보드 읽기 API를 사용할 수 없습니다.");
        return null;
    }
    return navigator.clipboard.readText();
}
```

- [ ] **Step 5: Use helpers in DataChannel clipboard receive and copy send**

In `setupDataChannelHandlers(channel)`, replace the clipboard branch with:

```javascript
            } else if (message.type === 'clipboard') {
                const text = message.text;
                if (typeof text === 'string') {
                    writeClipboardFromAndroid(text);
                }
```

Replace `setupClipboardSync()` with:

```javascript
function setupClipboardSync() {
    document.addEventListener('copy', () => {
        setTimeout(async () => {
            try {
                const text = await readClipboardForAndroid();
                if (text !== null && dataChannel && dataChannel.readyState === 'open') {
                    const sent = sendControlPayload({ type: 'clipboard', text });
                    if (sent) {
                        log(`맥 클립보드 원격 전송 성공: length=${text.length}`);
                    }
                }
            } catch (e) {
                log(`맥 클립보드 읽기/전송 실패: ${e.message}`);
            }
        }, 100);
    });
}
```

- [ ] **Step 6: Run clipboard tests**

Run:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: PASS.

- [ ] **Step 7: Run Android tests touched by clipboard validation**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon --tests com.example.galaxymirror.ControlEventValidatorTest
```

Expected: PASS.

- [ ] **Step 8: Commit clipboard repair**

Run:

```bash
git add android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt \
  android/app/src/main/resources/files/viewer.js \
  android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "fix: harden clipboard sync fallbacks"
```

---

### Task 5: Viewer Reconnect And Recording Repair

**Files:**
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Add failing reconnect and recording tests**

In `FakeWebSocket.close()`, replace the method with:

```javascript
        close(code = 1000, reason = '') {
            this.readyState = FakeWebSocket.CLOSED;
            this.onclose?.({ code, reason });
        }
```

Append these tests to `viewer-keyboard.test.mjs`:

```javascript
await test('manual connect click refreshes an existing signaling session immediately', async () => {
    const { context, document, webSockets } = loadViewer();

    vm.runInContext('connectSignaling();', context);
    webSockets[0].onopen();
    await flushAsyncWork();

    document.getElementById('connectBtn').dispatchEvent({ type: 'click', preventDefault() {} });

    assert.equal(webSockets.length, 2);
});

await test('auto reconnect schedules backoff after closing an open signaling socket', async () => {
    const { context, webSockets, clock } = loadViewer();

    vm.runInContext('connectSignaling();', context);
    webSockets[0].onopen();
    await flushAsyncWork();

    vm.runInContext('triggerAutoReconnect();', context);

    assert.equal(webSockets.length, 1);
    clock.tick(1000);

    assert.equal(webSockets.length, 2);
});

await test('record button handles missing MediaRecorder without throwing', () => {
    const { document, remoteVideo } = loadViewer({ MediaRecorder: undefined });
    remoteVideo.srcObject = {};

    assert.doesNotThrow(() => {
        document.getElementById('recordBtn').dispatchEvent({ type: 'click', preventDefault() {} });
    });
});
```

- [ ] **Step 2: Run JS tests to verify new tests fail**

Run:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: FAIL on reconnect behavior and missing `MediaRecorder` guard.

- [ ] **Step 3: Fix manual refresh and auto reconnect close handling**

Add this state near other reconnect state variables in `viewer.js`:

```javascript
let reconnectCloseInProgress = false;
```

In `connectSignaling()`, reset it on successful open:

```javascript
        reconnectCloseInProgress = false;
```

Replace the explicit close logic in `signalingSocket.onclose`:

```javascript
        const isExplicitClose = event.code === 1008 || !shouldAutoReconnect;

        if (reconnectCloseInProgress) {
            reconnectCloseInProgress = false;
            startReconnectSequence();
            return;
        }
```

Then keep the existing explicit/manual and `startReconnectSequence()` branches below.

Replace the connect button handler with:

```javascript
connectBtn.addEventListener('click', () => {
    shouldAutoReconnect = true;
    statusDetailMessage = "";
    reconnectAttempts = 0;
    isReconnecting = false;
    reconnectCloseInProgress = false;
    if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
    }
    hideReconnectOverlay();

    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
        log("기존 연결이 감지되어 세션을 갱신합니다.");
        const oldSocket = socket;
        oldSocket.onclose = null;
        oldSocket.close();
        socket = null;
        cleanupPeerConnection();
        connectSignaling();
    } else {
        connectSignaling();
    }
});
```

Replace `triggerAutoReconnect()` with:

```javascript
function triggerAutoReconnect() {
    if (isReconnecting || reconnectTimeoutId) return;
    log("네트워크 단절 감지 - 자동 재연결 복원을 시작합니다.");
    isReconnecting = true;
    if (socket && socket.readyState !== WebSocket.CLOSED && socket.readyState !== WebSocket.CLOSING) {
        reconnectCloseInProgress = true;
        socket.close();
    } else {
        startReconnectSequence();
    }
}
```

- [ ] **Step 4: Guard MediaRecorder and centralize recording cleanup**

Add this helper above `setupMediaCapture()`:

```javascript
function selectRecordingOptions() {
    if (typeof MediaRecorder === 'undefined') return null;
    if (typeof MediaRecorder.isTypeSupported !== 'function') return {};

    const candidates = [
        { mimeType: 'video/webm;codecs=vp9' },
        { mimeType: 'video/webm;codecs=vp8' },
        { mimeType: 'video/webm' }
    ];
    return candidates.find(option => MediaRecorder.isTypeSupported(option.mimeType)) || {};
}

function resetRecordingState(recordBtn) {
    isRecording = false;
    mediaRecorder = null;
    if (recordBtn) {
        recordBtn.classList.remove('recording');
        recordBtn.title = "화면 녹화 시작";
        recordBtn.textContent = "⏺️";
    }
}
```

In the recording start branch, replace codec option selection and recorder setup with:

```javascript
                const stream = video.srcObject;
                recordedChunks = [];
                const options = selectRecordingOptions();
                if (options === null) {
                    showGlowToast("이 브라우저는 화면 녹화를 지원하지 않습니다.");
                    return;
                }

                try {
                    mediaRecorder = new MediaRecorder(stream, options);
                    mediaRecorder.ondataavailable = (e) => {
                         if (e.data && e.data.size > 0) {
                              recordedChunks.push(e.data);
                         }
                    };
                    mediaRecorder.onerror = (event) => {
                         log(`녹화 중 오류 발생: ${event?.error?.message || 'unknown'}`);
                         resetRecordingState(recordBtn);
                         showGlowToast("화면 녹화 중 오류가 발생했습니다.");
                    };
                    mediaRecorder.onstop = () => {
                         if (recordedChunks.length > 0) {
                             const blob = new Blob(recordedChunks, { type: 'video/webm' });
                             const url = URL.createObjectURL(blob);
                             const link = document.createElement('a');
                             const date = new Date().toISOString().replace(/[:.]/g, '-');
                             link.download = `recording_${date}.webm`;
                             link.href = url;
                             link.click();
                             setTimeout(() => URL.revokeObjectURL(url), 1000);
                             showGlowToast("화면 녹화본이 저장되었습니다.");
                         }
                         resetRecordingState(recordBtn);
                    };

                    mediaRecorder.start();
                    isRecording = true;
                    recordBtn.classList.add('recording');
                    recordBtn.title = "화면 녹화 중지";
                    recordBtn.textContent = "⏹️";
                    showGlowToast("화면 녹화를 시작했습니다.");
                } catch (err) {
                    resetRecordingState(recordBtn);
                    log(`녹화 초기화 실패: ${err.message}`);
                    showGlowToast("화면 녹화를 시작할 수 없습니다.");
                }
```

In the recording stop branch, replace manual UI reset with:

```javascript
                if (mediaRecorder) {
                    mediaRecorder.stop();
                } else {
                    resetRecordingState(recordBtn);
                }
                showGlowToast("녹화를 중지하고 파일을 생성하는 중입니다...");
```

- [ ] **Step 5: Run JS tests**

Run:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit viewer reconnect and recording repair**

Run:

```bash
git add android/app/src/main/resources/files/viewer.js \
  android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "fix: repair viewer reconnect and recording fallbacks"
```

---

### Task 6: Protocol Docs, Handoff, CI, And Whitespace

**Files:**
- Modify: `docs/Protocols.md`
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`
- Modify: `docs/Dashboard.md`
- Modify: `.github/workflows/android-build.yml`
- Modify whitespace only: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`, `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`, `android/app/src/main/resources/files/index.html`, `android/app/src/main/resources/files/viewer.js`

- [ ] **Step 1: Add viewer JS tests to CI**

In `.github/workflows/android-build.yml`, add this step after `Run unit tests`:

```yaml
    - name: Run viewer JavaScript tests
      run: node --test android/app/src/test/js/viewer-keyboard.test.mjs
```

- [ ] **Step 2: Update `docs/Protocols.md` key and clipboard protocol**

Replace the allowed-key paragraph in section `2.2 제한된 키 제어` with:

```markdown
허용 키코드는 `4`(Back), `3`(Home), `187`(Recent apps), `24`(Volume Up),
`25`(Volume Down), `164`(Mute toggle), `26`(Lock Screen)입니다. Android Host는
Back/Home/Recent/Lock Screen은 접근성 전역 액션으로 처리하고, Volume Up/Down/Mute는
`AudioManager.adjustStreamVolume()`로 처리합니다. 볼륨 키는 raw Accessibility global action
ID를 사용하지 않습니다.
```

Add this section after `2.3 텍스트 입력` and before `2.4 제어 입력 ACK`:

```markdown
### 2.4 클립보드 동기화 (`clipboard`)
Mac Viewer와 Android Host는 `control` DataChannel의 `clipboard` payload로 평문 클립보드
텍스트를 동기화합니다. 빈 문자열은 "원격 클립보드 비우기" 명령으로 취급하므로, 구현체는
`text` 필드가 존재하는지 확인하고 truthy 여부로 버리면 안 됩니다.

* **클립보드 전송 예시:**
```json
{
  "type": "clipboard",
  "text": "copied text"
}
```

* **클립보드 비우기 예시:**
```json
{
  "type": "clipboard",
  "text": ""
}
```

브라우저 Clipboard API는 `http://<MagicDNS-host>:8080` 같은 일반 HTTP origin에서 브라우저
정책에 따라 제한될 수 있습니다. Viewer는 `navigator.clipboard`를 feature-detect하고, 자동
쓰기 실패 시 수동 복사 fallback toast를 표시합니다.
```

Then renumber the current `2.4 제어 입력 ACK` heading to:

```markdown
### 2.5 제어 입력 ACK (`CONTROL_ACK`)
```

- [ ] **Step 3: Update MediaProjection reconnect policy docs**

In `docs/Protocols.md`, replace section `5. MediaProjection 재연결 정책` with:

```markdown
## 5. MediaProjection 재연결 정책

Android 14+ 계열에서는 화면 공유 승인 결과 Intent를 같은 projection 세션 재생성에 재사용할
수 없습니다. Galaxy Mirror는 보수적인 개인정보 보호 정책을 따른다: viewer WebSocket이
닫히거나 새 viewer 세션이 기존 세션을 교체하면 Android Host는 WebRTC peer connection,
DataChannel, ScreenCapturerAndroid, VideoSource/VideoTrack, EGL/PeerConnectionFactory, 저장된
projection Intent를 정리합니다.

이후 새 Offer가 들어오면 Android Host는 `WAITING_FOR_SCREEN_CAPTURE` 상태를 보내고,
바인딩된 `MainActivity`에 화면 공유 권한 요청을 트리거합니다. Android 사용자가 화면 공유를
승인하면 pending offer를 재개하고 새 projection token으로 WebRTC 협상을 시작합니다. 화면
잠금, 화면 꺼짐, 시스템 projection 중단, `startCapture()` 실패는 모두
`SCREEN_CAPTURE_REAUTH_REQUIRED` 상태로 귀결되며, 기존 capturer는 재사용하지 않습니다.
```

- [ ] **Step 4: Update Handoff Milestone 6 status and next tasks**

In `docs/Handoff.md`, under Milestone 6, keep the feature boxes checked only after implementation is complete. Add this note below Milestone 6:

```markdown
> 2026-06-08 코드 리뷰 후속 하드닝: 볼륨 키는 `AudioManager` 기반으로 보정했고,
> clipboard empty-string 동기화와 HTTP origin fallback을 보강했습니다. MediaProjection은
> viewer 종료/교체 시 캡처를 정리하고 다음 Offer에서 새 화면 공유 승인을 요청하는
> privacy-first 정책으로 확정했습니다.
```

If a subtask is still being implemented in the current branch, use unchecked bullets under a new `Post-review hardening` subsection:

```markdown
### 🧯 Post-review hardening
- [ ] MediaProjection viewer close/replacement cleanup verified on device
- [ ] Clipboard sync verified on actual `http://<MagicDNS-host>:8080` viewer origin
- [ ] Volume up/down/mute verified on physical device
```

Before final commit, convert these to checked boxes only after the real smoke checks pass.

- [ ] **Step 5: Update Log**

Append this entry to `docs/Log.md`:

```markdown
### 2026-06-08 (Post-review hardening plan and fixes)
* `docs/CodeReview-2026-06-08.md`의 병렬 리뷰 결과를 기준으로 MediaProjection 생명주기,
  원격 볼륨 키, 클립보드 fallback/empty-string, `CONTROL_ACK` JSON escaping, Viewer 재연결,
  녹화 API guard, JS 테스트 CI 누락을 순차 수정 대상으로 확정했습니다.
* MediaProjection 정책은 viewer 종료/교체 시 화면 캡처를 정리하고 새 Offer에서 Android
  화면 공유 권한을 다시 요청하는 privacy-first 방식으로 맞춥니다.
```

- [ ] **Step 6: Update Dashboard**

In `docs/Dashboard.md`, replace the M6 roadmap block with:

```markdown
- [x] **M6: 양방향 클립보드 동기화 및 고급 제어 기능 구현 (완료)**
  - [x] 양방향 클립보드 데이터 전송 및 디바이스 연동
  - [x] 물리 하드웨어 키(볼륨, 잠금) 제어 주입
  - [x] 브라우저 내 스크린샷 및 미디어 레코더 녹화 기능 구현
  - [x] 코드 리뷰 후속 하드닝: HTTP origin 클립보드 fallback, 볼륨 AudioManager 라우팅, MediaProjection privacy-first 재승인 정책 반영
```

- [ ] **Step 7: Strip trailing whitespace**

Run:

```bash
perl -0pi -e 's/[ \t]+$//mg' \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt \
  android/app/src/main/resources/files/index.html \
  android/app/src/main/resources/files/viewer.js
```

- [ ] **Step 8: Run docs/CI related checks**

Run:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
git diff --check
```

Expected: JS tests PASS and `git diff --check` prints no output.

- [ ] **Step 9: Commit docs, CI, and whitespace cleanup**

Run:

```bash
git add .github/workflows/android-build.yml \
  docs/Protocols.md docs/Handoff.md docs/Log.md docs/Dashboard.md \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt \
  android/app/src/main/resources/files/index.html \
  android/app/src/main/resources/files/viewer.js
git commit -m "chore: align post-review docs and CI"
```

---

### Task 7: Full Verification And Device Smoke Checklist

**Files:**
- Modify only if verification exposes a bug in a prior task-owned file.
- Read: `docs/CodeReview-2026-06-08.md`
- Read: `docs/Handoff.md`
- Read: `docs/Protocols.md`

- [ ] **Step 1: Run full local verification**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon
./gradlew app:lintDebug --no-daemon
./gradlew assembleDebug --no-daemon
cd ..
node --test android/app/src/test/js/viewer-keyboard.test.mjs
git diff --check
```

Expected:
- Gradle unit tests PASS.
- Lint PASS or reports only pre-existing warnings that are documented in the final summary.
- Debug assemble PASS.
- Node viewer tests PASS.
- `git diff --check` prints no output.

- [ ] **Step 2: Physical Android smoke test**

Use an actual Galaxy device on the private Tailscale network:

```text
1. Install/run the debug APK.
2. Open the Android app and confirm the viewer URL includes the token.
3. Open `http://<MagicDNS-host>:8080/?token=<token>` from the Mac browser.
4. Click `미러링 연결하기`.
5. Approve Android screen sharing.
6. Confirm video frames render in the Mac viewer.
7. Tap and swipe inside the viewer and confirm Android responds.
8. Type Korean and Latin text, press Enter, press Backspace, and confirm text behavior.
9. Click Volume Up, Volume Down, Mute, and Lock Screen; confirm volume changes and lock works.
10. Copy text on Mac while viewer is focused; confirm Android clipboard receives it.
11. Clear or copy empty clipboard content where possible; confirm stale clipboard text is not retained.
12. Copy text on Android; confirm Mac viewer receives it or shows manual fallback on HTTP origin.
13. Click screenshot; confirm PNG downloads.
14. Start and stop recording; confirm WebM downloads and button resets.
15. Close the Mac viewer tab; confirm Android capture notification/service state stops capture.
16. Reopen viewer and reconnect; confirm a fresh Android screen-share prompt is requested.
17. Lock/unlock the device during mirroring; confirm viewer shows reauth state and reconnect works after approval.
```

- [ ] **Step 3: Update checklist results in docs**

If every physical smoke check passes in a later device session, update the `Post-review hardening` checklist in `docs/Handoff.md` from unchecked to checked:

```markdown
### 🧯 Post-review hardening
- [x] MediaProjection viewer close/replacement cleanup verified on device
- [x] Clipboard sync verified on actual `http://<MagicDNS-host>:8080` viewer origin
- [x] Volume up/down/mute verified on physical device
```

Then append this line to the latest `docs/Log.md` entry:

```markdown
* 실기기 스모크 테스트에서 viewer 종료/재연결, 화면 공유 재승인, 볼륨/잠금, 양방향 클립보드, 스크린샷, 녹화 흐름을 확인했습니다.
```

- [ ] **Step 4: Commit verification docs**

Run:

```bash
git add docs/Handoff.md docs/Log.md
git commit -m "docs: record post-review smoke verification"
```

- [ ] **Step 5: Final status check**

Run:

```bash
git status --short
git log --oneline -5
```

Expected:
- `git status --short` is empty.
- The latest commits are the task commits from this plan.

---

## Self-Review Checklist

- [x] Spec coverage: every finding in `docs/CodeReview-2026-06-08.md` maps to a task.
- [x] P1 MediaProjection stopped capturer reuse: Task 1.
- [x] P1 capture active without viewer: Task 1 and Task 6.
- [x] P1 auto reconnect stops before backoff: Task 5.
- [x] P1 missing permission prompt: Task 1.
- [x] P1 volume wrong global actions: Task 2.
- [x] P1 Clipboard API HTTP origin failure: Task 4.
- [x] P2 startCapture partial state: Task 1.
- [x] P2 manual refresh button only disconnects: Task 5.
- [x] P2 ACK escaping: Task 3.
- [x] P2 empty clipboard not propagated: Task 4 and Task 6.
- [x] P2 MediaRecorder unsupported browser throw: Task 5.
- [x] P2 stale protocol docs: Task 6.
- [x] P2 reconnect policy docs contradiction: Task 6.
- [x] P3 recording UI stuck: Task 5.
- [x] P3 duplicate clipboard listener/test clock: Task 4.
- [x] P3 JS tests missing in CI: Task 6.
- [x] P3 trailing whitespace: Task 6.
- [x] Placeholder scan: no marker strings or vague edge-case steps remain.
- [x] Type consistency: `HardwareKeyAction`, `screenCapturePermissionRequired`, `onScreenCapturePermissionRequired`, and `requestScreenCapturePermissionFromActivity` are named consistently across tasks.
