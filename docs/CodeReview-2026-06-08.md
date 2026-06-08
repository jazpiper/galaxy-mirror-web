# 2026-06-08 Commit Code Review

## Scope

- Review date: 2026-06-08, Asia/Seoul 기준
- Commit range: `7ee4930ff5ccfe89f39394326707fb7de6191e76^..HEAD`
- Reviewed commits:
  - `7ee4930` refactor WebRTC lifecycle / capturer cache / H.264 priority
  - `40001c1` viewer UI / reconnect / tab visibility / mock clock
  - `10a65b5` Milestone 5 docs
  - `7519854` Milestone 6 clipboard / hardware keys / recording / screenshots
  - `db189a0` Milestone 6 docs
  - `3203bc0` clipboard listener migration to AccessibilityService
  - `ef3016b` clipboard listener docs
- Review mode: parallel subsystem review plus local verification

## Verification

- Passed: `cd android && ./gradlew app:testDebugUnitTest --no-daemon`
- Passed: `node --test android/app/src/test/js/viewer-keyboard.test.mjs`
- Failed hygiene check: `git diff --check 7ee4930ff5ccfe89f39394326707fb7de6191e76^..HEAD`
  reported trailing whitespace in `MainActivity.kt`, `MediaProjectionService.kt`,
  `index.html`, and `viewer.js`.
- Not run: `./gradlew assembleDebug --no-daemon`, `./gradlew app:lintDebug --no-daemon`,
  real Android device smoke test, WebRTC stream test, browser clipboard test on the actual
  `http://<MagicDNS-host>:8080` origin.

## Findings

### P1. MediaProjection reauthorization can reuse a stopped capturer

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:990`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:993`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:996`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1000`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1102`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1109`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1174`
- Issue: `ScreenCapturerAndroid.onStop()` calls
  `handleScreenCaptureReauthorizationRequired(..., stopCapturer = false)`. That clears
  `mediaProjectionResultData` but does not null/dispose `videoCapturer`, `videoSource`, or
  `videoTrack`.
- Impact: after screen lock, user stop, projection revocation, or reauthorization, the next
  permission grant can skip the `videoCapturer == null` setup and keep using a stopped capturer
  bound to the old projection token. The viewer can reconnect but receive no frames.
- Suggested fix: on MediaProjection `onStop`, dispose/null the capturer pipeline or explicitly
  rebuild it when `mediaProjectionResultData` changes. Add a regression test around stopped
  capturer plus fresh grant.

### P1. Viewer disconnect/replacement leaves screen capture alive with no active viewer

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:687`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:690`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:702`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:707`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1139`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1174`
  - `android/app/src/test/java/com/example/galaxymirror/SignalingStateTest.kt:68`
- Issue: session replacement and WebSocket end call `cleanupWebRTCResources(..., stopCapturer = false)`.
  That closes the peer connection but keeps the active screen capturer, video source, video track,
  factory, and projection intent alive. Existing cleanup policy tests still say viewer socket close
  and viewer replacement should stop projection.
- Impact: screen capture can continue after the Mac viewer disconnects. This is a privacy/battery
  regression for a sensitive remote-control app, and it also makes reapproval expectations unclear.
- Suggested fix: decide the product policy explicitly. If capture must continue for fast reconnect,
  update tests/docs and surface a clear active-capture state. If not, enforce `CleanupPolicy` and stop
  projection on viewer close/replacement.

### P1. Automatic reconnect can stop before backoff starts

- Files:
  - `android/app/src/main/resources/files/viewer.js:447`
  - `android/app/src/main/resources/files/viewer.js:456`
  - `android/app/src/main/resources/files/viewer.js:1053`
  - `android/app/src/main/resources/files/viewer.js:1058`
  - `android/app/src/main/resources/files/viewer.js:1064`
- Issue: `triggerAutoReconnect()` closes an open signaling socket with `socket.close()`. The close
  handler treats close code `1000` as explicit/manual and does not call `startReconnectSequence()`.
- Impact: ICE/peer failure while the signaling socket is still open can log that auto reconnect was
  triggered, close the socket, and then stop without scheduling the exponential backoff reconnect.
- Suggested fix: distinguish reconnect-initiated close from user/manual close, or call
  `startReconnectSequence()` directly after closing the old socket.

### P1. Screen-capture permission waits can hang without prompting the Android user

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:782`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:784`
  - `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt:224`
- Issue: after signaling moved into `MediaProjectionService`, `QUEUE_AND_REQUEST_PERMISSION` only
  queues the offer and sends `WAITING_FOR_SCREEN_CAPTURE`; it does not call back into
  `MainActivity.requestScreenCapturePermission()`.
- Impact: after denial, service restart, or reauth-required state, the viewer can wait forever unless
  the Android activity happens to be reopened and requests projection again.
- Suggested fix: add an activity-facing state/event for pending projection requests, or expose a
  notification/action that brings the user to the permission flow.

### P1. Remote volume buttons call the wrong Accessibility global actions

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt:271`
  - `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt:272`
  - `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt:275`
  - `android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt:65`
  - `android/app/src/test/js/viewer-keyboard.test.mjs:558`
- Issue: keycodes 24/25/164 are mapped to raw `performGlobalAction(10/11/12)` and commented as
  volume actions. Android SDK sources define those IDs as headset hook, accessibility button, and
  accessibility button chooser, not volume controls.
- Impact: clicking Volume Up/Down/Mute can answer/hang up calls or open Accessibility UI instead of
  changing volume. Current tests only verify that the browser sends keycodes and the validator accepts
  them, not that Android applies the right action.
- Suggested fix: use `AudioManager.adjustStreamVolume()` for volume up/down/mute, return false if the
  service cannot obtain `AudioManager`, and avoid raw numeric global action IDs where constants exist.

### P1. Clipboard sync can fail on the intended HTTP viewer origin

- Files:
  - `android/app/src/main/resources/files/viewer.js:764`
  - `android/app/src/main/resources/files/viewer.js:775`
  - `android/app/src/main/resources/files/viewer.js:1174`
- Issue: both Android-to-Mac and Mac-to-Android paths assume `navigator.clipboard` is available.
  The intended viewer is served from `http://<MagicDNS-host>:8080`, where browser clipboard APIs are
  secure-context and permission gated.
- Impact: bidirectional clipboard sync can silently fail in the normal deployment shape. The fallback
  click handler also calls `navigator.clipboard.writeText()` again, so it is not a real fallback when
  the API is unavailable.
- Suggested fix: feature-detect `navigator.clipboard`, provide a manual textarea/select-copy fallback,
  or serve the viewer over a secure origin before marking clipboard sync complete.

### P2. `startCapture()` failures leave half-initialized cached state

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:990`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1011`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1021`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:1070`
- Issue: `startCapture()` is inside the broad WebRTC init try/catch. If it throws, the catch logs but
  does not clean up partially assigned `videoCapturer`/`videoSource` state.
- Impact: the next negotiation can skip capture setup because `videoCapturer` is already non-null, and
  can attempt `addTrack(videoTrack, ...)` with stale or null capture state.
- Suggested fix: wrap capture setup in a local failure handler that disposes/nulls capture resources and
  sends a reauth/error status to the viewer.

### P2. Manual session refresh button now only disconnects when already connected

- Files:
  - `android/app/src/main/resources/files/viewer.js:1005`
  - `android/app/src/main/resources/files/viewer.js:1017`
  - `android/app/src/main/resources/files/viewer.js:1021`
- Issue: clicking `미러링 연결하기` while a socket is open or connecting closes the socket but no longer
  calls `connectSignaling()` afterward.
- Impact: the old behavior refreshed the session immediately. The new behavior can leave the viewer
  disconnected, depending on close code and reconnect state.
- Suggested fix: after closing an existing socket for a user-requested refresh, connect again once the
  old socket is closed, or route the button through a reconnect-specific close reason/state flag.

### P2. `CONTROL_ACK` JSON serialization no longer escapes strings

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/ControlEventResult.kt:10`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:970`
  - `android/app/src/test/java/com/example/galaxymirror/ControlEventResultTest.kt:9`
- Issue: `ControlEventResult.toAckJson()` was changed from `JSONObject` serialization to raw string
  interpolation. Rejected events can pass remote-controlled `type` into this serializer.
- Impact: a rejected payload containing quotes, backslashes, or control characters in `type` can produce
  invalid ACK JSON or inject extra fields, causing the viewer DataChannel parser to fail.
- Suggested fix: restore `JSONObject` serialization or quote string fields with a JSON encoder. Add a
  test with a quoted/backslash-containing rejected event type.

### P2. Empty clipboard values are accepted but not propagated outbound

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt:575`
  - `android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt:75`
  - `android/app/src/main/resources/files/viewer.js:763`
  - `android/app/src/main/resources/files/viewer.js:1175`
- Issue: the validator accepts empty clipboard text, but Android-to-Mac sends only `!text.isNullOrEmpty()`
  and viewer handling also gates on truthy `text`.
- Impact: clearing the Android clipboard does not clear the Mac clipboard, leaving stale clipboard
  contents on the viewer side.
- Suggested fix: define clipboard-clear semantics in `docs/Protocols.md`, propagate empty strings when
  the clip item exists, and update tests on both Kotlin and JS sides.

### P2. Recording can throw before the error handler on unsupported browsers

- Files:
  - `android/app/src/main/resources/files/viewer.js:1240`
  - `android/app/src/main/resources/files/viewer.js:1250`
- Issue: `MediaRecorder.isTypeSupported()` is called before the `try` block.
- Impact: on a browser without `MediaRecorder`, clicking record throws before the toast/error handling
  path runs.
- Suggested fix: guard `window.MediaRecorder` and `MediaRecorder.isTypeSupported` before probing codecs,
  then keep all recorder construction and codec selection inside the handled path.

### P2. M6 protocol documentation is stale

- Files:
  - `docs/Dashboard.md:59`
  - `docs/Handoff.md:123`
  - `docs/Protocols.md:143`
  - `docs/Protocols.md:153`
- Issue: milestone docs mark clipboard and hardware-key controls complete, but `docs/Protocols.md` still
  documents only the older Back/Home/Recents key surface and does not document the `clipboard` packet.
- Impact: future Android/JS changes can be implemented against a stale protocol spec.
- Suggested fix: update `docs/Protocols.md` with keycodes 24/25/26/164, the clipboard message shape, ACK
  behavior, and clipboard-clear semantics.

### P2. Android 14 reconnect policy docs contradict current service behavior

- Files:
  - `docs/Handoff.md:145`
  - `docs/Protocols.md:282`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:689`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt:707`
- Issue: docs still say viewer close/session replacement can require screen-share reapproval, while
  current code keeps the capturer/video track alive for replacement and session end.
- Impact: testers and future agents will debug Android 14 reconnect behavior from contradictory sources.
- Suggested fix: after deciding the correct capture policy, update the docs and tests to match the code.

### P3. Recording UI can get stuck when recording stops outside the button path

- Files:
  - `android/app/src/main/resources/files/viewer.js:1226`
  - `android/app/src/main/resources/files/viewer.js:1257`
- Issue: the click branch resets `isRecording` and button state before user-triggered stop, but `onstop`
  only downloads the blob.
- Impact: if the stream ends, reconnects, or recorder errors/stops externally, the UI can stay in the
  recording state.
- Suggested fix: centralize recorder cleanup and call it from click stop, `onstop`, and `onerror`.

### P3. Clipboard JS test hides duplicate listener/timer behavior

- Files:
  - `android/app/src/test/js/viewer-keyboard.test.mjs:112`
  - `android/app/src/test/js/viewer-keyboard.test.mjs:586`
  - `android/app/src/main/resources/files/viewer.js:1289`
- Issue: `viewer.js` already calls `setupClipboardSync()` on load, then the test calls it again. The
  fake clock does not drain all same-deadline timers, so duplicate 100 ms callbacks can be hidden.
- Impact: the test can pass while the app has duplicate listeners or duplicate sends.
- Suggested fix: install the clipboard mock before loading `viewer.js`, avoid calling setup twice, and
  make the fake clock drain all due timers at the same timestamp.

### P3. Viewer JS tests are not part of CI

- Files:
  - `.github/workflows/android-build.yml:28`
  - `android/app/src/test/js/viewer-keyboard.test.mjs:558`
  - `docs/Log.md:270`
- Issue: today added meaningful JS behavior and tests, but CI still runs only Gradle unit tests, lint,
  and assemble.
- Impact: PR/main builds can pass with broken viewer JS syntax or browser-side M5/M6 behavior.
- Suggested fix: add a Node step for `node --test android/app/src/test/js/viewer-keyboard.test.mjs` to
  CI, or wrap it in a Gradle task that CI invokes.

### P3. Today commits contain trailing whitespace

- Files:
  - `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
  - `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - `android/app/src/main/resources/files/index.html`
  - `android/app/src/main/resources/files/viewer.js`
- Issue: `git diff --check` reports trailing whitespace across the review range.
- Impact: not a runtime bug, but it creates avoidable diff noise and can fail stricter CI hooks later.
- Suggested fix: strip trailing whitespace in the touched files and consider adding an editor/CI check.

## Notes For Follow-Up

- The highest-risk cluster is MediaProjection lifecycle policy: decide whether fast reconnect is allowed
  to keep capture alive without an active viewer. That decision affects code, tests, and docs.
- The second highest-risk cluster is clipboard behavior on the actual HTTP viewer origin. Local JS tests
  mock the Clipboard API, so they do not prove the real deployment works.
- Device smoke testing should cover: connect, viewer close, viewer reconnect, screen lock/unlock,
  screen-share reauthorization, volume up/down/mute, clipboard copy both directions, recording start/stop,
  and screenshot.
