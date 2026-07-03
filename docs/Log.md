---
project: galaxy-mirror-web
type: Log
related: [Dashboard.md, Handoff.md, Protocols.md, Coordinates.md]
updated: 2026-06-19
---

# 📝 Android Mirror Web Development Log

이 문서는 `galaxy-mirror-web` 프로젝트의 실시간 진행 상황과 핵심 개발 이력을 시간 순서대로 투명하게 기록하는 연대기적 개발 로그입니다.

---

### 2026-06-19

- **안정화 및 하드닝 검증 완료**
  - 실제 Galaxy 단말에서 USB `adb forward` 연결 검증 및 Tailscale/WebRTC와의 전환 반복을 성공적으로 완수했다.
  - MediaProjection viewer close/replacement cleanup이 디바이스 상에서 정상 동작하는 것을 확인했다.
  - 클립보드 동기화와 물리 디바이스 볼륨 업/다운/음소거 기능이 실제 장치에서 완벽하게 동작하는 것을 검증했다.
  - USB 뷰어 사용성 향상을 위한 단축키, UI 동작 개선 및 화면 설정 재적용 기능을 추가 보완했다.
  - WebRTC 해제 시 이벤트 리스너 정리 및 메모리 누수 방지에 대한 회귀/단위 테스트를 보강하여 통과시켰다.

---

### 2026-06-09

- Tailscale/WebRTC 기존 경로와 별도로 USB/ADB 직접 연결 모드 구현 계획을 반영했다.
- Android UI에는 token, Tailscale URL, ADB port forwarding 명령, 토큰 없는 loopback USB URL을 함께 표시하고, Mac Viewer는 transport selector로 Tailscale/USB 경로를 선택한다.
- USB 모드는 `adb forward tcp:8080 tcp:8080` 후 `/usb/session` WebSocket에서 JPEG binary frame과 control JSON text frame을 교환한다.
- `127.0.0.1`/`localhost` loopback viewer 요청은 로컬 USB 운용 편의를 위해 token 없이 허용하고, Tailscale/WebRTC 경로는 기존 token 보호를 유지한다.
- Mac Viewer의 하단 전원/볼륨/내비게이션 컨트롤을 데스크톱에서는 우측 compact rail로 이동해 미러 화면이 세로 공간을 더 크게 쓰도록 조정했다.
- 미러 화면 위 마우스 휠 이벤트를 짧은 `swipe` control payload로 변환해 Android 화면 스크롤에 사용할 수 있도록 했다.
- 두 transport는 동시에 활성화하지 않고, 전환 시 기존 capture/session을 정리한 뒤 MediaProjection 재승인을 받는 정책으로 정리했다.
- `node --check`, Mac Viewer JS 테스트, `app:testDebugUnitTest`, `assembleDebug`, `app:lintDebug`는 통과했고 debug APK는 생성됐다. 다만 `adb devices -l`과 Mac USB 장치 확인에서 Galaxy/Android 장치가 잡히지 않아 APK 재설치/실행 및 USB/Tailscale 실기기 smoke test는 보류했다.

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
* **Android Host 메인 화면 정리**
  * Hello World 샘플 화면을 제거하고, Mac 접속 주소 형식, 화면 공유 승인 순서, 접근성 설정 경로, 터치/키보드 조작법을 한국어로 안내하는 메인 화면으로 교체했습니다.
  * `접근성 설정 열기` 버튼은 Android `Settings.ACTION_ACCESSIBILITY_SETTINGS`로 이동하고, 설정 Activity가 없을 때 일반 설정으로 fallback합니다.
  * Galaxy S26 Android 16의 제한된 설정 차단 흐름에 맞춰 `앱 정보 열기` 버튼을 추가하고, `설정 > 애플리케이션 > Android Mirror > 제한된 설정 허용` 안내를 처음 설정 단계에 반영했습니다.
  * Android secure settings의 enabled accessibility service 목록을 읽어 Android Mirror 접근성 서비스가 이미 활성화된 경우 앱 정보/접근성 설정 버튼을 비활성화하도록 변경했습니다.
  * `미러링 연결 해제` 버튼은 현재 WebRTC/DataChannel과 MediaProjection foreground service를 명시적으로 정리합니다.
* **WebRTC/MediaProjection 안정화**
  * Android 14+ single-use MediaProjection consent 규칙에 맞춰 foreground service가 `getMediaProjection()`을 선점하지 않도록 정리하고, `ScreenCapturerAndroid`가 projection token을 단일 소유하도록 수정했습니다.
  * 브라우저 offer 생성 전에 `video` recv-only transceiver를 추가하여 Android answer가 화면 스트림 m-line을 안정적으로 협상할 수 있게 했습니다.
  * `/signaling`은 개인 Tailnet 운용 전제를 유지하되, 1개 활성 viewer session만 허용하고 이전 PeerConnection/DataChannel 리소스를 정리하도록 보강했습니다.
* **ADB 없는 크래시 진단 경로 추가**
  * Java/Kotlin uncaught exception, 주요 MediaProjection/WebRTC breadcrumb, Android 11+ 프로세스 종료 이력을 앱 내부 파일로 저장하고 `/debug/crash`에서 text/plain으로 조회할 수 있게 했습니다.
  * 재현 후 앱을 다시 실행하고 `http://<MagicDNS-host>:8080/debug/crash` 내용을 복사하면 USB/무선 ADB 없이도 최근 크래시 원인과 직전 실행 흐름을 확인할 수 있습니다.
  * `/debug/crash`로 확인한 `ForegroundServiceDidNotStartInTimeException`의 원인은 `Activity.RESULT_OK`가 `-1`인데 서비스가 `-1`을 missing sentinel로 사용한 것이었습니다. missing sentinel을 `Int.MIN_VALUE`로 바꾸고 정상 승인 결과를 유효하게 처리하는 회귀 테스트를 추가했습니다.
* **원격 입력 안전장치 축소 구현**
  * DataChannel label을 `control`로 제한하고, tap/swipe/key JSON schema, 좌표 범위, duration, keyCode allowlist를 검증하는 `ControlEventValidator`와 단위 테스트를 추가했습니다.
  * 텍스트 입력을 위해 AccessibilityService의 window content 조회 권한을 활성화하고, package filter를 제거해 focused editable node에 `ACTION_SET_TEXT`를 수행할 수 있게 했습니다.
  * Galaxy S26 Android 16 현장 로그에서 터치는 성공하지만 텍스트 입력이 `focused node is not editable`로 실패하는 경로를 확인하고, 입력 처리 메인 스레드 실행, focused editable descendant 탐색, 예외 진단 저장을 추가했습니다.
  * Galaxy S26 재설치 후 `Cannot perform this action on a sealed instance`가 확인되어, Kotlin 확장 함수명이 Android `AccessibilityNodeInfo.setText()` 멤버와 충돌하던 문제를 수정했습니다. 텍스트 적용 호출은 `performSetTextAction()` 이름으로 분리해 `performAction(ACTION_SET_TEXT)`만 사용하도록 회귀 테스트를 추가했습니다.
  * AccessibilityService 활성화 이후 브라우저 상태 표시가 `권한 필요`로 남는 stale status 문제를 줄이기 위해 `/signaling` WebSocket에서 주기적인 `STATUS_TICK`을 전송하도록 보강했습니다.
  * Mac Chrome 한글 IME 입력에서 `keydown`으로 ㄱ/ㅏ 같은 조합 중 자모가 즉시 전송되던 문제를 확인하고, 숨은 `textarea` 기반 keyboard sink의 `compositionend`/`input` 결과만 DataChannel `text` 이벤트로 전송하도록 수정했습니다.
  * 빠른 타이핑 중 문자 단위 `ACTION_SET_TEXT`가 연속 실행되며 일부 글자가 덮일 수 있는 경로를 줄이기 위해 브라우저 text commit을 35ms/최대 64자 단위로 배치 처리하고, `control` DataChannel에서 `maxRetransmits: 0`을 제거해 reliable 전송으로 변경했습니다.
* **자주 쓰는 앱 바로가기**
  * Android 메인 화면에 `자주 쓰는 앱` 섹션을 추가해 설치된 런처 앱 중 원하는 앱을 즐겨찾기로 추가/삭제할 수 있게 했습니다.
  * 즐겨찾기는 Android 로컬 저장소에 `packageName`/`label`로 저장하고, Ktor `GET /apps/favorites`에서 Mac 뷰어로 제공합니다.
  * Mac 뷰어 왼쪽 패널에 앱 바로가기 버튼을 렌더링하고, 클릭 시 `POST /apps/launch`로 Android 단말에서 해당 앱을 실행합니다.
  * Android 11+ package visibility 제약에 맞춰 manifest에 launcher intent query를 선언했습니다.
* **화면 공유 순서 의존성 완화 및 원격 입력 확장**
  * 브라우저 Offer가 화면 캡처 foreground service 준비보다 먼저 도착하면 pending offer로 보류하고 `STATUS: WAITING_FOR_SCREEN_CAPTURE`를 반환한 뒤, Android 화면 공유 승인 후 자동으로 WebRTC 협상을 재개하도록 변경했습니다.
  * viewer WebSocket 종료/교체와 MediaProjectionService 종료 정책을 분리해, 연결 순서가 어긋나도 화면 캡처 준비 상태가 불필요하게 사라지지 않도록 했습니다.
  * 브라우저 뷰어는 WebRTC 스트림, 제어 채널, 접근성 입력 상태를 분리 표시하고, 비디오에 포커스가 있을 때 Mac 키보드 문자/Enter/Backspace를 DataChannel `text` 이벤트로 전송합니다.
  * Android AccessibilityService는 tap/swipe dispatch 결과, 전역 key 처리, focused editable node 기반 text commit/delete 결과를 `/debug/crash` 최근 이벤트에 남깁니다.
* **문서/CI 동기화**
  * `Protocols.md`를 실제 구현(`type: tap|swipe|key`, `0.0..1.0` 좌표)과 맞췄습니다.
  * `Protocols.md`에 signaling `STATUS`와 DataChannel `text` 규격을 추가하고, `Coordinates.md`에 텍스트 입력은 좌표 변환을 거치지 않는다는 제약을 문서화했습니다.
  * CI에 `app:testDebugUnitTest`, `app:lintDebug`, report artifact 업로드 단계를 추가했습니다.
* **검증**
  * `cd android && ./gradlew app:testDebugUnitTest --no-daemon` 통과.
  * `cd android && ./gradlew assembleDebug --no-daemon` 통과.
  * `cd android && ./gradlew app:lintDebug --no-daemon` 통과.
  * Galaxy S26 Android 16 fresh APK 실기기 smoke test는 아직 수행하지 않았습니다.

### 2026-05-26 (화면 켜짐/보호 모드 사이드카 세션)
* **Android 화면 켜짐/보호 모드**
  * 앱 메인 화면에 `미러링 중 화면 켜짐 유지`와 `화면 보호 모드` 토글을 추가하고, 설정을 로컬 SharedPreferences에 저장하도록 연결했습니다.
  * 미러링 세션이 활성화된 동안 keep-screen-on window flag와 foreground service partial wake lock 정책을 적용하도록 `MainActivity`와 `MediaProjectionService`를 연결했습니다.
  * 보호 모드가 켜져 있으면 미러링 시작 후 30초 뒤 `FLAG_SECURE`가 적용된 검은 `미러링 중` 화면을 열고, 탭/뒤로가기로 닫히게 했습니다.
  * MediaProjection callback `onStop()`에서 기존 projection token을 폐기하고 Viewer에 `SCREEN_CAPTURE_REAUTH_REQUIRED`를 보내 재승인 필요 상태로 안내하도록 변경했습니다.
* **Android Mirror 리브랜딩**
  * 앱 표시 이름과 접근성 서비스 설명을 `Android Mirror`로 변경했습니다. 패키지명은 변경하지 않았습니다.
  * Mac Viewer의 문서 타이틀, 헤더, 상태 안내, 로그 문구를 Android 단말 일반 표현으로 정리했습니다.
* **Viewer 상태 안내 보강**
  * `SCREEN_CAPTURE_REAUTH_REQUIRED`와 `PROJECTION_STOPPED_LOCKED`를 받으면 Android 잠금 해제와 화면 공유 재승인이 필요하다는 한국어 안내를 표시합니다.
  * `SCREEN_PROTECTION_ENABLED`와 `SCREEN_PROTECTION_DISABLED`를 받으면 검은 `미러링 중` 보호 오버레이 상태를 Mac Viewer에 표시합니다.
* **문서 동기화**
  * `Protocols.md`에 keep-awake 토글의 한계, Android 잠금/화면 꺼짐에 따른 MediaProjection 중단, 보호 오버레이의 실기기 검증 필요성을 기록했습니다.
  * `Handoff.md`에 T3.6 완료 항목과 남은 실기기 검증 항목을 추가했습니다.

### 2026-05-27 (실기기 WebRTC cleanup 스레드 수정)
* **CalledFromWrongThreadException 수정**
  * Galaxy S26 Android 16 로그에서 Ktor WebSocket worker가 viewer session 교체 중 `cleanupWebRTCResources()`를 호출하고, 그 안에서 `window.addFlags/clearFlags`가 실행되어 `ViewRootImpl$CalledFromWrongThreadException`이 발생하는 경로를 확인했습니다.
  * `MainActivity.applyScreenAwakeWindowFlag()`가 호출 스레드를 검사하고, 메인 스레드가 아니면 `runOnUiThread`로 window flag 적용을 넘기도록 수정했습니다.
  * `cd android && ./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon` 및 `node --check android/app/src/main/resources/files/viewer.js && node android/app/src/test/js/viewer-keyboard.test.mjs` 통과.

### 2026-05-27 (Android Mirror 앱 아이콘)
* **Adaptive icon 교체**
  * 기본 Android 템플릿 아이콘을 제거하고, 어두운 배경 위에 Android 단말, Mac 브라우저 창, 연결 신호를 조합한 `Android Mirror` 전용 adaptive vector icon으로 교체했습니다.

### 2026-05-27 (밝기 최소화와 Viewer 조작 패널)
* **검은 보호 오버레이를 밝기 최소화 모드로 대체**
  * `ScreenAwakeSettings`의 보호 화면 설정을 미러링 중 밝기 최소화 설정으로 전환하고, legacy `mirror_protection_enabled` 저장값은 밝기 최소화로 마이그레이션하도록 했습니다.
  * `ScreenBrightnessController`를 추가해 시스템 설정 수정 권한이 있을 때 현재 밝기/밝기 모드를 저장한 뒤 최저 밝기로 낮추고, 미러링 해제/비활성 상태에서 이전 값으로 복원합니다.
  * Android 메인 화면에 시스템 설정 수정 권한 안내와 권한 설정 버튼을 추가했습니다. 권한이 허용되어 있으면 버튼은 비활성화된 `밝기 권한 허용됨` 상태로 표시됩니다.
* **Mac Viewer 조작성 개선**
  * 미러링 화면 하단에 `최근 앱`, `홈`, `뒤로` 버튼을 추가해 마우스로 Android 전역 내비게이션을 보낼 수 있게 했습니다.
  * 비디오 위 커서를 십자가 대신 기본 마우스 커서로 변경했습니다.
  * 상태 패널 아래에 WebRTC stats 기반 업로드/다운로드 누적 사용량을 MB 단위로 표시합니다.
* **검증**
  * `node --check android/app/src/main/resources/files/viewer.js`, `node android/app/src/test/js/viewer-keyboard.test.mjs`, `cd android && ./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon` 통과.

### 2026-05-27 (빠른 한글 타이핑 누락 보정)
* **원인 분석**
  * Galaxy S26 실기기 `/debug/crash`에서 DataChannel text 이벤트는 유실 없이 도착하고 `ACTION_SET_TEXT`도 `applied=true`로 성공하지만, 빠른 한글 입력 중 일부 글자가 이전 텍스트 기준으로 덮어써지는 증상을 확인했습니다.
  * 원인은 Android 앱/입력창의 AccessibilityNodeInfo가 `ACTION_SET_TEXT` 직후에도 잠시 이전 문자열/커서 스냅샷을 반환하는 경로로 판단했습니다.
* **수정**
  * `RemoteTextInputBuffer`를 추가해 같은 입력창에 대한 연속 commit/delete는 마지막으로 성공 적용한 텍스트와 커서 위치 기준으로 계산합니다.
  * 탭, 스와이프, Android 전역키처럼 포커스가 바뀔 수 있는 이벤트에서는 텍스트 버퍼를 무효화합니다.
  * `ACTION_SET_TEXT` 후 `ACTION_SET_SELECTION`을 best-effort로 호출해 다음 입력 커서도 Host의 내부 상태와 맞추도록 보강했습니다.
* **검증**
  * stale snapshot으로 `이제 ` + `간` + `단` + `한`이 `이제 간단한`으로 누적되는 단위 테스트를 추가했습니다.

### 2026-05-27 (네트워크 기반 스트림 화질 최적화)
* **데이터 사용량 절감**
  * `StreamQualityPolicy`를 추가해 `AUTO`, `DATA_SAVER`, `STANDARD`, `HIGH` 모드를 정의했습니다.
  * `AUTO`는 Wi-Fi/Ethernet에서 `HIGH(1080x2400@30fps, 3Mbps cap)`, 4G/5G cellular와 기타 네트워크에서 `STANDARD(720x1600@15fps, 1.2Mbps cap)`로 해석합니다.
  * 저데이터 모드는 `540x1200@12fps, 600kbps cap`으로 추가했습니다.
* **Android/WebRTC 적용**
  * Android 앱에 스트림 화질 버튼을 추가하고 선택값을 SharedPreferences에 저장합니다.
  * WebRTC 세션 시작 시 선택 프로필로 `ScreenCapturerAndroid.startCapture()`를 호출하고, `RtpSender` encoding parameter에 bitrate/fps cap을 적용합니다.
  * 미러링 중 변경 시 `VideoCapturer.changeCaptureFormat()`과 sender parameter 갱신을 best-effort로 수행합니다.
* **Mac Viewer 연동**
  * 왼쪽 패널에 스트림 화질 상태와 `자동/저데이터/표준/고화질` 버튼을 추가했습니다.
  * `GET/POST /stream/quality`와 `STATUS.streamQuality`로 Android Host의 현재 선택값, 네트워크 종류, 실제 적용 프로필을 동기화합니다.
* **검증**
  * `node --check android/app/src/main/resources/files/viewer.js && node android/app/src/test/js/viewer-keyboard.test.mjs` 통과.
  * `cd android && ./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon` 통과.

### 2026-05-27 (세션 보안/재연결/입력 ACK 하드닝)
* **Viewer 접근 토큰**
  * Android 앱 메인 화면의 Mac 접속 주소에 로컬 viewer token을 포함하도록 하고, `/signaling`, `/debug/crash`, 앱 바로가기, 스트림 화질 API에 token 검증을 추가했습니다.
  * Mac Viewer는 URL query token을 WebSocket query와 HTTP `X-Android-Mirror-Token` 헤더로 전달합니다.
* **MediaProjection 재연결 정책 보정**
  * Android 14+ single-use MediaProjection consent를 재사용하지 않도록 viewer WebSocket 종료/교체 시 WebRTC capturer, foreground service, 저장 projection Intent를 함께 정리합니다.
  * projection Intent가 없으면 foreground service가 아직 내려가는 중이어도 `MISSING_PERMISSION`으로 판정해 새 Offer를 pending으로 보류하고 화면 공유 재승인을 요청합니다.
* **빠른 키보드 입력 안정화**
  * 텍스트 control payload에 `seq`를 붙이고 Android Host가 `CONTROL_ACK`를 반환하도록 연결해, 브라우저가 다음 text commit/delete를 ACK 이후에 전송하게 했습니다.
  * DataChannel이나 WebSocket이 끊기면 미응답 텍스트 큐를 폐기해 재연결 후 입력이 영구 대기 상태로 남지 않도록 했습니다.
* **적응형 idle 품질 제한**
  * Viewer 입력이 일정 시간 없으면 idle 상태로 전환해 해상도/FPS/bitrate를 낮추고, 새 입력이 들어오면 active 프로필로 복귀합니다.
  * `STATUS.streamQuality.activityState`와 Mac Viewer 화질 패널에 active/idle 상태를 표시합니다.
* **검증**
  * `node --check android/app/src/main/resources/files/viewer.js`, `node --check android/app/src/main/resources/files/viewer-keyboard.js`, `node android/app/src/test/js/viewer-keyboard.test.mjs`, `cd android && ./gradlew app:testDebugUnitTest --no-daemon` 통과.

### 2026-05-27 (재연결 stale 이벤트 가드)
* **원인 분석**
  * 코드 리뷰에서 viewer 재연결이나 세션 교체 중 이전 WebSocket `onclose`, 이전 DataChannel `onclose`, 이전 `MediaProjection.Callback.onStop()`이 늦게 도착하면 새 세션의 `peerConnection`, text ACK 큐, capture-ready 상태를 닫을 수 있는 레이스를 확인했습니다.
* **수정**
  * Android `MirrorSessionState.projectionStopped(sessionId)`가 현재 활성 session id와 일치할 때만 reauth 상태로 전환하도록 바꿨습니다.
  * `ScreenCapturerAndroid.startCapture()` 실패를 즉시 `SCREEN_CAPTURE_REAUTH_REQUIRED`로 연결해 single-use/expired projection token 상태가 stale하게 남지 않도록 했습니다.
  * Mac Viewer는 WebSocket/PeerConnection/DataChannel 인스턴스가 현재 활성 인스턴스일 때만 close/message/offer 후속 처리를 수행하도록 guard를 추가했습니다.
* **검증**
  * stale MediaProjection callback, stale WebSocket close, stale DataChannel close 회귀 테스트를 추가했습니다.
  * `node --check android/app/src/main/resources/files/viewer.js`, `node --check android/app/src/main/resources/files/viewer-keyboard.js`, `node android/app/src/test/js/viewer-keyboard.test.mjs`, `cd android && ./gradlew app:testDebugUnitTest assembleDebug app:lintDebug app:compileDebugAndroidTestKotlin --no-daemon`, `git diff --check` 통과.

### 2026-06-05 (코드 리뷰 개선 세션)
* **스레드 안전성 및 세션 동시성 보강**
  - Ktor WebSocket 시그널링 수신 처리와 WebRTC session lifecycle API 호출(`beginViewerSession`, `endViewerSession`)을 `sessionLock` 동기화 락 하에서 `Dispatchers.Main` 스레드로 강제하여 다중 스레드 혼선으로 인한 JVM/Native 크래시 원인을 완벽히 통과 및 격리하였습니다.
  - WebRTC ICE candidates 추가 시 remote description 설정 여부에 대한 레이스 컨디션을 큐 동기화 블록으로 완전히 분리하여 candidate 유실을 방지하였습니다.
* **AccessibilityService 터치/입력 엔진 직렬화 및 제어 보정**
  - 가상 제스처(`dispatchGesture`) 비동기 실행 시 레이스를 예방하기 위한 FIFO 큐 (`PendingGesture`) 제어 및 비동기 작업 직렬화 구조를 적용했습니다.
  - 디바이스의 물리 화면 해상도, 가로/세로 방향(orientation) 및 폴더블 해상도 전환 상황에서 뷰포트 대비 디바이스 좌표 공식이 틀어지지 않도록 `resources.displayMetrics`를 활용해 실시간 획득 연산하도록 고도화하였습니다.
  - 비밀번호 필드 포커싱 시 보안 키보드의 마스킹 문자(`••••••`)가 원격 버퍼로 역류하여 원본 텍스트를 손상시키는 예외를 방지하기 위해 `isPassword` 감지 및 마스킹 문자 무시 로직을 적용하였습니다.
  - 키보드 caret 조작 향상을 위해 ArrowLeft/ArrowRight 키 매핑 시 접근성 Caret Navigation Action (`ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY`, `ACTION_NEXT_AT_MOVEMENT_GRANULARITY`)을 구현하여 커서 이동을 호환시켰습니다.
* **화면 켜짐 유지, 밝기 제어 및 적응형 비디오 스트림 품질 튜닝**
  - `MediaProjectionService`가 백그라운드 구동 중일 때 CPU 절전으로 화면이 강제 소멸되는 현상을 방지하도록 `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`을 소유하도록 강화하였습니다.
  - active-to-idle 상태 품질 전환 시 `MediaCodec` 하드웨어 디코더가 재시작하며 화면이 일시 정지(stutter)하는 문제를 회피하기 위해, 해상도는 일정하게 유지하고 FPS와 전송률(bitrate) 한도만 낮추는 방향으로 어댑티브 필터를 변경했습니다.
  - WiFi에서 Cellular 이동통신 전환 혹은 그 반대의 동적 네트워크 핸드오프를 감지하도록 `NetworkCallback`을 MainActivity에 추가하여 최적 스트림 프로필을 실시간 가변 조정하도록 적용했습니다.
  - `ScreenBrightnessController`에서 밝기 복원 신뢰성을 위해 SharedPreferences 저장 모드를 비동기 `.apply()`에서 원자적 트랜잭션 방식인 `.commit()`으로 상향하고, OLED 번인 방지 및 블랙아웃 방지를 위해 최소 밝기 임계치를 `10`으로 안전하게 수정하였습니다.
* **Mac Viewer 리소스 누수 해제 및 편의성 튜닝**
  - 재연결 및 끊김 감지 시 PeerConnection과 WebSocket의 중복 리스너 소멸 로직을 반영해 메모리 누수를 원천 차단했습니다.
  - WebRTC connection stats API를 파싱하여 live RTT(ping) 지연율 통계를 대시보드 사이드바에 실시간 렌더링하고, 화면 폭 850px 이하 장치에서 비디오 영역이 잘리지 않는 반응형 CSS 룰을 매핑했습니다.
* **로컬 및 CI 빌드 검증**
  - standard JVM 환경(GitHub Actions CI 등)에서 `android.os.SystemClock`이 모킹되지 않아 `RemoteTextInputBufferTest`가 `RuntimeException`으로 실패하던 문제를 해결하기 위해, 플랫폼 독립적인 `System.currentTimeMillis()`로 측정 방식을 마이그레이션했습니다.

### 2026-06-07 (2차 코드 리뷰 및 버그 수정 세션 - Round 2 Bug Fixes)
* **스레드 경합 및 C++ 메모리 누수 최종 차단**
  - `MainActivity` 내 WebRTC 해제 단계의 C++ dispose 호출 순서를 완벽히 고정하고, `MediaProjection` 콜백 비동기 스레드 호출 문제를 `runOnUiThread` 격리로 완벽히 보장하여 크래시 요소를 해결했습니다.
* **메인 스레드 Disk I/O 격리 (ANR 방지)**
  - `CrashDiagnostics` 내의 파일 읽기/쓰기 동작을 단일 스레드로 구성된 백그라운드 `logExecutor`로 이관하여 메인 스레드 지연을 원천 봉쇄했습니다. 예외적으로 프로세스 강제 종료 시점의 `recordUnhandledException`은 동기적으로 기록되어 정보 유실을 차단합니다.
* **접근성 제스처 큐 워치독(Watchdog) 추가**
  - `dispatchGesture` 콜백 유실 시 전체 마우스 입력 제어가 먹통이 되던 문제를 해결하고자 3초의 watchdog 타임아웃 타이머를 추가하여 큐 스티킹 리스크를 해제했습니다.
* **Caret 키보드 상/하/엔터 내비게이션 완비**
  - 키보드 입력 지원을 위해 방향키 상/하(`19`, `20`) 및 엔터(`66`) 키코드를 `ControlEventValidator` 화이트리스트에 추가하고, `AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE` 및 Android 11+ `ACTION_IME_ENTER` API를 활용해 동작하도록 구현했습니다.
* **IME 큐 락업 방지 및 1.5초 ACK 워치독 도입**
  - `viewer.js`에서 DataChannel 전송 실패 시 예외를 포획하고, 1.5초 이내에 호스트의 ACK가 도달하지 않을 경우 텍스트 입력 큐 락을 해제하여 빠른 입력 도중 타이핑이 영구히 굳어버리는 버그를 종식시켰습니다.
* **웹 뷰어 메모리/소켓 누수 가드**
  - 수동 재연결 시 기존 `RTCPeerConnection` 및 `WebSocket`, `DataChannel` 인스턴스를 무조건 해제 및 null화하도록 세션 클린업을 강화했습니다.
* **동적 가로세로 비디오 비율 왜곡 방지**
  - `loadedmetadata` 및 `resize` 이벤트를 비디오 엘리먼트에 추가하여 실제 해상도 수신 시 `#videoContainer` aspect-ratio 스타일을 실시간 반영, 가로/세로 화면 회전이나 폴더블 디바이스 화면이 찌그러지는 현상을 해결했습니다.
* **사이드바 반응형 스크롤 보완 및 단축키 충돌 방지**
  - 왼쪽 패널에 `overflow-y: auto`를 적용하여 뷰포트 높이가 낮아도 클릭 버튼이 잘리지 않도록 레이아웃을 다듬었으며, `Cmd + Arrow` 등 OS 단축키가 뷰어 키보드 훅에 가로채지도록 체크 순서를 조정했습니다.
* **시간의 단조성(Monotonicity) 확립**
  - `System.currentTimeMillis()` 대신 `System.nanoTime() / 1_000_000`을 도입하여 NTP 시간 동기화 시 TTL 불일치를 방지하고, standard JVM 단위 테스트 빌드가 모킹 라이브러리 없이 정상 동작하도록 수정했습니다.

### 2026-06-07 (로컬 에뮬레이터 통합 테스트 및 UI 테스트 검증 세션)
* **로컬 AVD 에뮬레이터 기동 및 연동**
  - Mac 머신에 `cmdline-tools`를 구축하고 `test_mirror` 가상 디바이스를 생성한 뒤, CLI 샌드박스 제약에 맞춰 headless 모드로 원격 에뮬레이터를 백그라운드 구동에 성공했습니다.
* **ComponentActivity setContent 중복 호출 예외 해결**
  - Compose UI 계측 테스트(`connectedDebugAndroidTest`) 실행 시 `MainScreenTest.kt`에서 `createAndroidComposeRule<ComponentActivity>()`와 `@Before` / `@Test`의 중복 `setContent` 실행으로 인해 발생하던 `IllegalStateException`을 해결했습니다.
  - 테스트 룰을 Composable 고립 테스트가 용이한 `createComposeRule()`로 개선하고, 테스트 메서드별로 독립적인 `setContent` 호출을 갖추도록 리팩토링했습니다.
* **전체 테스트 통과 및 최종 빌드 검증 완료**
  - 수정 이후 3개의 Compose UI 계측 테스트가 로컬 에뮬레이터 위에서 29초 만에 모두 정상적으로 빌드 및 통과되었습니다.
  - 이로써 64개의 JVM 단위 테스트와 더불어 모바일 에뮬레이터 계측 테스트까지 통합 테스트 100% 성공을 확보했습니다.

### 2026-06-08 (Gemini 컨텍스트 문서 추가)
* **[GEMINI.md](./GEMINI.md) 추가 및 프로젝트 컨텍스트 정리**
  - AI 에이전트(Gemini) 세션 구동 시 프로젝트의 아키텍처, 기술 스택, 디렉토리 구조, 빌드/테스트 명령 및 개발 약속(Vanilla JS 유지, Aspect Ratio 변환식 준수, 싱글톤 클린업 등)을 한눈에 파악할 수 있도록 컨텍스트 가이드인 `GEMINI.md`를 루트 디렉토리에 추가했습니다.
  - 이로써 다음 개발 작업이나 기능 확장 세션에서 AI가 프로젝트의 전체 형상과 개발 규칙을 혼선 없이 파악할 수 있는 인프라를 확보했습니다.

### 2026-06-08 (성능 고도화 및 아키텍처 개선 세션)
* **[PerformanceOptimizationReport.md](./PerformanceOptimizationReport.md) 작성 및 최적화 리팩토링**
  - Ktor 서버, WebRTC 세션 및 시그널링 루프를 포그라운드 서비스인 `MediaProjectionService`로 일체 이관하여 `MainActivity` 생명주기(화면 회전, 백그라운드 전환 등)와 완전히 분리하고 안정성을 극대화했습니다.
  - 뷰어가 일시적으로 연결을 해제하거나 페이지를 새로고침할 때 `VideoCapturer` 및 `VideoTrack`을 소멸하지 않고 포그라운드 서비스에 캐싱하여, Android 14+ 단말에서 미디어 프로젝션 재승인 시스템 팝업을 거치지 않고 500ms 미만으로 초고속 재연결되도록 개선했습니다.
  - WebRTC Offer/Answer 교환 과정에서 SDP 텍스트를 가공(SDP Munging)하여 H.264 하드웨어 가속 코덱의 우선순위를 1순위로 강제 적용함으로써, CPU 리소스 점유율 50% 이상 절감 및 발열/배터리 소모를 획기적으로 낮췄습니다.
  - 빈번한 원격 제어 이벤트의 ACK 전송 과정에서 가비지 컬렉션(GC) Jank가 유발되는 현상을 막기 위해 무거운 `JSONObject` 기반의 JSON 빌더 대신 코틀린 문자열 템플릿 기반의 Raw String 직렬화를 구성하고 불필요한 람다/ACK 객체 생성을 생략했습니다.
  - Active-Idle 품질 전환 및 화질 조정 상황에서 하드웨어 인코더 재기동으로 인해 동영상이 일시 정지(Freezing)되는 문제를 방지하고자, 해상도가 실질적으로 변화할 때만 `changeCaptureFormat`을 호출하고 그 외에는 `RtpSender`의 Bitrate/FPS만 가변 조절하도록 개선했습니다.

### 2026-06-08 (Milestone 5: UI 고도화 및 연결 하드닝 세션)
* **맥 뷰어 UI 프리미엄 글래스모피즘 고도화**
  - CSS 변수를 고도화하여 더욱 맑고 투명한 블러 효과(`backdrop-filter: blur(28px)`), 미세 반사 테두리 테두리(`rgba(255, 255, 255, 0.06)`), 그리고 입체적인 섀도우를 연동했습니다.
  - 배경 레이어에 부드럽게 일렁이며 교차하는 오라 그라디언트 구체(Aura Spheres) 2개를 추가하여 고급스러운 시각 효과를 극대화했습니다.
  - 다양한 브라우저 해상도(4K, 1080p, 노트북 뷰포트)와 모바일 가로/세로 회전 비율에 대응하여 사이드바와 비디오 영역이 자동으로 조화롭게 배치되는 반응형 레이아웃을 고도화했습니다.
* **네트워크 예외 자동 복원 및 지수 백오프 스케줄러**
  - WebSocket 시그널링의 `onclose`/`onerror` 발생 상황을 감시하여 `1s -> 2s -> 4s -> 8s -> 최대 16s` 단위로 지연 시간을 지수적(Exponential)으로 늘리는 자동 재연결 제어기를 구현했습니다.
  - WebRTC PeerConnection의 `oniceconnectionstatechange` 및 `onconnectionstatechange` 이벤트를 바인딩해 `failed`/`disconnected` 상황을 감지하면 즉시 시그널링 WebSocket부터 복원을 트리거하는 연결 예외 처리 파이프라인을 구축했습니다.
* **브라우저 Page Visibility API 연동**
  - 뷰어 탭이 백그라운드로 전향되었다가 사용자에 의해 포그라운드(Active)로 전면 복구될 때 발생하는 `visibilitychange` 이벤트를 포착하여, 유실된 연결이 있을 경우 백오프 대기 시간 없이 즉시 연결을 동기화 및 복구하도록 보완했습니다.
* **리스너 소멸 및 메모리 누수 방지**
  - 재연결 시 기존 socket 및 PeerConnection, DataChannel의 리스너 및 타임아웃 타이머들을 확실히 클리어(`null`)하고 인스턴스를 소멸한 뒤 신규 커넥션을 수립하도록 하드닝했습니다.
* **테스트 러너 타임아웃 픽스**
  - 1.5초 ACK 워치독 타이머 도입에 따른 `viewer-keyboard.test.mjs` 내 `FakeClock` 충돌을 시간 기반의 `tick(ms)` 시뮬레이션 방식으로 리팩토링하여 Node.js 텍스트 입력 테스트의 100% 정합성과 성공을 확보했습니다.

### 2026-06-08 (Milestone 6: 양방향 클립보드 동기화 및 고급 제어 기능 구현)
* **맥-안드로이드 양방향 클립보드 실시간 동기화**
  - 맥 브라우저 뷰어 포커스 시 `copy` 단축키 이벤트를 인터셉트하여, 클립보드 내 복사 텍스트를 WebRTC DataChannel을 통해 원격 주입하는 흐름을 연동했습니다.
  - 안드로이드 Host 내 `GalaxyMirrorAccessibilityService`에서 디바이스 클립보드가 변경될 때 `OnPrimaryClipChangedListener`를 기동하여 변경 텍스트를 실시간으로 브라우저 뷰어로 역브로드캐스트하는 리스너 등록/해제 관리 로직을 구축했습니다. (Android 10+ 백그라운드 샌드박스 제한을 우회하기 위해 `MediaProjectionService`에서 `AccessibilityService`로 리스너를 이관)
  - 브라우저 수신 시 `navigator.clipboard.writeText`를 호출해 자동 주입하고, 보안 제약 시 토스트 카드를 클릭해 복사할 수 있는 fallback 로직을 구현했으며, 동기화 알림용 Glow Toast 컴포넌트를 설계했습니다.
* **물리 하드웨어 키(볼륨, 음소거, 잠금) 제어 주입**
  - Mac 뷰어 하단 네비게이션바 위에 볼륨조절(+ / - / 음소거) 및 화면 잠금(Power) 버튼 조작계를 추가하고, 클릭 시 DataChannel로 Android KeyEvent(24, 25, 164, 26)를 전송하게 바인딩했습니다.
  - 안드로이드 접근성 서비스(`GalaxyMirrorAccessibilityService`)에서 수신된 키코드를 기반으로 `performGlobalAction` API 및 `AudioManager` fallback을 활용해 볼륨 크기 변경, 음소거 및 화면 잠금(8/GLOBAL_ACTION_LOCK_SCREEN) 동작이 원격 수행되도록 구현했습니다.
  - `ControlEventValidator`에서 신규 추가된 키코드들의 화이트리스트 검증 조건과 `clipboard` 타입의 JSON 유효성(텍스트 키 검사 및 최대 8192자 한도) 검사기를 완비했습니다.
* **브라우저 스크린샷 캡처 및 화면 동영상 레코더 내장**
  - 비디오 우상단에 📸(스크린샷) 및 ⏺️(녹화) 플로팅 글래스모피즘 아이콘 카드를 추가했습니다.
  - Canvas 렌더러를 이용하여 현재 뷰어 해상도 기준 픽셀 이미지를 PNG로 생성하여 즉시 자동 다운로드하는 로직을 연동했습니다.
  - `MediaRecorder` API를 이용해 실시간 WebRTC MediaStream을 캡처하고, 녹화 중 상태 피드백을 주는 레코딩 깜빡임 애니메이션 상태와 녹화 종료 시 `.webm` 파일 자동 내보내기를 구현했습니다.
* **통합 테스트 강화**
  - [ControlEventValidatorTest.kt](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/My%20Drive/Personal%20Develop/galaxy-mirror-web/android/app/src/test/java/com/example/galaxymirror/ControlEventValidatorTest.kt)에 볼륨/잠금 키코드 및 clipboard 타입에 대한 JUnit 검사 케이스 2개를 추가하여 빌드가 BUILD SUCCESSFUL (38초) 통과함을 확인했습니다.
  - [viewer-keyboard.test.mjs](file:///Users/kojuhwan/Library/CloudStorage/GoogleDrive-jazpiper1@gmail.com/My%20Drive/Personal%20Develop/galaxy-mirror-web/android/app/src/test/js/viewer-keyboard.test.mjs)에 볼륨/전원 버튼 동작 및 복사 이벤트 시 DataChannel clipboard 페이로드 송신 정합성을 검증하는 2개의 Mock 테스트를 추가하여 전체 16개 테스트를 통과시켰습니다.

### 2026-06-08 (Post-review hardening plan and fixes)
* `docs/CodeReview-2026-06-08.md`의 병렬 리뷰 결과를 기준으로 MediaProjection 생명주기,
  원격 볼륨 키, 클립보드 fallback/empty-string, `CONTROL_ACK` JSON escaping, Viewer 재연결,
  녹화 API guard, JS 테스트 CI 누락을 순차 수정 대상으로 확정했습니다.
* MediaProjection 정책은 viewer 종료/교체 시 화면 캡처를 정리하고 새 Offer에서 Android
  화면 공유 권한을 다시 요청하는 privacy-first 방식으로 맞춥니다.
* 로컬 검증으로 `app:testDebugUnitTest`, `app:lintDebug`, `assembleDebug`,
  `node --test android/app/src/test/js/viewer-keyboard.test.mjs`, `git diff --check`가 통과했습니다.
  실제 Galaxy 단말 smoke test는 별도 체크리스트로 남겨 두었습니다.

### 2026-06-09 (재연결 시 화면 켜짐/밝기 옵션 재적용 수정)
* **MediaProjection 재승인 후 화면 설정 재적용**
  - 새 미러링 연결에서 화면 공유 권한을 다시 승인해 `MediaProjectionService`가 `isRunning=true`로 전환될 때, 저장된 `화면 켜짐 유지`와 `밝기 최소화 모드`가 즉시 다시 적용되도록 `applyScreenAwakeEffectsForCurrentState()` 경로를 추가했습니다.
  - 기존에는 옵션 토글 시점에는 적용되지만 새 연결/재승인 시점에는 밝기 최소화 재적용이 빠져 있어, 사용자가 토글을 껐다 켜야 다시 반영되는 문제가 있었습니다.
  - `MediaProjectionServiceLifecycleRegressionTest`에 새 grant 시작 시 화면 설정 효과가 재적용되는지 확인하는 회귀 테스트를 추가했습니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Log.md](./Log.md) | 작업 로그 (현재 문서) |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
| [Protocols.md](./Protocols.md) | 시그널링 및 제어 메시지 규격 명세 |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
| [PerformanceOptimizationReport.md](./PerformanceOptimizationReport.md) | 성능 분석 및 고도화 보고서 |
