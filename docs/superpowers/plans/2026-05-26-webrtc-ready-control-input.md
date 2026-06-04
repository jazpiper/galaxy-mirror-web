# WebRTC Ready Control Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Galaxy Mirror connect regardless of whether Chrome or Android screen-share approval happens first, and make remote tap, swipe, control keys, and Mac keyboard text input work through Android AccessibilityService.

**Architecture:** Keep the current 1:1 Ktor WebSocket + WebRTC model, but separate capture-service lifetime from viewer WebSocket lifetime. Add a small signaling/control protocol layer so the browser can show capture/accessibility readiness separately from DataChannel readiness. Use AccessibilityService gestures for tap/swipe and `ACTION_SET_TEXT` on the focused editable node for real text input.

**Tech Stack:** Android Kotlin, Ktor CIO WebSocket, org.webrtc, Android MediaProjection foreground service, Android AccessibilityService, Vanilla HTML/JavaScript viewer, JUnit unit tests.

**Implementation Status (2026-05-26):** Tasks 0-6 Step 3 are implemented and verified with unit tests, debug build, lint, and `node --check` for the viewer script. Task 6 Step 4 remains pending until a fresh APK is installed on the Galaxy S26 Android 16 device for real-device smoke testing.

---

## File Structure

- `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
  - Preserve Ktor/WebRTC ownership.
  - Add pending-offer handling when capture is not ready.
  - Stop ending MediaProjectionService merely because a viewer WebSocket closed.
  - Send lightweight signaling status packets to the browser.
- `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - Keep `isRunning` as the readiness signal.
  - No major change expected unless tests reveal a missing readiness callback seam.
- `android/app/src/main/java/com/example/galaxymirror/ControlEventValidator.kt`
  - Add `text` commit/delete validation.
  - Keep control channel label validation.
- `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
  - Add readiness helper.
  - Record diagnostic events when control messages arrive and when gestures/text fail.
  - Add `text` injection with focused editable node lookup and `ACTION_SET_TEXT`.
- `android/app/src/main/res/xml/accessibility_service_config.xml`
  - Enable window content retrieval so focused text nodes can be found.
- `android/app/src/main/resources/files/viewer.js`
  - Distinguish WebSocket, capture readiness, WebRTC streaming, DataChannel, and AccessibilityService readiness.
  - Send `text` events for normal Mac keyboard input when the video surface is focused.
  - Keep Back/Home/Recents shortcuts.
- `android/app/src/main/resources/files/index.html`
  - Add or rename status rows only as needed for clear state.
- `android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt`
  - Cover valid/invalid text input events.
- `android/app/src/test/java/com/example/galaxymirror/SignalingStateTest.kt`
  - Cover pure signaling/lifecycle decisions before Android/WebRTC integration.
- `docs/Protocols.md`
  - Document signaling `STATUS` packet and DataChannel `text` event.
- `docs/Coordinates.md`
  - Document accessibility readiness and text injection constraints.
- `docs/Handoff.md`
  - Update task checkboxes for connection ordering, touch, and text-input work.
- `docs/Log.md`
  - Add a dated implementation log entry with verification commands.

---

### Task 0: Signaling Lifecycle Decision Tests

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/SignalingState.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/SignalingStateTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `SignalingStateTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class SignalingStateTest {
    @Test
    fun projectionReadiness_distinguishesMissingStartingAndReady() {
        assertEquals(
            ProjectionReadiness.MISSING_PERMISSION,
            ProjectionReadiness.from(hasProjectionIntent = false, isServiceRunning = false)
        )
        assertEquals(
            ProjectionReadiness.SERVICE_STARTING,
            ProjectionReadiness.from(hasProjectionIntent = true, isServiceRunning = false)
        )
        assertEquals(
            ProjectionReadiness.READY,
            ProjectionReadiness.from(hasProjectionIntent = true, isServiceRunning = true)
        )
    }

    @Test
    fun signalingDecision_queuesOfferUntilProjectionIsReady() {
        assertEquals(
            SignalingDecision.QUEUE_AND_SEND_STATUS,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.SERVICE_STARTING,
                activeSessionMatches = true
            )
        )
    }

    @Test
    fun signalingDecision_startsNegotiationOnlyWhenReady() {
        assertEquals(
            SignalingDecision.START_NEGOTIATION,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.READY,
                activeSessionMatches = true
            )
        )
    }

    @Test
    fun signalingDecision_rejectsMissingPermissionAndIgnoresInactiveSessions() {
        assertEquals(
            SignalingDecision.QUEUE_AND_REQUEST_PERMISSION,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.MISSING_PERMISSION,
                activeSessionMatches = true
            )
        )
        assertEquals(
            SignalingDecision.IGNORE_INACTIVE,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.READY,
                activeSessionMatches = false
            )
        )
    }

    @Test
    fun cleanupPolicy_keepsProjectionForViewerCloseButStopsOnActivityDestroy() {
        assertEquals(false, CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_SOCKET_CLOSED))
        assertEquals(false, CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_REPLACED))
        assertEquals(true, CleanupPolicy.shouldStopProjection(CleanupReason.ACTIVITY_DESTROYED))
        assertEquals(true, CleanupPolicy.shouldStopProjection(CleanupReason.EXPLICIT_STOP))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests 'com.example.galaxymirror.SignalingStateTest' --no-daemon
```

Expected: FAIL because `ProjectionReadiness`, `SignalingDecision`, `CleanupPolicy`, and `CleanupReason` do not exist.

- [ ] **Step 3: Implement the pure decision helpers**

Create `SignalingState.kt`:

```kotlin
package com.example.galaxymirror

enum class ProjectionReadiness {
    MISSING_PERMISSION,
    SERVICE_STARTING,
    READY;

    companion object {
        fun from(hasProjectionIntent: Boolean, isServiceRunning: Boolean): ProjectionReadiness {
            return when {
                isServiceRunning -> READY
                hasProjectionIntent -> SERVICE_STARTING
                else -> MISSING_PERMISSION
            }
        }
    }
}

enum class SignalingDecision {
    START_NEGOTIATION,
    QUEUE_AND_SEND_STATUS,
    QUEUE_AND_REQUEST_PERMISSION,
    IGNORE_INACTIVE;

    companion object {
        fun onOffer(
            readiness: ProjectionReadiness,
            activeSessionMatches: Boolean
        ): SignalingDecision {
            if (!activeSessionMatches) return IGNORE_INACTIVE
            return when (readiness) {
                ProjectionReadiness.READY -> START_NEGOTIATION
                ProjectionReadiness.SERVICE_STARTING -> QUEUE_AND_SEND_STATUS
                ProjectionReadiness.MISSING_PERMISSION -> QUEUE_AND_REQUEST_PERMISSION
            }
        }
    }
}

enum class CleanupReason {
    VIEWER_SOCKET_CLOSED,
    VIEWER_REPLACED,
    ACTIVITY_DESTROYED,
    EXPLICIT_STOP
}

object CleanupPolicy {
    fun shouldStopProjection(reason: CleanupReason): Boolean {
        return when (reason) {
            CleanupReason.VIEWER_SOCKET_CLOSED,
            CleanupReason.VIEWER_REPLACED -> false
            CleanupReason.ACTIVITY_DESTROYED,
            CleanupReason.EXPLICIT_STOP -> true
        }
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests 'com.example.galaxymirror.SignalingStateTest' --no-daemon
```

Expected: PASS.

---

### Task 1: Control Protocol Tests And Validator

**Files:**
- Modify: `android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ControlEventValidator.kt`

- [ ] **Step 1: Write the failing tests**

Add these assertions to `isValid_acceptsSupportedControlEvents`:

```kotlin
assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":"hello"}""")))
assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":"한글 입력"}""")))
assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":"\n"}""")))
assertTrue(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":1}""")))
```

Add a new test:

```kotlin
@Test
fun isValid_rejectsInvalidTextEvents() {
    assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":""}""")))
    assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"commit","text":${"a".repeat(129).let { JSONObject.quote(it) }}}""")))
    assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":0}""")))
    assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"deleteBackward","count":65}""")))
    assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","action":"replace","text":"x"}""")))
    assertFalse(ControlEventValidator.isValid(JSONObject("""{"type":"text","keyCode":66}""")))
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests 'com.example.galaxymirror.ControlEventValidatorTest' --no-daemon
```

Expected: FAIL because `ControlEventValidator` does not accept `type=text`.

- [ ] **Step 3: Implement minimal validator support**

In `ControlEventValidator.kt`, add:

```kotlin
private const val MAX_TEXT_LENGTH = 128
private const val MIN_DELETE_COUNT = 1
private const val MAX_DELETE_COUNT = 64
```

Extend `isValid`:

```kotlin
"text" -> isTextValid(json)
```

Add:

```kotlin
private fun isTextValid(json: JSONObject): Boolean {
    return when (json.optString("action", "")) {
        "commit" -> {
            if (!json.has("text")) return false
            val text = json.optString("text", "")
            text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH
        }
        "deleteBackward" -> {
            val count = json.optInt("count", -1)
            count in MIN_DELETE_COUNT..MAX_DELETE_COUNT
        }
        else -> false
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests 'com.example.galaxymirror.ControlEventValidatorTest' --no-daemon
```

Expected: PASS.

---

### Task 2: Accessibility Readiness, Gesture Diagnostics, And Text Injection

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`
- Modify: `android/app/src/main/res/xml/accessibility_service_config.xml`

- [ ] **Step 1: Enable focused text lookup**

Change `android:canRetrieveWindowContent` in `accessibility_service_config.xml`:

```xml
android:canRetrieveWindowContent="true"
```

Keep `android:canPerformGestures="true"`.

- [ ] **Step 2: Add readiness helper**

In `GalaxyMirrorAccessibilityService.kt` companion object, add:

```kotlin
fun isReadyForRemoteInput(): Boolean = instance != null
```

- [ ] **Step 3: Add diagnostics for received control events**

At the start of `handleControlEvent`, after validation succeeds, add:

```kotlin
CrashDiagnostics.recordEvent(this, "Accessibility control event accepted: ${json.optString("type")}.")
```

Inside each dispatch branch, keep existing `Log` calls and add event records for successful call attempts:

```kotlin
CrashDiagnostics.recordEvent(this, "Accessibility tap requested.")
CrashDiagnostics.recordEvent(this, "Accessibility swipe requested.")
CrashDiagnostics.recordEvent(this, "Accessibility key requested: $keyCode.")
```

- [ ] **Step 4: Add text handling branch**

In the `when` inside `handleControlEvent`, add:

```kotlin
"text" -> {
    when (json.getString("action")) {
        "commit" -> {
            val text = json.getString("text")
            CrashDiagnostics.recordEvent(this, "Accessibility text commit requested length=${text.length}.")
            commitTextInput(text)
        }
        "deleteBackward" -> {
            val count = json.getInt("count")
            CrashDiagnostics.recordEvent(this, "Accessibility text delete requested count=$count.")
            deleteTextBackward(count)
        }
    }
}
```

- [ ] **Step 5: Implement focused-node text input and delete**

Add imports:

```kotlin
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
```

Add:

```kotlin
private fun commitTextInput(text: String) {
    val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focusedNode == null) {
        CrashDiagnostics.recordEvent(this, "Accessibility text commit failed: no focused input node.")
        Log.w(TAG, "No focused input node for text input.")
        return
    }

    if (!focusedNode.isEditable) {
        CrashDiagnostics.recordEvent(this, "Accessibility text commit failed: focused node is not editable.")
        Log.w(TAG, "Focused node is not editable.")
        focusedNode.recycle()
        return
    }

    val existingText = focusedNode.text?.toString().orEmpty()
    val selectionStart = focusedNode.textSelectionStart
    val selectionEnd = focusedNode.textSelectionEnd
    val start = minOf(selectionStart, selectionEnd).takeIf { it >= 0 } ?: existingText.length
    val end = maxOf(selectionStart, selectionEnd).takeIf { it >= 0 } ?: existingText.length
    val nextText = existingText.replaceRange(start, end, text)
    val arguments = Bundle().apply {
        putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            nextText
        )
    }
    val applied = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    CrashDiagnostics.recordEvent(this, "Accessibility text commit applied=$applied.")
    Log.d(TAG, "Text commit applied=$applied length=${text.length}")
    focusedNode.recycle()
}

private fun deleteTextBackward(count: Int) {
    val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focusedNode == null) {
        CrashDiagnostics.recordEvent(this, "Accessibility text delete failed: no focused input node.")
        Log.w(TAG, "No focused input node for text delete.")
        return
    }

    if (!focusedNode.isEditable) {
        CrashDiagnostics.recordEvent(this, "Accessibility text delete failed: focused node is not editable.")
        Log.w(TAG, "Focused node is not editable.")
        focusedNode.recycle()
        return
    }

    val existingText = focusedNode.text?.toString().orEmpty()
    val selectionStart = focusedNode.textSelectionStart
    val selectionEnd = focusedNode.textSelectionEnd
    val cursorStart = minOf(selectionStart, selectionEnd).takeIf { it >= 0 } ?: existingText.length
    val cursorEnd = maxOf(selectionStart, selectionEnd).takeIf { it >= 0 } ?: existingText.length
    val deleteStart = if (cursorStart != cursorEnd) cursorStart else maxOf(0, cursorStart - count)
    val nextText = existingText.replaceRange(deleteStart, cursorEnd, "")
    val arguments = Bundle().apply {
        putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            nextText
        )
    }
    val applied = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    CrashDiagnostics.recordEvent(this, "Accessibility text delete applied=$applied.")
    Log.d(TAG, "Text delete applied=$applied count=$count")
    focusedNode.recycle()
}
```

Note: The implementation inserts or deletes at the focused node's reported selection range, and falls back to the end of the field when selection information is unavailable.

- [ ] **Step 6: Run compile/unit verification**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon
```

Expected: PASS.

---

### Task 3: Capture Readiness And Pending Offer Handling

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`

- [ ] **Step 1: Add pending offer state**

Near existing WebRTC fields, add:

```kotlin
private data class PendingOffer(
  val sessionId: Int,
  val remoteSdp: SessionDescription,
  val sendResponse: (String) -> Unit
)

@Volatile private var pendingOffer: PendingOffer? = null
```

- [ ] **Step 2: Add signaling status sender**

Add helper:

```kotlin
private fun buildStatusMessage(
  captureReady: Boolean = MediaProjectionService.isRunning,
  accessibilityReady: Boolean = GalaxyMirrorAccessibilityService.isReadyForRemoteInput(),
  message: String
): String {
  return org.json.JSONObject().apply {
    put("type", "STATUS")
    put("payload", org.json.JSONObject().apply {
      put("captureReady", captureReady)
      put("accessibilityReady", accessibilityReady)
      put("message", message)
    })
  }.toString()
}
```

- [ ] **Step 3: Defer Offer when capture is not ready**

Replace the early return in `initializeWebRTC`:

```kotlin
if (!MediaProjectionService.isRunning) {
  CrashDiagnostics.recordEvent(this, "MediaProjectionService not running when WebRTC initialization was requested.")
  Log.e("WebRTC", "MediaProjectionService not running yet. Cannot initialize WebRTC.")
  return
}
```

with:

```kotlin
if (!MediaProjectionService.isRunning) {
  CrashDiagnostics.recordEvent(this, "Capture not ready; deferring offer for sessionId=$sessionId.")
  pendingOffer = PendingOffer(sessionId, remoteSdp, sendResponse)
  sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
  requestScreenCapturePermission()
  return
}
```

- [ ] **Step 4: Resume pending offer when MediaProjectionService is started**

At the end of `startMediaProjectionService`, after `startForegroundService/startService`, add:

```kotlin
lifecycleScope.launch(Dispatchers.Main) {
  resumePendingOfferIfReady()
}
```

Add helper:

```kotlin
private fun resumePendingOfferIfReady() {
  val offer = pendingOffer ?: return
  if (!MediaProjectionService.isRunning) {
    CrashDiagnostics.recordEvent(this, "Pending offer not resumed because capture service is not ready yet.")
    return
  }
  if (!isActiveSession(offer.sessionId)) {
    CrashDiagnostics.recordEvent(this, "Dropping pending offer for inactive sessionId=${offer.sessionId}.")
    pendingOffer = null
    return
  }
  pendingOffer = null
  CrashDiagnostics.recordEvent(this, "Resuming pending offer for sessionId=${offer.sessionId}.")
  offer.sendResponse(buildStatusMessage(captureReady = true, message = "SCREEN_CAPTURE_READY"))
  initializeWebRTC(offer.sessionId, offer.remoteSdp, offer.sendResponse)
}
```

If `MediaProjectionService.isRunning` is not true immediately after service start in manual testing, replace this direct call with a short condition-based retry loop on the main dispatcher.

- [ ] **Step 5: Preserve MediaProjection across viewer reconnects**

Change `endViewerSession` cleanup:

```kotlin
cleanupWebRTCResources(stopProjectionService = true)
```

to:

```kotlin
pendingOffer = null
cleanupWebRTCResources(stopProjectionService = false)
```

Keep `onDestroy` using `stopProjectionService = true`.

- [ ] **Step 6: Include accessibility readiness at DataChannel open**

Inside `onDataChannel`, after assigning `controlChannel = dc`, send no browser-bound message through DataChannel yet. Let the browser infer DataChannel open from its own side, and send accessibility readiness through `STATUS` during signaling response:

```kotlin
sendResponse(buildStatusMessage(message = "CONTROL_CHANNEL_ACCEPTED"))
```

This is enough for the viewer to show whether AccessibilityService is enabled.

- [ ] **Step 7: Run compile/unit verification**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon
```

Expected: PASS.

---

### Task 4: Browser Viewer Status And Keyboard Text Events

**Files:**
- Modify: `android/app/src/main/resources/files/index.html`
- Modify: `android/app/src/main/resources/files/viewer.js`

- [ ] **Step 1: Add a separate accessibility status row**

In `index.html`, keep the current `controlStatus` row but make its label about the DataChannel:

```html
<span class="status-label">제어 채널</span>
<span class="status-value" id="controlStatus">비활성</span>
```

Add a fourth status item:

```html
<div class="status-item">
    <span class="status-label">접근성 입력</span>
    <span class="status-value" id="accessibilityStatus">확인 중</span>
</div>
```

- [ ] **Step 2: Wire the new DOM reference**

In `viewer.js`, add:

```javascript
const accessibilityStatus = document.getElementById('accessibilityStatus');
let accessibilityReady = false;
```

- [ ] **Step 3: Handle signaling status packets**

In `socket.onmessage`, add:

```javascript
case 'STATUS':
    handleStatusMessage(message.payload || {});
    break;
```

Add:

```javascript
function handleStatusMessage(payload) {
    if (typeof payload.captureReady === 'boolean') {
        rtcStatus.innerText = payload.captureReady ? 'Capture Ready' : '화면 공유 대기';
    }
    if (typeof payload.accessibilityReady === 'boolean') {
        accessibilityReady = payload.accessibilityReady;
        accessibilityStatus.innerText = accessibilityReady ? '활성화' : '권한 필요';
    }
    if (payload.message) {
        log(`Android 상태: ${payload.message}`);
    }
}
```

- [ ] **Step 4: Make DataChannel status wording precise**

In `setupDataChannelHandlers`, change:

```javascript
controlStatus.innerText = "제어 활성화";
```

to:

```javascript
controlStatus.innerText = "채널 연결됨";
```

In `channel.onclose`, keep `controlStatus.innerText = "비활성";`.

- [ ] **Step 5: Focus the video surface for keyboard capture**

In `index.html`, make `remoteVideo` focusable:

```html
<video id="remoteVideo" autoplay playsinline muted tabindex="0"></video>
```

If the current tag already has other attributes, preserve them and add `tabindex="0"`.

- [ ] **Step 6: Send text input events**

In `setupKeyControl`, before the shortcut switch or after handling shortcuts, add:

```javascript
function sendTextCommit(text) {
    if (!dataChannel || dataChannel.readyState !== 'open') return;
    dataChannel.send(JSON.stringify({ type: 'text', action: 'commit', text }));
    log(`Text sent: length=${text.length}`);
}

function sendTextDeleteBackward(count) {
    if (!dataChannel || dataChannel.readyState !== 'open') return;
    dataChannel.send(JSON.stringify({ type: 'text', action: 'deleteBackward', count }));
    log(`Text delete sent: count=${count}`);
}
```

Replace the current `keydown` listener body with:

```javascript
document.addEventListener('keydown', (e) => {
    if (!document.hasFocus()) return;

    switch (e.key) {
        case 'Backspace':
            e.preventDefault();
            sendTextDeleteBackward(1);
            return;
        case 'Home':
            e.preventDefault();
            sendKey(3);
            return;
        case 'F1':
            e.preventDefault();
            sendKey(187);
            return;
        case 'Enter':
            e.preventDefault();
            sendTextCommit('\n');
            return;
    }

    if (e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key.length === 1) {
        e.preventDefault();
        sendTextCommit(e.key);
    }
});
```

Note: Backspace sends `deleteBackward` for focused text fields. Escape remains the browser-side shortcut for Android Back.

- [ ] **Step 7: Focus video after stream starts**

In `peerConnection.ontrack`, after assigning `remoteVideo.srcObject`, add:

```javascript
remoteVideo.focus();
```

- [ ] **Step 8: Browser smoke check**

Open Chrome to `http://s26-ultra.taile02b2a.ts.net:8080/` after installing a fresh APK. Expected browser behavior:

- If screen capture is not ready, log shows `Android 상태: WAITING_FOR_SCREEN_CAPTURE`.
- After Android approval, log shows `Android 상태: SCREEN_CAPTURE_READY`.
- WebRTC stream becomes active without manually reordering Chrome/Android steps.
- Control channel shows `채널 연결됨`.
- Accessibility input shows `활성화` only when Android AccessibilityService is enabled.

---

### Task 5: Documentation Sync

**Files:**
- Modify: `docs/Protocols.md`
- Modify: `docs/Coordinates.md`
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`

- [ ] **Step 1: Update signaling protocol docs**

In `docs/Protocols.md`, add a section after ICE Candidate:

```markdown
### 1.4 Android 상태 알림 (`STATUS`)
Android Host는 화면 캡처 또는 접근성 입력 준비 상태가 바뀌거나, Offer 처리를 대기시킬 때 브라우저에 `STATUS` 패킷을 보낸다.

```json
{
  "type": "STATUS",
  "payload": {
    "captureReady": false,
    "accessibilityReady": true,
    "message": "WAITING_FOR_SCREEN_CAPTURE"
  }
}
```

`message` 값은 현재 `WAITING_FOR_SCREEN_CAPTURE`, `SCREEN_CAPTURE_READY`, `CONTROL_CHANNEL_ACCEPTED`를 사용한다.
```

- [ ] **Step 2: Update control protocol docs**

In `docs/Protocols.md`, add under control events:

```markdown
### 2.3 텍스트 입력 (`text`)
Mac 키보드에서 입력한 일반 문자는 DataChannel `text` 이벤트로 Android Host에 전달된다. Android Host는 현재 포커스된 editable AccessibilityNode에 `ACTION_SET_TEXT`로 커서/선택 영역 기준 문자열을 갱신한다.

```json
{
  "type": "text",
  "action": "commit",
  "text": "hello"
}
```

```json
{
  "type": "text",
  "action": "deleteBackward",
  "count": 1
}
```

초기 구현은 Android 접근성 노드가 제공하는 selection 범위 안에서 커서 삽입/삭제를 수행한다. selection 정보를 얻을 수 없는 앱에서는 문자열 끝 기준으로 입력/삭제한다.
```

- [ ] **Step 3: Update coordinate/accessibility docs**

In `docs/Coordinates.md`, add a note under section 3:

```markdown
텍스트 입력은 좌표 변환을 거치지 않는다. 브라우저는 일반 키 입력을 `text` commit/deleteBackward 이벤트로 전송하고, Android AccessibilityService는 `rootInActiveWindow.findFocus(FOCUS_INPUT)`으로 현재 입력창을 찾은 뒤 selection 범위 기준으로 `ACTION_SET_TEXT`를 수행한다. 이 기능 때문에 접근성 설정은 gesture 권한뿐 아니라 window content retrieval도 필요하다.
```

- [ ] **Step 4: Update handoff board**

In `docs/Handoff.md`, mark the relevant Milestone 3 signaling/control subtasks as completed only after code verification passes. Add a short next-risk bullet for real-device smoke testing if it was not run.

- [ ] **Step 5: Update development log**

In `docs/Log.md`, add a 2026-05-26 entry containing:

- Capture-readiness pending-offer behavior.
- WebSocket reconnect no longer stops MediaProjection prematurely.
- Accessibility status reporting.
- DataChannel `text` input protocol.
- Verification commands and whether device smoke was run.

---

### Task 6: End-To-End Verification

**Files:**
- No source edits unless verification exposes a defect.

- [ ] **Step 1: Run unit tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run debug build**

Run:

```bash
cd android
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Optional lint**

Run if time allows:

```bash
cd android
./gradlew app:lintDebug --no-daemon
```

Expected: no new lint failures. If existing lint fails, record exact failure in `docs/Log.md`.

- [ ] **Step 4: Real-device smoke test**

Install the fresh APK on the Galaxy device, then verify:

1. Open Chrome viewer before approving screen share.
2. Android asks for screen-share permission.
3. Approve screen share.
4. Browser connects automatically and video starts.
5. Enable Galaxy Mirror AccessibilityService in Android settings.
6. Tap the browser video and confirm Android tap happens.
7. Swipe browser video and confirm Android swipe happens.
8. Focus an Android text field, focus the browser video, type `abc 한글`, and confirm text appears.
9. Open `http://s26-ultra.taile02b2a.ts.net:8080/debug/crash` and confirm no saved crash/caught exception; recent events should show capture, signaling, DataChannel, and accessibility input breadcrumbs.

If a real-device step cannot be run in the agent session, record it as pending in `docs/Handoff.md` and `docs/Log.md`.
