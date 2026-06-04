# Refactor, Hardening, and Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden Android Mirror's reconnect/input/control surfaces, reduce idle data usage, and split the largest files so future WebRTC and remote-control work is safer.

**Architecture:** First make lifecycle cleanup and session state deterministic, because those are the highest-risk paths for reconnect failures. Then add a local viewer token for Tailnet defense-in-depth, add ACK-based text input ordering, and introduce adaptive idle stream quality. Finish by extracting Ktor routes, WebRTC session orchestration, and viewer modules after behavior is covered by tests.

**Tech Stack:** Android Kotlin, Jetpack Compose Material3, Ktor CIO HTTP/WebSocket, kotlinx.coroutines, org.webrtc, AccessibilityService, SharedPreferences, Vanilla HTML/JS, Node VM tests, JUnit.

## Execution Status

- [x] Task 1 Cleanup hardening
- [x] Task 2 Session state reducer integration
- [x] Task 3 Viewer access token guard
- [x] Task 4 ACK-based text input ordering
- [x] Task 5 Adaptive active/idle stream quality
- [ ] Task 6 Large-file structural split
  - Deferred to the next refactor pass because the hardening changes are broad and already verified as a behavior-preserving integration set.
- [x] Task 7 Documentation and verification

---

## Execution Order

1. Task 1 must run first because cleanup failures can mask later session and stream bugs.
2. Task 2 must run before token, ACK, or adaptive quality changes because it establishes a single session state owner.
3. Tasks 3 and 5 can run in parallel after Task 2 because access control and stream quality touch different seams.
4. Task 4 should run after Task 2 because text ACK needs the active `DataChannel` and session identity to be stable.
5. Task 6 should run after Tasks 1-5 so the refactor moves known-good behavior, not ambiguous behavior.
6. Task 7 closes the loop with docs and full verification.

## File Structure

- Create: `android/app/src/main/java/com/example/galaxymirror/CleanupStepRunner.kt`
  - Runs named cleanup steps independently and returns every failure without aborting later steps.
- Create: `android/app/src/test/java/com/example/galaxymirror/CleanupStepRunnerTest.kt`
  - Covers "all cleanup steps execute even when one throws".
- Create: `android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt`
  - Pure Kotlin state reducer for active session id, pending offer presence, projection readiness, and reauth-required state.
- Create: `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`
  - Covers replace/end/projection-stop/offer-queue transitions.
- Create: `android/app/src/main/java/com/example/galaxymirror/ViewerAccessTokenStore.kt`
  - Persists a per-install random viewer token.
- Create: `android/app/src/main/java/com/example/galaxymirror/ViewerAccessGuard.kt`
  - Validates token from query string or `X-Android-Mirror-Token`.
- Create: `android/app/src/test/java/com/example/galaxymirror/ViewerAccessGuardTest.kt`
  - Covers accepted and rejected token shapes.
- Create: `android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt`
  - Represents applied/rejected control input with optional `seq` for ACKs.
- Create: `android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt`
  - Covers ACK JSON shape.
- Create: `android/app/src/main/java/com/example/galaxymirror/AdaptiveStreamQuality.kt`
  - Resolves active vs idle profile from selected mode, network, and viewer activity.
- Create: `android/app/src/test/java/com/example/galaxymirror/AdaptiveStreamQualityTest.kt`
  - Covers idle profile lowering and active profile restoration.
- Create: `android/app/src/main/java/com/example/galaxymirror/MirrorHttpServer.kt`
  - Owns Ktor server creation, token guard, HTTP routes, and WebSocket route wiring.
- Create: `android/app/src/main/java/com/example/galaxymirror/WebRtcSessionController.kt`
  - Owns session lifecycle, WebRTC setup, cleanup, signaling handling, and stream-quality application.
- Create: `android/app/src/main/resources/files/viewer-signaling.js`
  - Owns WebSocket and WebRTC signaling.
- Create: `android/app/src/main/resources/files/viewer-control.js`
  - Owns DataChannel control, touch, navigation, keyboard ACK bridge.
- Create: `android/app/src/main/resources/files/viewer-stats.js`
  - Owns WebRTC stats/data usage rendering.
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
  - Shrink to Android lifecycle, permission launchers, Compose state wiring, and controller callbacks.
- Modify: `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
  - Return structured control results instead of swallowing all control outcomes internally.
- Modify: `android/app/src/main/resources/files/viewer-keyboard.js`
  - Add sequential text commit queue and wait for Android ACK before sending the next batch.
- Modify: `android/app/src/main/resources/files/viewer.js`
  - Keep only top-level bootstrapping after module extraction.
- Modify: `android/app/src/main/resources/files/index.html`
  - Load split JS modules and include the viewer token in client requests.
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
  - Display the tokenized viewer URL.
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
  - Update Korean setup copy for tokenized URL and security expectations.
- Modify: `docs/Protocols.md`, `docs/Handoff.md`, `docs/Log.md`, `README.md`
  - Document token, ACK, cleanup/session behavior, and adaptive stream quality.

---

### Task 1: Cleanup Hardening

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/CleanupStepRunner.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/CleanupStepRunnerTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`

- [ ] **Step 1: Write the failing cleanup runner test**

Create `android/app/src/test/java/com/example/galaxymirror/CleanupStepRunnerTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class CleanupStepRunnerTest {
    @Test
    fun runsEveryStepEvenWhenOneStepFails() {
        val calls = mutableListOf<String>()
        val failures =
            CleanupStepRunner.run(
                listOf(
                    CleanupStep("first") { calls += "first" },
                    CleanupStep("throws") {
                        calls += "throws"
                        error("boom")
                    },
                    CleanupStep("last") { calls += "last" },
                )
            )

        assertEquals(listOf("first", "throws", "last"), calls)
        assertEquals(1, failures.size)
        assertEquals("throws", failures.single().name)
        assertTrue(failures.single().throwable.message!!.contains("boom"))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.CleanupStepRunnerTest --no-daemon
```

Expected: compile fails because `CleanupStepRunner` and `CleanupStep` do not exist.

- [ ] **Step 3: Implement the cleanup runner**

Create `android/app/src/main/java/com/example/galaxymirror/CleanupStepRunner.kt`:

```kotlin
package com.example.galaxymirror

data class CleanupStep(
    val name: String,
    val action: () -> Unit,
)

data class CleanupFailure(
    val name: String,
    val throwable: Throwable,
)

object CleanupStepRunner {
    fun run(steps: List<CleanupStep>): List<CleanupFailure> {
        val failures = mutableListOf<CleanupFailure>()
        steps.forEach { step ->
            try {
                step.action()
            } catch (throwable: Throwable) {
                failures += CleanupFailure(step.name, throwable)
            }
        }
        return failures
    }
}
```

- [ ] **Step 4: Run GREEN for cleanup runner**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.CleanupStepRunnerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Replace broad cleanup try in `MainActivity`**

Modify `cleanupWebRTCResources(...)` in `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt` so resource cleanup uses named steps and nulling always runs:

```kotlin
private fun cleanupWebRTCResources(stopProjectionService: Boolean, stopCapturer: Boolean = true) {
  val failures =
    CleanupStepRunner.run(
      listOf(
        CleanupStep("control channel close") { controlChannel?.close() },
        CleanupStep("video capturer stop") {
          if (stopCapturer) {
            videoCapturer?.stopCapture()
          }
        },
        CleanupStep("video capturer dispose") { videoCapturer?.dispose() },
        CleanupStep("surface texture helper dispose") { surfaceTextureHelper?.dispose() },
        CleanupStep("peer connection close") { peerConnection?.close() },
        CleanupStep("peer connection factory dispose") { peerConnectionFactory?.dispose() },
        CleanupStep("egl release") { eglBase?.release() },
      )
    )

  failures.forEach { failure ->
    CrashDiagnostics.recordCaughtException(filesDir, "WebRTC cleanup ${failure.name}", failure.throwable)
    Log.e("WebRTC", "Error during WebRTC cleanup step ${failure.name}", failure.throwable)
  }

  synchronized(pendingRemoteIceCandidates) {
    pendingRemoteIceCandidates.clear()
  }
  controlChannel = null
  videoSender = null
  videoCapturer = null
  videoTrack = null
  surfaceTextureHelper = null
  peerConnection = null
  peerConnectionFactory = null
  eglBase = null
  remoteDescriptionSet = false

  if (stopProjectionService) {
    stopService(Intent(this, MediaProjectionService::class.java))
    mediaProjectionResultData = null
  } else {
    MediaProjectionService.instance?.setKeepScreenAwake(
      screenAwakeSettings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings()),
    )
  }
  applyScreenAwakeWindowFlag()
  applyBrightnessMinimizationForCurrentState()
  Log.d("WebRTC", "WebRTC resources cleaned up with ${failures.size} cleanup failures.")
}
```

- [ ] **Step 6: Verify cleanup hardening**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/CleanupStepRunner.kt \
  android/app/src/test/java/com/example/galaxymirror/CleanupStepRunnerTest.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt
git commit -m "WebRTC 정리 흐름을 예외에 강하게 개선"
```

---

### Task 2: Serialized Session State

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`

- [ ] **Step 1: Write pure state tests**

Create `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MirrorSessionStateTest {
    @Test
    fun beginSessionReplacesPreviousSessionAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(1)
                .queueOffer(1)
                .beginSession(2)

        assertEquals(2, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
        assertFalse(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun endActiveSessionClearsSessionState() {
        val state =
            MirrorSessionState()
                .beginSession(7)
                .queueOffer(7)
                .endSession(7)

        assertEquals(0, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
    }

    @Test
    fun endingInactiveSessionDoesNotClearActiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(7)
                .endSession(6)

        assertEquals(7, state.activeSessionId)
    }

    @Test
    fun projectionStoppedRequiresReauthorizationAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(3)
                .queueOffer(3)
                .projectionStopped()

        assertEquals(0, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
        assertTrue(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun queueOfferIgnoresInactiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(3)
                .queueOffer(4)

        assertNull(state.pendingOfferSessionId)
        assertFalse(state.hasPendingOffer)
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

Expected: compile fails because `MirrorSessionState` does not exist.

- [ ] **Step 3: Implement pure session state**

Create `android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt`:

```kotlin
package com.example.galaxymirror

data class MirrorSessionState(
    val activeSessionId: Int = 0,
    val pendingOfferSessionId: Int? = null,
    val requiresScreenCaptureReauthorization: Boolean = false,
) {
    val hasPendingOffer: Boolean = pendingOfferSessionId != null

    fun beginSession(sessionId: Int): MirrorSessionState =
        copy(
            activeSessionId = sessionId,
            pendingOfferSessionId = null,
            requiresScreenCaptureReauthorization = false,
        )

    fun endSession(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(activeSessionId = 0, pendingOfferSessionId = null)
        } else {
            this
        }

    fun queueOffer(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(pendingOfferSessionId = sessionId)
        } else {
            this
        }

    fun clearPendingOffer(): MirrorSessionState = copy(pendingOfferSessionId = null)

    fun projectionStopped(): MirrorSessionState =
        copy(
            activeSessionId = 0,
            pendingOfferSessionId = null,
            requiresScreenCaptureReauthorization = true,
        )

    fun isActive(sessionId: Int): Boolean = activeSessionId == sessionId
}
```

- [ ] **Step 4: Run GREEN for state**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Add a session mutex to `MainActivity`**

Modify `MainActivity` fields:

```kotlin
private val sessionMutex = kotlinx.coroutines.sync.Mutex()
@Volatile private var mirrorSessionState = MirrorSessionState()
```

Keep `activeSessionId` only while migrating if needed, but make `isActiveSession(sessionId)` read from `mirrorSessionState.isActive(sessionId)`.

- [ ] **Step 6: Serialize session transitions**

Change `beginViewerSession()`, `endViewerSession(sessionId)`, `queuePendingOffer(...)`, and MediaProjection callback `onStop()` so they update `mirrorSessionState` inside `sessionMutex.withLock { ... }`. Use `withContext(Dispatchers.Main.immediate)` for transitions that also touch window flags or brightness:

```kotlin
private suspend fun beginViewerSessionSerialized(): Int =
  withContext(Dispatchers.Main.immediate) {
    sessionMutex.withLock {
      val sessionId = sessionCounter.incrementAndGet()
      val replacingSessionId = mirrorSessionState.activeSessionId
      if (replacingSessionId != 0) {
        CrashDiagnostics.recordEvent(this@MainActivity, "Replacing active viewer session: $replacingSessionId -> $sessionId.")
        pendingOffer = null
        cleanupWebRTCResources(
          stopProjectionService = CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_REPLACED)
        )
      }
      mirrorSessionState = mirrorSessionState.beginSession(sessionId)
      applyScreenAwakeWindowFlag()
      applyBrightnessMinimizationForCurrentState()
      sessionId
    }
  }
```

- [ ] **Step 7: Update WebSocket route to use serialized begin/end**

In the `/signaling` route, replace `val sessionId = beginViewerSession()` with:

```kotlin
val sessionId = beginViewerSessionSerialized()
```

In `finally`, replace `endViewerSession(sessionId)` with:

```kotlin
endViewerSessionSerialized(sessionId)
```

- [ ] **Step 8: Verify session serialization**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/MirrorSessionState.kt \
  android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt
git commit -m "미러링 세션 상태 전이를 직렬화"
```

---

### Task 3: Viewer Access Token

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/ViewerAccessTokenStore.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/ViewerAccessGuard.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/ViewerAccessGuardTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
- Modify: `android/app/src/main/resources/files/viewer.js`

- [ ] **Step 1: Write token guard tests**

Create `android/app/src/test/java/com/example/galaxymirror/ViewerAccessGuardTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ViewerAccessGuardTest {
    @Test
    fun acceptsMatchingQueryToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = "abc123", headerToken = null))
    }

    @Test
    fun acceptsMatchingHeaderToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = null, headerToken = "abc123"))
    }

    @Test
    fun rejectsMissingOrMismatchedToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertFalse(guard.isAllowed(queryToken = null, headerToken = null))
        assertFalse(guard.isAllowed(queryToken = "wrong", headerToken = null))
        assertFalse(guard.isAllowed(queryToken = null, headerToken = "wrong"))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ViewerAccessGuardTest --no-daemon
```

Expected: compile fails because `ViewerAccessGuard` does not exist.

- [ ] **Step 3: Implement token store and guard**

Create `android/app/src/main/java/com/example/galaxymirror/ViewerAccessGuard.kt`:

```kotlin
package com.example.galaxymirror

class ViewerAccessGuard(
    private val expectedToken: String,
) {
    fun isAllowed(queryToken: String?, headerToken: String?): Boolean {
        val token = queryToken?.takeIf { it.isNotBlank() } ?: headerToken?.takeIf { it.isNotBlank() }
        return token == expectedToken
    }
}
```

Create `android/app/src/main/java/com/example/galaxymirror/ViewerAccessTokenStore.kt`:

```kotlin
package com.example.galaxymirror

import android.content.Context
import java.security.SecureRandom

class ViewerAccessTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("viewer_access", Context.MODE_PRIVATE)

    fun getOrCreateToken(): String {
        preferences.getString(KEY_TOKEN, null)?.let { return it }
        val token = generateToken()
        preferences.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private fun generateToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_TOKEN = "token"
    }
}
```

- [ ] **Step 4: Run GREEN for guard**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ViewerAccessGuardTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Add token to Android UI state**

In `MainActivity`, initialize:

```kotlin
private lateinit var viewerAccessTokenStore: ViewerAccessTokenStore
private var viewerAccessToken by mutableStateOf("")
```

In `onCreate`:

```kotlin
viewerAccessTokenStore = ViewerAccessTokenStore(applicationContext)
viewerAccessToken = viewerAccessTokenStore.getOrCreateToken()
```

Pass `viewerAccessToken` to `MainScreen` and show a copyable address string:

```kotlin
val viewerPath = "/?token=$viewerAccessToken"
```

- [ ] **Step 6: Guard Ktor control routes**

In `MainActivity.startKtorServer()`, create:

```kotlin
val accessGuard = ViewerAccessGuard(viewerAccessTokenStore.getOrCreateToken())
```

For `/debug/crash`, `/debug/crash/clear`, `/apps/favorites`, `/apps/launch`, `/stream/quality`, and `/signaling`, reject requests when neither query token nor header token matches:

```kotlin
private suspend fun ApplicationCall.respondUnauthorizedViewer() {
  respondText(
    """{"ok":false,"error":"UNAUTHORIZED_VIEWER"}""",
    ContentType.Application.Json,
    HttpStatusCode.Unauthorized,
  )
}
```

For WebSocket, check `call.request.queryParameters["token"]` before beginning the viewer session. Close with `CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHORIZED_VIEWER")` when invalid.

- [ ] **Step 7: Add browser token propagation**

In `viewer.js`, parse token once:

```javascript
const viewerAccessToken = new URLSearchParams(window.location.search).get('token') || '';

function viewerAuthHeaders() {
    return viewerAccessToken ? { 'X-Android-Mirror-Token': viewerAccessToken } : {};
}
```

Use it for fetch:

```javascript
headers: { ...viewerAuthHeaders(), 'Content-Type': 'application/json' }
```

Use it for WebSocket:

```javascript
const wsUrl = `${protocol}//${window.location.host}/signaling?token=${encodeURIComponent(viewerAccessToken)}`;
```

- [ ] **Step 8: Verify token flow**

Run:

```bash
node --check android/app/src/main/resources/files/viewer.js
cd android
./gradlew app:testDebugUnitTest assembleDebug --no-daemon
```

Expected: both commands succeed.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/ViewerAccessGuard.kt \
  android/app/src/main/java/com/example/galaxymirror/ViewerAccessTokenStore.kt \
  android/app/src/test/java/com/example/galaxymirror/ViewerAccessGuardTest.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt \
  android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt \
  android/app/src/main/resources/files/viewer.js
git commit -m "뷰어 제어 API에 로컬 접근 토큰 추가"
```

---

### Task 4: ACK-Based Text Input Queue

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ControlEventValidator.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/resources/files/viewer-keyboard.js`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Write ACK result test**

Create `android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.json.JSONObject
import org.junit.Test

class ControlEventResultTest {
    @Test
    fun ackJsonContainsSeqAppliedAndMessage() {
        val json =
            JSONObject(
                ControlEventResult(
                    seq = 42,
                    type = "text",
                    applied = true,
                    message = "TEXT_COMMIT_APPLIED",
                ).toAckJson()
            )

        assertEquals("CONTROL_ACK", json.getString("type"))
        assertEquals(42, json.getJSONObject("payload").getLong("seq"))
        assertEquals("text", json.getJSONObject("payload").getString("eventType"))
        assertEquals(true, json.getJSONObject("payload").getBoolean("applied"))
        assertEquals("TEXT_COMMIT_APPLIED", json.getJSONObject("payload").getString("message"))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ControlEventResultTest --no-daemon
```

Expected: compile fails because `ControlEventResult` does not exist.

- [ ] **Step 3: Implement control result**

Create `android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt`:

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

- [ ] **Step 4: Return results from accessibility input**

Change `GalaxyMirrorAccessibilityService.handleControlEvent(json)` from `Unit` to `ControlEventResult`. Extract `seq` with:

```kotlin
val seq = if (json.has("seq")) json.optLong("seq") else null
```

Return `ControlEventResult(seq, "text", applied, "TEXT_COMMIT_APPLIED")` after commit/delete and `ControlEventResult(seq, type, dispatched, "GESTURE_DISPATCH_REQUESTED")` for tap/swipe.

- [ ] **Step 5: Send ACK over DataChannel**

In `MainActivity` DataChannel `onMessage`, after `handleControlEvent(json)`:

```kotlin
val result =
  GalaxyMirrorAccessibilityService.instance?.handleControlEvent(json)
    ?: ControlEventResult(
      seq = if (json.has("seq")) json.optLong("seq") else null,
      type = json.optString("type", "unknown"),
      applied = false,
      message = "ACCESSIBILITY_SERVICE_NOT_READY",
    )
if (result.seq != null) {
  dc.send(DataChannel.Buffer(ByteBuffer.wrap(result.toAckJson().toByteArray(Charsets.UTF_8)), false))
}
```

- [ ] **Step 6: Add JS ACK queue test**

In `android/app/src/test/js/viewer-keyboard.test.mjs`, add:

```javascript
await test('rapid text waits for Android ACK before next commit is sent', () => {
    const { keyboardSink, messages, clock, context } = loadViewer();

    keyboardSink.focus();
    keyboardSink.dispatchEvent(textInputEvent('input', 'a'));
    clock.runAll();
    keyboardSink.dispatchEvent(textInputEvent('input', 'b'));
    clock.runAll();

    assert.equal(messages.length, 1);
    assert.equal(messages[0].type, 'text');
    assert.equal(messages[0].action, 'commit');
    assert.equal(messages[0].text, 'a');
    assert.equal(messages[0].seq, 1);

    vm.runInContext('handleControlAck({ seq: 1, applied: true });', context);

    assert.equal(messages.length, 2);
    assert.equal(messages[1].text, 'b');
    assert.equal(messages[1].seq, 2);
});
```

- [ ] **Step 7: Implement JS queue**

In `viewer-keyboard.js`, add monotonic sequence and one in-flight text commit:

```javascript
let nextTextSeq = 1;
let inFlightTextSeq = null;
let queuedTextAfterInFlight = '';

function sendQueuedTextNow(text) {
    const seq = nextTextSeq;
    nextTextSeq += 1;
    inFlightTextSeq = seq;
    sendTextCommit(text, seq);
}

function handleTextAck(payload) {
    if (!payload || payload.seq !== inFlightTextSeq) return;
    inFlightTextSeq = null;
    if (queuedTextAfterInFlight) {
        const text = queuedTextAfterInFlight;
        queuedTextAfterInFlight = '';
        sendQueuedTextNow(text);
    }
}
```

Change the keyboard helper option to call `sendTextCommit(text, seq)`, and expose `handleControlAck(payload)` from `viewer.js` to `viewer-keyboard.js`.

- [ ] **Step 8: Verify text ACK flow**

Run:

```bash
node --check android/app/src/main/resources/files/viewer-keyboard.js
node --check android/app/src/main/resources/files/viewer.js
node android/app/src/test/js/viewer-keyboard.test.mjs
cd android
./gradlew app:testDebugUnitTest assembleDebug --no-daemon
```

Expected: all commands succeed.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt \
  android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt \
  android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt \
  android/app/src/main/java/com/example/galaxymirror/ControlEventValidator.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/main/resources/files/viewer-keyboard.js \
  android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "원격 키보드 입력에 ACK 기반 순서 보장 추가"
```

---

### Task 5: Adaptive Idle Stream Quality

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/AdaptiveStreamQuality.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/AdaptiveStreamQualityTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/StreamQuality.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `docs/Protocols.md`

- [ ] **Step 1: Write adaptive quality tests**

Create `android/app/src/test/java/com/example/galaxymirror/AdaptiveStreamQualityTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class AdaptiveStreamQualityTest {
    @Test
    fun activeAutoWifiUsesHighProfile() {
        val profile =
            AdaptiveStreamQuality.resolve(
                selectedMode = StreamQualityMode.AUTO,
                networkTransport = StreamNetworkTransport.WIFI,
                viewerActivity = ViewerActivityState.ACTIVE,
            )

        assertEquals(StreamQualityMode.HIGH, profile.mode)
        assertEquals(30, profile.fps)
    }

    @Test
    fun idleAutoWifiUsesLowerFpsThanActive() {
        val active =
            AdaptiveStreamQuality.resolve(
                selectedMode = StreamQualityMode.AUTO,
                networkTransport = StreamNetworkTransport.WIFI,
                viewerActivity = ViewerActivityState.ACTIVE,
            )
        val idle =
            AdaptiveStreamQuality.resolve(
                selectedMode = StreamQualityMode.AUTO,
                networkTransport = StreamNetworkTransport.WIFI,
                viewerActivity = ViewerActivityState.IDLE,
            )

        assertTrue(idle.fps < active.fps)
        assertTrue(idle.maxBitrateBps < active.maxBitrateBps)
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.AdaptiveStreamQualityTest --no-daemon
```

Expected: compile fails because `AdaptiveStreamQuality` and `ViewerActivityState` do not exist.

- [ ] **Step 3: Implement adaptive resolver**

Create `android/app/src/main/java/com/example/galaxymirror/AdaptiveStreamQuality.kt`:

```kotlin
package com.example.galaxymirror

enum class ViewerActivityState {
    ACTIVE,
    IDLE,
}

object AdaptiveStreamQuality {
    fun resolve(
        selectedMode: StreamQualityMode,
        networkTransport: StreamNetworkTransport,
        viewerActivity: ViewerActivityState,
    ): StreamQualityProfile {
        val active = StreamQualityPolicy.resolve(selectedMode, networkTransport)
        if (viewerActivity == ViewerActivityState.ACTIVE) return active
        return active.copy(
            width = minOf(active.width, 540),
            height = minOf(active.height, 1200),
            fps = minOf(active.fps, 5),
            maxBitrateBps = minOf(active.maxBitrateBps, 350_000),
        )
    }
}
```

- [ ] **Step 4: Run GREEN for adaptive resolver**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.AdaptiveStreamQualityTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Track viewer activity in `MainActivity`**

Add fields:

```kotlin
private var viewerActivityState by mutableStateOf(ViewerActivityState.ACTIVE)
private var idleQualityJob: Job? = null
private val idleQualityDelayMs = 6_000L
```

When a valid DataChannel control message arrives, mark active and reschedule idle:

```kotlin
private fun markViewerActivity() {
  viewerActivityState = ViewerActivityState.ACTIVE
  applyStreamQualityProfile(refreshStreamQualityState(), reason = "viewer activity")
  idleQualityJob?.cancel()
  idleQualityJob =
    lifecycleScope.launch {
      delay(idleQualityDelayMs)
      viewerActivityState = ViewerActivityState.IDLE
      applyStreamQualityProfile(refreshStreamQualityState(), reason = "viewer idle")
    }
}
```

Change `refreshStreamQualityState()` and `buildStreamQualityStatusJson()` to use `AdaptiveStreamQuality.resolve(...)`.

- [ ] **Step 6: Display idle/active quality state in viewer**

Add `activityState` to `StreamQualityCodec.toStatusJson(...)` and display it in `viewer.js`:

```javascript
const activityLabel = payload.activityState === 'IDLE' ? '대기 절약 중' : '활성';
const effectiveText = [effectiveLabel, activityLabel, resolution, bitrate].filter(Boolean).join(' · ');
```

- [ ] **Step 7: Verify adaptive quality**

Run:

```bash
node --check android/app/src/main/resources/files/viewer.js
cd android
./gradlew app:testDebugUnitTest assembleDebug --no-daemon
```

Expected: both commands succeed.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/AdaptiveStreamQuality.kt \
  android/app/src/test/java/com/example/galaxymirror/AdaptiveStreamQualityTest.kt \
  android/app/src/main/java/com/example/galaxymirror/StreamQuality.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/main/resources/files/viewer.js \
  docs/Protocols.md
git commit -m "대기 상태에서 스트림 화질을 자동 절약"
```

---

### Task 6: Structural Refactor

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/MirrorHttpServer.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/WebRtcSessionController.kt`
- Create: `android/app/src/main/resources/files/viewer-signaling.js`
- Create: `android/app/src/main/resources/files/viewer-control.js`
- Create: `android/app/src/main/resources/files/viewer-stats.js`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/main/resources/files/index.html`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Move Ktor setup to `MirrorHttpServer` without behavior changes**

Create `MirrorHttpServer` with this public API:

```kotlin
package com.example.galaxymirror

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

interface SignalingSessionHandle {
    suspend fun onMessage(message: String)
    suspend fun close()
}

class MirrorHttpServer(
    private val dependencies: Dependencies,
) {
    data class Dependencies(
        val port: Int,
        val host: String,
        val tokenProvider: () -> String,
        val statusJson: () -> String,
        val crashReport: () -> String,
        val clearCrashReport: () -> Unit,
        val favoriteAppsJson: () -> String,
        val launchFavoriteApp: suspend (String) -> Boolean,
        val streamQualityJson: () -> String,
        val updateStreamQuality: suspend (StreamQualityMode) -> String,
        val beginSignalingSession: suspend (send: suspend (String) -> Unit) -> SignalingSessionHandle,
    )

    fun start(): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(CIO, port = dependencies.port, host = dependencies.host) {
            install(WebSockets)
            routing {
                staticResources("/", "files")
                get("/status") {
                    call.respondText(dependencies.statusJson(), ContentType.Application.Json)
                }
                get("/debug/crash") {
                    call.respondText(dependencies.crashReport(), ContentType.Text.Plain)
                }
                get("/debug/crash/clear") {
                    dependencies.clearCrashReport()
                    call.respondText(
                        "Cleared saved crash and caught exception. Recent events were kept.\n",
                        ContentType.Text.Plain,
                    )
                }
                get("/apps/favorites") {
                    call.respondText(dependencies.favoriteAppsJson(), ContentType.Application.Json)
                }
                post("/apps/launch") {
                    val packageName = FavoriteAppsCodec.parseLaunchPackageName(call.receiveText())
                    if (packageName == null) {
                        call.respondText(
                            """{"ok":false,"error":"INVALID_PACKAGE"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                    val launched = dependencies.launchFavoriteApp(packageName)
                    call.respondText(
                        if (launched) """{"ok":true}""" else """{"ok":false,"error":"APP_NOT_FOUND"}""",
                        ContentType.Application.Json,
                        if (launched) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    )
                }
                get("/stream/quality") {
                    call.respondText(dependencies.streamQualityJson(), ContentType.Application.Json)
                }
                post("/stream/quality") {
                    val mode = StreamQualityCodec.parseMode(call.receiveText())
                    if (mode == null) {
                        call.respondText(
                            """{"ok":false,"error":"INVALID_STREAM_QUALITY_MODE"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                    call.respondText(dependencies.updateStreamQuality(mode), ContentType.Application.Json)
                }
                webSocket("/signaling") {
                    val handle = dependencies.beginSignalingSession { response ->
                        send(Frame.Text(response))
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handle.onMessage(frame.readText())
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        }.start(wait = false)
    }
}
```

Move the current route bodies from `MainActivity.startKtorServer()` into `MirrorHttpServer.start()` and keep the same endpoint paths.

- [ ] **Step 2: Run server compile check**

Run:

```bash
cd android
./gradlew app:compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Move WebRTC setup to `WebRtcSessionController`**

Create `WebRtcSessionController` with this public API:

```kotlin
class WebRtcSessionController(
    private val activity: MainActivity,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun buildStatusMessage(captureReady: Boolean = MediaProjectionService.isRunning, message: String): String
        fun onMirroringSessionStarted()
        fun onProjectionStopped()
        fun onViewerActivity()
    }

    fun initialize(sessionId: Int, remoteSdp: SessionDescription, sendResponse: (String) -> Unit)

    fun cleanup(stopProjectionService: Boolean, stopCapturer: Boolean = true)

    fun addRemoteIceCandidate(candidate: IceCandidate)
}
```

Move WebRTC fields from `MainActivity` into this controller:

```kotlin
private var peerConnectionFactory: PeerConnectionFactory? = null
private var peerConnection: PeerConnection? = null
private var videoTrack: VideoTrack? = null
private var videoSender: RtpSender? = null
private var surfaceTextureHelper: SurfaceTextureHelper? = null
private var videoCapturer: VideoCapturer? = null
private var controlChannel: DataChannel? = null
private var eglBase: EglBase? = null
```

- [ ] **Step 4: Keep `MainActivity` as coordinator only**

After extraction, `MainActivity` should retain:

```kotlin
private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
private lateinit var webRtcSessionController: WebRtcSessionController
private lateinit var viewerAccessTokenStore: ViewerAccessTokenStore
private lateinit var favoriteAppsRepository: FavoriteAppsRepository
private lateinit var screenAwakeSettingsStore: ScreenAwakeSettingsStore
private lateinit var streamQualitySettingsStore: StreamQualitySettingsStore
private lateinit var networkTransportDetector: NetworkTransportDetector
```

Remove direct WebRTC object fields from `MainActivity`.

- [ ] **Step 5: Split viewer JS modules**

Move functions as follows:

- `viewer-signaling.js`: `connectSignaling`, `setupWebRTC`, `addRemoteCandidate`, `flushPendingRemoteCandidates`, `handleStatusMessage`.
- `viewer-control.js`: `sendControlPayload`, touch handling, nav button handling, favorite app launch, `handleControlAck`.
- `viewer-stats.js`: `extractNetworkBytes`, `sampleWebRtcStats`, `startDataUsagePolling`, `stopDataUsagePolling`.
- `viewer.js`: DOM lookup, boot sequence, shared logging, quality UI rendering.

Update `index.html` script order:

```html
<script src="viewer-stats.js"></script>
<script src="viewer-keyboard.js"></script>
<script src="viewer-control.js"></script>
<script src="viewer-signaling.js"></script>
<script src="viewer.js"></script>
```

- [ ] **Step 6: Verify refactor behavior**

Run:

```bash
node --check android/app/src/main/resources/files/viewer-stats.js
node --check android/app/src/main/resources/files/viewer-keyboard.js
node --check android/app/src/main/resources/files/viewer-control.js
node --check android/app/src/main/resources/files/viewer-signaling.js
node --check android/app/src/main/resources/files/viewer.js
node android/app/src/test/js/viewer-keyboard.test.mjs
cd android
./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: all commands succeed.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/example/galaxymirror/MirrorHttpServer.kt \
  android/app/src/main/java/com/example/galaxymirror/WebRtcSessionController.kt \
  android/app/src/main/java/com/example/galaxymirror/MainActivity.kt \
  android/app/src/main/resources/files/index.html \
  android/app/src/main/resources/files/viewer.js \
  android/app/src/main/resources/files/viewer-signaling.js \
  android/app/src/main/resources/files/viewer-control.js \
  android/app/src/main/resources/files/viewer-stats.js \
  android/app/src/test/js/viewer-keyboard.test.mjs
git commit -m "미러링 서버와 뷰어 스크립트 구조 분리"
```

---

### Task 7: Docs, Build, and Device Smoke

**Files:**
- Modify: `README.md`
- Modify: `docs/Protocols.md`
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`

- [ ] **Step 1: Update protocol docs**

In `docs/Protocols.md`, document:

- `?token=` and `X-Android-Mirror-Token`.
- WebSocket close reason `UNAUTHORIZED_VIEWER`.
- DataChannel `seq` field for control events.
- DataChannel `CONTROL_ACK` response shape.
- `STATUS.streamQuality.activityState` with values `ACTIVE` and `IDLE`.

- [ ] **Step 2: Update user-facing docs**

In `README.md`, add the operational rule:

```markdown
Mac Viewer 접속 주소는 Android 앱의 "Mac 연결 주소"에 표시되는 토큰 포함 URL을 사용합니다.
토큰은 Tailscale 내부망에서도 오접속을 막기 위한 로컬 방어 장치이며, 화면 공유 권한과 접근성 권한을 대체하지 않습니다.
```

- [ ] **Step 3: Update handoff and log**

In `docs/Handoff.md`, mark completed items for cleanup hardening, session serialization, token, input ACK, adaptive quality, and structural split.

In `docs/Log.md`, add a dated entry with:

- cleanup no longer aborts on the first exception,
- session transitions are serialized,
- viewer control routes require a local token,
- text input uses ACK ordering,
- idle mirroring lowers stream quality,
- `MainActivity` and viewer JS were split.

- [ ] **Step 4: Run full local verification**

Run:

```bash
node --check android/app/src/main/resources/files/viewer-stats.js
node --check android/app/src/main/resources/files/viewer-keyboard.js
node --check android/app/src/main/resources/files/viewer-control.js
node --check android/app/src/main/resources/files/viewer-signaling.js
node --check android/app/src/main/resources/files/viewer.js
node android/app/src/test/js/viewer-keyboard.test.mjs
cd android
./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: all commands succeed.

- [ ] **Step 5: Build APK**

Run:

```bash
cd android
./gradlew assembleDebug --no-daemon
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

Expected: APK exists at `android/app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Manual Galaxy S26 smoke test**

Install the debug APK by the user's current preferred method. Then verify:

1. Android app shows a tokenized Mac Viewer URL.
2. Chrome opens `http://s26-ultra.taile02b2a.ts.net:8080/?token=<token>`.
3. Opening without the token rejects `/signaling`, `/apps/launch`, `/stream/quality`, and `/debug/crash`.
4. Screen share starts after Android permission approval.
5. Tap, swipe, back, home, and recents work from the viewer.
6. Fast Korean input such as `이제 간단한 한글은 잘 되지?` does not drop characters.
7. Leaving the viewer idle lowers effective stream quality to idle mode.
8. Moving the mouse or typing restores active stream quality.
9. `/debug/crash?token=<token>` shows no saved crash or caught exception after the smoke test.

- [ ] **Step 7: Commit docs**

```bash
git add README.md docs/Protocols.md docs/Handoff.md docs/Log.md
git commit -m "미러링 안정화와 성능 개선 문서 갱신"
```

---

## Final Verification Checklist

- [ ] `node --check` passes for every viewer JS file.
- [ ] `node android/app/src/test/js/viewer-keyboard.test.mjs` passes.
- [ ] `cd android && ./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon` passes.
- [ ] `git diff --check` passes.
- [ ] Debug APK exists at `android/app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Real-device smoke confirms WebRTC video, touch, navigation, fast Korean text input, token rejection, and idle stream quality.

## Rollback Notes

- If token rollout blocks viewer access, temporarily allow `/` static resources without token and keep token required for `/signaling` and mutating endpoints.
- If ACK input introduces latency, keep `ordered: true` DataChannel and raise the JS batch delay from `35ms` to `60ms` before disabling ACK.
- If adaptive idle causes black frames or encoder instability, keep bitrate cap changes and disable `changeCaptureFormat` during idle until real-device behavior is stable.
- If structural extraction becomes noisy, stop after `MirrorHttpServer` extraction and leave `WebRtcSessionController` for a follow-up branch.
