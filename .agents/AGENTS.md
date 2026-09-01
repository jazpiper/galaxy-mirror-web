# Galaxy Mirror Web Agent Guide

> 이 문서는 프로젝트 특화 아키텍처 제약과 개발 원칙을 정의하는 정본 프로젝트 규칙 파일이다.
> Antigravity 글로벌 룰과 결합하여 적용되며, 저장소 전체에 우선 적용된다.

## 1. Project Shape

- 갤럭시 단말을 **Android Host**로, 맥 브라우저를 **Mac Viewer**로 사용하는 **Zero-install 무설치 미러링 및 원격 제어 시스템**이다.
- 핵심 연결 모델: Tailscale MagicDNS + Android 임베디드 Ktor 서버 + WebRTC + AccessibilityService 제어 입력.
- Android 프로젝트가 주 구현체이며, 브라우저 클라이언트는 Android 리소스의 정적 파일로 서빙된다.
- 진행 상태와 설계 문서는 `docs/`가 기준이다. 구현을 바꿀 때 문서와 태스크 상태가 어긋나지 않게 함께 확인한다.

## 2. Core Architecture & Stack

- **Android Host (Kotlin / Java 21 toolchain)**
  - 임베디드 웹서버: Ktor CIO 3.0.3, `127.0.0.1:8080` 바인딩 (외부/Tailscale 망 접속 허용을 위해 반드시 `127.0.0.1`).
  - 화면 캡처: `MediaProjection` API → WebRTC(H.264/VP8) 또는 USB(H.264/JPEG) 스트리밍.
  - 제어 입력 주입: `AccessibilityService` (터치/스크롤/키보드).
  - Gradle Kotlin DSL, Kotlin 2.x, Jetpack Compose + Material3 + Navigation, `compileSdk`/`targetSdk` 36, `minSdk` 29.
- **Mac Viewer (Web Client)**
  - **오직 Vanilla HTML5 / JavaScript (ES6 Modules) / CSS만 사용.** React/Vue 등 프레임워크나 빌드 스텝 도입 금지 — 무설치 단순성 유지.
  - 모듈 구조: `main.js`, `ui.js`, `controls.js`, `signaling.js`, `webrtc.js`, `viewer-keyboard.js`.
- **Dual Transport Network**
  - **Tailscale MagicDNS**: 보안 터널링 + WebRTC (기본 모드, 무선).
  - **USB / ADB Forward**: `adb forward tcp:8080 tcp:8080`로 로컬 유선 연결. WebCodecs 지원 시 `/usb/session?codec=h264`, 실패 시 `/usb/session?codec=jpeg`로 fallback.

## 3. Important Paths

- `android/` : Android Studio/Gradle 프로젝트 루트 (모든 빌드·테스트 명령은 여기서 실행).
  - `.../MediaProjectionService.kt` : **서비스 코어.** Ktor 서버 라이프사이클 + WebRTC 피어 연결/SDP·ICE 교환 + MediaProjection 캡처 라이프사이클 관장.
  - `.../MirrorRouting.kt` : **HTTP/WebSocket 라우팅 모듈.** Ktor 엔드포인트 라우팅 (`/signaling`, `/usb/session`, `/stream/quality`, `/apps/*`, `/debug/perf` 등).
  - `.../MainActivity.kt` : Compose UI 진입점. 서비스에 bind하고 화면 캡처 권한을 요청, `MirrorServiceState`(서비스가 발행하는 StateFlow)를 UI에 반영.
  - `.../GalaxyMirrorAccessibilityService.kt` : Viewer 입력(터치 %, 키, 텍스트)을 Android 제스처/전역 액션으로 변환. 입력은 `ControlEventValidator → ControlEventDispatcher → ControlEventApplier → ControlEventResult` 파이프라인을 거친다.
  - `.../MirrorTransport.kt` : `TAILSCALE_WEBRTC` / `USB_JPEG` / `USB_H264` 전송 경로 discriminator (세션 관리 전반에 관통).
  - USB 스트리밍: `UsbH264ScreenStreamer`(MediaCodec H.264, WebCodecs 있을 때 우선) / `UsbScreenStreamer`(JPEG fallback), `UsbStreamProfile`·`UsbH264StreamProfile`(해상도/fps/bitrate tier), `UsbThermalPolicy`+`UsbThermalReader`+viewer-idle로 발열/유휴 시 프로필 clamp, `UsbFrameChangeGate`·`UsbFrameRateGate`(중복 프레임 skip), `UsbPerfMonitor`(`/debug/perf` 피드).
  - `.../resources/files/` : Ktor가 서빙하는 Mac Viewer 정적 ES6 모듈 리소스 (`index.html`, `main.js`, `ui.js`, `controls.js`, `signaling.js`, `webrtc.js`, `viewer-keyboard.js`).
  - `android/gradle/libs.versions.toml` : AGP/Kotlin/Compose/AndroidX 버전 카탈로그.
- `.github/workflows/android-build.yml` : main/pull_request용 CI.
- `docs/` : 프로젝트 상태와 규격 문서 허브. `Dashboard.md`(허브/로드맵), `Handoff.md`(현재 상태 보드), `Log.md`(개발 연대기), `Protocols.md`(메시지 규격), `Coordinates.md`(좌표 변환 규격).

## 4. Strict Implementation Rules

- **MediaProjection Lifecycle (Android 14+)**: 단일 사용(single-use) 토큰 제약이 매우 엄격하다. 캡처 grant는 재사용 불가. Viewer 종료/교체·연결 단절 시 기존 캡처를 정리하고, 재연결 시 클라이언트에 `SCREEN_CAPTURE_REAUTH_REQUIRED`를 보내 사용자의 명시적 재승인을 유도한다(Privacy-First). 캐시된 grant가 재연결에도 살아있다고 가정하지 말 것.
- **No Public Network Surface**: 개인 로컬 사용 전제. 외부 릴레이(TURN), Public Endpoint, 인증 없는 인터넷 노출을 절대 추가하지 않는다. 오직 Tailscale 기반 사설망 또는 USB 로컬 루프백만 전제. 확장이 필요하면 먼저 설계 문서에 명시.
- **No Viewer Token (의도적)**: 이 앱은 viewer 접근 토큰을 쓰지 않는다. `/signaling`, `/usb/session`, `/debug/perf`, 앱 바로가기·화질 API는 모두 token header/query 없이 동작한다(loopback + WireGuard 터널이 신뢰 경계). `ViewerAccessGuard`/`ViewerAccessTokenStore`는 의도적으로 제거됨 — token 인증을 재도입하지 말 것.
- **Coordinates & Input Injection**: 모든 클릭/스와이프/키 입력의 안드로이드 해상도 맵핑은 반드시 `docs/Coordinates.md` 스펙(레터박스/필러박스 보정, `object-fit: contain` 기준)을 준수. 텍스트 입력은 `AccessibilityNodeInfo.ACTION_SET_TEXT`를 쓰며, IME 프리징/캐러셀 락업 방지용 지수 백오프/Watchdog 메커니즘(`RemoteTextInputBuffer`, `TextInputTargetSelector`)을 훼손하지 말 것.
- **Session Cleanup**: WebRTC/WebSocket/MediaProjection 정리 태스크는 session/instance ID를 확인해 Viewer 재연결 중 race condition을 막도록 견고하게 유지한다(`MirrorSessionState`는 단일 활성 세션을 추적하며, 새 세션 시작 시 이전 transport를 정리).
- **Protocol & Port**: signaling 메시지 타입/payload, USB frame, HTTP API는 `docs/Protocols.md`가 기준. 새 타입 추가나 JSON shape 변경 시 Android 처리 코드·`main.js` 및 모듈·문서를 같은 변경 단위로 갱신한다. 포트(`8080`)나 정적 경로를 바꾸면 README/docs/브라우저 클라이언트도 함께 맞춘다.
- **Manifest/Permission**: MediaProjection 라이프사이클, foreground service notification, Android 권한은 OS 버전별 제약이 강하다. 권한/서비스 선언 변경 시 `AndroidManifest.xml`·service 코드·실제 단말 동작을 함께 검증.

## 5. Build, Test, and Verification

모든 Gradle 명령은 저장소 루트가 아니라 `android/`에서 실행한다.

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon      # JVM 단위 테스트 (JUnit + Mockito + coroutines-test)
./gradlew app:lintDebug --no-daemon              # Android lint
./gradlew assembleDebug --no-daemon              # 디버그 APK 빌드
./gradlew app:connectedDebugAndroidTest --no-daemon   # 실기기/에뮬레이터 필요 시
```

단일 테스트 실행:

```bash
./gradlew app:testDebugUnitTest --tests "com.example.galaxymirror.UsbStreamProfileTest"
./gradlew app:testDebugUnitTest --tests "com.example.galaxymirror.UsbStreamProfileTest.someMethodName"
```

Mac Viewer의 JS 로직은 **Gradle이 아니라** Node 기반 테스트로 검증한다(프레임워크 없이 `node:test` + DOM fake 사용). CI와 동일하게 직접 실행:

```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
node --test android/app/src/test/js/viewer-layout.test.mjs
node --check android/app/src/main/resources/files/*.js
```

- **CI 실행 순서**(`.github/workflows/android-build.yml`, JDK 21, push/PR to `main`): `app:testDebugUnitTest` → JS `viewer-keyboard` 테스트 → `app:lintDebug` → `assembleDebug`. 로컬도 이 순서로 맞춘 뒤 push.
- Ktor/WebRTC/MediaProjection/AccessibilityService를 건드린 경우 단순 컴파일만으로 완료라 보지 말고, 가능하면 단말에서 앱 실행 → 화면 캡처 권한 승인 → `http://<MagicDNS-host>:8080/status`, `/signaling` 연결 → 브라우저 입력 전달까지 확인한다.

## 6. Implementation Notes

- Ktor 엔드포인트 및 라우팅 확장은 `MirrorRouting.kt`에 정의하며, `MediaProjectionService.kt` 코어 라이프사이클을 보존하고 필요할 때만 국소 단위로 조정한다.
- WebRTC 스트림 화질은 `AdaptiveStreamQuality`가 (사용자 모드 × `NetworkTransportDetector`의 wifi/ethernet vs. cellular × viewer 활동)로 유효 프로필을 산출한다.
- Mac Viewer 확장은 기존 정적 ES6 서빙 구조 안에서 작게. 좌표 변환·tap/swipe/key 규칙은 `docs/Coordinates.md`와 `GalaxyMirrorAccessibilityService.kt`를 함께 확인한다.

## 7. Documentation Rules

- 구현 상태가 바뀌면 `docs/Handoff.md`(현재 상태 보드)의 태스크 상태와 `docs/Log.md`(개발 연대기)의 변경 기록을 함께 갱신하는 것을 기본값으로 한다.
- 프로토콜 payload나 좌표 공식이 바뀌면 `docs/Protocols.md`·`docs/Coordinates.md`도 실제 Kotlin/JS 코드에 맞춰 갱신. 추측으로 쓰지 말 것.
- 장기 조사나 세션 인수인계는 새 외부 노트보다 `docs/` 안의 기존 문서 체계를 우선 사용한다.

## 8. Safety, Secrets, and Git Hygiene

- `android/local.properties`, 로컬 SDK 경로, Tailscale 계정 정보, 단말별 MagicDNS 이름, 개인 네트워크 정보는 커밋하지 않는다.
- 화면 미러링/원격 입력은 민감도가 높다. 접근성 권한·MediaProjection 권한·네트워크 노출 범위를 넓히는 변경은 최소화하고 운영 조건을 문서에 남긴다.
- 사용자 변경을 되돌리지 않는다. 작업 전후 `git status --short`로 범위를 확인한다.
- 빌드 산출물, Gradle 캐시, APK, 로컬 IDE 파일은 커밋하지 않는다.
- 커밋/PR 생성 시 구현 변경·문서 동기화·검증 결과가 서로 맞는지 확인한다.
