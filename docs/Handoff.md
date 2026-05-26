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

### 🚀 Milestone 2: Android Host 기초 인프라 구축 (Active)
- [ ] **T2.1: Kotlin Android 프로젝트 뼈대 생성**
- [ ] **T2.2: Ktor 모듈 내장 및 HTTP 서버 포트 8080 구동**
- [ ] **T2.3: 웹 뷰어 클라이언트 정적 리소스 서빙 구현**

### ⏳ Milestone 3: WebRTC 스트리밍 및 시그널링 구현 (Backlog)
- [ ] **T3.1: Android MediaProjection 화면 실시간 캡처 프로토타이핑**
- [ ] **T3.2: Ktor WebSocket 기반 1:1 시그널링 채널 개설**
- [ ] **T3.3: WebRTC PeerConnection 화질 및 초당 프레임 수 최적화**

---

## 🤝 세션 인수인계 노트 (Handoff Notes)

### 1. 현재 개발 상태 요약
* 현재 코드가 작성될 수 있는 빈 폴더 구조에서 시작하여, 프로젝트 문서화 프레임워크 수립에 이어 실제 코드 작성의 기준선이 되는 **아키텍처 상세 기술 설계 명세서 2종([Protocols.md](./Protocols.md), [Coordinates.md](./Coordinates.md))을 완전하게 완성**했습니다.
* 기획 및 아키텍처 설계 단계(Milestone 1)가 공식 종료됨에 따라, 다음 작업 세션에서는 **Milestone 2 (Android Host 기초 인프라 구축)**에 곧바로 진입하면 됩니다.

### 2. 다음 개발 단계 핵심 미션
1. **Android 스튜디오 프로젝트 초기화**: Kotlin 기반 프로젝트를 생성하고, Gradle 디펜던시에 `Ktor` 서버 라이브러리를 추가합니다.
2. **HTTP/WebSocket 기본 라우팅 구현**: Ktor 웹 서버를 구축하여 `/signaling` 엔드포인트를 바인딩하고 가볍게 접속 상태를 로깅하는 에코(Echo) 기능을 구현합니다.
3. **기초 HTML 서빙**: Ktor 리소스 서빙 기능을 사용해 `index.html` 파일을 맥북 브라우저 뷰어로 정상 송출할 수 있는지 확인합니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Log.md](./Log.md) | 작업 로그 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 (현재 문서) |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
