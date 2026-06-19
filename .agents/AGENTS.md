# Galaxy Mirror Web Project Guidelines

이 프로젝트는 갤럭시 스마트폰을 Host로, 맥 브라우저를 Viewer로 사용하는 **Zero-install 무설치 미러링 및 원격 제어 시스템**입니다.
이 문서는 Antigravity 글로벌 룰과 결합하여, 프로젝트 특화된 아키텍처 제약사항 및 개발 원칙을 정의합니다. 

## 1. Core Architecture & Stack
- **Android Host (Kotlin / Java 21)**
  - 임베디드 웹서버: Ktor CIO 3.0.3 (포트 `0.0.0.0:8080`)
  - 화면 캡처: `MediaProjection` API (H.264/VP8 WebRTC 스트리밍)
  - 화면 제어 주입: `AccessibilityService` (Touch, Scroll, Keyboard Injection)
- **Mac Viewer (Web Client)**
  - 프론트엔드: **오직 Vanilla HTML5 / JavaScript / CSS 만 사용**. 프레임워크(React, Vue 등) 도입 금지.
- **Dual Transport Network**
  - **Tailscale MagicDNS**: 보안 터널링 및 WebRTC 연동 (기본 모드).
  - **USB / ADB Forward**: `adb forward tcp:8080 tcp:8080`를 통한 로컬 유선 연결 (JPEG binary frame + WebSocket 제어).

## 2. Directory & Important Paths
- `android/` : Android Studio/Gradle 프로젝트 루트 (모든 빌드 및 테스트 명령은 여기서 실행)
  - `.../MainActivity.kt` : Ktor 서버 시작, WebRTC 시그널링 허브, 접근 권한 요청 진입점.
  - `.../MediaProjectionService.kt` : 화면 캡처용 Foreground Service.
  - `.../GalaxyMirrorAccessibilityService.kt` : 뷰어의 마우스/키보드 입력을 Android 제스처 및 액션으로 변환.
  - `.../resources/files/` : Ktor가 서빙하는 Mac Viewer 정적 파일(`index.html`, `viewer.js` 등).
- `docs/` : 프로젝트 상태와 규격을 관리하는 문서 허브.

## 3. Strict Implementation Rules
- **MediaProjection Lifecycle Constraints (Android 14+)**: 
  단일 사용(single-use) 토큰 제약이 매우 엄격합니다. Viewer 종료/교체 혹은 연결 단절 시 기존 캡처를 정리하고, 재연결 시 `SCREEN_CAPTURE_REAUTH_REQUIRED` 상태를 클라이언트에게 보내어 사용자의 명시적 재승인을 유도하는 Privacy-First 정책을 따릅니다.
- **Coordinates & Input Injection**: 
  모든 클릭/스와이프/키보드 입력(DataChannel 경유)의 안드로이드 해상도 맵핑 연산은 반드시 `docs/Coordinates.md`의 스펙을 준수해야 합니다.
  텍스트 입력은 `AccessibilityNodeInfo`의 `ACTION_SET_TEXT`를 활용하며, IME 프리징 및 캐러셀 락업을 방지하기 위한 지수 백오프/Watchdog 타이머 메커니즘을 훼손하지 마십시오.
- **Security & Network**: 
  외부 릴레이(TURN)나 Public Endpoint 개방을 절대 추가하지 마십시오. 오직 Tailscale 기반 사설망 혹은 USB 로컬 루프백 환경만 전제합니다.

## 4. Build, Test, and Quality Control
모든 명령어는 `android/` 디렉터리 내에서 실행합니다.
```bash
./gradlew assembleDebug --no-daemon
./gradlew app:testDebugUnitTest --no-daemon
./gradlew app:lintDebug --no-daemon
```
기능 추가/수정 시, 위 세 가지 명령을 실행하여 로컬 단위 테스트와 Lint를 반드시 통과해야 합니다. 핵심 로직(Ktor, WebRTC, AccessibilityService) 변경 시 단순 컴파일 외에 실제 단말 검증(`app:connectedDebugAndroidTest` 또는 실기기 smoke test)을 병행하십시오.

## 5. Documentation Synchronization
- 글로벌 룰에 따라 무의미한 일회성 로그의 문서화는 지양하되, **이 프로젝트의 특수한 영구적 맥락 보존**을 위해 구현 상태 변경 시 `docs/Handoff.md`(현재 상태 보드) 및 `docs/Log.md`(개발 연대기)는 반드시 함께 갱신해야 합니다.
- 프로토콜 페이로드나 좌표 변환식이 바뀐 경우 `docs/Protocols.md`와 `docs/Coordinates.md`도 실코드에 맞춰 업데이트하십시오.
