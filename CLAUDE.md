# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Zero-install screen mirroring + remote control: an **Android phone is the Host** (runs an embedded Ktor web server + captures its own screen), and a **Mac browser is the Viewer** (plain HTML/JS served by that server). No app is installed on the Mac. Two transports carry the stream: **Tailscale MagicDNS + WebRTC** (default, wireless) and **USB via `adb forward` + WebSocket** (localhost, wired).

## Commands

All build/test/lint commands run from the `android/` directory:

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon      # JVM unit tests (Robolectric-free; JUnit + Mockito + coroutines-test)
./gradlew app:lintDebug --no-daemon              # Android lint
./gradlew assembleDebug --no-daemon              # Build debug APK
./gradlew app:connectedDebugAndroidTest --no-daemon   # On-device instrumentation tests (needs a device/emulator)
```

Run a single JVM test class or method:

```bash
./gradlew app:testDebugUnitTest --tests "com.example.galaxymirror.UsbStreamProfileTest"
./gradlew app:testDebugUnitTest --tests "com.example.galaxymirror.UsbStreamProfileTest.someMethodName"
```

The Mac Viewer's JS logic has its own Node-based test suite (no framework, uses `node:test` + DOM fakes). These are **not run by Gradle** — run them directly, exactly as CI does:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
node --test android/app/src/test/js/viewer-layout.test.mjs
node --check android/app/src/main/resources/files/viewer.js   # syntax check
```

CI (`.github/workflows/android-build.yml`, on push/PR to `main`) runs: `app:testDebugUnitTest` → the JS `viewer-keyboard` test → `app:lintDebug` → `assembleDebug`. Match that order locally before pushing.

Toolchain: Java 21, `compileSdk`/`targetSdk` 36, `minSdk` 29, `applicationId` `com.example.galaxymirror`.

To exercise the USB transport locally (phone connected via cable), forward the port and open the Viewer against loopback:

```bash
adb forward tcp:8080 tcp:8080
# then open http://127.0.0.1:8080/?transport=usb in Chrome (WebCodecs → H.264, else JPEG)
```

## Architecture

The Host is a single Android app under `android/app/src/main/java/com/example/galaxymirror/`. Key seams:

- **`MediaProjectionService.kt`** — the center of gravity. A foreground service that (1) hosts the Ktor CIO server bound to `127.0.0.1:8080`, (2) owns all WebSocket routes (`setupRouting()` defines `/signaling`, `/usb/session`, `/stream/quality`, `/apps/*`, `/debug/perf`), (3) runs the WebRTC peer connection and SDP/ICE exchange, and (4) enforces MediaProjection lifecycle. Most cross-cutting changes land here.
- **`MainActivity.kt`** — Compose UI entry point; binds to the service, requests the screen-capture permission, and reflects `MirrorServiceState` (a StateFlow published by the service) into the UI.
- **`GalaxyMirrorAccessibilityService.kt`** — turns Viewer input (touch %, keys, text) into Android gestures/actions. Input flows into it through the `ControlEvent*` pipeline: `ControlEventValidator` → `ControlEventDispatcher` → `ControlEventApplier` → `ControlEventResult`.
- **Transport & streaming**: `MirrorTransport` enum (`TAILSCALE_WEBRTC`, `USB_JPEG`, `USB_H264`) is the discriminator threaded through session management. USB streaming has two codecs — `UsbH264ScreenStreamer` (MediaCodec H.264, preferred when the browser has WebCodecs) and `UsbScreenStreamer` (JPEG fallback). `UsbStreamProfile`/`UsbH264StreamProfile` define resolution/fps/bitrate tiers; `UsbThermalPolicy` + `UsbThermalReader` + viewer-idle state clamp the active profile down under heat/idle; `UsbFrameChangeGate`/`UsbFrameRateGate` skip redundant frames; `UsbPerfMonitor` feeds `/debug/perf`.
- **Stream quality (WebRTC)**: `AdaptiveStreamQuality` resolves the effective profile from user mode × `NetworkTransportDetector` (wifi/ethernet vs. cellular) × viewer activity.
- **Session state**: `MirrorSessionState` is an immutable value type tracking the single active session; only one Viewer session is active at a time, and beginning a new one tears down the previous transport.
- **Mac Viewer** lives in `android/app/src/main/resources/files/` (`index.html`, `viewer.js`, ...), served statically by Ktor. **Vanilla HTML/JS/CSS only — do not introduce React/Vue or any frontend framework or build step.**

### Hard constraints (violating these breaks the product)

- **MediaProjection single-use tokens (Android 14+)**: capture grants are not reusable. On Viewer disconnect/replacement, clean up the existing capture; on reconnect, send `SCREEN_CAPTURE_REAUTH_REQUIRED` and wait for the user to re-approve in Android. Never assume a cached grant survives a reconnect.
- **No public network surface**: this is a private/local tool. Do not add TURN servers, public endpoints, or any relay beyond Tailscale's own DERP. There is intentionally **no viewer access token** — `/signaling`, `/usb/session`, `/debug/perf`, and the app/quality APIs are all unauthenticated by design (loopback + WireGuard tunnel are the trust boundary). `ViewerAccessGuard`/`ViewerAccessTokenStore` were deliberately removed; don't reintroduce token auth.
- **Coordinate mapping & text injection**: touch/swipe %-to-resolution math must follow `docs/Coordinates.md` (letterbox/pillarbox correction against `object-fit: contain`). Text input uses `AccessibilityNodeInfo.ACTION_SET_TEXT` with a backoff/watchdog mechanism (`RemoteTextInputBuffer`, `TextInputTargetSelector`) to avoid IME freezes — don't strip that.
- Wire protocol payloads (signaling, control, USB frames, HTTP APIs) are specified in `docs/Protocols.md`; keep code and that doc in sync when payloads change.

## Docs are the source of truth for state

`docs/` is the project's persistent memory. When implementation state changes, update `docs/Handoff.md` (current-state board) and `docs/Log.md` (dated chronicle). When protocol payloads or coordinate formulas change, also update `docs/Protocols.md` / `docs/Coordinates.md`. `.agents/AGENTS.md` holds the same project rules for the Antigravity agent — keep it consistent with this file.
