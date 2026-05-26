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
* **Milestone 2 완수 : Ktor CIO 웹서버 탑재 및 GitHub Actions 무설치 빌드 성공**
  * **Android Host 뼈대 구축**: `android-cli` 도구를 이용해 코틀린 안드로이드 프로젝트 뼈대를 자동 생성하고, 최신 Gradle Kotlin DSL 디펜던시 및 Android Manifest 인터넷/클리어텍스트 권한 설정을 완료했습니다.
  * **Ktor CIO 초경량 서버 탑재**: `MainActivity.kt` 라이프사이클에 비동기(Coroutine IO)로 물리는 Ktor CIO 임베디드 웹서버를 구축, 포트 `8080`에 바인딩하여 기본 에코 라우팅 및 `/signaling` 웹소켓 채널의 기본 수신 토대를 세웠습니다.
  * **GitHub Actions 무설치 빌드 파이프라인 완비**: `.github/workflows/android-build.yml` CI 스크립트를 작성하여 원격 main 브랜치 푸시 시 자동 빌드(JDK 21 + Gradle)를 트리거했습니다. 첫 1차 원격 빌드(4분 18초 소요)를 완벽히 통과하여 `app-debug.apk` 아티팩트를 업로드하는 데 성공했습니다.
* **마일스톤 2 완수 및 마일스톤 3 활성화**
  * 개발 기반 인프라 구축이 완료됨에 따라 Milestone 2를 완료(Completed) 처리하고, 실질적 WebRTC 미디어 연동 단계인 **Milestone 3 (WebRTC 스트리밍 및 시그널링 구현)**를 활성(Active) 상태로 전환하여 태스크를 정비했습니다.
  * 모든 마크다운 문서 간의 상대 양방향 링크 연결성 및 유효성 검증을 마쳤습니다.
* **JDK 21 LTS & Ktor 3.0.3 컴파일 툴체인 최신화**
  * 사용자의 최신 스택 고도화 요구에 따라 컴파일 JDK 버전을 Java 17에서 최신 LTS 안정 버전인 **Java 21**로 전격 상향했습니다.
  * 로컬 코틀린 툴체인(`jvmToolchain`) 및 그레이들 컴파일 호환 버전을 모두 Java 21 규격으로 마이그레이션 완료했습니다.
  * 포그라운드 웹서버 엔진인 Ktor 프레임워크를 최신 배포판인 **Ktor 3.0.3** 버전으로 일제히 업그레이드하여 동시성 처리 효율성과 미러링 통신 성능을 극대화했습니다.
  * GitHub Actions 클라우드 빌드 가상 머신도 JDK 21을 기동해 빌드하도록 스크립트를 최종 셋업 완료했습니다.

### 2026-05-26 (리뷰 수정 세션)
* **WebRTC/MediaProjection 안정화**
  * Android 14+ single-use MediaProjection consent 규칙에 맞춰 foreground service가 `getMediaProjection()`을 선점하지 않도록 정리하고, `ScreenCapturerAndroid`가 projection token을 단일 소유하도록 수정했습니다.
  * 브라우저 offer 생성 전에 `video` recv-only transceiver를 추가하여 Android answer가 화면 스트림 m-line을 안정적으로 협상할 수 있게 했습니다.
  * `/signaling`은 개인 Tailnet 운용 전제를 유지하되, 1개 활성 viewer session만 허용하고 이전 PeerConnection/DataChannel 리소스를 정리하도록 보강했습니다.
* **원격 입력 안전장치 축소 구현**
  * DataChannel label을 `control`로 제한하고, tap/swipe/key JSON schema, 좌표 범위, duration, keyCode allowlist를 검증하는 `ControlEventValidator`와 단위 테스트를 추가했습니다.
  * AccessibilityService 권한을 현재 구현에 필요한 gesture dispatch 중심으로 축소하고, window content 조회 권한을 비활성화했습니다.
* **문서/CI 동기화**
  * `Protocols.md`를 실제 구현(`type: tap|swipe|key`, `0.0..1.0` 좌표)과 맞췄습니다.
  * CI에 `app:testDebugUnitTest`, `app:lintDebug`, report artifact 업로드 단계를 추가했습니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Log.md](./Log.md) | 작업 로그 (현재 문서) |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
