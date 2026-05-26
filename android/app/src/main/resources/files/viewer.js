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
    dataChannel = peerConnection.createDataChannel('controlChannel', {
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
    };

    channel.onclose = () => {
        log("WebRTC DataChannel 제어 채널 닫힘.");
        controlStatus.innerText = "비활성";
    };

    channel.onmessage = (event) => {
        log(`DataChannel 수신 메시지: ${event.data}`);
    };
}

// 4. 터치/클릭 마우스 이벤트 캡처 및 백분율(%) 계산식
function setupTouchControl() {
    log("마우스 원격 터치 좌표 리스너 기동 완료.");

    function sendTouchMessage(action, e) {
        if (!dataChannel || dataChannel.readyState !== 'open') return;

        const rect = remoteVideo.getBoundingClientRect();
        
        // 1. 마우스 원시 오프셋 좌표 산출
        const xOff = e.clientX - rect.left;
        const yOff = e.clientY - rect.top;

        // Video 엘리먼트 스펙 및 원본 비디오 크기 획득
        const wElem = rect.width;
        const hElem = rect.height;
        
        // 비디오 본래 종횡비 획득 (가변 뷰포트 containment 감안)
        const wVideo = remoteVideo.videoWidth || 1080;
        const hVideo = remoteVideo.videoHeight || 2400;
        const rVideo = wVideo / hVideo;
        const rElem = wElem / hElem;

        let xPct = 0;
        let yPct = 0;

        // 2. Coordinates.md에 설계된 조건별 레터박스 상쇄 보정 공식 대입
        if (rElem > rVideo) {
            // Pillarbox 발생 (좌우 여백 발생 조건)
            const wAct = hElem * rVideo;
            const wMargin = (wElem - wAct) / 2;
            xPct = ((xOff - wMargin) / wAct) * 100;
            yPct = (yOff / hElem) * 100;
        } else {
            // Letterbox 발생 (상하 여백 발생 조건)
            const hAct = wElem / rVideo;
            const hMargin = (hElem - hAct) / 2;
            xPct = (xOff / wElem) * 100;
            yPct = ((yOff - hMargin) / hAct) * 100;
        }

        // 3. 바운더리 체크 (0% ~ 100% 범위 보장)
        xPct = Math.max(0, Math.min(100, xPct));
        yPct = Math.max(0, Math.min(100, yPct));

        // 4. DataChannel로 제어 메시지 최종 전송
        const payload = {
            action: action,
            pointerId: 0,
            x: parseFloat(xPct.toFixed(2)),
            y: parseFloat(yPct.toFixed(2))
        };

        dataChannel.send(JSON.stringify(payload));
    }

    // 마우스 이벤트 바인딩
    remoteVideo.addEventListener('mousedown', (e) => {
        sendTouchMessage('TOUCH_DOWN', e);
    });

    remoteVideo.addEventListener('mousemove', (e) => {
        if (e.buttons === 1) { // 드래그 중인 경우에만 TOUCH_MOVE 전송
            sendTouchMessage('TOUCH_MOVE', e);
        }
    });

    remoteVideo.addEventListener('mouseup', (e) => {
        sendTouchMessage('TOUCH_UP', e);
    });
}

// 5. 연결하기 버튼 이벤트
connectBtn.addEventListener('click', () => {
    if (socket && socket.readyState === WebSocket.OPEN) {
        log("이미 연결된 상태입니다. 재연결을 시도합니다.");
        socket.close();
    }
    connectSignaling();
});
