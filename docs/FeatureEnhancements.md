---
project: galaxy-mirror-web
type: Specification
related: [Dashboard.md, Handoff.md, Protocols.md, Coordinates.md]
created: 2026-07-22
---

# 🚀 Galaxy Mirror Web 신규 고도화 기능 명세 (Feature Enhancements)

본 문서는 Galaxy Mirror Web 프로젝트의 핵심 안정성 달성 이후, 사용자 경험(UX), 보안/Privacy, 생산성 및 아키텍처 완성도를 높이기 위해 기획된 **신규 보완 기능 명세서 및 이행 로드맵**입니다.

---

## 📋 기능 로드맵 및 백로그 (Roadmap & Backlog)

| 우선순위 | 기능명 (Feature) | 분류 | 주요 목표 | 상세 명세 링크 |
| :--- | :--- | :--- | :--- | :--- |
| **P1** | **📱 블랙 오버레이 모드** | Privacy / 배터리 | 폰 디스플레이 검은색 차단, OLED 발열/전력 절감 | [Feat 1 상세](#1-📱-블랙-오버레이-모드-black-overlay-mode) |
| **P2** | **🖥️ 가상 해상도 & 맥 창 맞춤 미러링** | UX / 멀티태스킹 | Mac 브라우저 창 비율 맞춤 VirtualDisplay 해상도 동적 조절 및 Freeform Window 지원 | [Feat 2 상세](#2-🖥️-가상-해상도--맥-창-크기-맞춤-미러링-dynamic-virtual-display--freeform-window) |
| **P3** | **🔊 갤럭시 오디오 맥북 스트리밍** | 오디오 / 미디어 | Android AudioPlaybackCapture 기반 미디어/게임 소리 WebRTC 오디오 스트리밍 | [Feat 3 상세](#3-🔊-갤럭시-오디오-맥북-스트리밍-audio-playback-capture) |
| **P4** | **🏗️ MediaProjectionService 분리** | 리팩토링 | `MediaProjectionService.kt` 코어 67KB 도메인 분리 | [Feat 4 상세](#4-🏗️-mediaprojectionservice-도메인-모듈화) |

---

## 1. 📱 블랙 오버레이 모드 (Black Overlay Mode)

### 1.1 개요
맥북에서 미러링 제어를 진행하는 동안, 스마트폰 실물 디스플레이에 전면 블랙 오버레이(Full-screen Black View)를 적용하여 OLED 화면의 픽셀 전력을 완전히 끄고(Pixel Off), 주변 사람에게 스마트폰 화면 내용이 노출되는 것을 차단합니다.

### 1.2 주요 요구사항
- **MediaProjection 비영향성**: `VirtualDisplay`를 통한 화면 캡처 스트림에는 오버레이가 가려지지 않고 원래 화면 내용이 정상 전송되어야 함.
- **상태 동기화**: Mac Viewer 사이드바에 👁️/🕶️ 오버레이 토글 버튼 제공.
- **긴급 해제**: 실물 디스플레이 조작 시 스마트폰 전원 버튼 2회 누름 또는 화면 터치 시 오버레이 즉시 해제 안심 가드 제공.

### 1.3 데이터 흐름 & 프로토콜
```json
// DataChannel 또는 HTTP POST /stream/overlay
{
  "type": "black_overlay",
  "enabled": true
}
```

---

## 2. 🖥️ 가상 해상도 & 맥 창 크기 맞춤 미러링 (Dynamic Virtual Display & Freeform Window)

### 2.1 개요
Mac 브라우저 뷰어의 창 크기(Aspect Ratio 및 픽셀 너비/높이)를 실시간 감지하여, 안드로이드 캡처 가상 디스플레이(`VirtualDisplay`)의 해상도를 16:9, 16:10, 21:9 등 PC 창에 딱 맞는 시원한 비율로 동적 변경합니다. 또한 안드로이드 자유형 창(Freeform Window Mode) 및 팝업 뷰 전환을 원격으로 지원하여 DeX와 같이 여러 앱을 윈도우 창 형태로 띄워 조작할 수 있게 합니다.

### 2.2 주요 요구사항
- **동적 가상 디스플레이 리사이즈 (`VirtualDisplay.resize`)**: Mac Viewer 브라우저의 Resizing 감지 시 시그널 수신 후 `VirtualDisplay.resize(width, height, densityDpi)` 및 비디오 인코더 포맷 동적 업데이트.
- **좌표 역변환 자동 보정**: `Coordinates.md` 스펙에 따른 뷰어 Canvas 및 레터박스/필러박스 비율 맵핑 실시간 재계산.
- **Freeform / Pop-up view 원격 트리거**: 접근성 서비스(`AccessibilityService`) 또는 시스템 인텐트를 통해 앱을 자유 크기 팝업 창으로 띄우는 원격 제어 지원.

---

## 3. 📁 드래그 앤 드롭 파일 전송 (Mac Viewer ➔ Galaxy Host)

### 3.1 개요
맥북 브라우저 뷰어 영역으로 컴퓨터의 파일을 드래그 앤 드롭하면, 안드로이드 호스트의 `Downloads` 디렉터리로 즉시 전송 및 저장하는 기능입니다.

### 3.2 전송 채널 및 프로토콜
- **WebRTC DataChannel (초고속 바이너리 Chunk)**:
  - Header: `{"type": "file_start", "fileName": "photo.jpg", "fileSize": 1048576, "mimeType": "image/jpeg"}`
  - Chunk: ArrayBuffer (64KB 단위 분할 전송)
  - End: `{"type": "file_end"}`
- **HTTP Multipart Endpoint (Fallback & USB 모드)**:
  - `POST http://<host>:8080/files/upload`
  - Multipart form-data 기반 파일 수신 및 저장.

### 3.3 안드로이드 호스트 처리
- Android `MediaStore` 및 `Downloads` 폴더 저장 후 수신 완료 상단 시스템 알림(Notification) 생성.

---

## 4. 🏗️ MediaProjectionService 도메인 모듈화

### 4.1 개요
현재 약 67KB 규모로 작성되어 있는 [MediaProjectionService.kt](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/내%20드라이브/Personal%20Develop/galaxy-mirror-web/android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt) 코어 파일을 연관 책임에 따라 3개 명확한 도메인으로 리팩토링 및 분리합니다.

### 4.2 분리 아키텍처
1. **`ScreenCaptureManager.kt`**:
   - `MediaProjection`, `VirtualDisplay`, `SurfaceTextureHelper`, Video Capturer 라이프사이클 100% 캡슐화.
2. **`WebRtcManager.kt`**:
   - `PeerConnectionFactory`, `RTCPeerConnection`, `DataChannel`, SDP/ICE Candidate 처리 및 시그널링 이벤트 바인딩 전담.
3. **`MediaProjectionService.kt` (경량화)**:
   - Android Service 라이프사이클, Notification 관리 및 위 2개 매니저 컴포넌트 간 바인딩 전담.

---

## 🔗 연관 문서 (Related Documents)
- [Dashboard.md](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/내%20드라이브/Personal%20Develop/galaxy-mirror-web/docs/Dashboard.md) - 전체 프로젝트 대시보드
- [Handoff.md](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/내%20드라이브/Personal%20Develop/galaxy-mirror-web/docs/Handoff.md) - 활성 태스크 보드 및 인수인계 노터
- [Protocols.md](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/내%20드라이브/Personal%20Develop/galaxy-mirror-web/docs/Protocols.md) - 통신 규격 명세서
- [Coordinates.md](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/내%20드라이브/Personal%20Develop/galaxy-mirror-web/docs/Coordinates.md) - 터치 및 제스처 좌표 공식
