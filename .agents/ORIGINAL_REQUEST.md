# Original User Request

## 2026-06-17T05:43:21Z

# Teamwork Project Prompt

Galaxy Mirror Web 프로젝트의 전체 성능을 튜닝하기 위한 대대적인 리팩토링을 수행합니다. Android Host 로직과 Web Viewer 양측 모두를 최적화하며, 성능 향상을 최우선으로 하여 팀워크 에이전트의 자체 판단하에 가장 효율적인 구조로 코드를 통합 및 재배치합니다.

Working directory: /Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/내 드라이브/Personal Develop/galaxy-mirror-web
Integrity mode: benchmark

## Requirements

### R1. Android Host 성능 및 구조 최적화
- Ktor 서버 응답, WebRTC 스트리밍 처리, MediaProjection 캡처 루프 등 리소스 집약적인 부분을 분석하고 병목을 해소합니다.
- 코틀린 기반 백엔드 로직의 불필요한 중복을 제거하고, 성능 향상에 유리한 구조로 재설계합니다. 

### R2. Web Viewer 클라이언트 최적화
- HTML/CSS 렌더링 부하 완화, DataChannel 이벤트(특히 클립보드 및 제스처)의 효율적인 처리, 메모리 누수 방지에 집중합니다.
- Vanilla JS 기반의 구조를 유지하되 성능에 가장 최적화된 파일 구조와 비동기 로직으로 통합 및 재배치합니다.

### R3. 최적화 중심의 아키텍처 재배치
- 기존 아키텍처에 얽매이지 않고 성능이 가장 잘 나올 수 있는 방향으로 에이전트가 주도적으로 구조를 개편합니다.
- 최적화나 코드 감량을 위해 잘 알려진 라이브러리나 패턴, 오픈소스를 적극 차용하여 도입할 수 있습니다.

## Acceptance Criteria

### 빌드 및 정적 분석 (Automated Verification)
- [ ] `cd android && ./gradlew assembleDebug --no-daemon` 빌드가 성공해야 합니다.
- [ ] `cd android && ./gradlew app:lintDebug --no-daemon` 실행 시 새로운 치명적 오류나 경고가 없어야 합니다.

### 테스트 무결성 (Automated Verification)
- [ ] `cd android && ./gradlew app:testDebugUnitTest --no-daemon` 단위 테스트를 모두 통과해야 합니다.
- [ ] 구조 변경 후에도 기존의 모든 로직(시그널링, 제어 채널 통신, 제스처 주입 등)이 손상되지 않고 보존되어야 합니다.
