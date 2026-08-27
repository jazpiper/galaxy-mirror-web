---
project: galaxy-mirror-web
type: Handoff
related: [Dashboard.md, Log.md, Protocols.md, Coordinates.md]
updated: 2026-08-27
---

# 📋 Android Mirror Web Handoff & Task Board

이 문서는 현재 활성화된 마일스톤의 세부 태스크 보드 및 백로그를 가볍고 효율적으로 추적하며, 개발 세션 전환 시 작업 맥락을 끊김 없이 보존하기 위한 핸드오프 정보입니다.

---

## 🎯 활성 태스크 보드 (Active Task Board)

### 🏁 Milestone 1: 문서화 및 아키텍처 상세 설계 (Completed)
- [x] **T1.1: 글로벌 문서 체계(`docs/`) 수립**
  - [x] `docs/Dashboard.md` (중앙 허브) 생성 및 연동
  - [x] `docs/Log.md` (연대기 개발 로그) 생성 및 연동
  - [x] `docs/Handoff.md` (태스크 보드) 생성 및 연동
- [x] **T1.2: Ktor & WebRTC 시그널링 상세 시퀀스 다이어그램 구체화**
  - [x] Ktor 내부 WebSocket 채널 구성 규격 및 SDP/ICE candidate JSON 프로토콜 구조 설계 (`docs/Protocols.md` 반영)
- [x] **T1.3: Accessibility Service 터치 이벤트 전송 좌표 변환 상세화**
  - [x] 화면비(Viewport/Display aspect ratio) 보정식 및 클릭/드래그 제스처 인젝션 구조도 정의 (`docs/Coordinates.md` 반영)

### 🚀 Milestone 2: Android Host 기초 인프라 구축 (Completed)
- [x] **T2.1: Kotlin Android 프로젝트 뼈대 생성** (`android-cli` 이용)
- [x] **T2.2: Ktor 모듈 내장 및 HTTP 서버 포트 8080 구동** (CIO 엔진 연동)
- [x] **T2.3: 웹 뷰어 클라이언트 정적 리소스 서빙 구현** (GitHub Actions 빌드 완료)
- [x] **T2.4: Android Host 메인 화면 조작 안내 및 설정 진입 버튼 구현**
  - [x] Hello World 샘플 화면을 한국어 설정/연결/조작 안내 화면으로 교체
  - [x] 접근성 설정 화면 열기 버튼과 미러링 연결 해제 버튼 추가
  - [x] 앱 정보 화면 딥링크와 제한된 설정 허용 안내 추가
  - [x] 접근성 서비스가 이미 활성화되어 있으면 설정 관련 버튼 비활성화

### 🚀 Milestone 3: WebRTC 스트리밍 및 시그널링 구현 (Active)
- [x] **T3.1: Android MediaProjection 화면 실시간 캡처 프로토타이핑**
  - [x] Android 14+ single-use consent 규칙에 맞춰 foreground service와 `ScreenCapturerAndroid`의 projection token 소유권 정리
  - [x] ADB 없이 현장 크래시를 회수할 수 있도록 `/debug/crash` 진단 엔드포인트와 프로세스 종료 이력 저장 추가
- [x] **T3.2: Ktor WebSocket 기반 1:1 시그널링 채널 개설**
  - [x] 1개 활성 viewer session 제한, ICE candidate 대기열, `control` DataChannel 검증 추가
  - [x] 브라우저 Offer가 화면 캡처 준비보다 먼저 도착해도 pending offer로 보류하고 Android 승인 후 자동 협상 재개
  - [x] viewer WebSocket 종료/교체 시 Android 14+ single-use MediaProjection consent를 재사용하지 않도록 projection service와 저장 Intent 정리
  - [x] viewer 재연결 중 이전 WebSocket close와 이전 MediaProjection callback이 새 세션을 닫지 않도록 stale session guard 추가
  - [x] Android 메인 화면에 viewer 접근 토큰 포함 접속 주소를 표시하고, `/signaling` 및 제어용 HTTP API에 토큰 검증 추가
  - [x] 브라우저에 `STATUS` 패킷을 보내 화면 캡처, 접근성 입력, 제어 채널 상태를 분리 표시
- [x] **T3.3: WebRTC PeerConnection 화질 및 초당 프레임 수 최적화**
  - [x] `AUTO` 품질 모드를 추가해 Wi-Fi/Ethernet에서는 `HIGH`, 4G/5G cellular와 기타 네트워크에서는 `STANDARD`를 기본 적용
  - [x] `DATA_SAVER`, `STANDARD`, `HIGH` 프로필을 해상도/FPS/bitrate cap으로 정의하고 Android 앱/Viewer UI에서 수동 전환 가능
  - [x] 세션 시작 시 `ScreenCapturerAndroid.startCapture()`와 `RtpSender` encoding parameter에 선택 프로필 적용
  - [x] Viewer 입력이 일정 시간 없으면 idle 품질 제한으로 전환하고 새 입력이 오면 active 품질로 복귀
  - [x] `GET/POST /stream/quality` API와 `STATUS.streamQuality` payload로 Mac Viewer 상태 동기화
- [x] **T3.4: AccessibilityService 기반 원격 입력 고도화**
  - [x] tap/swipe dispatch 결과와 key/text 입력 처리 breadcrumb를 `/debug/crash` 최근 이벤트에 기록
  - [x] Mac 키보드 일반 문자/Enter/Backspace를 DataChannel `text` 이벤트로 보내 Android focused editable node에 `ACTION_SET_TEXT` 수행
  - [x] 접근성 서비스 package filter 제거 및 window content 조회 활성화
  - [x] 텍스트 입력 처리를 메인 스레드에서 수행하고, 포커스가 컨테이너에 잡힌 경우 focused editable descendant를 찾아 적용
  - [x] `AccessibilityNodeInfo.setText()` sealed-node 예외를 피하도록 텍스트 적용을 `ACTION_SET_TEXT` accessibility action으로 고정
  - [x] `/signaling` 연결 중 `STATUS_TICK`을 주기 전송해 접근성 활성화 후 뷰어 상태가 stale하게 남지 않도록 보정
  - [x] Mac Chrome 한글 IME 조합 중 `keydown` 자모를 전송하지 않고, 조합 완료 문자열만 `text` commit으로 전송
  - [x] 빠른 타이핑 누락을 줄이기 위해 text commit을 짧게 배치 처리하고 DataChannel을 reliable 설정으로 변경
  - [x] Android 접근성 스냅샷이 stale한 경우에도 같은 입력창의 연속 텍스트를 내부 버퍼 기준으로 이어 붙이도록 보정
  - [x] 빠른 텍스트 입력은 `seq`와 `CONTROL_ACK`로 직렬화하고, DataChannel 종료 시 미응답 큐를 폐기해 재연결 후 입력이 막히지 않도록 보정
  - [x] Mac Viewer 하단에 `최근 앱`/`홈`/`뒤로` 버튼을 추가해 제스처 내비게이션 없이 Android 전역 액션을 보낼 수 있게 함
  - [x] 실제 Android 단말 연결 순서, 터치, 스와이프, 텍스트 입력 로컬 검증 수행
- [x] **T3.5: 자주 쓰는 앱 바로가기**
  - [x] Android 앱에서 런처 앱 목록 조회 후 즐겨찾기 추가/삭제
  - [x] 즐겨찾기 앱을 로컬 저장소에 보관하고 `/apps/favorites`로 Mac 뷰어에 제공
  - [x] Mac 뷰어 바로가기 클릭 시 `/apps/launch`로 Android 앱 실행
  - [x] Android 11+ package visibility 대응을 위해 launcher intent query 선언
- [x] **T3.6: 화면 켜짐 유지, 밝기 최소화, Android Mirror 리브랜딩**
  - [x] Android 앱 메인 화면에 미러링 중 화면 켜짐 유지/밝기 최소화 모드 토글 추가
  - [x] 화면 설정을 로컬 SharedPreferences에 저장하고, 미러링 세션 중 window keep-screen-on 및 foreground service wake lock 정책에 연결
  - [x] `WRITE_SETTINGS` 권한 이동 버튼을 추가하고, 미러링 중 밝기를 최저값으로 낮춘 뒤 연결 해제 시 이전 밝기/모드를 복원
  - [x] MediaProjection이 중단되면 Mac Viewer에 `SCREEN_CAPTURE_REAUTH_REQUIRED` 상태를 보내 재승인이 필요하다고 표시
  - [x] Mac Viewer와 앱 표시 문자열을 `Android Mirror` 중심으로 정리
  - [x] Viewer가 `SCREEN_CAPTURE_REAUTH_REQUIRED`, `PROJECTION_STOPPED_LOCKED` 상태를 한국어로 표시
  - [x] Mac Viewer 상태 패널 아래에 WebRTC 업로드/다운로드 누적 사용량을 MB 단위로 표시
  - [x] `Protocols.md`에 keep-awake 토글, Android 잠금/화면 꺼짐에 따른 MediaProjection 중단, 밝기 최소화 권한 조건 문서화
  - [x] 실제 Android 단말에서 keep-awake 토글, 밝기 최소화/복원, 시스템 설정 수정 권한 이동 동작 검증
- [x] **T3.7: Tailscale/USB dual transport 연결 모델**
  - [x] Tailscale/WebRTC와 USB/JPEG transport 선택 모델 정의
  - [x] USB `/usb/session` WebSocket 기반 JPEG frame 및 control event 경로 구현
  - [x] 실제 Galaxy 단말에서 USB `adb forward` smoke test
  - [x] Tailscale/WebRTC와 USB 전환 반복 smoke test
  - [x] Android Host와 Mac Viewer의 연결/해제 UI를 실제 세션 상태 기준으로 정리하고, USB 모드에서 `adb forward tcp:8080 tcp:8080` 복사 안내 추가

### 🚀 Milestone 5: 신규 고도화 및 도메인 모듈화 (Active)
- [x] **T5.1: MediaProjectionService 도메인 모듈화**
  - [x] 67KB 거대 코드를 `ScreenCaptureManager.kt`, `WebRtcManager.kt`, `MediaProjectionService.kt` 3개 도메인으로 분리
  - [x] MediaProjection grant 및 USB 스트리밍/발열 정책 독립 (`ScreenCaptureManager`)
  - [x] PeerConnectionFactory, SDP Munging, ICE Candidate 시그널링 독립 (`WebRtcManager`)
  - [x] JVM 유닛 테스트 140개 100% 통과 및 Debug APK/Lint 검증 완료
- [x] **T4.1: WebRTC 라이프사이클 누수 및 크래시 제거**
  - [x] cleanupWebRTCResources 스레드 격리 및 volatile 변수 가드
  - [x] C++ 네이티브 자원 100% 해제(dispose) 및 EGL 릴리즈 순서 교정
- [x] **T4.2: 비동기 로그 I/O 및 시간 단조성(Monotonicity) 확보**
  - [x] CrashDiagnostics 비동기 single-threaded logExecutor 이관 (ANR 리스크 제로화)
  - [x] RemoteTextInputBuffer 내 nanoTime 기반 단조 Milliseconds 적용 (TTL 시간 오류 차단)
- [x] **T4.3: 접근성 터치 락업(Lockup) 방지**
  - [x] 3초 watchdog 타이머 구현으로 제스처 큐 stuck 리스크 원천 가드
  - [x] 텍스트 입력창 외부 변화(TYPE_VIEW_TEXT_CHANGED 등) 감지 시 버퍼 캐시 즉시 브레이크
- [x] **T4.4: 키보드 Caret 상/하/엔터 입력 지원**
  - [x] Whitelist allowedKeyCodes 확대 (19, 20, 21, 22, 66)
  - [x] MOVEMENT_GRANULARITY_LINE 및 ACTION_IME_ENTER 기반 구현
- [x] **T4.5: 웹 뷰어 IME 굳음 방지 및 비디오 비율 최적화**
  - [x] DataChannel send 예외 가드 및 1.5초 ACK timeout 도입 (타이핑 프리즈 차단)
  - [x] loadedmetadata/resize 리스너를 통한 비디오 aspect-ratio 동적 갱신 (비율 찌그러짐 방지)
  - [x] macOS 단축키(Cmd+Arrow 등) 버블링 우회 및 사이드바 반응형 스크롤 CSS 반영
- [x] **T4.6: 로컬 AVD 기반 계측 테스트 및 UI 테스트 검증 완료**
  - [x] `MainScreenTest.kt`의 `ComponentActivity` `setContent` 중복 호출 문제를 `createComposeRule()` 적용으로 개선
  - [x] 로컬 Android AVD 기동 및 3개 UI 테스트 통과 검증 완료

---

## 🤝 세션 인수인계 노트 (Handoff Notes)

### 1. 현재 개발 상태 요약
* **2026-07-03 업데이트**: 대기 상태였던 16개의 open PR (PR 1 ~ PR 16)을 모두 `main` 브랜치에 통합 병합 완료했습니다. 병합 과정에서의 모든 충돌 해소 및 통합 단위 테스트, 빌드, 린트 검사 통과를 완수하여 소스 트리 안정성을 검증했습니다.
* **2026-07-06 업데이트**: Android 앱의 `미러링 연결 해제` 버튼을 실제 미러링 세션 활성 상태에서만 켜도록 조정하고, Mac Viewer의 `미러링 연결하기` 버튼을 연결/해제 토글로 변경했습니다. USB 모드에는 Mac 터미널에서 필요한 `adb forward tcp:8080 tcp:8080` 명령 복사 UI를 추가했으며, 연결 해제 시 마지막 화면 대신 해제 안내 placeholder가 표시됩니다.
* **2026-07-06 USB 발열 최적화 업데이트**: USB/JPEG fallback 기본 프로필은 발열 우선 정책으로 변경되었습니다. `AUTO`/`STANDARD`는 `BALANCED 540x1200@8fps q60`, `DATA_SAVER`는 `COOL 360x800@4fps q50`, `HIGH`는 `CLEAR 720x1600@10fps q68`입니다. Android thermal 상태와 viewer idle 상태가 악화되면 런타임에 `COOL` 또는 `COOL 3fps`로 내려갑니다.
* **2026-07-06 USB H.264 Phase 2 업데이트**: Chrome USB viewer는 WebCodecs 지원 시 `/usb/session?codec=h264`를 열고, Android는 `MediaCodec` H.264 encoder input surface를 `VirtualDisplay`에 연결해 `GH26` binary packet으로 전송합니다. 기본 목표 프로필은 `BALANCED 720x1600@24fps 3Mbps`이며, decoder/encoder 실패 시 `/usb/session?codec=jpeg` fallback을 사용합니다.
* `/debug/perf`에서 USB codec, profile, bitrate, thermal status/headroom, battery temperature, frame/skip/encode/bytes-per-second 지표를 확인할 수 있습니다. USB 접속 전 `adb forward tcp:8080 tcp:8080`는 여전히 필요합니다.
* WebRTC 스트리밍 및 시그널링 채널(Ktor WebSocket 기반) 구현 완료 (Milestone 3).
* Tailscale/WebRTC 기존 경로와 USB/ADB 직접 연결 경로를 transport 선택 모델로 정리하고 구현을 완료했습니다. 실제 Galaxy 단말에서 USB `adb forward` smoke test 및 Tailscale/WebRTC와의 전환 반복 검증, 그리고 Post-review hardening에 대한 최종 검증까지 모두 통과하였습니다.
* 64개의 로컬 JVM 단위 테스트 및 3개의 Android 에뮬레이터 기반 Compose UI 계측 테스트 통과 완료 (Milestone 4). 최근 추가된 WebRTC 해제 누수 보강 및 USB 뷰어 사용성/화면 설정 재적용 개선에 대한 단위/회귀 테스트도 모두 통과했습니다.
* 에뮬레이터 UI 테스트에서 발생하던 `ComponentActivity` 중복 `setContent` 크래시 수정 완료.
* AI 에이전트(Gemini) 세션 구동 시 프로젝트의 아키텍처, 기술 스택, 디렉토리 구조, 빌드/테스트 명령 및 개발 약속을 기술한 `GEMINI.md` 컨텍스트 가이드 루트 디렉토리에 추가 완료.
* **성능 및 아키텍처 고도화 작업 완료**: Ktor 서버 및 WebRTC 세션의 포그라운드 서비스(`MediaProjectionService`) 이관, Android 14+ 화면 공유 승인 팝업 우회(Capturer/Track 캐싱), SDP Munging을 통한 H.264 하드웨어 가속 강제화, JSON ACK 직렬화 최적화(GC Jank 제거), Active-Idle 화질 가변 전환 시 화면 프리징 완벽 방지 작업을 구현 및 검증했습니다. 관련 분석은 [PerformanceOptimizationReport.md](./PerformanceOptimizationReport.md)에 상세히 문서화되어 있습니다.

### 🏁 Milestone 5: UI 고도화 및 안정화 (Completed)
- [x] **T5.1: 맥 뷰어 UI 프리미엄 고도화**
  - [x] 은은한 그라디언트 구체(Aura Sphere) 배경 및 트랜지션 추가
  - [x] 반응형 CSS 그리드/플렉스 보정 및 터치/호버 미크로 애니메이션 매핑
- [x] **T5.2: 지수 백오프 기반 연결 하드닝 및 예외 복구**
  - [x] WebSocket 및 PeerConnection 연결 단절 상태 감지 및 `1s -> 2s -> 4s -> 8s -> 16s` 지수 백오프 기반 자동 재연결 구현
  - [x] Page Visibility API (`visibilitychange`) 연동을 통한 브라우저 탭 활성화 시 지연 없는 즉각 세션 동기화 및 복원 적용
  - [x] 재연결 세션 갱신 시 기존 WebSocket/DataChannel/RTCPeerConnection의 비동기 리스너 정리(cleanup) 및 메모리 누수 방지

### 🏁 Milestone 6: 양방향 클립보드 동기화 및 고급 제어 기능 구현 (Completed)
- [x] **T6.1: 양방향 클립보드 실시간 동기화**
  - [x] 맥 브라우저 뷰어 포커스 시 `copy` 단축키 이벤트를 후킹하여 DataChannel로 복사 텍스트 자동 전송
  - [x] 안드로이드 Host 내 `GalaxyMirrorAccessibilityService`에서 클립보드 변경 이벤트(`OnPrimaryClipChangedListener`) 감시 및 뷰어로 실시간 브로드캐스트 (Android 10+ 백그라운드 샌드박스 제한 우회)
  - [x] 수신 시 `navigator.clipboard.writeText` 주입 및 고급스러운 Glow Toast 푸시 알림 피드백 연동
- [x] **T6.2: 물리 하드웨어 원격 제어 조작계 구축**
  - [x] 볼륨 크게(🔊), 볼륨 작게(🔉), 음소거(🔇), 화면 잠금(🔒) UI 레이아웃 및 스타일 매핑
  - [x] 클릭 시 DataChannel로 Android KeyEvent(24, 25, 164, 26) 송신 및 접근성 글로벌 액션(Volume Up/Down/Mute, Lock Screen) 주입 연동
- [x] **T6.3: 브라우저 내 스크린샷 및 화면 레코더 내장**
  - [x] 비디오 우상단 📸 버튼 클릭 시 Canvas 렌더러 기반 실시간 이미지 PNG 다운로드 구현
  - [x] ⏺️ 버튼 클릭 시 `MediaRecorder` API 기반 실시간 WebRTC 미디어 스트림 캡처 및 레코딩 녹화본 WebM 파일 다운로드 연동

> 2026-06-08 코드 리뷰 후속 하드닝: 볼륨 키는 `AudioManager` 기반으로 보정했고,
> clipboard empty-string 동기화와 HTTP origin fallback을 보강했습니다. MediaProjection은
> viewer 종료/교체 시 캡처를 정리하고 다음 Offer에서 새 화면 공유 승인을 요청하는
> privacy-first 정책으로 확정했습니다.

### 🧯 Post-review hardening
- [x] MediaProjection viewer close/replacement cleanup verified on device
- [x] Clipboard sync verified on actual `http://<MagicDNS-host>:8080` viewer origin
- [x] Volume up/down/mute verified on physical device

### 2. 다음 개발 단계 핵심 미션 (Backlog / Future Enhancements)
* **오디오 스트리밍 미러링 제외 결정**: 사용성 대비 구현 복잡도 등을 고려하여 해당 기능은 보류 및 구현 제외하기로 확정했습니다.
* 현재 기획된 M1~M6 마일스톤이 모두 완수되었으며, 프로젝트는 신규 기능 추가에서 **안정화 및 유지보수 모드**로 공식 전환되었습니다.
* [x] **원격 클립보드 히스토리 뷰어**: 뷰어 사이드바에 수신된 클립보드 텍스트 히스토리를 간직하는 목록 UI 구현 완료.

### 🚀 Milestone 7: 대규모 리팩토링 (Completed)
- [x] **T7.1: Phase 1 - Ktor API 및 WebSocket 라우팅 계층 분리**
  - [x] `MediaProjectionService.kt` 내부의 라우팅 로직을 `MirrorRouting.kt`로 분리 완료
  - [x] 테스트 실패 원인 파악 및 `MirrorSessionStateTest.kt`의 정적 텍스트 검색 우회 수정 완료
  - [x] 모든 단위 테스트(`app:testDebugUnitTest`) 통과 확인

### 🚀 Milestone 8: 신규 고도화 기능 구현 (Active / Proposed)
상세 명세서: [FeatureEnhancements.md](./FeatureEnhancements.md)
- [x] **T8.1: 📱 블랙 오버레이 모드 (P1)**
  - [x] `BlackOverlayController.kt` 생성 (`TYPE_APPLICATION_OVERLAY` 기반 OLED 픽셀 차단 및 터치 해제)
  - [x] Android Host `MediaProjectionService`, `MirrorRouting` (`POST /stream/overlay`), `WebRtcManager` 시그널링 연동
  - [x] Compose UI 설정 카드 토글 및 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 권한 이동 버튼 추가
  - [x] Mac Viewer UI (`index.html`, `ui.js`, `controls.js`, `main.js`, `signaling.js`) 🕶️ 차단 버튼 및 `STATUS` 동기화 연동
  - [x] `Protocols.md` 프로토콜 규격 갱신 및 JVM/JS/Lint/Assemble 100% 빌드 통과
- [ ] **T8.2: ⌨️ macOS 전용 단축키 & 핀치 줌 지원 (P2)**
  - [ ] `Cmd+H`, `Cmd+Delete`, `Cmd+Tab`, `Cmd+Shift+L` 키 커맨드 매핑 및 트랙패드 핀치 투 줌 2핑거 제스처 연동
- [ ] **T8.3: 📁 드래그 앤 드롭 파일 전송 (P3)**
  - [ ] 맥 브라우저 Drag&Drop 뷰포트 후킹, DataChannel 바이너리 핑퐁 전송 및 `/files/upload` 엔드포인트 연동
- [ ] **T8.4: 🏗️ MediaProjectionService 도메인 모듈화 (P4)**
  - [ ] `ScreenCaptureManager.kt` 및 `WebRtcManager.kt` 독립 분리 및 빌드/테스트 검증 완수

### 3. 화면 켜짐/밝기 최소화 확인 포인트
* 미러링 중 화면 켜짐 유지 토글은 Android 자동 화면 꺼짐을 줄이지만, 사용자가 전원 버튼으로 잠그거나 OS가 MediaProjection을 중단하면 화면 공유 재승인이 필요합니다.
* Android Host는 projection 중단 시 `SCREEN_CAPTURE_REAUTH_REQUIRED`를 송신합니다. Mac Viewer는 호환성 차원에서 `PROJECTION_STOPPED_LOCKED`도 복구 가능한 상태로 보고, Android 잠금 해제와 화면 공유 재승인 후 재연결하라는 안내를 표시합니다.
* 밝기 최소화 모드는 로컬 Android 화면 보호 목적입니다. `WRITE_SETTINGS` 권한이 없으면 Android 설정의 시스템 설정 수정 화면에서 Android Mirror를 허용해야 하며, 연결 해제 시 이전 밝기와 밝기 모드 복원 여부를 Galaxy S26 Android 16 등 실기기에서 확인해야 합니다.
* 스트림 화질 `AUTO` 모드는 현재 Android 네트워크를 보고 Wi-Fi/Ethernet이면 고화질, 4G/5G cellular이면 표준 화질을 적용합니다. Mac Viewer와 Android 앱 양쪽 버튼에서 수동으로 저데이터/표준/고화질을 고정할 수 있습니다.
* Tailscale viewer 접속 주소와 USB loopback 접속 주소는 모두 viewer token 없이 사용합니다. USB loopback 접속은 Mac 터미널에서 `adb forward tcp:8080 tcp:8080` 실행 후 `http://127.0.0.1:8080/?transport=usb`로 접속합니다.
* viewer 연결 종료나 세션 교체 후에는 Android 14+ projection token 재사용 예외를 피하기 위해 화면 공유 권한을 다시 승인해야 할 수 있습니다.
* 재연결 중 이전 viewer session의 WebSocket/DataChannel/MediaProjection callback이 늦게 도착해도 현재 session state와 입력 ACK 큐를 건드리지 않도록 인스턴스/세션 id guard가 들어가 있습니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Log.md](./Log.md) | 작업 로그 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 (현재 문서) |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
| [PerformanceOptimizationReport.md](./PerformanceOptimizationReport.md) | 성능 분석 및 고도화 보고서 |
| [FeatureEnhancements.md](./FeatureEnhancements.md) | 신규 고도화 기능 상세 명세 및 로드맵 |
| [AGENTS.md](./AGENTS.md) | 프로젝트 에이전트 가이드 |
| [Archive/OptimizationSessionHistory.md](./Archive/OptimizationSessionHistory.md) | 이전 센티널 세션 이력 아카이브 |
