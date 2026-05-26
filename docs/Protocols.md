---
project: galaxy-mirror-web
type: Specification
related: [Dashboard.md, Coordinates.md, Handoff.md]
updated: 2026-05-26
---

# 🔌 Galaxy Mirror Web: 시그널링 & 제어 입력 프로토콜 상세 명세서

본 문서는 맥북 브라우저 클라이언트와 갤럭시 안드로이드 호스트가 WebRTC 연결을 초기 수립(Signaling)하고, 수립된 연결을 통해 실시간 마우스/키보드 원격 제어 이벤트를 송수신(DataChannel Control)하기 위한 JSON 통신 프로토콜 상세 명세입니다.

---

## 1. Ktor 1:1 WebSocket 시그널링 규격 (Signaling Protocol)

Tailscale 가상 메시 VPN 내부에서 맥북 브라우저가 안드로이드 내장 Ktor 웹서버(`ws://[MagicDNS-Galaxy-Host]:8080/signaling`)로 직접 소켓 연결을 시도합니다. 1:1 전용 연결이므로 방(Room) 관리 없이 단일 통신 파이프라인으로 작동합니다.

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

---

## 2. WebRTC DataChannel 제어 입력 프로토콜 (Control Protocol)

WebRTC PeerConnection의 DataChannel(레이턴시 최소화를 위해 `ordered: true`, `maxRetransmits: 0` 설정 권장)을 열어 실시간 좌표 및 텍스트 입력을 전송합니다.

### 2.1 실시간 터치 제스처 (Mouse/Touch Control)
뷰포트 크기에 무관하도록 마우스 클릭 및 드래그 좌표는 소수점 단위의 **백분율(%)**로 인코딩되어 전송됩니다.

#### ① 단발성 터치 (`TOUCH_DOWN`, `TOUCH_UP`)
마우스 좌클릭을 누르고 뗄 때 발생합니다.
* **JSON 패킷 예시 (`TOUCH_DOWN`):**
```json
{
  "action": "TOUCH_DOWN",
  "pointerId": 0,
  "x": 45.28,
  "y": 72.15
}
```

#### ② 연속 드래그 (`TOUCH_MOVE`)
마우스를 드래그하여 스와이프하거나 화면을 스크롤할 때 발생합니다.
* **JSON 패킷 예시 (`TOUCH_MOVE`):**
```json
{
  "action": "TOUCH_MOVE",
  "pointerId": 0,
  "x": 46.12,
  "y": 71.85
}
```

### 2.2 실시간 키보드 타이핑 (`KEYBOARD_INPUT`)
한글 자소 분리 깨짐 현상을 원천 방지하기 위해 단일 문자가 아닌 **완성된 문자열(String)** 단위로 전송합니다.
* **입력 원리**: 맥북 뷰어 화면 내 숨겨진 `input` 태그에 포커스를 준 뒤 사용자가 입력을 마치거나 조합이 끝나는 시점에 텍스트 버퍼를 전송합니다. 안드로이드 호스트는 활성화된 텍스트 필드의 `AccessibilityNodeInfo`를 감지하여 텍스트를 인젝션합니다.
* **JSON 패킷 예시:**
```json
{
  "action": "KEYBOARD_INPUT",
  "text": "안녕하세요 세계"
}
```

---

## 🔗 Related Documents
| 문서 | 관계 |
| :--- | :--- |
| [Dashboard.md](./Dashboard.md) | 프로젝트 허브 (상위 색인) |
| [Coordinates.md](./Coordinates.md) | 터치 좌표 변환 및 제스처 스트로크 공식 명세 |
| [Handoff.md](./Handoff.md) | 핸드오프 및 태스크 보드 |
