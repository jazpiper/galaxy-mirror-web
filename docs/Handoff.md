---
project: galaxy-mirror-web
type: Handoff
related: [Dashboard.md, Log.md, Protocols.md, Coordinates.md]
updated: 2026-05-26
---

# 📋 Galaxy Mirror Web Handoff & Task Board

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

### 🚀 Milestone 3: WebRTC 스트리밍 및 시그널링 구현 (Active)
- [ ] **T3.1: Android MediaProjection 화면 실시간 캡처 프로토타이핑**
- [ ] **T3.2: Ktor WebSocket 기반 1:1 시그널링 채널 개설**
- [ ] **T3.3: WebRTC PeerConnection 화질 및 초당 프레임 수 최적화**

---

## 🤝 세션 인수인계 노트 (Handoff Notes)

### 1. 현재 개발 상태 요약
* 기 완비된 설계 명세를 바탕으로 안드로이드 뼈대 프로젝트 신설 및 **Ktor CIO 초경량 서버 탑재(0.0.0.0:8080 바인딩 및 에코/시그널링 웹소켓 기본 채널 매핑)**를 완수하였습니다.
* 맥북에 무거운 컴파일 환경을 구성하지 않고 깃허브 푸시만으로 `app-debug.apk`를 완전 무설치 생산하는 **GitHub Actions CI/CD 인프라 구축 및 1차 빌드 통과 검증**을 마쳤습니다.
* 다음 작업 세션에서는 최핵심 성능 구역인 **Milestone 3 (WebRTC 스트리밍 및 시그널링 구현)**에 본격 돌입합니다.

### 2. 다음 개발 단계 핵심 미션
1. **MediaProjection 디스플레이 캡처**: Android 화면 미디어 프로젝션 API를 기동하여 화면 스트림을 가로채고, WebRTC 송출을 위해 프레임을 인코딩(VP8/H.264)할 수 있는 모듈 실장.
2. **시그널링 채널 실구현**: Ktor WebSocket(`/signaling`)을 통하여 `Protocols.md` 규격의 Offer/Answer SDP 및 ICE Candidate를 교환하는 1:1 브로드캐스트 라우팅 코드 작성.
3. **WebRTC PeerConnection 완성**: 맥 브라우저 뷰어 HTML5 코드와 단말기 간의 1:1 초저지연 비디오 스트리밍 채널 최종 연결.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Log.md](./Log.md) | 작업 로그 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 (현재 문서) |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
