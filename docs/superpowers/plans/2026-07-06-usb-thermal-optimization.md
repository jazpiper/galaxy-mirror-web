# USB Thermal Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Android phone heat in the primary USB mirroring path by measuring the current JPEG pipeline, lowering default USB capture cost, adapting to thermal and idle state, skipping still frames, and making the active cooling state visible in the web UI.
**Architecture:** Keep the current USB JPEG transport as the production path. Add small pure Kotlin policy/metric components, wire them into `UsbScreenStreamer` and `MediaProjectionService`, expose `/debug/perf`, and update the web viewer to show compact USB profile and thermal status. Defer H.264/WebCodecs to a later experiment after this measured baseline is installed.
**Tech Stack:** Android Kotlin, MediaProjection, ImageReader, Ktor, org.json, JUnit4, vanilla browser JavaScript, Node-based JS tests, Gradle.

---

## Scope

This plan implements the measured USB cooling baseline only:

- USB stream profile downgrades for default use.
- Runtime thermal and idle policy.
- USB frame and encode metrics.
- Still-frame skip gate.
- Web UI status for USB profile, thermal state, and throughput.
- Device smoke verification on the connected `SM-S948N`.

This plan does not implement H.264 streaming. H.264 is evaluated only after the new `/debug/perf` data shows whether JPEG encode cost remains the dominant heat source.

## Current State

Existing USB path:

- `android/app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt`
  - Captures RGBA frames with `ImageReader`.
  - Copies into cached `Bitmap`.
  - Compresses every emitted frame to JPEG.
  - Emits bytes over the USB WebSocket.
- `android/app/src/main/java/com/example/galaxymirror/UsbStreamProfile.kt`
  - `DATA_SAVER`: `540x1200`, `8fps`, JPEG `65`.
  - `AUTO` and `STANDARD`: `720x1600`, `10fps`, JPEG `70`.
  - `HIGH`: `1080x2400`, `12fps`, JPEG `75`.
- `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - Starts USB streamer with a profile resolved once at session start.
  - Sends `USB_STATUS` with stream quality data.
  - Already has viewer idle tracking for WebRTC quality, but USB does not adapt after start.
- `android/app/src/main/resources/files/viewer.js`
  - Renders USB JPEG frames.
  - Already receives `USB_STATUS`.

Existing uncommitted UI work is present in the repository. Before implementation, preserve it or move this work to a clean branch/worktree so optimization commits remain reviewable.

## Target Behavior

- USB default should prioritize lower heat:
  - `DATA_SAVER` maps to `COOL`: `360x800`, `4fps`, JPEG `50`.
  - `AUTO` and `STANDARD` map to `BALANCED`: `540x1200`, `8fps`, JPEG `60`.
  - `HIGH` maps to `CLEAR`: `720x1600`, `10fps`, JPEG `68`.
- Thermal downgrade should clamp profile tier:
  - `NORMAL`: selected USB tier.
  - `LIGHT`: max `BALANCED`.
  - `MODERATE`: max `COOL`.
  - `SEVERE`, `CRITICAL`, `EMERGENCY`, `SHUTDOWN`: max `COOL` with `3fps`.
- Idle viewer state should clamp profile tier:
  - Active viewer: selected tier subject to thermal clamp.
  - Idle viewer: max `COOL`.
- Still screens should skip frame emission after repeated similar signatures while still allowing heartbeat frames.
- `/debug/perf` should return measurable counters:
  - active profile tier, dimensions, fps, JPEG quality.
  - thermal status, optional headroom, battery temperature if available.
  - frames acquired, emitted, dropped by FPS, skipped by still-frame gate, encode failures.
  - average and last encode milliseconds.
  - bytes emitted and approximate bytes per second.
- Web UI should show a compact USB status line when USB status data is available:
  - Example: `USB BALANCED 540x1200 8fps q60 | 1.2 MB/s | encode 18ms | thermal NORMAL`.

## Task 0: Protect The Current Worktree

**Files:** no code edits in this task.

- [ ] Run:

```bash
git status --short
```

- [ ] Confirm that these existing UI files are already modified before optimization begins:

```text
android/app/src/main/resources/files/index.html
android/app/src/main/resources/files/viewer.js
android/app/src/test/js/viewer-keyboard.test.mjs
android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt
```

- [ ] Choose one isolation path before editing optimization files:
  - Preferred: commit the previous USB UI fix first if the user wants it preserved as a separate change.
  - Alternative: create a new worktree from the current branch and implement optimization there.
  - Fallback: continue in the same worktree but stage only optimization files during review.

- [ ] Record the decision in the implementation summary.

## Task 1: Add USB Profile Tiers And Thermal Policy

**Files:**

- Modify: `android/app/src/main/java/com/example/galaxymirror/UsbStreamProfile.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbThermalPolicy.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/UsbStreamProfileTest.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/UsbThermalPolicyTest.kt`

### Test First

- [ ] Update `UsbStreamProfileTest` so the base USB profiles become:

```kotlin
@Test
fun autoResolvesToBalancedCoolingProfile() {
    val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO)

    assertEquals(UsbStreamProfileTier.BALANCED, profile.tier)
    assertEquals(540, profile.width)
    assertEquals(1200, profile.height)
    assertEquals(8, profile.fps)
    assertEquals(60, profile.jpegQuality)
}

@Test
fun dataSaverResolvesToCoolProfile() {
    val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)

    assertEquals(UsbStreamProfileTier.COOL, profile.tier)
    assertEquals(360, profile.width)
    assertEquals(800, profile.height)
    assertEquals(4, profile.fps)
    assertEquals(50, profile.jpegQuality)
}

@Test
fun highResolvesToClearCoolingProfile() {
    val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.HIGH)

    assertEquals(UsbStreamProfileTier.CLEAR, profile.tier)
    assertEquals(720, profile.width)
    assertEquals(1600, profile.height)
    assertEquals(10, profile.fps)
    assertEquals(68, profile.jpegQuality)
}
```

- [ ] Update the JSON status test to require:

```kotlin
assertEquals("AUTO", json.getString("selectedMode"))
assertEquals("BALANCED", json.getString("effectiveTier"))
assertEquals(540, json.getInt("effectiveWidth"))
assertEquals(1200, json.getInt("effectiveHeight"))
assertEquals(8, json.getInt("effectiveFps"))
assertEquals(60, json.getInt("jpegQuality"))
assertEquals("heat-first", json.getString("policy"))
```

- [ ] Create `UsbThermalPolicyTest` with these cases:

```kotlin
@Test
fun normalThermalKeepsSelectedProfile() {
    val profile = UsbThermalPolicy.resolve(
        selectedMode = StreamQualityMode.HIGH,
        thermalStatus = UsbThermalStatus.NORMAL,
        viewerIdle = false,
    )

    assertEquals(UsbStreamProfileTier.CLEAR, profile.tier)
    assertEquals(10, profile.fps)
}

@Test
fun lightThermalClampsHighToBalanced() {
    val profile = UsbThermalPolicy.resolve(
        selectedMode = StreamQualityMode.HIGH,
        thermalStatus = UsbThermalStatus.LIGHT,
        viewerIdle = false,
    )

    assertEquals(UsbStreamProfileTier.BALANCED, profile.tier)
}

@Test
fun moderateThermalClampsToCool() {
    val profile = UsbThermalPolicy.resolve(
        selectedMode = StreamQualityMode.HIGH,
        thermalStatus = UsbThermalStatus.MODERATE,
        viewerIdle = false,
    )

    assertEquals(UsbStreamProfileTier.COOL, profile.tier)
    assertEquals(4, profile.fps)
}

@Test
fun severeThermalUsesEmergencyCoolFps() {
    val profile = UsbThermalPolicy.resolve(
        selectedMode = StreamQualityMode.HIGH,
        thermalStatus = UsbThermalStatus.SEVERE,
        viewerIdle = false,
    )

    assertEquals(UsbStreamProfileTier.COOL, profile.tier)
    assertEquals(3, profile.fps)
}

@Test
fun idleViewerClampsToCool() {
    val profile = UsbThermalPolicy.resolve(
        selectedMode = StreamQualityMode.HIGH,
        thermalStatus = UsbThermalStatus.NORMAL,
        viewerIdle = true,
    )

    assertEquals(UsbStreamProfileTier.COOL, profile.tier)
}
```

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbStreamProfileTest --tests com.example.galaxymirror.UsbThermalPolicyTest --no-daemon
```

- [ ] Expected result before implementation: tests fail because the new tier, status, and policy types do not exist or old dimensions are still returned.

### Implementation

- [ ] Change `UsbStreamProfile` to include tier and policy metadata:

```kotlin
data class UsbStreamProfile(
    val tier: UsbStreamProfileTier,
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
    val policy: String = "heat-first",
)

enum class UsbStreamProfileTier {
    COOL,
    BALANCED,
    CLEAR,
}
```

- [ ] Update `UsbStreamProfilePolicy.resolve(selectedMode)` to return the target behavior values.

- [ ] Add `UsbStreamProfilePolicy.resolveTier(tier, emergencyFps: Boolean = false)`:

```kotlin
fun resolveTier(tier: UsbStreamProfileTier, emergencyFps: Boolean = false): UsbStreamProfile =
    when (tier) {
        UsbStreamProfileTier.COOL -> UsbStreamProfile(
            tier = tier,
            width = 360,
            height = 800,
            fps = if (emergencyFps) 3 else 4,
            jpegQuality = 50,
        )
        UsbStreamProfileTier.BALANCED -> UsbStreamProfile(
            tier = tier,
            width = 540,
            height = 1200,
            fps = 8,
            jpegQuality = 60,
        )
        UsbStreamProfileTier.CLEAR -> UsbStreamProfile(
            tier = tier,
            width = 720,
            height = 1600,
            fps = 10,
            jpegQuality = 68,
        )
    }
```

- [ ] Update `UsbStreamProfileCodec.toStatusJson` to emit:

```json
{
  "selectedMode": "AUTO",
  "effectiveTier": "BALANCED",
  "effectiveWidth": 540,
  "effectiveHeight": 1200,
  "effectiveFps": 8,
  "jpegQuality": 60,
  "policy": "heat-first"
}
```

- [ ] Create `UsbThermalPolicy.kt` with:

```kotlin
enum class UsbThermalStatus {
    UNKNOWN,
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

object UsbThermalPolicy {
    fun resolve(
        selectedMode: StreamQualityMode,
        thermalStatus: UsbThermalStatus,
        viewerIdle: Boolean,
    ): UsbStreamProfile {
        val selected = UsbStreamProfilePolicy.resolve(selectedMode)
        val maxTier = when {
            viewerIdle -> UsbStreamProfileTier.COOL
            thermalStatus == UsbThermalStatus.LIGHT -> UsbStreamProfileTier.BALANCED
            thermalStatus == UsbThermalStatus.MODERATE -> UsbStreamProfileTier.COOL
            thermalStatus >= UsbThermalStatus.SEVERE -> UsbStreamProfileTier.COOL
            else -> selected.tier
        }
        val targetTier = minOf(selected.tier, maxTier)
        val emergencyFps = thermalStatus >= UsbThermalStatus.SEVERE
        return UsbStreamProfilePolicy.resolveTier(targetTier, emergencyFps)
    }
}
```

- [ ] If Kotlin enum ordering is considered too implicit during review, replace `thermalStatus >= UsbThermalStatus.SEVERE` with a helper function `thermalStatus.isSevereOrWorse()`.

### Verify

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbStreamProfileTest --tests com.example.galaxymirror.UsbThermalPolicyTest --no-daemon
```

- [ ] Expected result after implementation: both tests pass.

## Task 2: Add USB Performance Monitor

**Files:**

- Create: `android/app/src/main/java/com/example/galaxymirror/UsbPerfMonitor.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/UsbPerfMonitorTest.kt`

### Test First

- [ ] Create `UsbPerfMonitorTest`:

```kotlin
class UsbPerfMonitorTest {
    @Test
    fun recordsFrameCountersAndEncodeTiming() {
        val monitor = UsbPerfMonitor(clockMillis = { 1_000L })

        monitor.recordFrameAcquired()
        monitor.recordFrameDroppedByFps()
        monitor.recordFrameSkippedByStillness()
        monitor.recordFrameEncoded(bytes = 100_000, encodeMillis = 20L)
        monitor.recordEncodeFailure()

        val snapshot = monitor.snapshot(
            profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO),
            thermalStatus = UsbThermalStatus.NORMAL,
            thermalHeadroom = 0.8f,
            batteryTemperatureC = 32.5f,
        )

        assertEquals(1, snapshot.framesAcquired)
        assertEquals(1, snapshot.framesDroppedByFps)
        assertEquals(1, snapshot.framesSkippedByStillness)
        assertEquals(1, snapshot.framesEmitted)
        assertEquals(1, snapshot.encodeFailures)
        assertEquals(20L, snapshot.lastEncodeMillis)
        assertEquals(20.0, snapshot.averageEncodeMillis, 0.01)
        assertEquals(100_000L, snapshot.bytesEmitted)
        assertEquals(UsbStreamProfileTier.BALANCED, snapshot.profile.tier)
        assertEquals(UsbThermalStatus.NORMAL, snapshot.thermalStatus)
        assertEquals(0.8f, snapshot.thermalHeadroom)
        assertEquals(32.5f, snapshot.batteryTemperatureC)
    }

    @Test
    fun snapshotJsonContainsStableKeysForDebugEndpoint() {
        val monitor = UsbPerfMonitor(clockMillis = { 1_000L })
        monitor.recordFrameEncoded(bytes = 50_000, encodeMillis = 10L)

        val json = monitor.snapshot(
            profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER),
            thermalStatus = UsbThermalStatus.LIGHT,
            thermalHeadroom = null,
            batteryTemperatureC = null,
        ).toJson()

        assertEquals("COOL", json.getJSONObject("profile").getString("tier"))
        assertEquals(360, json.getJSONObject("profile").getInt("width"))
        assertEquals("LIGHT", json.getString("thermalStatus"))
        assertTrue(json.has("bytesPerSecond"))
        assertTrue(json.has("framesAcquired"))
        assertTrue(json.has("framesEmitted"))
    }
}
```

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbPerfMonitorTest --no-daemon
```

- [ ] Expected result before implementation: fails because `UsbPerfMonitor` does not exist.

### Implementation

- [ ] Create `UsbPerfMonitor` with synchronized counters:

```kotlin
class UsbPerfMonitor(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val startedAtMillis = clockMillis()
    private var framesAcquired = 0L
    private var framesDroppedByFps = 0L
    private var framesSkippedByStillness = 0L
    private var framesEmitted = 0L
    private var encodeFailures = 0L
    private var bytesEmitted = 0L
    private var totalEncodeMillis = 0L
    private var lastEncodeMillis = 0L

    fun recordFrameAcquired()
    fun recordFrameDroppedByFps()
    fun recordFrameSkippedByStillness()
    fun recordFrameEncoded(bytes: Int, encodeMillis: Long)
    fun recordEncodeFailure()
    fun reset()
    fun snapshot(
        profile: UsbStreamProfile,
        thermalStatus: UsbThermalStatus,
        thermalHeadroom: Float?,
        batteryTemperatureC: Float?,
    ): UsbPerfSnapshot
}
```

- [ ] Create `UsbPerfSnapshot` with immutable values and `toJson(): JSONObject`.

- [ ] Calculate `bytesPerSecond` from elapsed seconds with a minimum denominator of one second.

- [ ] Keep this class Android-free except for `org.json.JSONObject`; this keeps unit tests fast.

### Verify

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbPerfMonitorTest --no-daemon
```

- [ ] Expected result after implementation: test passes.

## Task 3: Add Dynamic FPS And Still-Frame Gates

**Files:**

- Modify: `android/app/src/main/java/com/example/galaxymirror/UsbFrameRateGate.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbFrameChangeGate.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/UsbFrameRateGateTest.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/UsbFrameChangeGateTest.kt`

### Test First

- [ ] Extend `UsbFrameRateGateTest`:

```kotlin
@Test
fun updateFpsChangesIntervalWithoutRecreatingGate() {
    var now = 0L
    val gate = UsbFrameRateGate(fps = 10, nanoTime = { now })

    assertTrue(gate.shouldEmit())
    now += 60_000_000L
    assertFalse(gate.shouldEmit())

    gate.updateFps(4)
    now += 190_000_000L
    assertTrue(gate.shouldEmit())
}
```

- [ ] Create `UsbFrameChangeGateTest`:

```kotlin
class UsbFrameChangeGateTest {
    @Test
    fun emitsFirstFrame() {
        val gate = UsbFrameChangeGate(maxStillSkips = 4, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(
            UsbFrameChangeDecision.EMIT,
            gate.evaluate(signature = 10L, nowNanos = 0L),
        )
    }

    @Test
    fun skipsRepeatedStillFramesUntilHeartbeat() {
        val gate = UsbFrameChangeGate(maxStillSkips = 4, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 0L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 100_000_000L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 200_000_000L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 300_000_000L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 400_000_000L))
        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 500_000_000L))
    }

    @Test
    fun emitsWhenSignatureChanges() {
        val gate = UsbFrameChangeGate(maxStillSkips = 4, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 0L))
        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(11L, 100_000_000L))
    }

    @Test
    fun emitsHeartbeatEvenWhenStill() {
        val gate = UsbFrameChangeGate(maxStillSkips = 100, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 0L))
        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 1_000_000_000L))
    }
}
```

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbFrameRateGateTest --tests com.example.galaxymirror.UsbFrameChangeGateTest --no-daemon
```

- [ ] Expected result before implementation: `UsbFrameChangeGate` is missing and `UsbFrameRateGate.updateFps` is missing.

### Implementation

- [ ] Modify `UsbFrameRateGate` so `fps` can be updated:

```kotlin
class UsbFrameRateGate(
    fps: Int,
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    private var intervalNanos = intervalFor(fps)
    private var lastEmitNanos = Long.MIN_VALUE

    fun updateFps(fps: Int) {
        intervalNanos = intervalFor(fps)
    }

    fun shouldEmit(): Boolean { ... }
}
```

- [ ] Create `UsbFrameChangeGate` using frame signatures only:

```kotlin
enum class UsbFrameChangeDecision {
    EMIT,
    SKIP_STILL,
}

class UsbFrameChangeGate(
    private val maxStillSkips: Int = 4,
    private val heartbeatEveryNanos: Long = 1_000_000_000L,
) {
    private var lastSignature: Long? = null
    private var skippedStillFrames = 0
    private var lastEmitNanos = Long.MIN_VALUE

    fun evaluate(signature: Long, nowNanos: Long = System.nanoTime()): UsbFrameChangeDecision { ... }
    fun reset() { ... }
}
```

- [ ] The signature computation itself stays in `UsbScreenStreamer` so the pure gate can remain JVM-testable.

### Verify

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbFrameRateGateTest --tests com.example.galaxymirror.UsbFrameChangeGateTest --no-daemon
```

- [ ] Expected result after implementation: tests pass.

## Task 4: Wire Metrics And Gates Into UsbScreenStreamer

**Files:**

- Modify: `android/app/src/main/java/com/example/galaxymirror/UsbScreenStreamer.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/UsbScreenStreamerSourceTest.kt`

### Test First

- [ ] Add source-level assertions in `UsbScreenStreamerSourceTest` because Android `Image` and `ImageReader` are not JVM-friendly:

```kotlin
@Test
fun sourceRecordsUsbPerfCounters() {
    val source = readSource()

    assertTrue(source.contains("perfMonitor.recordFrameAcquired()"))
    assertTrue(source.contains("perfMonitor.recordFrameDroppedByFps()"))
    assertTrue(source.contains("perfMonitor.recordFrameSkippedByStillness()"))
    assertTrue(source.contains("perfMonitor.recordFrameEncoded("))
    assertTrue(source.contains("perfMonitor.recordEncodeFailure()"))
}

@Test
fun sourceUsesDynamicProfileProviderAndChangeGate() {
    val source = readSource()

    assertTrue(source.contains("profileProvider: () -> UsbStreamProfile"))
    assertTrue(source.contains("frameRateGate.updateFps(profile.fps)"))
    assertTrue(source.contains("UsbFrameChangeGate"))
    assertTrue(source.contains("computeFrameSignature("))
}
```

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbScreenStreamerSourceTest --no-daemon
```

- [ ] Expected result before implementation: fails because streamer does not use these new collaborators.

### Implementation

- [ ] Change `UsbScreenStreamer.start` signature to accept dynamic profile and metrics:

```kotlin
fun start(
    resultCode: Int,
    resultData: Intent,
    profileProvider: () -> UsbStreamProfile,
    perfMonitor: UsbPerfMonitor,
    changeGate: UsbFrameChangeGate = UsbFrameChangeGate(),
    onFrame: (ByteArray) -> Unit,
)
```

- [ ] Keep the initial `ImageReader` and `VirtualDisplay` dimensions from the current profile at start. Dynamic profile changes update FPS and JPEG quality during the current session. Dimension changes apply on the next session restart.

- [ ] In `handleImageAvailable`:
  - call `perfMonitor.recordFrameAcquired()` after acquiring a non-null image.
  - resolve the latest `profile = profileProvider()`.
  - call `frameRateGate.updateFps(profile.fps)` before `shouldEmit()`.
  - if FPS gate rejects the frame, call `perfMonitor.recordFrameDroppedByFps()` and close the image.
  - compute a cheap frame signature before JPEG encode.
  - if change gate returns `SKIP_STILL`, call `perfMonitor.recordFrameSkippedByStillness()` and close the image.
  - measure JPEG encode time with `SystemClock.elapsedRealtime()`.
  - after `onFrame(bytes)`, call `perfMonitor.recordFrameEncoded(bytes.size, encodeMillis)`.
  - on encode failure, call `perfMonitor.recordEncodeFailure()`.

- [ ] Add `computeFrameSignature(image: Image): Long`:
  - Use the first plane buffer.
  - Sample a small fixed grid such as 8 rows by 8 columns.
  - Use row stride and pixel stride.
  - Read only one byte per sampled pixel for speed.
  - Combine samples with a simple rolling hash.
  - Rewind or duplicate the buffer so JPEG encode still sees the full frame.

- [ ] Avoid new bitmap allocations in the signature path.

- [ ] Reset `changeGate` when a session starts and in `releaseResources`.

### Verify

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbScreenStreamerSourceTest --no-daemon
```

- [ ] Expected result after implementation: source-level tests pass.

## Task 5: Wire Thermal Sampling, Dynamic Profile, And `/debug/perf`

**Files:**

- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Create: `android/app/src/main/java/com/example/galaxymirror/UsbThermalReader.kt`
- Create: `android/app/src/test/java/com/example/galaxymirror/UsbThermalReaderTest.kt`
- Modify: `android/app/src/test/java/com/example/galaxymirror/MirrorSessionStateTest.kt`

### Test First

- [ ] Create `UsbThermalReaderTest` for pure status mapping:

```kotlin
class UsbThermalReaderTest {
    @Test
    fun mapsAndroidThermalStatusConstants() {
        assertEquals(UsbThermalStatus.NORMAL, UsbThermalReader.mapStatus(0))
        assertEquals(UsbThermalStatus.LIGHT, UsbThermalReader.mapStatus(1))
        assertEquals(UsbThermalStatus.MODERATE, UsbThermalReader.mapStatus(2))
        assertEquals(UsbThermalStatus.SEVERE, UsbThermalReader.mapStatus(3))
        assertEquals(UsbThermalStatus.CRITICAL, UsbThermalReader.mapStatus(4))
        assertEquals(UsbThermalStatus.EMERGENCY, UsbThermalReader.mapStatus(5))
        assertEquals(UsbThermalStatus.SHUTDOWN, UsbThermalReader.mapStatus(6))
        assertEquals(UsbThermalStatus.UNKNOWN, UsbThermalReader.mapStatus(999))
    }
}
```

- [ ] Add source assertions to `MirrorSessionStateTest`:

```kotlin
@Test
fun serviceExposesUsbPerfDebugEndpoint() {
    val source = readServiceSource()

    assertTrue(source.contains("private val usbPerfMonitor"))
    assertTrue(source.contains("get(\"/debug/perf\")"))
    assertTrue(source.contains("usbPerfMonitor.snapshot("))
}

@Test
fun serviceStartsUsbStreamerWithThermalPolicyProfileProvider() {
    val source = readServiceSource()

    assertTrue(source.contains("UsbThermalPolicy.resolve("))
    assertTrue(source.contains("profileProvider = ::resolveCurrentUsbProfile"))
    assertTrue(source.contains("perfMonitor = usbPerfMonitor"))
}
```

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbThermalReaderTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

- [ ] Expected result before implementation: fails because the reader and endpoint wiring do not exist.

### Implementation

- [ ] Create `UsbThermalReader`:

```kotlin
class UsbThermalReader(private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun readStatus(): UsbThermalStatus =
        mapStatus(powerManager?.currentThermalStatus ?: -1)

    fun readHeadroom(): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { powerManager?.getThermalHeadroom(10) }.getOrNull()
        } else {
            null
        }

    fun readBatteryTemperatureC(): Float? { ... }

    companion object {
        fun mapStatus(status: Int): UsbThermalStatus = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> UsbThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> UsbThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> UsbThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> UsbThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> UsbThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> UsbThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> UsbThermalStatus.SHUTDOWN
            else -> UsbThermalStatus.UNKNOWN
        }
    }
}
```

- [ ] Implement battery temperature through `ACTION_BATTERY_CHANGED`, dividing `BatteryManager.EXTRA_TEMPERATURE` by `10f`.

- [ ] Add service fields:

```kotlin
private val usbPerfMonitor = UsbPerfMonitor()
private lateinit var usbThermalReader: UsbThermalReader
@Volatile private var lastUsbProfile: UsbStreamProfile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO)
```

- [ ] Initialize `usbThermalReader` in `onCreate`.

- [ ] Add:

```kotlin
private fun resolveCurrentUsbProfile(): UsbStreamProfile {
    val profile = UsbThermalPolicy.resolve(
        selectedMode = streamQualityMode,
        thermalStatus = usbThermalReader.readStatus(),
        viewerIdle = viewerActivityState.isIdle,
    )
    lastUsbProfile = profile
    return profile
}
```

- [ ] If `viewerActivityState` does not expose a boolean, add a small service-local helper that compares the latest viewer activity timestamp to the existing idle delay.

- [ ] Start USB streaming with:

```kotlin
usbScreenStreamer.start(
    resultCode = resultCode,
    resultData = resultData,
    profileProvider = ::resolveCurrentUsbProfile,
    perfMonitor = usbPerfMonitor,
) { frame -> ... }
```

- [ ] Reset `usbPerfMonitor` when a new USB session starts.

- [ ] Update `buildUsbStatusMessage` so `streamQuality` reflects `lastUsbProfile` instead of only the selected mode. Include a `usbPerf` object using the monitor snapshot.

- [ ] Add authorized endpoint:

```kotlin
get("/debug/perf") {
    if (!requireViewerAuthorization(call)) return@get
    call.respondText(
        usbPerfMonitor.snapshot(
            profile = lastUsbProfile,
            thermalStatus = usbThermalReader.readStatus(),
            thermalHeadroom = usbThermalReader.readHeadroom(),
            batteryTemperatureC = usbThermalReader.readBatteryTemperatureC(),
        ).toJson().toString(),
        ContentType.Application.Json,
    )
}
```

### Verify

- [ ] Run:

```bash
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.UsbThermalReaderTest --tests com.example.galaxymirror.MirrorSessionStateTest --no-daemon
```

- [ ] Expected result after implementation: tests pass.

## Task 6: Show Compact USB Cooling Status In Web UI

**Files:**

- Modify: `android/app/src/main/resources/files/index.html`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/test/js/viewer-keyboard.test.mjs`

### Test First

- [ ] Add a JS test that sends `USB_STATUS` with `streamQuality` and `usbPerf`:

```javascript
test('renders USB thermal and perf status from USB_STATUS', () => {
  const context = createViewerContext();
  context.handleUsbTextMessage(JSON.stringify({
    type: 'USB_STATUS',
    captureReady: true,
    accessibilityReady: true,
    streamQuality: {
      selectedMode: 'AUTO',
      effectiveTier: 'BALANCED',
      effectiveWidth: 540,
      effectiveHeight: 1200,
      effectiveFps: 8,
      jpegQuality: 60,
      policy: 'heat-first',
    },
    usbPerf: {
      thermalStatus: 'NORMAL',
      bytesPerSecond: 1200000,
      lastEncodeMillis: 18,
      averageEncodeMillis: 20,
      framesSkippedByStillness: 5,
    },
  }));

  assert.match(context.document.body.textContent, /USB BALANCED/);
  assert.match(context.document.body.textContent, /540x1200/);
  assert.match(context.document.body.textContent, /8fps/);
  assert.match(context.document.body.textContent, /q60/);
  assert.match(context.document.body.textContent, /1.2 MB\/s/);
  assert.match(context.document.body.textContent, /encode 18ms/);
  assert.match(context.document.body.textContent, /thermal NORMAL/);
});
```

- [ ] Run:

```bash
node android/app/src/test/js/viewer-keyboard.test.mjs
```

- [ ] Expected result before implementation: fails because the compact USB cooling status is not rendered.

### Implementation

- [ ] Add a compact status element near the existing stream quality display:

```html
<div id="usbCoolingStatus" class="status-line" hidden></div>
```

- [ ] In `viewer.js`, add formatting helpers:

```javascript
function formatBytesPerSecond(value) {
  if (!Number.isFinite(value) || value <= 0) return '0 MB/s';
  return `${(value / 1_000_000).toFixed(1)} MB/s`;
}

function renderUsbCoolingStatus(streamQuality, usbPerf) {
  const el = document.getElementById('usbCoolingStatus');
  if (!el || !streamQuality) return;
  const tier = streamQuality.effectiveTier || 'USB';
  const size = `${streamQuality.effectiveWidth || '-'}x${streamQuality.effectiveHeight || '-'}`;
  const fps = `${streamQuality.effectiveFps || '-'}fps`;
  const quality = `q${streamQuality.jpegQuality || '-'}`;
  const thermal = usbPerf?.thermalStatus || 'UNKNOWN';
  const bps = formatBytesPerSecond(usbPerf?.bytesPerSecond || 0);
  const encode = Number.isFinite(usbPerf?.lastEncodeMillis)
    ? `encode ${usbPerf.lastEncodeMillis}ms`
    : 'encode -';
  el.textContent = `USB ${tier} ${size} ${fps} ${quality} | ${bps} | ${encode} | thermal ${thermal}`;
  el.hidden = false;
}
```

- [ ] Call `renderUsbCoolingStatus(payload.streamQuality, payload.usbPerf)` from `handleUsbTextMessage` for `USB_STATUS`.

- [ ] Hide or replace the compact status on disconnect using the already implemented disconnected placeholder flow.

### Verify

- [ ] Run:

```bash
node android/app/src/test/js/viewer-keyboard.test.mjs
```

- [ ] Expected result after implementation: JS tests pass.

## Task 7: Full Local Verification

**Files:** all modified files.

- [ ] Run targeted tests:

```bash
./gradlew app:testDebugUnitTest \
  --tests com.example.galaxymirror.UsbStreamProfileTest \
  --tests com.example.galaxymirror.UsbThermalPolicyTest \
  --tests com.example.galaxymirror.UsbPerfMonitorTest \
  --tests com.example.galaxymirror.UsbFrameRateGateTest \
  --tests com.example.galaxymirror.UsbFrameChangeGateTest \
  --tests com.example.galaxymirror.UsbScreenStreamerSourceTest \
  --tests com.example.galaxymirror.UsbThermalReaderTest \
  --tests com.example.galaxymirror.MirrorSessionStateTest \
  --no-daemon
```

- [ ] Expected result: all targeted JVM tests pass.

- [ ] Run web UI tests:

```bash
node android/app/src/test/js/viewer-keyboard.test.mjs
```

- [ ] Expected result: JS tests pass.

- [ ] Run Android compile/install checks:

```bash
./gradlew app:assembleDebug app:installDebug --no-daemon
```

- [ ] Expected result: debug APK builds and installs on the connected Android device.

- [ ] Run diff hygiene:

```bash
git diff --check
```

- [ ] Expected result: no whitespace errors.

## Task 8: USB Device Smoke And Perf Capture

**Files:** no source edits unless smoke reveals a specific defect.

- [ ] Ensure USB port forwarding:

```bash
adb forward tcp:8080 tcp:8080
```

- [ ] Start the app:

```bash
adb shell am start -n com.example.galaxymirror/.MainActivity
```

- [ ] Confirm web server responds:

```bash
curl -I --max-time 5 'http://127.0.0.1:8080/?transport=usb'
```

- [ ] Expected result: HTTP `200 OK`.

- [ ] Start USB mirroring in the web UI and keep it running for 3 minutes.

- [ ] Capture performance snapshot:

```bash
curl --max-time 5 'http://127.0.0.1:8080/debug/perf'
```

- [ ] Expected result: JSON includes `profile`, `thermalStatus`, `framesAcquired`, `framesEmitted`, `framesSkippedByStillness`, `averageEncodeMillis`, `bytesPerSecond`.

- [ ] Capture Android thermal service snapshot:

```bash
adb shell dumpsys thermalservice
```

- [ ] Capture app CPU snapshot:

```bash
adb shell top -b -n 1 | rg 'galaxymirror|PID|CPU'
```

- [ ] Record:
  - starting thermal status and temperature.
  - 3-minute thermal status and temperature.
  - `/debug/perf` profile tier.
  - average encode milliseconds.
  - bytes per second.
  - app CPU percentage.

## Task 9: Documentation And Handoff

**Files:**

- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`

- [ ] Add a concise entry to `docs/Log.md`:
  - date.
  - USB thermal optimization changes.
  - tests run.
  - device smoke data.

- [ ] Update `docs/Handoff.md`:
  - current USB default profile behavior.
  - `/debug/perf` usage.
  - `adb forward tcp:8080 tcp:8080` reminder remains visible in web UI.
  - known next step: evaluate H.264 only if JPEG encode still dominates heat.

## Task 10: Review And Commit

- [ ] Inspect changed files:

```bash
git diff -- android/app/src/main/java/com/example/galaxymirror android/app/src/test/java/com/example/galaxymirror android/app/src/main/resources/files android/app/src/test/js docs/Handoff.md docs/Log.md
```

- [ ] Confirm no unrelated user changes are staged.

- [ ] If the previous USB UI fix is still uncommitted, stage optimization files separately from UI fix files or ask the user whether to combine them.

- [ ] Commit message in Korean:

```bash
git commit -m "USB 미러링 발열 최적화 적용"
```

## Success Criteria

- Default USB mirroring no longer starts at `720x1600 10fps q70`; it starts at the heat-first `BALANCED 540x1200 8fps q60`.
- User-selected high quality no longer uses `1080x2400`; it caps at `CLEAR 720x1600 10fps q68`.
- Thermal and idle state can reduce USB frame cost without restarting the app.
- Still screens skip repeated frame emission while heartbeat frames continue.
- `/debug/perf` gives enough data to decide whether H.264 is worth implementing next.
- Web UI clearly shows the active USB cooling status.
- Targeted Kotlin tests, JS tests, APK build/install, and USB smoke checks pass.
