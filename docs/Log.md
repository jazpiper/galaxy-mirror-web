---
project: galaxy-mirror-web
type: Log
related: [Dashboard.md, Handoff.md, Protocols.md, Coordinates.md]
updated: 2026-05-26
---

# 📝 Galaxy Mirror Web Development Log

이 문서는 `galaxy-mirror-web` 프로젝트의 실시간 진행 상황과 핵심 개발 이력을 시간 순서대로 투명하게 기록하는 연대기적 개발 로그입니다.

---

### 2026-05-26 (초기 세션)
* **문서 프레임워크 구축**
  * 글로벌 conduct 규칙(`RULE[user_global]`)에 기초하여 `docs/` 디렉토리를 신설하고 `Dashboard.md`, `Log.md`, `Handoff.md` 핵심 문서 세트 구축을 완료했습니다.
  * 모든 문서에 YAML frontmatter를 정의하고 상호 참조 양방향 링크(`## 🔗 Related Documents`)를 연결하여 문서의 유기적인 탐색 흐름을 설계했습니다.
* **핵심 기술 스택 확정 및 로드맵 수립**
  * Android Host의 웹 프레임워크 후보군 중 비동기 처리 성능이 우수하고 코틀린 생태계와 직결되는 **Ktor**로 프레임워크를 확정했습니다.
  * Tailscale 메시 가상 네트워크의 성격을 100% 활용하기 위해 별도 매개자(Signal Server)가 불필요한 **1:1 인라인 WebSocket 시그널링** 아키텍처를 상세화했습니다.
  * 전체 개발 주기를 M1~M5의 5대 마일스톤으로 세분화하여 `Dashboard.md` 및 `Handoff.md`에 배치 완료했습니다.

### 2026-05-26 (두 번째 세션)
* **아키텍처 상세 기술 명세서(2종) 설계 완료**
  * **[Protocols.md](./Protocols.md)** 생성: Ktor WebSocket 시그널링 채널 메시지 규격(Offer, Answer, ICE Candidate)과 WebRTC DataChannel을 활용한 실시간 마우스 제어 및 키보드 완성형 텍스트 입력의 JSON 통신 프로토콜을 규정했습니다.
  * **[Coordinates.md](./Coordinates.md)** 생성: 맥 브라우저 뷰어의 반응형 창 비율 왜곡에 대응하기 위해, 2D 레터박스/필러박스 상쇄 보정 공식 및 Android `AccessibilityService`를 통한 클릭/드래그 스트로크 구현 뼈대를 완성했습니다.
* **GitHub 비공개(Private) 저장소 신설 및 연동**
  * 로컬 개발 도구인 `gh` CLI 권한 검증을 통과하여 깃허브에 비공개 원격 저장소 **`jazpiper/galaxy-mirror-web`**을 성공적으로 생성하고, 로컬의 뼈대 문서 세트와 `.gitignore`를 1차로 원격 동기화했습니다.
* **마일스톤 1 완수 및 마일스톤 2 활성화**
  * 기술 설계가 완비됨에 따라 Milestone 1을 완료(Completed) 처리하고, 실제 개발 단계인 **Milestone 2 (Android Host 기초 인프라 구축)**를 활성(Active) 상태로 전환하여 태스크를 정비했습니다.
  * 모든 마크다운 문서 간의 상대 양방향 링크 연결성 및 유효성 검증을 마쳤습니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Log.md](./Log.md) | 작업 로그 (현재 문서) |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
