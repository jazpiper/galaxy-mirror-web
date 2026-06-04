# Favorite App Shortcuts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android 앱에서 관리하는 자주 쓰는 앱 목록을 Mac 뷰어에 바로가기 버튼으로 표시하고, 클릭 시 Galaxy 단말에서 해당 앱을 실행한다.

**Architecture:** `FavoriteAppsRepository`가 런처 앱 조회/저장/JSON/실행을 맡고, `MainActivity`가 Ktor API와 Compose 상태를 연결한다. Mac 뷰어는 `/apps/favorites`를 읽어 버튼을 렌더링하고 `/apps/launch`에 packageName을 전송한다.

**Tech Stack:** Kotlin, Android PackageManager, SharedPreferences, Jetpack Compose Material3, Ktor CIO, Vanilla HTML/JS, Node VM tests.

---

### Task 1: Favorite App Domain

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/FavoriteApp.kt`
- Test: `android/app/src/test/java/com/example/galaxymirror/FavoriteAppsRepositoryTest.kt`

- [ ] Add `FavoriteApp(packageName: String, label: String)` and JSON helper functions.
- [ ] Test duplicate package names keep a single favorite.
- [ ] Test JSON response shape is `{"apps":[{"packageName":"...","label":"..."}]}`.
- [ ] Test launch request parsing requires a nonblank `packageName`.

### Task 2: Android Repository And Manifest

**Files:**
- Create: `android/app/src/main/java/com/example/galaxymirror/FavoriteAppsRepository.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] Add launcher intent `<queries>` for Android package visibility.
- [ ] Implement launchable app lookup with `ACTION_MAIN` + `CATEGORY_LAUNCHER`.
- [ ] Store favorites in `SharedPreferences`.
- [ ] Implement `launchFavorite(packageName)` using `getLaunchIntentForPackage()`.

### Task 3: Compose Management UI

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/Navigation.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreen.kt`
- Modify: `android/app/src/main/java/com/example/galaxymirror/ui/main/MainScreenContent.kt`
- Test: `android/app/src/androidTest/java/com/example/galaxymirror/ui/main/MainScreenTest.kt`

- [ ] Keep favorite app state in `MainActivity`.
- [ ] Add `앱 추가`, selected favorite rows, and `삭제` controls.
- [ ] Open an app picker dialog from the Android app only.
- [ ] Refresh favorites after add/delete and on resume.

### Task 4: Ktor And Mac Viewer

**Files:**
- Modify: `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`
- Modify: `android/app/src/main/resources/files/index.html`
- Modify: `android/app/src/main/resources/files/viewer.js`
- Test: `android/app/src/test/js/viewer-keyboard.test.mjs`

- [ ] Add `GET /apps/favorites`.
- [ ] Add `POST /apps/launch`.
- [ ] Render favorite buttons in the left viewer panel.
- [ ] Send launch requests on click and log success/failure.

### Task 5: Verification

**Files:**
- Modify: `docs/Handoff.md`
- Modify: `docs/Log.md`
- Modify: `docs/Protocols.md`

- [ ] Document `/apps/favorites` and `/apps/launch`.
- [ ] Run `node android/app/src/test/js/viewer-keyboard.test.mjs`.
- [ ] Run `cd android && ./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon`.
