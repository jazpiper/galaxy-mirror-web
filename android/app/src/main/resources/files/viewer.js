// 🌌 Galaxy Mirror: WebRTC & Signaling Client
const remoteVideo = document.getElementById('remoteVideo');
const connectBtn = document.getElementById('connectBtn');
const wsIndicator = document.getElementById('wsIndicator');
const wsStatus = document.getElementById('wsStatus');
const rtcStatus = document.getElementById('rtcStatus');
const controlStatus = document.getElementById('controlStatus');
const logBox = document.getElementById('logBox');

let socket = null;
let peerConnection = null;
let dataChannel = null;

const rtcConfig = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' } // Tailscale 릴레이가 있어서 STUN 1개로 충분
    ]
};

// 로그 출력 함수
function log(msg) {
    console.log(msg);
    const time = new Date().toLocaleTimeString();
    logBox.innerHTML += `<div>[${time}] ${msg}</div>`;
    logBox.scrollTop = logBox.scrollHeight;
}

// 1. WebSocket 시그널링 채널 연결
function connectSignaling() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/signaling`;

    log(`Signaling WebSocket 연결 시도 중: ${wsUrl}`);
    socket = new WebSocket(wsUrl);

    socket.onopen = () => {
        log("Signaling WebSocket 연결 성공!");
        wsIndicator.classList.add('online');
        wsStatus.innerHTML = `<span class="indicator online" id="wsIndicator"></span>Online`;
        setupWebRTC();
    };

    socket.onclose = () => {
        log("Signaling WebSocket 연결이 종료되었습니다.");
        wsIndicator.classList.remove('online');
        wsStatus.innerHTML = `<span class="indicator" id="wsIndicator"></span>Offline`;
        rtcStatus.innerText = "Offline";
        controlStatus.innerText = "비활성";
        dataChannel = null;
    };

    socket.onerror = (err) => {
        log(`WebSocket 에러 발생: ${err.message || '알 수 없는 오류'}`);
    };

    socket.onmessage = async (event) => {
        try {
            const message = JSON.parse(event.data);
            log(`수신된 시그널 패킷: ${message.type}`);

            switch (message.type) {
                case 'ANSWER':
                    await peerConnection.setRemoteDescription(new RTCSessionDescription(message.payload));
                    log("WebRTC Remote Description (Answer) 설정 완료.");
                    break;
                case 'ICE_CANDIDATE':
                    if (message.payload) {
                        await peerConnection.addIceCandidate(new RTCIceCandidate(message.payload));
                        log("신규 ICE Candidate 추가 완수.");
                    }
                    break;
            }
        } catch (e) {
            log(`메시지 파싱 실패: ${e.message}`);
        }
    };
}

// 2. WebRTC PeerConnection 세팅 및 Offer 생성
async function setupWebRTC() {
    log("WebRTC PeerConnection 생성 및 초기화 중...");
    peerConnection = new RTCPeerConnection(rtcConfig);

    // DataChannel 개설 (터치/키보드 제어 명령 전송용)
    // Channel name 'control' is matched by Android onDataChannel handler
    dataChannel = peerConnection.createDataChannel('control', {
        ordered: true,
        maxRetransmits: 0 // 레이턴시 최소화 세팅
    });

    setupDataChannelHandlers(dataChannel);

    // 비디오 스트림 수신 이벤트 바인딩
    peerConnection.ontrack = (event) => {
        log("갤럭시 실시간 화면 비디오 트랙 감지!");
        if (event.streams && event.streams[0]) {
            remoteVideo.srcObject = event.streams[0];
            rtcStatus.innerText = "Streaming Active";
            log("비디오 소스 스트림 렌더링 시작.");
        }
    };

    // ICE Candidate 획득 시 시그널링 서버로 전송
    peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
            socket.send(JSON.stringify({
                type: 'ICE_CANDIDATE',
                payload: event.candidate
            }));
        }
    };

    // WebRTC Offer 생성 및 전송
    try {
        const offer = await peerConnection.createOffer();
        await peerConnection.setLocalDescription(offer);
        log("WebRTC Local Description (Offer) 생성 및 설정 완료.");

        socket.send(JSON.stringify({
            type: 'OFFER',
            payload: offer
        }));
        log("Signaling Server로 Offer 전송 완료.");
    } catch (err) {
        log(`WebRTC 기동 중 오류 발생: ${err.message}`);
    }
}

// 3. DataChannel 이벤트 핸들러 세팅
function setupDataChannelHandlers(channel) {
    channel.onopen = () => {
        log("WebRTC DataChannel 제어 채널 오픈!");
        controlStatus.innerText = "제어 활성화";
        setupTouchControl();
        setupKeyControl();
    };

    channel.onclose = () => {
        log("WebRTC DataChannel 제어 채널 닫힘.");
        controlStatus.innerText = "비활성";
    };

    channel.onmessage = (event) => {
        log(`DataChannel 수신 메시지: ${event.data}`);
    };
}

// ─── Coordinate helper ───────────────────────────────────────────────────────
/**
 * Convert a mouse event into normalized {x, y} coordinates (0.0–1.0)
 * that account for letterbox / pillarbox caused by object-fit: contain.
 */
function getNormalizedCoords(e) {
    const rect   = remoteVideo.getBoundingClientRect();
    const xOff   = e.clientX - rect.left;
    const yOff   = e.clientY - rect.top;
    const wElem  = rect.width;
    const hElem  = rect.height;
    const wVideo = remoteVideo.videoWidth  || 1080;
    const hVideo = remoteVideo.videoHeight || 2400;
    const rVideo = wVideo / hVideo;
    const rElem  = wElem  / hElem;

    let x, y;
    if (rElem > rVideo) {
        // Pillarbox: black bars on left/right
        const wAct    = hElem * rVideo;
        const wMargin = (wElem - wAct) / 2;
        x = (xOff - wMargin) / wAct;
        y = yOff / hElem;
    } else {
        // Letterbox: black bars on top/bottom
        const hAct    = wElem / rVideo;
        const hMargin = (hElem - hAct) / 2;
        x = xOff / wElem;
        y = (yOff - hMargin) / hAct;
    }

    return {
        x: Math.max(0, Math.min(1, parseFloat(x.toFixed(4)))),
        y: Math.max(0, Math.min(1, parseFloat(y.toFixed(4))))
    };
}

// 4. 터치/클릭 & 스와이프 제어 세팅
function setupTouchControl() {
    log("마우스 원격 터치 좌표 리스너 기동 완료.");

    let dragStart   = null;  // { x, y, time }
    let isDragging  = false;
    let startClientX = 0;
    let startClientY = 0;
    const DRAG_THRESHOLD_PX = 8;

    function sendControl(payload) {
        if (!dataChannel || dataChannel.readyState !== 'open') return;
        dataChannel.send(JSON.stringify(payload));
    }

    remoteVideo.addEventListener('mousedown', (e) => {
        e.preventDefault();
        const coords = getNormalizedCoords(e);
        dragStart    = { ...coords, time: Date.now() };
        startClientX = e.clientX;
        startClientY = e.clientY;
        isDragging   = false;
    });

    remoteVideo.addEventListener('mousemove', (e) => {
        if (e.buttons !== 1 || !dragStart) return;
        const dx = e.clientX - startClientX;
        const dy = e.clientY - startClientY;
        if (!isDragging && Math.sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD_PX) {
            isDragging = true;
        }
    });

    remoteVideo.addEventListener('mouseup', (e) => {
        if (!dragStart) return;
        const end      = getNormalizedCoords(e);
        const duration = Date.now() - dragStart.time;

        if (isDragging) {
            // Swipe gesture
            sendControl({
                type: 'swipe',
                x1: dragStart.x,
                y1: dragStart.y,
                x2: end.x,
                y2: end.y,
                duration: Math.max(100, Math.min(duration, 1500))
            });
            log(`Swipe: (${dragStart.x},${dragStart.y})→(${end.x},${end.y}) ${duration}ms`);
        } else {
            // Tap gesture
            sendControl({ type: 'tap', x: dragStart.x, y: dragStart.y });
            log(`Tap: (${dragStart.x}, ${dragStart.y})`);
        }

        dragStart  = null;
        isDragging = false;
    });

    // Cancel drag if mouse leaves the video element
    remoteVideo.addEventListener('mouseleave', () => {
        dragStart  = null;
        isDragging = false;
    });
}

// 5. 키보드 단축키 → Android 키 이벤트
function setupKeyControl() {
    log("키보드 단축키 리스너 기동 완료.");

    function sendKey(keyCode) {
        if (!dataChannel || dataChannel.readyState !== 'open') return;
        dataChannel.send(JSON.stringify({ type: 'key', keyCode }));
        log(`Key sent: keyCode=${keyCode}`);
    }

    document.addEventListener('keydown', (e) => {
        switch (e.key) {
            case 'Backspace':
                e.preventDefault();
                sendKey(4);    // Android KEYCODE_BACK
                break;
            case 'Home':
                e.preventDefault();
                sendKey(3);    // Android KEYCODE_HOME
                break;
            case 'F1':
                e.preventDefault();
                sendKey(187);  // Android KEYCODE_APP_SWITCH (recent apps)
                break;
        }
    });
}

// 6. 연결하기 버튼 이벤트
connectBtn.addEventListener('click', () => {
    if (socket && socket.readyState === WebSocket.OPEN) {
        log("이미 연결된 상태입니다. 재연결을 시도합니다.");
        socket.close();
    }
    connectSignaling();
});
