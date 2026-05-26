# Galaxy Mirror Web Agent Guide

## Scope

이 지침은 이 저장소 전체에 적용된다. 더 좁은 하위 디렉터리에 별도
`AGENTS.md`가 생기면 그 파일의 지침을 우선한다.

## Project Shape

- 이 프로젝트는 갤럭시 단말을 Android Host로 사용하고, 맥 브라우저를
  Mac Viewer로 사용하는 무설치 미러링 시스템이다.
- 핵심 연결 모델은 Tailscale MagicDNS + Android 임베디드 Ktor 서버 +
  WebRTC + AccessibilityService 제어 입력이다.
- 현재 코드는 Android 프로젝트가 주 구현체이며, 브라우저 클라이언트는
  Android 리소스의 정적 파일로 서빙된다.
- 진행 상태와 설계 문서는 `docs/`가 기준이다. 구현을 바꿀 때 문서와
  태스크 상태가 어긋나지 않게 함께 확인한다.

## Important Paths

- `android/`: Android Studio/Gradle 프로젝트 루트.
- `android/app/src/main/java/com/example/galaxymirror/`: Kotlin 앱 코드.
- `android/app/src/main/java/com/example/galaxymirror/MainActivity.kt`: Compose 앱
  진입점, Ktor CIO 서버, `/status`, `/signaling`, WebRTC 초기 연결 로직.
- `android/app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt`:
  화면 캡처용 foreground service.
- `android/app/src/main/java/com/example/galaxymirror/GalaxyMirrorAccessibilityService.kt`:
  DataChannel 입력을 Android 제스처/전역 액션으로 주입하는 서비스.
- `android/app/src/main/resources/files/`: Ktor가 서빙하는 Mac Viewer 정적 파일
  (`index.html`, `viewer.js`).
- `android/gradle/libs.versions.toml`: Android Gradle Plugin, Kotlin, Compose,
  AndroidX 버전 카탈로그.
- `.github/workflows/android-build.yml`: main/pull_request용 Android CI 빌드.
- `docs/Dashboard.md`: 프로젝트 허브와 로드맵.
- `docs/Handoff.md`: 현재 태스크 보드와 다음 작업 인수인계.
- `docs/Log.md`: 개발 로그.
- `docs/Protocols.md`: WebSocket/WebRTC/DataChannel 메시지 규격.
- `docs/Coordinates.md`: 브라우저 뷰포트 좌표와 Android 화면 좌표 변환 규격.

## Build And Verification

Android 명령은 저장소 루트가 아니라 `android/`에서 실행한다.

```bash
cd android
./gradlew assembleDebug --no-daemon
./gradlew app:testDebugUnitTest --no-daemon
./gradlew app:lintDebug --no-daemon
```

- 기기나 에뮬레이터가 필요한 변경이면 `./gradlew app:connectedDebugAndroidTest --no-daemon`
  또는 실제 단말 smoke test를 추가로 수행한다.
- Ktor/WebRTC/MediaProjection/AccessibilityService를 건드린 경우 단순 컴파일만으로
  완료라고 보지 말고, 가능하면 단말에서 앱 실행, 화면 캡처 권한 승인,
  `http://<MagicDNS-host>:8080/status`, `/signaling` 연결, 브라우저 입력 전달을
  확인한다.
- GitHub Actions는 JDK 21로 `cd android && ./gradlew assembleDebug --no-daemon`를
  실행한다. 로컬도 JDK 21 기준으로 맞춘다.

## Implementation Notes

- Kotlin 코드는 현재 Gradle Kotlin DSL, Kotlin 2.x, Java toolchain 21, Jetpack
  Compose, Material3, Navigation3 패턴을 따른다.
- Web server는 Ktor CIO가 `0.0.0.0:8080`에 바인딩하고 정적 파일을
  `resources/files`에서 서빙한다. 포트나 경로를 바꾸면 README/docs와 브라우저
  클라이언트도 같이 맞춘다.
- WebRTC signaling 메시지 타입과 payload는 `docs/Protocols.md`를 기준으로 한다.
  새 타입을 추가하거나 JSON shape를 바꾸면 Android 처리 코드, `viewer.js`, 문서를
  같은 변경 단위로 갱신한다.
- 좌표 변환, tap/swipe/key 입력 규칙은 `docs/Coordinates.md`와
  `GalaxyMirrorAccessibilityService.kt`를 함께 확인한다.
- MediaProjection lifecycle, foreground service notification, Android permission은
  OS 버전별 제약이 강하다. 권한/서비스 선언을 바꿀 때
  `AndroidManifest.xml`, service 코드, 실제 단말 동작을 같이 검증한다.
- Mac Viewer는 현재 Vanilla HTML/JavaScript/CSS이다. 프론트엔드 프레임워크를
  도입하지 말고, 기존 정적 서빙 구조 안에서 작게 확장한다.
- `MainActivity.kt`가 Ktor, WebRTC, 권한 요청을 많이 품고 있으므로 큰 기능을
  추가할 때는 먼저 기존 흐름을 보존하고, 필요할 때만 작은 단위로 분리한다.

## Documentation Rules

- 구현 상태가 바뀌면 `docs/Handoff.md`의 태스크 체크 상태와
  `docs/Log.md`의 변경 기록을 함께 갱신하는 것을 기본값으로 한다.
- 설계/프로토콜/좌표 공식은 추측으로 쓰지 말고 실제 Kotlin/JS 코드와 맞춰서
  갱신한다.
- 장기 조사나 세션 인수인계가 필요한 경우 새 외부 노트보다 `docs/` 안의
  기존 문서 체계를 우선 사용한다.

## Safety And Secrets

- `android/local.properties`와 로컬 SDK 경로, Tailscale 계정 정보, 단말별
  MagicDNS 이름, 개인 네트워크 정보는 커밋하지 않는다.
- 화면 미러링/원격 입력 기능은 민감도가 높다. 접근성 권한, MediaProjection 권한,
  네트워크 노출 범위를 넓히는 변경은 최소화하고 문서에 운영 조건을 남긴다.
- Tailscale 기반 사설망 전제를 깨는 public endpoint, 외부 relay, TURN 서버,
  인증 없는 인터넷 노출을 추가하지 않는다. 필요하면 먼저 설계 문서에 명시한다.

## Git Hygiene

- 사용자 변경을 되돌리지 않는다. 작업 전후 `git status --short`로 범위를 확인한다.
- 빌드 산출물, Gradle 캐시, APK, 로컬 IDE 파일은 커밋하지 않는다.
- 커밋이나 PR을 만들 때는 구현 변경, 문서 동기화, 검증 결과가 서로 맞는지 확인한다.
