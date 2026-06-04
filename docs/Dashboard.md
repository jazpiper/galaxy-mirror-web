---
project: galaxy-mirror-web
type: Dashboard
related: [Log.md, Handoff.md, Protocols.md, Coordinates.md]
updated: 2026-05-26
---

# 🌌 Galaxy Mirror Web Dashboard

맥북에 별도의 프로그램을 설치하지 않고, Tailscale 메시 가상 네트워크와 MagicDNS, 그리고 WebRTC 및 안드로이드 접근성 서비스(Accessibility Service)를 조합하여 갤럭시 스마트폰의 화면을 실시간 미러링하고 원격 제어할 수 있는 고성능 1:1 개인 솔루션입니다.

---

## 🎯 프로젝트 골 (Goals)
* **Zero Client Installation:** 맥북 뷰어는 추가 네이티브 프로그램 설치 없이 오직 웹 브라우저(Safari/Chrome)만으로 구동합니다.
* **NAT Firewall Bypass:** 5G/LTE 셀룰러 환경이나 공유기 하위 NAT 방화벽을 Tailscale의 WireGuard 터널링 및 DERP 릴레이 기술로 완벽히 자동 우회합니다.
* **Military-Grade Security:** 스트리밍 데이터가 중앙 클라우드를 거쳐 노출되지 않으며, 사용자 본인의 사설 가상망(Tailnet) 단말기 사이에서만 1:1로 안전하게 전송됩니다.
* **Low Latency Remote Control:** WebRTC 실시간 미디어 스트림으로 초저지연 화면 공유를 달성하고, WebRTC DataChannel을 통해 브라우저 마우스 이벤트를 안드로이드 OS에 접근성 제스처로 실시간 주입합니다.

---

## 🛠️ 기술 스택 (Technology Stack)

### 1. Android Host (Galaxy App)
* **개발 언어:** Kotlin (Java 21 LTS 툴체인 기반)
* **임베디드 웹서버:** **Ktor 3.0.3** (최신 고성능 비동기 프레임워크)
  * 포트 `8080`을 `0.0.0.0`으로 바인딩하여 가상망 내에서 접근 허용
* **화면 캡처:** Android `MediaProjection` API (H.264/VP8 인코딩)
* **네트워크 터널링:** Tailscale Android SDK 또는 로컬 데몬 연동
* **제어 명령 처리:** Android `AccessibilityService` (Touch & Gesture Injection)

### 2. Mac Viewer (Web Client)
* **개발 언어:** HTML5, Vanilla JavaScript, Vanilla CSS
* **연결 방식:** Tailscale MagicDNS를 통한 고정 호스트명 접속 (예: `http://galaxy-s24:8080`)
* **미디어 전송:** WebRTC PeerConnection (초저지연 비디오 스트림)
* **좌표 보정:** 맥북 브라우저 뷰포트 내 가변 좌표 $\rightarrow$ 안드로이드 해상도 백분율(%) 보정 연산

---

## 🗺️ 전체 로드맵 (Milestones)

- [x] **M1: 문서화 및 아키텍처 상세 설계 (완료)**
  - [x] 글로벌 문서 체계(`docs/`) 수립
  - [x] 시그널링 및 제스처 주입 상세 프로토콜 정의
- [x] **M2: Android Host 기초 인프라 구축 (완료)**
  - [x] Ktor 기반 초경량 서버 구현 (포트 8080)
  - [x] HTML5 정적 뷰어 서빙 구현 (CI 빌드 구성)
- [x] **M3: WebRTC 스트리밍 및 시그널링 구현 (완료)**
  - [x] MediaProjection 기반 화면 캡처 모듈 개발
  - [x] Ktor WebSocket 기반 1:1 인라인 시그널링 구현
  - [x] WebRTC PeerConnection 비디오 스트리밍 연동
- [x] **M4: 마우스 제어 및 제스처 주입 구현 (완료 - 검증 대기)**
  - [x] Accessibility Service 기반 가상 터치 주입 구현
  - [x] WebRTC DataChannel 마우스 좌표 전송 프로토콜 수립
  - [x] 클라이언트 좌표 보정 알고리즘 실장
- [ ] **M5: UI 고도화 및 안정화 (진행 중)**
  - [ ] 프리미엄 글래스모피즘(Glassmorphism) 및 반응형 뷰어 UI 완성
  - [ ] Tailscale 터널 전환 및 통신 예외 처리

---

## 🗂️ 프로젝트 문서 인덱스 (Document Index)

| 문서 (Link) | 유형 (Type) | 상태 (Status) | 설명 (Summary) |
| :--- | :--- | :--- | :--- |
| [Dashboard.md](./Dashboard.md) | Dashboard | `Active` | 프로젝트 통합 대시보드 및 기술 아키텍처 정의 허브 |
| [Log.md](./Log.md) | Log | `Active` | 세션별 개발 진행 사항과 변경점의 연대기적 기록 |
| [Handoff.md](./Handoff.md) | Handoff | `Active` | 현재 마일스톤의 활성 태스크 보드 및 인수인계 사항 |
| [Protocols.md](./Protocols.md) | Specification | `Active` | 1:1 WebSocket 시그널링 및 WebRTC DataChannel 데이터 규격 |
| [Coordinates.md](./Coordinates.md) | Specification | `Active` | 뷰포트 대비 디바이스 터치 좌표 변환 및 제스처 주입 공식 명세 |

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (현재 문서) |
| [Log.md](./Log.md) | 작업 로그 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
