# Galaxy Mirror Web Agent Guide

> 이 문서는 프로젝트 특화 아키텍처 제약과 개발 원칙을 정의하는 정본 프로젝트 규칙 파일입니다.
> `docs/` 가이네스 중심 통합 관리용 사본이며, 실제 시스템 동작을 위한 룰 파일은 `.agents/AGENTS.md`에 위치합니다.

## 1. Project Shape

- 갤럭시 단말을 **Android Host**로, 맥 브라우저를 **Mac Viewer**로 사용하는 **Zero-install 무설치 미러링 및 원격 제어 시스템**입니다.
- 핵심 연결 모델: Tailscale MagicDNS + Android 임베디드 Ktor 서버 + WebRTC + AccessibilityService 제어 입력.
- Android 프로젝트가 주 구현체이며, 브라우저 클라이언트는 Android 리소스의 정적 파일로 서빙됩니다.
- 진행 상태와 설계 문서는 `docs/`가 기준입니다. 구현을 바꿀 때 문서와 태스크 상태가 어긋나지 않게 함께 확인합니다.

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

- **MediaProjection Lifecycle (Android 14+)**: 단일 사용(single-use) 토큰 제약이 매우 엄격합니다. 캡처 grant는 재사용 불가. Viewer 종료/교체·연결 단절 시 기존 캡처를 정리하고, 재연결 시 클라이언트에 `SCREEN_CAPTURE_REAUTH_REQUIRED`를 보내 사용자의 명시적 재승인을 유도합니다(Privacy-First). 캐시된 grant가 재연결에도 살아있다고 가정하지 말 것.
- **No Public Network Surface**: 개인 로컬 사용 전제. 외부 릴레이(TURN), Public Endpoint, 인증 없는 인터넷 노출을 절대 추가하지 않는다. 오직 Tailscale 기반 사설망 또는 USB 로컬 루프백만 전제. 확장이 필요하면 먼저 설계 문서에 명시.
- **No Viewer Token (의도적)**: 이 앱은 viewer 접근 토큰을 쓰지 않는다. `/signaling`, `/usb/session`, `/debug/perf`, 앱 바로가기·화질 API는 모두 token header/query 없이 동작합니다.
- **Coordinates & Input Injection**: 모든 클릭/스와이프/키 입력의 안드로이드 해상도 맵핑은 반드시 `docs/Coordinates.md` 스펙을 준수합니다.
- **Session Cleanup**: WebRTC/WebSocket/MediaProjection 정리 태스크는 session/instance ID를 확인해 Viewer 재연결 중 race condition을 막도록 유지합니다.

## 5. Build, Test, and Verification

```bash
cd android
./gradlew app:testDebugUnitTest --no-daemon      # JVM 단위 테스트
./gradlew app:lintDebug --no-daemon              # Android lint
./gradlew assembleDebug --no-daemon              # 디버그 APK 빌드
```

JS 뷰어 노드 테스트:
```bash
node --test android/app/src/test/js/viewer-keyboard.test.mjs
node --test android/app/src/test/js/viewer-layout.test.mjs
node --check android/app/src/main/resources/files/*.js
```
