---
project: galaxy-mirror-web
type: Specification
related: [Dashboard.md, Protocols.md, Handoff.md]
updated: 2026-05-26
---

# 📐 Galaxy Mirror Web: 2D 터치 좌표 변환 및 제스처 주입 공식 명세서

본 문서는 맥북 브라우저 내 가변 크기를 가진 반응형 HTML5 뷰포트 영역에서 획득한 마우스 이벤트 좌표를 안드로이드 갤럭시 스마트폰의 물리 해상도 2D 좌표로 왜곡 없이 1:1 환산 매핑하기 위한 수학적 공식 및 접근성 서비스(Accessibility Service) 내 제스처 모델링 명세입니다.

---

## 1. 레터박스/필러박스 왜곡 방지 좌표 보정 이론

맥북 브라우저에 표시되는 HTML5 `<video>` 엘리먼트는 브라우저 크기 조정이나 좌우 여백 레이아웃에 의해 비율 왜곡을 방지하기 위해 보통 `object-fit: contain` 스타일로 렌더링됩니다. 이로 인해 브라우저의 비디오 프레임 외곽에 **레터박스(상하 여백)** 또는 **필러박스(좌우 여백)**가 발생합니다.

정확한 마우스 원격 제어를 위해서는 여백 영역을 제외하고 **순수 비디오 화면 영역 내에서의 상대적 2D 백분율(%)**을 실시간 연산해야 합니다.

```
Pillarbox (좌우 여백 발생 예시)       Letterbox (상하 여백 발생 예시)
┌────────────────────────────────┐   ┌────────────────────────────────┐
│   │┌──────────────────────┐│   │   │        상단 여백 (Margin)      │   │
│여 ││                      ││여 │   ├────────────────────────────────┤
│백 ││   순수 비디오 영역   ││백 │   │                                │   │
│   ││   (Pure Video Area)  ││   │   │        순수 비디오 영역        │   │
│   ││                      ││   │   │                                │   │
│(M)││                      ││(M)│   ├────────────────────────────────┤
│   │└──────────────────────┘│   │   │        하단 여백 (Margin)      │   │
└────────────────────────────────┘   └────────────────────────────────┘
```

---

## 2. 좌표 변환 수학 공식

### 2.1 정의 및 변수 선언

* **디바이스 원본 해상도 (안드로이드)**
  * $W_{phone}$ : 안드로이드 단말기 가로 해상도 (예: 1080)
  * $H_{phone}$ : 안드로이드 단말기 세로 해상도 (예: 2400)
  * $R_v = W_{phone} / H_{phone}$ : 비디오 스트림 원본 종횡비 (Aspect Ratio)

* **브라우저 비디오 엘리먼트 스펙 (맥북)**
  * $W_e$ : HTML5 `<video>` 엘리먼트의 실제 렌더링 가로 크기
  * $H_e$ : HTML5 `<video>` 엘리먼트의 실제 렌더링 세로 크기
  * $R_e = W_e / H_e$ : 비디오 엘리먼트 종횡비
  * $(X_{off}, Y_{off})$ : 비디오 엘리먼트의 좌상단 기준(Bounding Box Offset) 마우스 클릭 원시 오프셋 좌표
    * $X_{off} = X_{mouse} - Left_{elem}$
    * $Y_{off} = Y_{mouse} - Top_{elem}$

---

### 2.2 종횡비 조건별 보정 좌표 연산 공식

비디오 엘리먼트의 비율($R_e$)과 스트림 비율($R_v$)을 비교하여 여백 영역의 오프셋을 상쇄합니다.

#### ① 필러박스(Pillarbox) 발생 조건 : $R_e > R_v$ (좌우 여백)
세로 방향은 여백이 없고, 좌우 가로 영역에 여백이 대칭 분배됩니다.
* 실제 렌더링된 비디오 너비 : $W_{act} = H_e \times R_v$
* 한쪽 여백 너비 : $W_{margin} = \frac{W_e - W_{act}}{2}$

$$X_{\%} = \frac{X_{off} - W_{margin}}{W_{act}} \times 100$$

$$Y_{\%} = \frac{Y_{off}}{H_e} \times 100$$

#### ② 레터박스(Letterbox) 발생 조건 : $R_e \le R_v$ (상하 여백)
가로 방향은 여백이 없고, 상하 세로 영역에 여백이 대칭 분배됩니다.
* 실제 렌더링된 비디오 높이 : $H_{act} = \frac{W_e}{R_v}$
* 한쪽 여백 높이 : $H_{margin} = \frac{H_e - H_{act}}{2}$

$$X_{\%} = \frac{X_{off}}{W_e} \times 100$$

$$Y_{\%} = \frac{Y_{off} - H_{margin}}{H_{act}} \times 100$$

---

## 3. 안드로이드 단말 내 제스처 주입 (Touch Injection)

안드로이드 Host 앱은 DataChannel로부터 수신한 백분율 좌표 $(X_{\%}, Y_{\%})$를 기기의 물리 해상도 좌표로 복원합니다.

$$X_{target} = \frac{X_{\%}}{100} \times W_{phone}$$

$$Y_{target} = \frac{Y_{\%}}{100} \times H_{phone}$$

### 3.1 AccessibilityService 제스처 생성 모델링
안드로이드의 `AccessibilityService.dispatchGesture()`는 펜/손가락 터치의 이동 궤적을 묘사하는 `GestureDescription.Builder`를 통해 구현됩니다.

#### ① 단일 클릭 (Tap / Click Simulation)
특정 시간 동안 누르고 떼는 최소 경로의 스트로크를 주입합니다.
* **지속 시간**: 80ms~100ms 권장
* **경로(Path)**: $X_{target}, Y_{target}$ 단일 좌표에 고정

```kotlin
val path = Path().apply {
    moveTo(xTarget, yTarget) // 클릭 타겟 지점으로 이동
}
// 0ms 딜레이 후 100ms간 탭 유지
val stroke = StrokeDescription(path, 0, 100)
val gesture = GestureDescription.Builder().addStroke(stroke).build()
dispatchGesture(gesture, null, null)
```

#### ② 화면 드래그 및 스크롤 (Drag / Scroll Simulation)
사용자가 마우스를 누른 채 이동시킬 때 (`TOUCH_DOWN` $\rightarrow$ 연속 `TOUCH_MOVE` $\rightarrow$ `TOUCH_UP`), 이 좌표 궤적을 메모리 큐(Queue)에 적재한 뒤 스와이프 제스처 경로로 변환합니다.
* **지속 시간**: 200ms ~ 500ms (드래그 길이에 비례)
* **경로(Path)**: 시점 좌표부터 종점 좌표까지 `lineTo` 메소드로 부드럽게 이어진 직선 또는 곡선

```kotlin
val path = Path().apply {
    moveTo(startX, startY)
    // 드래그 중간 궤적 주입
    for (point in dragPoints) {
        lineTo(point.x, point.y)
    }
    lineTo(endX, endY)
}
val stroke = StrokeDescription(path, 0, 300) // 300ms 스와이프
val gesture = GestureDescription.Builder().addStroke(stroke).build()
dispatchGesture(gesture, null, null)
```

### 3.2 텍스트 입력 주입 (Keyboard Text Injection)

텍스트 입력은 좌표 변환을 거치지 않습니다. 브라우저는 비디오 화면 클릭 후 숨은 keyboard sink에 포커스를 유지하고, 일반 키 입력을 `text` commit/deleteBackward 이벤트로 전송합니다. 한글 IME처럼 조합이 필요한 입력은 `keydown`의 중간 자모를 버리고 `compositionend`에서 완성된 문자열만 전송합니다. 빠른 타이핑은 35ms 또는 최대 64자 단위로 짧게 묶어 AccessibilityService가 stale한 기존 문자열을 연속으로 덮어쓰는 위험을 줄입니다. Android AccessibilityService는 `rootInActiveWindow.findFocus(FOCUS_INPUT)`으로 현재 입력창을 찾습니다.

입력창이 editable이면 기존 문자열과 `textSelectionStart`/`textSelectionEnd` 범위 기준으로 다음 문자열을 만든 뒤 `ACTION_SET_TEXT`를 수행합니다. 같은 입력창에 대한 연속 입력은 Android Host가 마지막 적용 텍스트/커서를 내부 버퍼에 보관해 이어 붙입니다. 이는 `ACTION_SET_TEXT` 성공 직후 일부 앱의 AccessibilityNodeInfo가 이전 문자열을 잠시 반환하면서 빠른 한글 입력을 덮어쓰는 현상을 줄이기 위한 보정입니다. selection 정보를 얻을 수 없는 앱에서는 문자열 끝 기준으로 입력하거나 삭제합니다. 이 기능 때문에 접근성 설정은 gesture 권한뿐 아니라 `canRetrieveWindowContent`도 필요합니다.

Mac Viewer 하단의 `최근 앱`, `홈`, `뒤로` 버튼은 좌표 변환을 거치지 않고 DataChannel `key` 이벤트만 전송합니다. 각각 Android `KEYCODE_APP_SWITCH(187)`, `KEYCODE_HOME(3)`, `KEYCODE_BACK(4)`로 매핑됩니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Protocols.md](./Protocols.md) | 시그널링 & 제어 메시지 규격 명세 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
