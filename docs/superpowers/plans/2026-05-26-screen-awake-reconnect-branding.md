# Screen Awake, Reconnect, and Branding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-controlled keep-screen-on and mirror screen-protection modes, make lock-induced MediaProjection stops visible and recoverable, and rebrand the app from Galaxy-specific wording to Android-compatible wording.

**Architecture:** Keep-screen-on and screen-protection state lives in Android local preferences and is surfaced in Compose. `MainActivity` and `MediaProjectionService` apply the keep-awake policy with window flags plus a foreground-service partial wake lock while mirroring is active. `MainActivity` also schedules a secure local protection Activity during mirroring; that Activity displays a black `미러링 중` screen with `FLAG_SECURE` so Android should exclude or blank it from MediaProjection capture. MediaProjection lock/stop is not hidden: `onStop()` records a stopped state, notifies the viewer through `STATUS`, and queues reconnection until Android screen-share permission is granted again.

**Tech Stack:** Android Kotlin, Jetpack Compose Material3, SharedPreferences, Ktor CIO WebSocket/HTTP, org.webrtc `ScreenCapturerAndroid`, `PowerManager.WakeLock`, Vanilla HTML/JS viewer, JUnit/Node tests.

---

## File Structure

- `android/app/src/main/java/com/example/galaxymirror/ScreenAwakeSettings.kt`
  - Owns persisted `keepScreenAwakeDuringMirroring`, `protectScreenDuringMirroring`, and protection delay flags.
- `android/app/src/main/java/com/example/galaxymirror/MirrorProtectionActivity.kt`
  - Secure black local screen shown during mirroring. It finishes on tap/back.
- `android/app/src/main/java/com/example/galaxymirror/ProjectionStopReason.kt`
  - Small pure Kotlin model for projection stop status messages.
- `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
  - Wires Compose state, Ktor `STATUS`, protection scheduling, screen-share re-request, WebRTC cleanup policy, and viewer-facing stopped status.
- `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
  - Holds/releases partial wake lock while foreground capture service is active and keep-awake is enabled.
- `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
  - Adds keep-awake and screen-protection toggles with local explanation.
- `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
  - Rebrands Korean/English display text and adds keep-awake copy.
- `android/app/src/main/resources/files/index.html`
  - Rebrands viewer title and adds screen/projection state row if needed.
- `android/app/src/main/resources/files/viewer.js`
  - Displays `PROJECTION_STOPPED_LOCKED` / `SCREEN_CAPTURE_REAUTH_REQUIRED` as actionable viewer status.
- `android/app/src/main/res/values/strings.xml`
  - Rebrands app label and accessibility service description.
- `android/app/src/main/res/drawable/ic_launcher_background.xml`
  - Adjust adaptive icon background colors.
- `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
  - Replace default foreground vector with a generic Android mirror mark.
- `docs/Protocols.md`, `docs/Handoff.md`, `docs/Log.md`, `README.md`
  - Document behavior, secure protection mode, and Android lock limitation.

---

### Task 1: Persisted Keep-Awake Setting

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/ScreenAwakeSettings.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/ScreenAwakeSettingsTest.kt`

- [ ] **Step 1: Write failing unit test**

Add `android/app/src/test/java/com/example/galaxymirror/ScreenAwakeSettingsTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ScreenAwakeSettingsTest {
    @Test
    fun defaultKeepAwakeSettingIsEnabled() {
        val store = FakeBooleanStore()
        val settings = ScreenAwakeSettings(store)

        assertTrue(settings.keepScreenAwakeDuringMirroring())
    }

    @Test
    fun keepAwakeSettingCanBeDisabledAndEnabled() {
        val store = FakeBooleanStore()
        val settings = ScreenAwakeSettings(store)

        settings.setKeepScreenAwakeDuringMirroring(false)
        assertFalse(settings.keepScreenAwakeDuringMirroring())

        settings.setKeepScreenAwakeDuringMirroring(true)
        assertTrue(settings.keepScreenAwakeDuringMirroring())
    }

    private class FakeBooleanStore : ScreenAwakeSettings.BooleanStore {
        private val values = mutableMapOf<String, Boolean>()

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return values[key] ?: defaultValue
        }

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ScreenAwakeSettingsTest --no-daemon
```

Expected: compile fails because `ScreenAwakeSettings` does not exist.

- [ ] **Step 3: Implement setting model**

Create `android/app/src/main/java/com/example/galaxymirror/ScreenAwakeSettings.kt`:

```kotlin
package com.example.galaxymirror

import android.content.Context

class ScreenAwakeSettings(
    private val store: BooleanStore,
) {
    fun keepScreenAwakeDuringMirroring(): Boolean {
        return store.getBoolean(KEY_KEEP_SCREEN_AWAKE, true)
    }

    fun setKeepScreenAwakeDuringMirroring(enabled: Boolean) {
        store.putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled)
    }

    interface BooleanStore {
        fun getBoolean(key: String, defaultValue: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
    }

    class SharedPreferencesBooleanStore(context: Context) : BooleanStore {
        private val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return preferences.getBoolean(key, defaultValue)
        }

        override fun putBoolean(key: String, value: Boolean) {
            preferences.edit().putBoolean(key, value).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "screen_awake_settings"
        const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake_during_mirroring"
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ScreenAwakeSettingsTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Compose Toggle and Android Window Flag

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/Navigation.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/ui/main/MainScreenContentTest.kt`
- Test: `android/app/src/androidTest/java/com/example/galaxymirror/ui/main/MainScreenTest.kt`

- [ ] **Step 1: Write failing content/UI tests**

In `MainScreenContentTest.kt`, include these constants in `allText` and assert Korean wording:

```kotlin
MainScreenContent.keepAwakeToggleLabel,
MainScreenContent.keepAwakeDescription,
```

Add assertions:

```kotlin
assertTrue(allText.any { it.contains("화면 켜짐") })
assertTrue(allText.any { it.contains("꺼짐") })
```

In `MainScreenTest.kt`, add:

```kotlin
@Test
fun keepAwakeToggleExists() {
  composeTestRule.setContent {
    MirrorHomeScreen(
      keepScreenAwake = true,
      onKeepScreenAwakeChange = {},
    )
  }

  composeTestRule.onNodeWithText(MainScreenContent.keepAwakeToggleLabel).assertExists()
}
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ui.main.MainScreenContentTest --no-daemon
./gradlew app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: compile fails because keep-awake constants/parameters do not exist.

- [ ] **Step 3: Add screen copy**

Add to `MainScreenContent.kt`:

```kotlin
const val keepAwakeToggleLabel = "미러링 중 화면 켜짐 유지"
const val keepAwakeDescription = "Android 15+에서는 화면이 잠기면 화면 공유가 자동 중단됩니다. 이 옵션은 자동 화면 꺼짐을 최대한 막습니다."
```

- [ ] **Step 4: Add Compose toggle**

In `MainScreen.kt`, add imports:

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
```

Add parameters to `MainScreen` and `MirrorHomeScreen`:

```kotlin
keepScreenAwake: Boolean = true,
onKeepScreenAwakeChange: (Boolean) -> Unit = {},
```

Pass them through each `MirrorHomeScreen(...)` call.

Add this panel before the action buttons:

```kotlin
Surface(
  modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(8.dp),
  color = MaterialTheme.colorScheme.surfaceContainer,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = MainScreenContent.keepAwakeToggleLabel,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = MainScreenContent.keepAwakeDescription,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Switch(
      checked = keepScreenAwake,
      onCheckedChange = onKeepScreenAwakeChange,
    )
  }
}
```

- [ ] **Step 5: Wire state and `FLAG_KEEP_SCREEN_ON`**

In `MainActivity.kt`, add imports:

```kotlin
import android.view.WindowManager
```

Add properties:

```kotlin
private lateinit var screenAwakeSettings: ScreenAwakeSettings
private var keepScreenAwake by mutableStateOf(true)
```

In `onCreate`, after diagnostics setup:

```kotlin
screenAwakeSettings =
  ScreenAwakeSettings(ScreenAwakeSettings.SharedPreferencesBooleanStore(applicationContext))
refreshKeepScreenAwake()
```

Add:

```kotlin
private fun refreshKeepScreenAwake() {
  if (!::screenAwakeSettings.isInitialized) return
  keepScreenAwake = screenAwakeSettings.keepScreenAwakeDuringMirroring()
  applyKeepScreenOnWindowFlag()
}

private fun setKeepScreenAwake(enabled: Boolean) {
  screenAwakeSettings.setKeepScreenAwakeDuringMirroring(enabled)
  keepScreenAwake = enabled
  applyKeepScreenOnWindowFlag()
  notifyMediaProjectionServiceKeepAwakeChanged()
}

private fun applyKeepScreenOnWindowFlag() {
  if (keepScreenAwake) {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  } else {
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
}
```

Pass to `MainNavigation`:

```kotlin
keepScreenAwake = keepScreenAwake,
onKeepScreenAwakeChange = ::setKeepScreenAwake,
```

Update `Navigation.kt` params and forward to `MainScreen`.

- [ ] **Step 6: Verify GREEN**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ui.main.MainScreenContentTest --no-daemon
./gradlew app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: both commands pass.

---

### Task 3: Foreground Service Wake Lock While Mirroring

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/MediaProjectionServiceTest.kt`

- [ ] **Step 1: Write failing policy test**

Add to `MediaProjectionServiceTest.kt`:

```kotlin
@Test
fun shouldHoldWakeLockOnlyWhenCaptureIsRunningAndKeepAwakeEnabled() {
    assertTrue(
        MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
            serviceRunning = true,
            keepAwakeEnabled = true
        )
    )
    assertFalse(
        MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
            serviceRunning = true,
            keepAwakeEnabled = false
        )
    )
    assertFalse(
        MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
            serviceRunning = false,
            keepAwakeEnabled = true
        )
    )
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MediaProjectionServiceTest --no-daemon
```

Expected: compile fails because `MediaProjectionWakeLockPolicy` does not exist.

- [ ] **Step 3: Implement policy**

Create `android/app/src/main/java/com/example/galaxymirror/MediaProjectionWakeLockPolicy.kt`:

```kotlin
package com.example.galaxymirror

object MediaProjectionWakeLockPolicy {
    fun shouldHoldWakeLock(serviceRunning: Boolean, keepAwakeEnabled: Boolean): Boolean {
        return serviceRunning && keepAwakeEnabled
    }
}
```

- [ ] **Step 4: Add wake lock permission**

In `AndroidManifest.xml`, add:

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

- [ ] **Step 5: Implement service wake lock**

In `MediaProjectionService.kt`, add imports:

```kotlin
import android.os.PowerManager
```

Add companion constants/state:

```kotlin
const val EXTRA_KEEP_SCREEN_AWAKE = "keepScreenAwake"
```

Add field:

```kotlin
private var wakeLock: PowerManager.WakeLock? = null
private var keepScreenAwake = true
```

In `onStartCommand`, read:

```kotlin
keepScreenAwake = intent?.getBooleanExtra(EXTRA_KEEP_SCREEN_AWAKE, true) ?: true
updateWakeLock()
```

Add:

```kotlin
fun setKeepScreenAwake(enabled: Boolean) {
    keepScreenAwake = enabled
    updateWakeLock()
}

private fun updateWakeLock() {
    val shouldHold =
        MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
            serviceRunning = isRunning,
            keepAwakeEnabled = keepScreenAwake,
        )
    if (shouldHold && wakeLock?.isHeld != true) {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock =
            powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidMirror:Projection")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        CrashDiagnostics.recordEvent(this, "Projection partial wake lock acquired.")
    } else if (!shouldHold && wakeLock?.isHeld == true) {
        wakeLock?.release()
        CrashDiagnostics.recordEvent(this, "Projection partial wake lock released.")
    }
}
```

In `onDestroy`, call:

```kotlin
if (wakeLock?.isHeld == true) {
    wakeLock?.release()
}
wakeLock = null
```

In `MainActivity.startMediaProjectionService`, pass:

```kotlin
putExtra(MediaProjectionService.EXTRA_KEEP_SCREEN_AWAKE, keepScreenAwake)
```

Add:

```kotlin
private fun notifyMediaProjectionServiceKeepAwakeChanged() {
  if (!MediaProjectionService.isRunning) return
  MediaProjectionService.getService().setKeepScreenAwake(keepScreenAwake)
}
```

- [ ] **Step 6: Verify wake-lock tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.MediaProjectionServiceTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Projection Stop State and Viewer Reauthorization UX

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/ProjectionStopReason.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Test: `android/app/src/test/java/com/example/galaxymirror/ProjectionStopReasonTest.kt`
- Test: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] **Step 1: Write failing Kotlin state test**

Create `ProjectionStopReasonTest.kt`:

```kotlin
package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class ProjectionStopReasonTest {
    @Test
    fun lockStopStatusRequiresReauthorization() {
        val status =
            ProjectionStopStatus(
                reason = ProjectionStopReason.LOCKED_OR_SYSTEM_STOPPED,
                requiresReauthorization = true,
            )

        assertEquals("PROJECTION_STOPPED_LOCKED", status.message)
    }
}
```

- [ ] **Step 2: Write failing JS viewer test**

In `viewer-keyboard.test.mjs`, add:

```javascript
await test('viewer shows reauthorization status when projection stops', () => {
    const { context, document } = loadViewer();

    vm.runInContext(
        'handleStatusMessage({ captureReady: false, message: "PROJECTION_STOPPED_LOCKED" });',
        context
    );

    assert.equal(document.getElementById('rtcStatus').innerText, '재승인 필요');
});
```

- [ ] **Step 3: Run tests to verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ProjectionStopReasonTest --no-daemon
cd ..
node android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: Kotlin compile fails because projection state classes do not exist, and JS fails because viewer does not map the new status.

- [ ] **Step 4: Implement projection status model**

Create `ProjectionStopReason.kt`:

```kotlin
package com.example.galaxymirror

enum class ProjectionStopReason {
    LOCKED_OR_SYSTEM_STOPPED,
}

data class ProjectionStopStatus(
    val reason: ProjectionStopReason,
    val requiresReauthorization: Boolean,
) {
    val message: String
        get() =
            when (reason) {
                ProjectionStopReason.LOCKED_OR_SYSTEM_STOPPED -> "PROJECTION_STOPPED_LOCKED"
            }
}
```

- [ ] **Step 5: Publish stopped status from `onStop()`**

In `MainActivity.kt`, add property:

```kotlin
@Volatile private var projectionStopStatus: ProjectionStopStatus? = null
```

Update `buildStatusMessage` payload:

```kotlin
projectionStopStatus?.let { status ->
  put("projectionStopped", true)
  put("requiresReauthorization", status.requiresReauthorization)
}
```

In `ScreenCapturerAndroid` callback `onStop()`:

```kotlin
projectionStopStatus =
  ProjectionStopStatus(
    reason = ProjectionStopReason.LOCKED_OR_SYSTEM_STOPPED,
    requiresReauthorization = true,
  )
mediaProjectionResultData = null
pendingOffer = null
sendStatusToActiveSessionIfPossible("PROJECTION_STOPPED_LOCKED")
cleanupWebRTCResources(stopProjectionService = false, stopCapturer = false)
```

Add helper:

```kotlin
private fun sendStatusToActiveSessionIfPossible(message: String) {
  CrashDiagnostics.recordEvent(this, "Projection status changed: $message.")
}
```

This helper only records the event if there is no durable WebSocket sender reference. The existing 2-second `STATUS_TICK` will send `projectionStopped` on the next tick.

Clear stop status when new screen capture permission is granted:

```kotlin
projectionStopStatus = null
```

- [ ] **Step 6: Update viewer status mapping**

In `viewer.js`, modify `handleStatusMessage`:

```javascript
if (payload.message === 'PROJECTION_STOPPED_LOCKED') {
    rtcStatus.innerText = '재승인 필요';
    log('Android 화면 잠금 또는 시스템 정책으로 화면 공유가 중단되었습니다. Android 앱에서 화면 공유를 다시 승인하세요.');
    return;
}
if (payload.message === 'SCREEN_CAPTURE_REAUTH_REQUIRED') {
    rtcStatus.innerText = '재승인 필요';
    return;
}
```

- [ ] **Step 7: Verify projection status tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ProjectionStopReasonTest --no-daemon
cd ..
node android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: both pass.

---

### Task 5: Reauthorization Flow After Projection Stops

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/SignalingState.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/SignalingStateTest.kt`

- [ ] **Step 1: Write failing signaling test**

In `SignalingStateTest.kt`, add:

```kotlin
@Test
fun stoppedProjectionQueuesOfferAndRequestsPermissionAgain() {
    val decision =
        SignalingDecision.onOffer(
            readiness = ProjectionReadiness.MISSING_PERMISSION,
            activeSessionMatches = true,
        )

    assertEquals(SignalingDecision.QUEUE_AND_REQUEST_PERMISSION, decision)
}
```

If this behavior already exists, add an assertion in `MainActivity`-adjacent testable model instead:

```kotlin
@Test
fun stoppedProjectionClearsSingleUseProjectionIntent() {
    assertTrue(ProjectionRestartPolicy.shouldClearProjectionIntentAfterStop())
}
```

- [ ] **Step 2: Ensure `onStop()` clears reusable token**

In `MainActivity.kt`, inside `ScreenCapturerAndroid` callback `onStop()`, make sure:

```kotlin
mediaProjectionResultData = null
```

This is required because Android 14+ MediaProjection tokens are single-use and lock stops the active projection.

- [ ] **Step 3: Request permission on next viewer offer**

Verify existing `handleSignalingMessage` path sees `mediaProjectionResultData == null` and calls:

```kotlin
requestScreenCapturePermission()
```

If a viewer is already connected when stop happens, send `PROJECTION_STOPPED_LOCKED` status and let the user click `미러링 연결하기` again after Android screen-share approval. Do not try to silently create a new projection token.

- [ ] **Step 4: Verify signaling tests**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.SignalingStateTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Rebrand App Name, Copy, and Launcher Icon

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`
- Modify: `android/app/src/main/resources/files/index.html`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Modify: `android/app/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Test: `android/app/src/test/java/com/example/galaxymirror/ui/main/MainScreenContentTest.kt`

- [ ] **Step 1: Write failing rebrand test**

In `MainScreenContentTest.kt`, add:

```kotlin
assertTrue(MainScreenContent.title.contains("Android"))
assertFalse(allText.any { it.contains("갤럭시") })
assertFalse(allText.any { it.contains("Galaxy Mirror") })
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ui.main.MainScreenContentTest --no-daemon
```

Expected: fails because current copy still says Galaxy/Galaxy Mirror.

- [ ] **Step 3: Update app label and accessibility description**

Change `strings.xml`:

```xml
<resources>
    <string name="app_name">Android Mirror</string>
    <string name="accessibility_service_description">Android Mirror uses Accessibility Service to control your Android device remotely from a browser.</string>
</resources>
```

- [ ] **Step 4: Update main screen copy**

Change `MainScreenContent.kt` key strings:

```kotlin
const val title = "Android Mirror"
const val subtitle = "Android 화면을 Mac 브라우저로 보고, 터치와 키보드를 원격 입력합니다."
const val viewerAddressHint = "Mac Chrome에서 http://<Android MagicDNS>:8080/ 주소로 접속하세요."
```

Update setup strings to use `Android Mirror`:

```kotlin
"처음 설치했다면 Android 설정 > 애플리케이션 > Android Mirror > 우측 상단 메뉴 > 제한된 설정 허용을 먼저 켭니다.",
"아래 앱 정보 열기 버튼으로 Android Mirror 앱 정보 화면에 바로 이동할 수 있습니다.",
"원격 터치와 키보드 입력이 필요하면 접근성 설정 > 설치된 앱에서 Android Mirror 서비스를 켭니다.",
```

- [ ] **Step 5: Update foreground notification**

In `MediaProjectionService.kt`, change notification title/channel description:

```kotlin
.setContentTitle("Android Mirror Active")
```

```kotlin
description = "Android Mirror 화면 캡처 상태 알림 채널"
```

- [ ] **Step 6: Update viewer branding**

In `index.html`:

```html
<title>Android Mirror Web Viewer</title>
<h1>ANDROID MIRROR</h1>
<p class="subtitle">Tailscale MagicDNS & WebRTC 기반 Android 원격 미러링 뷰어</p>
```

In `viewer.js`, update user-facing log:

```javascript
log("Android 실시간 화면 비디오 트랙 감지!");
```

- [ ] **Step 7: Update adaptive icon vector**

Set `ic_launcher_background.xml` to a neutral dark/teal color:

```xml
<resources>
    <color name="ic_launcher_background">#0F172A</color>
</resources>
```

Replace `ic_launcher_foreground.xml` with a simple vector using Android-neutral mirror shapes:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#38BDF8"
        android:pathData="M30,20h48c5.5,0 10,4.5 10,10v48c0,5.5 -4.5,10 -10,10H30c-5.5,0 -10,-4.5 -10,-10V30c0,-5.5 4.5,-10 10,-10z"/>
    <path
        android:fillColor="#0F172A"
        android:pathData="M34,32h40c2.2,0 4,1.8 4,4v30c0,2.2 -1.8,4 -4,4H34c-2.2,0 -4,-1.8 -4,-4V36c0,-2.2 1.8,-4 4,-4z"/>
    <path
        android:fillColor="#F8FAFC"
        android:pathData="M42,76h24v6H42z"/>
    <path
        android:fillColor="#F8FAFC"
        android:pathData="M47,48l14,-8v16z"/>
</vector>
```

- [ ] **Step 8: Verify rebrand test**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest --tests com.example.galaxymirror.ui.main.MainScreenContentTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 7: Documentation and Full Verification

**Files:**
- Modify: `docs/Protocols.md`
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`
- Modify: `README.md`

- [ ] **Step 1: Document lock limitation**

Add to `docs/Protocols.md` under signaling `STATUS`:

```markdown
`PROJECTION_STOPPED_LOCKED` means Android stopped MediaProjection because the device was locked or the system stopped the projection. Android 15 QPR1+ automatically stops screen projection on lock, so the viewer must ask the user to reapprove screen capture on the Android device.
```

- [ ] **Step 2: Update task board**

Add to `docs/Handoff.md`:

```markdown
- [x] **T3.6: 화면 켜짐 유지, 잠금 중단 안내, Android Mirror 리브랜딩**
  - [x] 미러링 중 화면 켜짐 유지 토글 추가
  - [x] 화면 잠금으로 MediaProjection이 중단되면 viewer에 재승인 필요 상태 표시
  - [x] 앱 표시 이름과 UI 문구를 Android Mirror로 변경
```

- [ ] **Step 3: Update development log**

Add to `docs/Log.md`:

```markdown
* **화면 켜짐 유지 및 리브랜딩**
  * Android 15 QPR1+에서 화면 잠금 시 MediaProjection이 자동 중단되는 제약을 반영해, 미러링 중 화면 켜짐 유지 옵션과 재승인 필요 상태 표시를 추가했습니다.
  * 앱 표시 이름, 접근성 서비스명, 알림, Android/Chrome UI 문구를 `Android Mirror` 중심으로 정리했습니다.
```

- [ ] **Step 4: Update README terminology**

Replace Galaxy-specific product wording with Android-compatible wording while preserving Tailscale/Android host architecture:

```markdown
# Android Mirror Web

본 프로젝트는 Android 단말 화면을 Mac 브라우저에서 보고 원격 입력하기 위한 개인용 미러링 시스템입니다.
```

- [ ] **Step 5: Run JS tests**

Run:

```bash
node android/app/src/test/js/viewer-keyboard.test.mjs
```

Expected: all `ok - ...` lines pass.

- [ ] **Step 6: Run Android verification**

Run:

```bash
cd android
./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Inspect APK contents**

Run:

```bash
unzip -p android/app/build/outputs/apk/debug/app-debug.apk files/viewer.js | rg 'PROJECTION_STOPPED_LOCKED|Android 실시간'
ls -lh android/app/build/outputs/apk/debug/app-debug.apk
```

Expected: viewer status strings are present and APK exists.

---

## Manual Smoke Test Checklist

- [ ] Install updated APK over current debug build.
- [ ] Confirm launcher/app info/accessibility service label says `Android Mirror`.
- [ ] Open Android Mirror and confirm `미러링 중 화면 켜짐 유지` is enabled by default.
- [ ] Start screen sharing and connect Chrome viewer.
- [ ] Leave device idle longer than the normal screen timeout; screen should remain on.
- [ ] Turn keep-awake off, reconnect, and verify normal timeout behavior returns.
- [ ] Press power button while streaming; viewer should show `재승인 필요` rather than silently hanging.
- [ ] Wake/unlock Android, reopen Android Mirror, reapprove screen sharing, and reconnect Chrome.

## References

- Android MediaProjection auto-stop on lock: https://developer.android.com/media/grow/media-projection
- Android keep screen on: https://developer.android.com/develop/background-work/background-tasks/awake/screen-on
- Android PowerManager WakeLock: https://developer.android.com/reference/android/os/PowerManager.WakeLock
