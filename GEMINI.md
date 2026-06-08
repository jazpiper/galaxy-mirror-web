# Galaxy Mirror Web Project Context

## Project Overview

**Galaxy Mirror Web** is a zero-install remote mirroring and control system that allows users to cast their Android device's screen to a Mac web browser and control it using a mouse and keyboard.

The core architecture relies on:
- **Tailscale MagicDNS:** For secure, zero-config domain access bypassing complex NAT firewalls.
- **Embedded Web Server:** A lightweight Ktor CIO server running on the Android host, binding to `0.0.0.0:8080`.
- **WebRTC Streaming:** Low-latency video streaming using Android's `MediaProjection` API and WebRTC PeerConnection.
- **Remote Control Injection:** Keyboard and mouse touch injection using Android's `AccessibilityService`.

## Main Technologies

- **Language:** Kotlin (Java 21 toolchain) for the Android app, Vanilla HTML5 / JavaScript / CSS for the Mac Viewer.
- **Host Web Server:** Ktor CIO (3.0.3)
- **Video Capture/Stream:** WebRTC SDK (Android and Browser native), MediaProjection API.
- **Control Input:** Android AccessibilityService (`GalaxyMirrorAccessibilityService`).
- **Network Tunneling:** Tailscale (MagicDNS).

## Repository Structure

- `android/` - Android project root
  - `MainActivity.kt` - Entry point, Compose UI, Ktor Server & WebRTC signaling handler
  - `MediaProjectionService.kt` - Foreground service for screen capture
  - `GalaxyMirrorAccessibilityService.kt` - Accessibility Service for input injection
  - `CrashDiagnostics.kt` - Local crash log diagnostics
  - `index.html`, `viewer.js`, `viewer-keyboard.js` - Mac Web Viewer static files (Ktor resources)
- `docs/` - Project documentation
  - `Dashboard.md` - Project hub & roadmap
  - `Log.md` - Development history
  - `Handoff.md` - Handoff notes & task board
  - `Protocols.md` - WebSocket & WebRTC DataChannel protocols
  - `Coordinates.md` - Coordinate conversion spec

## Building and Running

Commands should be executed within the `android/` directory:

```bash
cd android

# Compile and assemble debug APK
./gradlew assembleDebug --no-daemon

# Run local unit tests
./gradlew app:testDebugUnitTest --no-daemon

# Run instrumented UI tests (requires a running emulator or device)
./gradlew app:connectedDebugAndroidTest --no-daemon

# Run lint checks
./gradlew app:lintDebug --no-daemon
```

## Development Conventions

- **Vanilla Front-end Only:** Mac Viewer must be kept as Vanilla HTML/JS/CSS. Do not introduce any front-end frameworks (React, Vue, etc.) to maintain zero client installation simplicity.
- **Aspect Ratio Formulas:** All click/swipe coordinate conversions must conform to the specs in `docs/Coordinates.md`.
- **Media Projection Lifecycle:** Strictly adhere to the single-use token constraints in Android 14+. The application handles token reuse exceptions by signaling `SCREEN_CAPTURE_REAUTH_REQUIRED` to the client.
- **Documentation Updates:** When code changes are made, update `docs/Handoff.md` and `docs/Log.md` to keep documentation synchronised with the codebase status.
- **No Stale Sessions:** Make sure cleanup tasks for WebRTC, WebSocket connections, and MediaProjection screen captures are robust, checking session/instance IDs to prevent race conditions during viewer reconnection.
