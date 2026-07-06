# USB Thermal Optimization Design

## Context

The user primarily uses Android Mirror in USB mode through `adb forward` and
`http://127.0.0.1:8080/?transport=usb`. The current USB transport captures a
VirtualDisplay into an `ImageReader`, copies RGBA pixels into a `Bitmap`,
optionally draws into another cropped `Bitmap`, JPEG-compresses the frame, then
sends the resulting byte array over `/usb/session`.

That JPEG path is likely the highest heat source because it repeatedly consumes
CPU, memory bandwidth, and heap allocation on every emitted frame. WebRTC mode
already has H.264 preference, bitrate/FPS caps, and active/idle quality, but USB
mode still needs its own heat-aware strategy.

Relevant Android references:

- Thermal Headroom API: https://developer.android.com/games/optimize/adpf/thermal
- PowerManager thermal headroom listener: https://developer.android.com/reference/android/os/PowerManager.OnThermalHeadroomChangedListener
- MediaProjection / VirtualDisplay capture model: https://developer.android.com/media/grow/media-projection

## Goals

1. Reduce Android phone heat during normal USB mirroring.
2. Keep USB mode usable as the default daily workflow.
3. Make heat/performance behavior measurable before and after changes.
4. Prefer adaptive workload reduction over sudden stream failure.
5. Preserve existing USB JPEG compatibility while leaving a path toward a
   hardware-encoded transport.

## Non-Goals

- Do not remove the current USB JPEG transport in the first pass.
- Do not require a Mac native helper app.
- Do not add a public relay, TURN server, or external network dependency.
- Do not change Tailscale/WebRTC behavior except for shared telemetry if useful.

## Proposed Architecture

### Phase 0: USB Perf And Thermal Instrumentation

Add a small USB performance monitor owned by `MediaProjectionService` and
`UsbScreenStreamer`.

Metrics:

- USB frames acquired, emitted, dropped by FPS gate, skipped by still-frame gate.
- JPEG encode duration in milliseconds.
- Bytes emitted per second.
- Effective USB profile: width, height, fps, JPEG quality.
- Android thermal status and thermal headroom when available.
- Battery temperature from `ACTION_BATTERY_CHANGED` as a coarse fallback.

Expose the latest snapshot through:

- `GET /debug/perf` for raw inspection.
- Optional Mac Viewer status text near the existing quality panel.

### Phase 1: USB Cool Mode

Introduce USB-specific adaptive profiles rather than reusing WebRTC assumptions.

Initial profile ladder:

- `COOL`: 360x800, 3-5fps, JPEG quality 45-55.
- `BALANCED`: 540x1200, 6-8fps, JPEG quality 55-65.
- `CLEAR`: 720x1600, 8-10fps, JPEG quality 65-70.
- `HIGH`: kept available but clearly treated as heat-heavy.

Default USB mode should prefer `BALANCED` or `COOL` depending on actual smoke
results. Existing `StreamQualityMode` can map to USB profiles, but USB should
internally clamp high-cost values when thermal state is warm.

Adaptive rules:

- Viewer input marks USB active for a short window.
- If idle, reduce FPS first, then JPEG quality, then resolution.
- If thermal status/headroom worsens, force a lower USB profile until cooldown.
- If the screen is visually stable, skip JPEG compression and frame emission.
- Recover gradually to avoid oscillation.

### Phase 2: H.264-Over-USB Experiment

Add a separate experimental transport only after Phase 0/1 measurements show
JPEG is still the dominant heat source.

Target shape:

- Android: `MediaProjection -> VirtualDisplay Surface -> MediaCodec H.264`.
- USB WebSocket: send encoded chunks and small metadata frames.
- Mac Chrome: decode with WebCodecs when available.
- Fallback: keep the current JPEG transport.

This phase is higher risk because browser decode plumbing, keyframe handling,
codec configuration, and WebCodecs support must be verified carefully. It is the
best long-term route if JPEG compression remains too hot.

## Data Flow

USB JPEG path after Phase 1:

1. Browser opens `/usb/session`.
2. Android resolves a USB profile from selected quality, viewer activity, and
   thermal state.
3. `UsbScreenStreamer` gates frames by current FPS.
4. For acquired frames, streamer performs a lightweight change check.
5. If unchanged, it records a skipped frame and avoids JPEG compression.
6. If changed, it encodes using current JPEG quality and emits bytes.
7. Perf monitor records timing and byte counts.
8. Viewer displays the active USB profile and thermal/cooldown status.

## Thermal Policy

Use the lowest available API that is safe for each OS version:

- API 30+: use `PowerManager.currentThermalStatus` where available.
- API 30+: poll `getThermalHeadroom(forecastSeconds)` when supported.
- Fallback: sample battery temperature as a weak signal.

Suggested downgrade thresholds:

- Normal: selected USB profile applies.
- Light warmup: cap to `BALANCED`.
- Sustained warm or low headroom: cap to `COOL`.
- Severe thermal state: pause frame emission briefly or hold at `COOL 3fps`.

Thermal recovery should require a stable cooldown window before stepping back
up.

## Error Handling

- If thermal APIs fail or return unavailable values, continue with static USB
  profiles and battery temperature only.
- If frame-diff logic throws, disable diff skipping for the session and continue
  normal JPEG streaming.
- If adaptive profile changes cause `VirtualDisplay` instability, prefer
  changing FPS/JPEG quality without rebuilding the display, and only rebuild for
  deliberate resolution changes.
- If `/debug/perf` is requested without authorization from non-loopback hosts,
  keep existing viewer token protection.

## Testing And Verification

Automated:

- Unit tests for USB profile resolution and thermal downgrade policy.
- Unit tests for perf snapshot aggregation.
- JS tests for Viewer display of USB thermal/profile status if UI is changed.
- Existing `app:testDebugUnitTest`, JS viewer tests, `assembleDebug`.

Manual real-device smoke:

1. Baseline current USB mode for 5 minutes:
   - CPU, AP/SKIN/BAT temperature, emitted FPS, bytes/sec.
2. Run Phase 1 cool mode for 5 minutes with the same screen.
3. Compare temperature rise and subjective smoothness.
4. Verify tap, swipe, keyboard, navigation buttons.
5. Verify idle downgrade and active recovery.
6. Verify thermal forced downgrade by reading `/debug/perf`.

## Open Decisions

1. USB default profile should be `BALANCED` unless smoke testing proves `COOL`
   is necessary as the default.
2. H.264-over-USB should remain experimental until Phase 1 data proves JPEG is
   still not acceptable.
3. The Viewer should show thermal status compactly; avoid adding a large new
   dashboard unless the data proves useful during daily use.

## Recommended Implementation Order

1. Add USB profile and thermal policy tests.
2. Add `UsbPerfMonitor` and `/debug/perf`.
3. Add USB adaptive profile resolution.
4. Add idle/active hooks for USB sessions.
5. Add frame-diff skip in `UsbScreenStreamer`.
6. Add Viewer profile/thermal status display.
7. Run real-device baseline and after-change comparison.
8. Decide whether to start the H.264-over-USB experiment.
