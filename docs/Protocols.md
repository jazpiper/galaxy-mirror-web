---
project: galaxy-mirror-web
type: Specification
related: [Dashboard.md, Coordinates.md, Handoff.md]
updated: 2026-05-27
---

# 🔌 Android Mirror Web: 시그널링 & 제어 입력 프로토콜 상세 명세서

본 문서는 맥북 브라우저 클라이언트와 Android Host가 WebRTC 연결을 초기 수립(Signaling)하고, 수립된 연결을 통해 실시간 마우스/키보드 원격 제어 이벤트를 송수신(DataChannel Control)하기 위한 JSON 통신 프로토콜 상세 명세입니다.

---

## 1. Ktor 1:1 WebSocket 시그널링 규격 (Signaling Protocol)

Tailscale 가상 메시 VPN 내부에서 맥북 브라우저가 Android 내장 Ktor 웹서버(`ws://[MagicDNS-Android-Host]:8080/signaling?token=...`)로 직접 소켓 연결을 시도합니다. 1:1 전용 연결이므로 방(Room) 관리 없이 단일 통신 파이프라인으로 작동합니다.

### 1.0 Viewer 접근 토큰
Android Host는 앱 시작 시 로컬 저장소에 viewer 접근 토큰을 만들고, 메인 화면의 접속 주소에 `?token=...` 형태로 표시합니다. 브라우저 정적 리소스와 `/status`는 진단 편의를 위해 공개되지만, 원격 입력/디버그/앱 실행/화질 변경에 쓰이는 API와 `/signaling` WebSocket은 토큰이 필요합니다.

* HTTP API: `X-Android-Mirror-Token` 헤더 또는 `token` query parameter 중 하나가 Android Host의 현재 토큰과 일치해야 합니다.
* WebSocket: `/signaling?token=...` query parameter가 필요합니다.
* 토큰이 없거나 맞지 않으면 HTTP API는 `401 {"ok":false,"error":"UNAUTHORIZED_VIEWER"}`를 반환하고, WebSocket은 policy violation close reason `UNAUTHORIZED_VIEWER`로 종료됩니다.

### 1.1 WebRTC SDP Offer (맥 브라우저 $\rightarrow$ 안드로이드)
맥 브라우저가 WebRTC PeerConnection을 생성하고 로컬 미디어 디스크립션(SDP)을 생성해 전송합니다.
* **JSON 패킷 예시:**
```json
{
  "type": "OFFER",
  "payload": {
    "sdp": "v=0\r\no=- 4611731400... (SDP 데이터 생략)...",
    "type": "offer"
  }
}
```

### 1.2 WebRTC SDP Answer (안드로이드 $\rightarrow$ 맥 브라우저)
안드로이드가 Offer 수신 후 MediaProjection 화면 스트림을 바인딩하고 PeerConnection을 통해 응답 SDP를 작성하여 반환합니다.
* **JSON 패킷 예시:**
```json
{
  "type": "ANSWER",
  "payload": {
    "sdp": "v=0\r\no=- 8593821034... (SDP 데이터 생략)...",
    "type": "answer"
  }
}
```

### 1.3 ICE Candidate (상호 교환)
상호 1:1 다이렉트 및 릴레이 연결 통로를 찾기 위해 ICE 후보자 패킷을 수시로 송수신합니다.
* **JSON 패킷 예시:**
```json
{
  "type": "ICE_CANDIDATE",
  "payload": {
    "candidate": "candidate:842163049 1 udp 16777215 192.168.1.100 50352 typ host...",
    "sdpMid": "0",
    "sdpMLineIndex": 0
  }
}
```

### 1.4 Android 상태 알림 (`STATUS`)
Android Host는 화면 캡처 권한/서비스 준비 상태, 접근성 입력 준비 상태, Offer 대기 상태를 브라우저에 알려줍니다. 브라우저는 이 패킷을 통해 WebRTC 스트림, 제어 채널, 접근성 입력 상태를 분리해서 표시합니다.

* **JSON 패킷 예시:**
```json
{
  "type": "STATUS",
  "payload": {
    "captureReady": false,
    "accessibilityReady": true,
    "keepScreenAwake": false,
    "brightnessMinimizeEnabled": true,
    "brightnessWriteSettingsReady": true,
    "streamQuality": {
      "selectedMode": "AUTO",
      "selectedLabel": "자동",
      "networkTransport": "WIFI",
      "networkLabel": "Wi-Fi",
      "effectiveMode": "HIGH",
      "effectiveLabel": "고화질",
      "width": 1080,
      "height": 2400,
      "fps": 30,
      "maxBitrateBps": 3000000,
      "activityState": "ACTIVE"
    },
    "message": "WAITING_FOR_SCREEN_CAPTURE"
  }
}
```

현재 확인된 `message` 값은 `SIGNALING_CONNECTED`, `WAITING_FOR_SCREEN_CAPTURE`, `SCREEN_CAPTURE_READY`, `SCREEN_CAPTURE_PERMISSION_DENIED`, `SCREEN_CAPTURE_NOT_READY`, `SCREEN_CAPTURE_REAUTH_REQUIRED`, `CONTROL_CHANNEL_ACCEPTED`, `STATUS_TICK`입니다. `STATUS_TICK`은 연결 중 접근성 활성화 여부, 밝기 최소화 권한 상태, 스트림 화질 상태가 브라우저 UI에 stale하게 남지 않도록 주기적으로 전송됩니다.

화면 켜짐 유지와 밝기 최소화 작업에서는 다음 viewer-facing 상태명도 사용합니다.

| message | 의미 | Mac Viewer 동작 |
| :--- | :--- | :--- |
| `SCREEN_CAPTURE_REAUTH_REQUIRED` | Android 화면 캡처 토큰이 더 이상 유효하지 않아 사용자의 화면 공유 재승인이 필요함 | 재승인 필요 상태와 Android에서 다시 승인하라는 안내 표시 |
| `PROJECTION_STOPPED_LOCKED` | Android 화면 잠금, 화면 꺼짐, 또는 시스템 중단으로 MediaProjection이 정지됨. 현재 Viewer 호환용으로 인식하며 Android Host는 우선 `SCREEN_CAPTURE_REAUTH_REQUIRED`를 송신함 | 잠금으로 중단 상태와 잠금 해제/재승인 안내 표시 |

Android 15 QPR1+ 계열에서는 단말 잠금 또는 화면 꺼짐이 MediaProjection 중단으로 이어질 수 있습니다. keep-awake 토글은 자동 화면 꺼짐을 줄이는 보조 장치일 뿐이며, 잠금으로 중단된 세션은 기존 projection token을 재사용하지 않고 Android 기기에서 화면 공유 권한을 다시 승인해야 합니다.

밝기 최소화 모드는 Android 로컬 화면을 검은 overlay로 덮지 않고, 미러링 중 시스템 밝기를 최저값으로 낮추는 사용자 보호 기능입니다. 이 기능은 `WRITE_SETTINGS` 선언과 사용자의 시스템 설정 수정 허용이 필요하며, 연결 해제나 미러링 비활성 상태에서는 저장해 둔 이전 밝기/밝기 모드로 복원합니다.

---

## 2. WebRTC DataChannel 제어 입력 프로토콜 (Control Protocol)

WebRTC PeerConnection의 `control` DataChannel은 `ordered: true` 기반의 reliable 채널로 열어 실시간 좌표, 제한된 전역 키 입력, 텍스트 입력을 전송합니다. 초기 저지연 설정처럼 `maxRetransmits: 0`을 사용하면 빠른 텍스트 입력 중 메시지 유실 가능성이 있어 현재는 사용하지 않습니다.

### 2.1 실시간 터치 제스처 (Mouse/Touch Control)
뷰포트 크기에 무관하도록 마우스 클릭 및 드래그 좌표는 `0.0`부터 `1.0`까지의 정규화 좌표로 인코딩되어 전송됩니다. 브라우저 뷰어는 레터박스/필러박스 바깥 여백 클릭을 버리고 실제 영상 영역 안의 좌표만 전송합니다.

#### ① 단발성 터치 (`tap`)
마우스 좌클릭을 누른 뒤 드래그 임계값 이하에서 뗄 때 발생합니다.
* **JSON 패킷 예시 (`tap`):**
```json
{
  "type": "tap",
  "x": 0.4528,
  "y": 0.7215
}
```

#### ② 연속 드래그 (`swipe`)
마우스를 드래그하여 스와이프하거나 화면을 스크롤할 때 발생합니다.
* **JSON 패킷 예시 (`swipe`):**
```json
{
  "type": "swipe",
  "x1": 0.4612,
  "y1": 0.7185,
  "x2": 0.4612,
  "y2": 0.2185,
  "duration": 300
}
```

### 2.2 제한된 키 제어 (`key`)
Android 전역 액션에 매핑되는 제한된 키코드만 전송합니다. 브라우저에서는 `Escape`가 Android Back으로, `Home`이 Android Home으로, `F1`이 Recent apps로 매핑됩니다. Mac Viewer 하단의 `최근 앱`, `홈`, `뒤로` 버튼도 동일한 `key` payload를 전송합니다.
* **JSON 패킷 예시:**
```json
{
  "type": "key",
  "keyCode": 4
}
```

허용 키코드는 `4`(Back), `3`(Home), `187`(Recent apps)입니다.

### 2.3 텍스트 입력 (`text`)
Mac 키보드에서 입력한 일반 문자는 DataChannel `text` 이벤트로 Android Host에 전달됩니다. 브라우저 뷰어는 숨은 `textarea`를 keyboard sink로 사용해 macOS/Chrome IME 조합을 먼저 완료하고, 한글처럼 조합형 입력은 `compositionend` 이후 완성된 문자열만 전송합니다. 빠른 타이핑 중 Accessibility text tree가 stale해지는 것을 줄이기 위해 일반 텍스트는 짧은 35ms 윈도우 또는 최대 64자 단위로 묶어서 전송합니다. Android Host는 현재 포커스된 editable AccessibilityNode를 찾고, selection 범위 기준으로 `ACTION_SET_TEXT`를 수행합니다. 같은 입력창에 연속 텍스트 이벤트가 들어오면 방금 적용한 텍스트와 커서 위치를 내부 버퍼로 이어받아, Android 접근성 스냅샷이 한 박자 늦게 갱신되어도 이전 글자 위에 덮어쓰지 않습니다. selection 정보를 얻을 수 없는 앱에서는 문자열 끝 기준으로 입력/삭제합니다.

* **문자 입력 예시:**
```json
{
  "type": "text",
  "action": "commit",
  "text": "hello",
  "seq": 42
}
```

* **Backspace 삭제 예시:**
```json
{
  "type": "text",
  "action": "deleteBackward",
  "count": 1,
  "seq": 43
}
```

브라우저 비디오 화면을 클릭하면 keyboard sink가 포커스를 받아 일반 문자와 Enter는 `commit`, Backspace는 `deleteBackward`로 전송됩니다. IME 조합 중 Backspace와 조합 중간 자모는 Mac 로컬 IME에 맡기고 Android로 전송하지 않습니다. 텍스트 payload에는 `seq`를 붙이며, 브라우저는 Android Host의 ACK를 받은 뒤 다음 텍스트 payload를 전송합니다. 연결이 끊기면 미응답 텍스트 큐는 폐기하고 새 DataChannel에서 새 sequence로 다시 시작합니다.

### 2.4 제어 입력 ACK (`CONTROL_ACK`)
Android Host는 `seq`가 있는 control payload를 처리한 뒤 같은 DataChannel로 ACK를 반환합니다. 현재 브라우저는 텍스트 입력에만 ACK 기반 직렬화를 적용하고, tap/swipe/key는 기존처럼 즉시 전송합니다.

* **응답 예시:**
```json
{
  "type": "CONTROL_ACK",
  "payload": {
    "seq": 42,
    "eventType": "text",
    "applied": true,
    "message": "TEXT_COMMIT_APPLIED"
  }
}
```

`applied=false`이면 Android Host가 이벤트를 거부했거나 접근성 적용에 실패한 것입니다. 대표 메시지는 `CONTROL_EVENT_REJECTED`, `TEXT_COMMIT_APPLIED`, `TEXT_DELETE_APPLIED`, `KEY_APPLIED`, `GESTURE_DISPATCH_REQUESTED`, `CONTROL_EVENT_EXCEPTION`입니다.

---

## 3. Android 앱 바로가기 HTTP API

자주 쓰는 앱 관리는 Android Host 앱에서만 수행하고, Mac 뷰어는 저장된 바로가기 목록을 조회하거나 실행 요청만 보냅니다. 모든 요청은 viewer 접근 토큰이 필요합니다.

### 3.1 즐겨찾기 앱 목록 조회 (`GET /apps/favorites`)

* **응답 예시:**
```json
{
  "apps": [
    {
      "packageName": "com.example.chat",
      "label": "Chat"
    }
  ]
}
```

### 3.2 즐겨찾기 앱 실행 (`POST /apps/launch`)

* **요청 예시:**
```json
{
  "packageName": "com.example.chat"
}
```

* **성공 응답:**
```json
{
  "ok": true
}
```

실행할 앱이 삭제되었거나 launch intent가 없으면 `404`와 `{"ok":false,"error":"APP_NOT_FOUND"}`를 반환합니다. 요청 JSON에 유효한 `packageName`이 없으면 `400`과 `{"ok":false,"error":"INVALID_PACKAGE"}`를 반환합니다.

---

## 4. WebRTC 스트림 화질 HTTP API

스트림 화질은 Android Host가 저장한 선택 모드와 현재 네트워크 종류를 조합해 결정합니다. 기본 선택값은 `AUTO`이며, `AUTO`는 Wi-Fi 또는 Ethernet에서 `HIGH`, 4G/5G 등 cellular 네트워크와 기타 네트워크에서 `STANDARD`로 해석됩니다. `DATA_SAVER`, `STANDARD`, `HIGH`를 직접 선택하면 네트워크 종류와 무관하게 해당 프로필을 사용합니다. 모든 요청은 viewer 접근 토큰이 필요합니다.

| mode | 캡처 해상도/FPS | 송신 bitrate 상한 | 용도 |
| :--- | :--- | :--- | :--- |
| `DATA_SAVER` | `540x1200 @ 12fps` | `600000bps` | 모바일 데이터 절약 |
| `STANDARD` | `720x1600 @ 15fps` | `1200000bps` | 4G/5G 기본값 |
| `HIGH` | `1080x2400 @ 30fps` | `3000000bps` | Wi-Fi 기본값 |

### 4.1 현재 화질 조회 (`GET /stream/quality`)

* **응답 예시:**
```json
{
  "selectedMode": "AUTO",
  "selectedLabel": "자동",
  "networkTransport": "WIFI",
  "networkLabel": "Wi-Fi",
  "effectiveMode": "HIGH",
  "effectiveLabel": "고화질",
  "width": 1080,
  "height": 2400,
  "fps": 30,
  "maxBitrateBps": 3000000,
  "activityState": "ACTIVE"
}
```

### 4.2 화질 선택 변경 (`POST /stream/quality`)

* **요청 예시:**
```json
{
  "mode": "STANDARD"
}
```

유효한 `mode`는 `AUTO`, `DATA_SAVER`, `STANDARD`, `HIGH`입니다. 요청이 성공하면 변경 후의 현재 화질 상태 JSON을 반환합니다. 유효하지 않은 값은 `400`과 `{"ok":false,"error":"INVALID_STREAM_QUALITY_MODE"}`를 반환합니다.

Android Host는 WebRTC 세션 시작 시 선택된 프로필의 해상도/FPS로 `ScreenCapturerAndroid.startCapture()`를 호출하고, 비디오 track 추가 후 `RtpSender` encoding parameter에 `maxBitrateBps`, `maxFramerate`, `scaleResolutionDownBy`를 적용합니다. 이미 미러링 중일 때 화질을 바꾸면 `VideoCapturer.changeCaptureFormat()`과 sender parameter 갱신을 best-effort로 수행합니다. Viewer 입력이 일정 시간 없으면 `activityState=IDLE`로 전환하며, 데이터 사용량을 줄이기 위해 현재 선택 프로필을 더 낮은 해상도/FPS/bitrate로 일시 제한합니다. 새 tap/swipe/key/text 이벤트가 들어오면 `activityState=ACTIVE`로 복귀합니다.

---

## 5. MediaProjection 재연결 정책

Android 14+ 계열에서는 화면 공유 승인 결과 Intent를 같은 projection 세션 재생성에 재사용할 수 없습니다. 따라서 viewer WebSocket이 닫히거나 새 viewer 세션이 기존 세션을 교체하면 Android Host는 WebRTC capturer와 MediaProjection foreground service를 정리하고 저장된 projection Intent를 폐기합니다. 이후 새 Offer가 들어오면 `WAITING_FOR_SCREEN_CAPTURE` 상태를 보내고 Android 기기에서 화면 공유 권한을 다시 승인받은 뒤 pending offer를 재개합니다.

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
