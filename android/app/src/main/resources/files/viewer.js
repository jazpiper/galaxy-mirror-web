// Android Mirror: WebRTC & Signaling Client
const remoteVideo = document.getElementById('remoteVideo');
const keyboardSink = document.getElementById('keyboardSink');
const connectBtn = document.getElementById('connectBtn');
const wsIndicator = document.getElementById('wsIndicator');
const wsStatus = document.getElementById('wsStatus');
const rtcStatus = document.getElementById('rtcStatus');
const controlStatus = document.getElementById('controlStatus');
const accessibilityStatus = document.getElementById('accessibilityStatus');
const favoriteAppsList = document.getElementById('favoriteAppsList');
const statusDetail = document.getElementById('statusDetail');
const logBox = document.getElementById('logBox');
const uploadUsage = document.getElementById('uploadUsage');
const downloadUsage = document.getElementById('downloadUsage');
const usbFrame = document.getElementById('usbFrame');
const transportTailscaleBtn = document.getElementById('transportTailscaleBtn');
const transportUsbBtn = document.getElementById('transportUsbBtn');
const qualityMode = document.getElementById('qualityMode');
const qualityEffective = document.getElementById('qualityEffective');
const qualityNetwork = document.getElementById('qualityNetwork');
const qualityAutoBtn = document.getElementById('qualityAutoBtn');
const qualityDataSaverBtn = document.getElementById('qualityDataSaverBtn');
const qualityStandardBtn = document.getElementById('qualityStandardBtn');
const qualityHighBtn = document.getElementById('qualityHighBtn');
const navRecentsBtn = document.getElementById('navRecentsBtn');
const navHomeBtn = document.getElementById('navHomeBtn');
const navBackBtn = document.getElementById('navBackBtn');

let socket = null;
let usbSocket = null;
let peerConnection = null;
let dataChannel = null;
let remoteDescriptionSet = false;
let pendingRemoteCandidates = [];
let accessibilityReady = false;
let touchControlInitialized = false;
let keyControlInitialized = false;
let navigationControlInitialized = false;
let keyboardControl = null;
let dataUsagePollId = null;
let lastNetworkBytes = null;
let accumulatedNetworkBytes = { sent: 0, received: 0 };
let nextTextSeq = 1;
let inFlightTextSeq = null;
let queuedTextPayloads = [];
let ackTimeoutId = null;
let selectedTransport = initialTransport();
let lastUsbFrameUrl = null;

// Reconnection States
let shouldAutoReconnect = true;
let statusDetailMessage = "";
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 8;
let reconnectTimeoutId = null;
let isReconnecting = false;
let reconnectCloseInProgress = false;

function updateVideoAspectRatio() {
    const videoContainer = document.getElementById('videoContainer');
    if (!videoContainer || !remoteVideo || !remoteVideo.videoWidth || !remoteVideo.videoHeight) return;
    videoContainer.style.aspectRatio = `${remoteVideo.videoWidth} / ${remoteVideo.videoHeight}`;
    log(`비디오 컨테이너 aspect-ratio 갱신: ${remoteVideo.videoWidth}x${remoteVideo.videoHeight}`);
}
remoteVideo.addEventListener('loadedmetadata', updateVideoAspectRatio);
remoteVideo.addEventListener('resize', updateVideoAspectRatio);

const viewerAccessToken = new URLSearchParams(window.location.search).get('token') || '';

function initialTransport() {
    const params = new URLSearchParams(window.location.search);
    const requested = (params.get('transport') || '').toLowerCase();
    if (requested === 'usb' || requested === 'tailscale') return requested;

    const localHosts = ['127.0.0.1', 'localhost', '::1', '[::1]'];
    return localHosts.includes(window.location.hostname) ? 'usb' : 'tailscale';
}

const streamQualityButtons = [
    { mode: 'AUTO', element: qualityAutoBtn },
    { mode: 'DATA_SAVER', element: qualityDataSaverBtn },
    { mode: 'STANDARD', element: qualityStandardBtn },
    { mode: 'HIGH', element: qualityHighBtn }
];

const rtcConfig = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' } // Tailscale 릴레이가 있어서 STUN 1개로 충분
    ]
};

// 로그 출력 함수
function log(msg) {
    console.log(msg);
    const time = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.textContent = `[${time}] ${msg}`;
    logBox.appendChild(entry);

    // Evict old entries to prevent DOM bloat
    while (logBox.childElementCount > 200) {
        logBox.removeChild(logBox.firstChild);
    }

    logBox.scrollTop = logBox.scrollHeight;
}

function focusKeyboardCapture() {
    if (keyboardControl) {
        keyboardControl.focus();
        return;
    }
    remoteVideo.focus();
}

function showStatusDetail(text, tone = '') {
    if (!statusDetail) return;
    statusDetail.className = `status-detail${tone ? ` ${tone}` : ''}`;
    statusDetail.textContent = text;
}

function viewerAuthHeaders() {
    return viewerAccessToken ? { 'X-Android-Mirror-Token': viewerAccessToken } : {};
}

function formatMegabytes(bytes) {
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function updateDataUsageDisplay() {
    if (uploadUsage) uploadUsage.textContent = formatMegabytes(accumulatedNetworkBytes.sent);
    if (downloadUsage) downloadUsage.textContent = formatMegabytes(accumulatedNetworkBytes.received);
}

function resetDataUsageStats() {
    lastNetworkBytes = null;
    accumulatedNetworkBytes = { sent: 0, received: 0 };
    updateDataUsageDisplay();
}

function extractNetworkBytes(stats) {
    let selectedCandidatePairId = null;
    let selectedPair = null;
    let fallbackSent = 0;
    let fallbackReceived = 0;

    stats.forEach((report) => {
        if (report.type === 'transport' && report.selectedCandidatePairId) {
            selectedCandidatePairId = report.selectedCandidatePairId;
        }
    });

    if (selectedCandidatePairId && typeof stats.get === 'function') {
        selectedPair = stats.get(selectedCandidatePairId);
    }

    stats.forEach((report) => {
        if (
            !selectedPair &&
            report.type === 'candidate-pair' &&
            report.state === 'succeeded' &&
            (report.selected || report.nominated)
        ) {
            selectedPair = report;
        }

        if (report.type === 'inbound-rtp' && typeof report.bytesReceived === 'number') {
            fallbackReceived += report.bytesReceived;
        } else if (report.type === 'outbound-rtp' && typeof report.bytesSent === 'number') {
            fallbackSent += report.bytesSent;
        } else if (report.type === 'data-channel') {
            fallbackSent += report.bytesSent || 0;
            fallbackReceived += report.bytesReceived || 0;
        }
    });

    if (selectedPair) {
        return {
            sent: selectedPair.bytesSent || 0,
            received: selectedPair.bytesReceived || 0
        };
    }

    return {
        sent: fallbackSent,
        received: fallbackReceived
    };
}

async function sampleWebRtcStats() {
    if (!peerConnection || typeof peerConnection.getStats !== 'function') return;

    try {
        const stats = await peerConnection.getStats();
        const current = extractNetworkBytes(stats);

        // Extract round-trip latency time (RTT)
        let rtt = null;
        stats.forEach((report) => {
            if (report.type === 'candidate-pair' && report.state === 'succeeded' && (report.selected || report.nominated)) {
                if (typeof report.currentRoundTripTime === 'number') {
                    rtt = report.currentRoundTripTime;
                }
            }
        });

        const latencyEl = document.getElementById('rtcLatency');
        if (latencyEl) {
            if (typeof rtt === 'number') {
                latencyEl.textContent = `${(rtt * 1000).toFixed(0)} ms`;
            } else {
                latencyEl.textContent = '확인 중';
            }
        }

        if (!lastNetworkBytes) {
            lastNetworkBytes = current;
            updateDataUsageDisplay();
            return;
        }

        accumulatedNetworkBytes.sent += Math.max(0, current.sent - lastNetworkBytes.sent);
        accumulatedNetworkBytes.received += Math.max(0, current.received - lastNetworkBytes.received);
        lastNetworkBytes = current;
        updateDataUsageDisplay();
    } catch (error) {
        log(`데이터 사용량 집계 실패: ${error.message}`);
    }
}

function startDataUsagePolling() {
    stopDataUsagePolling();
    resetDataUsageStats();
    dataUsagePollId = setInterval(sampleWebRtcStats, 1000);
    sampleWebRtcStats();
}

function stopDataUsagePolling() {
    if (dataUsagePollId) {
        clearInterval(dataUsagePollId);
        dataUsagePollId = null;
    }
}

function formatBitrate(maxBitrateBps) {
    if (typeof maxBitrateBps !== 'number' || Number.isNaN(maxBitrateBps)) return '';
    return `${(maxBitrateBps / 1_000_000).toFixed(1)}Mbps`;
}

function renderStreamQualityStatus(payload = {}) {
    const selectedMode = payload.selectedMode || 'AUTO';
    const selectedLabel = payload.selectedLabel || selectedMode;
    const effectiveLabel = payload.effectiveLabel || payload.effectiveMode || '확인 중';
    const networkLabel = payload.networkLabel || payload.networkTransport || '확인 중';
    const activityLabel = payload.activityState === 'IDLE' ? '대기 절약 중' : '활성';
    const resolution =
        payload.width && payload.height && payload.fps
            ? `${payload.width}x${payload.height} ${payload.fps}fps`
            : '';
    const bitrate = formatBitrate(payload.maxBitrateBps);
    const effectiveText = [effectiveLabel, activityLabel, resolution, bitrate].filter(Boolean).join(' · ');

    if (qualityMode) qualityMode.textContent = selectedLabel;
    if (qualityEffective) qualityEffective.textContent = effectiveText || '확인 중';
    if (qualityNetwork) qualityNetwork.textContent = networkLabel;

    streamQualityButtons.forEach(({ mode, element }) => {
        if (!element) return;
        if (mode === selectedMode) {
            element.classList.add('active');
        } else {
            element.classList.remove('active');
        }
    });
}

function renderTransportSelection() {
    transportTailscaleBtn?.classList.toggle('active', selectedTransport === 'tailscale');
    transportUsbBtn?.classList.toggle('active', selectedTransport === 'usb');
    remoteVideo?.classList.toggle('hidden', selectedTransport === 'usb');
    usbFrame?.classList.toggle('hidden', selectedTransport !== 'usb');
}

function setTransport(transport) {
    if (transport !== 'tailscale' && transport !== 'usb') return;
    if (selectedTransport === transport) return;

    selectedTransport = transport;
    statusDetailMessage = "";
    reconnectAttempts = 0;
    isReconnecting = false;
    reconnectCloseInProgress = false;
    if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
    }
    hideReconnectOverlay();
    disconnectCurrentTransport();
    renderTransportSelection();
    showStatusDetail(
        transport === 'usb'
            ? 'USB 모드는 adb forward 후 127.0.0.1 주소에서 연결합니다.'
            : 'Tailscale 모드는 Android MagicDNS 주소에서 WebRTC로 연결합니다.'
    );
}

function setupTransportControls() {
    transportTailscaleBtn?.addEventListener('click', () => setTransport('tailscale'));
    transportUsbBtn?.addEventListener('click', () => setTransport('usb'));
}

async function loadStreamQualityStatus() {
    try {
        const response = await fetch('/stream/quality', {
            cache: 'no-store',
            headers: viewerAuthHeaders()
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        renderStreamQualityStatus(await response.json());
    } catch (error) {
        log(`스트림 화질 상태 로드 실패: ${error.message}`);
    }
}

async function setStreamQualityMode(mode) {
    try {
        const response = await fetch('/stream/quality', {
            method: 'POST',
            headers: { ...viewerAuthHeaders(), 'Content-Type': 'application/json' },
            body: JSON.stringify({ mode })
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        renderStreamQualityStatus(await response.json());
        log(`스트림 화질 변경 요청: ${mode}`);
        focusKeyboardCapture();
    } catch (error) {
        log(`스트림 화질 변경 실패: ${error.message}`);
    }
}

function sendControlPayload(payload) {
    if (selectedTransport === 'usb') {
        if (!usbSocket || usbSocket.readyState !== WebSocket.OPEN) {
            log('USB 제어 채널이 아직 열리지 않았습니다.');
            return false;
        }
        try {
            usbSocket.send(JSON.stringify(payload));
            return true;
        } catch (e) {
            log(`USB 제어 채널 전송 중 예외 발생: ${e.message}`);
            return false;
        }
    }

    if (!dataChannel || dataChannel.readyState !== 'open') {
        log("제어 채널이 아직 열리지 않았습니다.");
        return false;
    }
    try {
        dataChannel.send(JSON.stringify(payload));
        return true;
    } catch (e) {
        log(`제어 채널 전송 중 예외 발생: ${e.message}`);
        return false;
    }
}

function sendAndroidKey(keyCode) {
    if (sendControlPayload({ type: 'key', keyCode })) {
        log(`Key sent: keyCode=${keyCode}`);
    }
}

function sendSequencedTextPayload(payload) {
    if (inFlightTextSeq !== null) {
        queuedTextPayloads.push(payload);
        return false;
    }

    const seq = nextTextSeq;
    nextTextSeq += 1;
    inFlightTextSeq = seq;

    if (ackTimeoutId !== null) {
        clearTimeout(ackTimeoutId);
        ackTimeoutId = null;
    }

    let sent = false;
    try {
        sent = sendControlPayload({ ...payload, seq });
    } catch (e) {
        log(`DataChannel send error: ${e.message}`);
        sent = false;
    }

    if (!sent) {
        inFlightTextSeq = null;
        return false;
    }

    ackTimeoutId = setTimeout(() => {
        if (inFlightTextSeq === seq) {
            log(`Text control ACK timeout for seq=${seq}`);
            inFlightTextSeq = null;
            ackTimeoutId = null;
            flushNextQueuedTextPayload();
        }
    }, 1500);

    return true;
}

function resetTextControlState() {
    inFlightTextSeq = null;
    queuedTextPayloads = [];
    nextTextSeq = 1;
    if (ackTimeoutId !== null) {
        clearTimeout(ackTimeoutId);
        ackTimeoutId = null;
    }
}

function flushNextQueuedTextPayload() {
    if (inFlightTextSeq !== null || queuedTextPayloads.length === 0) return;
    const nextPayload = queuedTextPayloads.shift();
    sendSequencedTextPayload(nextPayload);
}

function handleControlAck(payload = {}) {
    if (payload.seq !== inFlightTextSeq) return;
    if (ackTimeoutId !== null) {
        clearTimeout(ackTimeoutId);
        ackTimeoutId = null;
    }
    inFlightTextSeq = null;
    if (payload.applied === false) {
        log(`Text control ACK failed: ${payload.message || 'UNKNOWN'}`);
    }
    flushNextQueuedTextPayload();
}

// 1. WebSocket 시그널링 채널 연결
function connectSignaling() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const tokenQuery = viewerAccessToken ? `?token=${encodeURIComponent(viewerAccessToken)}` : '';
    const wsUrl = `${protocol}//${window.location.host}/signaling${tokenQuery}`;

    resetTextControlState();
    log(`Signaling WebSocket 연결 시도 중: ${wsUrl}`);

    // Clean up socket and PeerConnection to prevent resource leaks
    closeUsbSocket();
    clearUsbFrame();
    closeSignalingSocket();
    cleanupPeerConnection();

    const signalingSocket = new WebSocket(wsUrl);
    socket = signalingSocket;

    signalingSocket.onopen = () => {
        if (socket !== signalingSocket) return;
        log("Signaling WebSocket 연결 성공!");
        wsIndicator.classList.add('online');
        wsStatus.innerHTML = `<span class="indicator online" id="wsIndicator"></span>Online`;

        // Reset reconnect states on success
        isReconnecting = false;
        reconnectAttempts = 0;
        reconnectCloseInProgress = false;
        if (reconnectTimeoutId) {
            clearTimeout(reconnectTimeoutId);
            reconnectTimeoutId = null;
        }
        hideReconnectOverlay();

        loadFavoriteApps();
        loadStreamQualityStatus();
        setupWebRTC(signalingSocket);
    };

    signalingSocket.onclose = (event) => {
        if (socket !== signalingSocket) return;
        log(`Signaling WebSocket 연결이 종료되었습니다. Code: ${event.code}, Reason: ${event.reason || '없음'}`);
        stopDataUsagePolling();

        cleanupPeerConnection();

        wsIndicator.classList.remove('online');
        wsStatus.innerHTML = `<span class="indicator" id="wsIndicator"></span>Offline`;
        rtcStatus.innerText = "Offline";
        controlStatus.innerText = "비활성";
        accessibilityStatus.innerText = "확인 중";

        accessibilityReady = false;
        resetTextControlState();
        resetDataUsageStats();

        const latencyEl = document.getElementById('rtcLatency');
        if (latencyEl) latencyEl.textContent = 'Offline';

        // Check if the connection was intentionally closed by the user or due to authorization issues
        const isExplicitClose = event.code === 1008 || !shouldAutoReconnect;

        if (reconnectCloseInProgress) {
            reconnectCloseInProgress = false;
            startReconnectSequence();
            return;
        }

        if (isExplicitClose) {
            log("명시적 세션 종료 또는 재인증이 요구되어 자동 재연결을 가동하지 않습니다.");
            showStatusDetail(statusDetailMessage || "Android Mirror 연결이 종료되었습니다. 다시 연결하려면 미러링 연결하기를 누르세요.");
            hideReconnectOverlay();
            isReconnecting = false;
            reconnectAttempts = 0;
        } else {
            startReconnectSequence();
        }
    };

    signalingSocket.onerror = (err) => {
        if (socket !== signalingSocket) return;
        log(`WebSocket 에러 발생: ${err.message || '네트워크 오류'}`);
    };

    signalingSocket.onmessage = async (event) => {
        if (socket !== signalingSocket) return;
        try {
            const message = JSON.parse(event.data);
            log(`수신된 시그널 패킷: ${message.type}`);

            switch (message.type) {
                case 'ANSWER':
                    await peerConnection.setRemoteDescription(new RTCSessionDescription(message.payload));
                    remoteDescriptionSet = true;
                    await flushPendingRemoteCandidates();
                    log("WebRTC Remote Description (Answer) 설정 완료.");
                    break;
                case 'STATUS':
                    handleStatusMessage(message.payload || {});
                    break;
                case 'ICE_CANDIDATE':
                    if (message.payload) {
                        await addRemoteCandidate(message.payload);
                    }
                    break;
            }
        } catch (e) {
            log(`메시지 파싱 실패: ${e.message}`);
        }
    };
}

function connectMirror() {
    if (selectedTransport === 'usb') {
        connectUsbSession();
        return;
    }

    connectSignaling();
}

function usbSessionUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const tokenQuery = viewerAccessToken ? `?token=${encodeURIComponent(viewerAccessToken)}` : '';
    return `${protocol}//${window.location.host}/usb/session${tokenQuery}`;
}

function connectUsbSession() {
    disconnectCurrentTransport();
    resetTextControlState();
    resetDataUsageStats();
    hideReconnectOverlay();

    const wsUrl = usbSessionUrl();
    log(`USB session 연결 시도 중: ${wsUrl}`);

    const sessionSocket = new WebSocket(wsUrl);
    sessionSocket.binaryType = 'blob';
    usbSocket = sessionSocket;

    sessionSocket.onopen = () => {
        if (usbSocket !== sessionSocket) return;
        wsIndicator.classList.add('online');
        wsStatus.innerHTML = `<span class="indicator online" id="wsIndicator"></span>Online`;
        rtcStatus.innerText = 'USB 스트림 대기';
        controlStatus.innerText = 'USB';
        setupTouchControl();
        setupKeyControl();
        showStatusDetail('USB 연결이 열렸습니다. Android 화면 공유 승인을 기다립니다.');
    };

    sessionSocket.onmessage = (event) => {
        if (usbSocket !== sessionSocket) return;
        if (typeof event.data === 'string') {
            handleUsbTextMessage(event.data);
            return;
        }
        renderUsbFrame(event.data);
    };

    sessionSocket.onclose = (event) => {
        if (usbSocket !== sessionSocket) return;
        log(`USB session 연결이 종료되었습니다. Code: ${event.code}, Reason: ${event.reason || '없음'}`);
        usbSocket = null;
        wsIndicator.classList.remove('online');
        wsStatus.innerHTML = `<span class="indicator" id="wsIndicator"></span>Offline`;
        rtcStatus.innerText = 'USB 연결 종료';
        controlStatus.innerText = '비활성';
        accessibilityStatus.innerText = '확인 중';
        accessibilityReady = false;
        resetTextControlState();
        clearUsbFrame();
        showStatusDetail('USB 연결이 종료되었습니다. 다시 연결하려면 미러링 연결하기를 누르세요.');
    };

    sessionSocket.onerror = () => {
        if (usbSocket !== sessionSocket) return;
        log('USB session WebSocket 에러 발생');
        showStatusDetail('USB 연결 오류가 발생했습니다. adb forward 상태를 확인하세요.', 'warning');
    };
}

function handleUsbTextMessage(text) {
    try {
        const message = JSON.parse(text);
        if (message.type === 'USB_STATUS') {
            const payload = message.payload || {};

            if (typeof payload.captureReady === 'boolean') {
                rtcStatus.innerText = payload.captureReady ? 'USB 캡처 준비' : '화면 공유 대기';
            }
            if (typeof payload.accessibilityReady === 'boolean') {
                accessibilityReady = payload.accessibilityReady;
                accessibilityStatus.innerText = accessibilityReady ? '활성화' : '권한 필요';
            }
            if (payload.streamQuality) {
                renderStreamQualityStatus(payload.streamQuality);
            }

            if (payload.message === 'USB_STREAMING') {
                rtcStatus.innerText = 'USB 스트리밍';
                showStatusDetail('USB 화면 전송 중입니다.', 'success');
            } else if (payload.message === 'WAITING_FOR_SCREEN_CAPTURE') {
                rtcStatus.innerText = '화면 공유 대기';
                clearUsbFrame();
                enterScreenCaptureApprovalWait('Android 기기에서 화면 공유 권한을 승인하면 USB 미러링이 시작됩니다.');
            } else if (payload.message) {
                showStatusDetail(`USB 상태: ${payload.message}`);
            }
            return;
        }

        if (message.type === 'CONTROL_ACK') {
            handleControlAck(message.payload || {});
        }
    } catch (error) {
        log(`USB 메시지 처리 실패: ${error.message}`);
    }
}

function renderUsbFrame(blob) {
    if (!usbFrame || selectedTransport !== 'usb') return;
    if (lastUsbFrameUrl) {
        URL.revokeObjectURL(lastUsbFrameUrl);
    }
    lastUsbFrameUrl = URL.createObjectURL(blob);
    usbFrame.src = lastUsbFrameUrl;
    usbFrame.classList.remove('hidden');
    remoteVideo?.classList.add('hidden');
    rtcStatus.innerText = 'USB 스트리밍';
    accumulatedNetworkBytes.received += blob?.size || 0;
    updateDataUsageDisplay();
}

function handleStatusMessage(payload) {
    if (typeof payload.captureReady === 'boolean') {
        rtcStatus.innerText = payload.captureReady ? "Capture Ready" : "화면 공유 대기";
    }
    if (typeof payload.accessibilityReady === 'boolean') {
        accessibilityReady = payload.accessibilityReady;
        accessibilityStatus.innerText = accessibilityReady ? "활성화" : "권한 필요";
    }
    if (typeof payload.brightnessWriteSettingsReady === 'boolean' && payload.brightnessMinimizeEnabled) {
        const message = payload.brightnessWriteSettingsReady
            ? '밝기 최소화 준비됨'
            : '밝기 최소화 권한 필요';
        log(`Android 밝기 상태: ${message}`);
    }
    if (payload.streamQuality) {
        renderStreamQualityStatus(payload.streamQuality);
    }
    if (payload.message) {
        applyAndroidStatusMessage(payload.message);
        log(`Android 상태: ${payload.message}`);
    }
}

function applyAndroidStatusMessage(message) {
    switch (message) {
        case 'SIGNALING_CONNECTED':
            showStatusDetail("Android Host와 연결되었습니다. 화면 공유 승인을 기다립니다.");
            return;
        case 'WAITING_FOR_SCREEN_CAPTURE':
        case 'SCREEN_CAPTURE_NOT_READY':
            rtcStatus.innerText = "화면 공유 대기";
            enterScreenCaptureApprovalWait("Android 기기에서 화면 공유 권한을 승인하면 미러링이 시작됩니다.");
            return;
        case 'SCREEN_CAPTURE_READY':
            rtcStatus.innerText = "Capture Ready";
            showStatusDetail("화면 공유가 준비되었습니다. 스트림이 시작되면 이 창에서 Android 화면을 제어할 수 있습니다.", "success");
            return;
        case 'SCREEN_CAPTURE_PERMISSION_DENIED':
            rtcStatus.innerText = "권한 거부됨";
            showStatusDetail("Android 화면 공유 권한이 거부되었습니다. Android 앱에서 화면 공유를 다시 시작하고 승인하세요.", "warning");
            return;
        case 'SCREEN_CAPTURE_REAUTH_REQUIRED':
            rtcStatus.innerText = "재승인 필요";
            statusDetailMessage = "Android 화면 공유 재승인이 필요합니다. Android 기기에서 화면 공유를 다시 승인한 뒤 미러링 연결하기를 누르세요.";
            enterScreenCaptureApprovalWait(statusDetailMessage);
            shouldAutoReconnect = false;
            triggerAutoReconnect();
            return;
        case 'PROJECTION_STOPPED_LOCKED':
            rtcStatus.innerText = "잠금으로 중단";
            statusDetailMessage = "Android 화면 잠금 또는 화면 꺼짐으로 미러링이 중단되었습니다. 잠금을 해제하고 화면 공유를 다시 승인하세요.";
            enterScreenCaptureApprovalWait(statusDetailMessage);
            shouldAutoReconnect = false;
            triggerAutoReconnect();
            return;
        case 'CONTROL_CHANNEL_ACCEPTED':
            controlStatus.innerText = "채널 승인됨";
            showStatusDetail("원격 입력 채널이 Android Host에서 승인되었습니다.", "success");
            return;
        case 'STATUS_TICK':
            return;
        default:
            showStatusDetail(`Android 상태: ${message}`);
    }
}

function renderFavoriteApps(apps) {
    if (!favoriteAppsList) return;

    const safeApps = Array.isArray(apps) ? apps : [];
    if (safeApps.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'shortcut-empty';
        empty.textContent = 'Android 앱에서 자주 쓰는 앱을 추가하세요.';
        favoriteAppsList.replaceChildren(empty);
        return;
    }

    const buttons = safeApps.map((app) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'shortcut-btn';
        button.textContent = app?.label || app?.packageName || '알 수 없는 앱';
        button.addEventListener('click', () => launchFavoriteApp(app?.packageName, app?.label));
        return button;
    });
    favoriteAppsList.replaceChildren(...buttons);
}

async function loadFavoriteApps() {
    if (!favoriteAppsList) return;
    try {
        const response = await fetch('/apps/favorites', {
            cache: 'no-store',
            headers: viewerAuthHeaders()
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const payload = await response.json();
        renderFavoriteApps(payload.apps || []);
    } catch (error) {
        log(`앱 바로가기 목록 로드 실패: ${error.message}`);
        renderFavoriteApps([]);
    }
}

async function launchFavoriteApp(packageName, label) {
    if (!packageName) return;
    try {
        const response = await fetch('/apps/launch', {
            method: 'POST',
            headers: { ...viewerAuthHeaders(), 'Content-Type': 'application/json' },
            body: JSON.stringify({ packageName })
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        log(`앱 실행 요청: ${label || packageName}`);
        focusKeyboardCapture();
    } catch (error) {
        log(`앱 실행 실패: ${label || packageName} (${error.message})`);
    }
}

// 2. WebRTC PeerConnection 세팅 및 Offer 생성
async function setupWebRTC(signalingSocket = socket) {
    log("WebRTC PeerConnection 생성 및 초기화 중...");
    stopDataUsagePolling();
    resetDataUsageStats();
    const currentPeerConnection = new RTCPeerConnection(rtcConfig);
    peerConnection = currentPeerConnection;
    remoteDescriptionSet = false;
    pendingRemoteCandidates = [];

    // Bind connection states for failure detection
    currentPeerConnection.oniceconnectionstatechange = () => {
        if (peerConnection !== currentPeerConnection) return;
        const state = currentPeerConnection.iceConnectionState;
        log(`WebRTC ICE Connection State: ${state}`);
        if (state === 'failed' || state === 'disconnected') {
            log("WebRTC ICE 연결 단절 감지 - 자동 재연결 트리거");
            triggerAutoReconnect();
        }
    };

    currentPeerConnection.onconnectionstatechange = () => {
        if (peerConnection !== currentPeerConnection) return;
        const state = currentPeerConnection.connectionState;
        log(`WebRTC PeerConnection State: ${state}`);
        if (state === 'failed' || state === 'closed') {
            log("WebRTC PeerConnection 단절 감지 - 자동 재연결 트리거");
            triggerAutoReconnect();
        }
    };

    currentPeerConnection.addTransceiver('video', { direction: 'recvonly' });

    // DataChannel 개설 (터치/키보드 제어 명령 전송용)
    // Channel name 'control' is matched by Android onDataChannel handler
    dataChannel = currentPeerConnection.createDataChannel('control', {
        ordered: true
    });

    setupDataChannelHandlers(dataChannel);

    // 비디오 스트림 수신 이벤트 바인딩
    currentPeerConnection.ontrack = (event) => {
        if (peerConnection !== currentPeerConnection) return;
        log("Android 실시간 화면 비디오 트랙 감지!");
        if (event.streams && event.streams[0]) {
            remoteVideo.srcObject = event.streams[0];
            rtcStatus.innerText = "Streaming Active";
            focusKeyboardCapture();
            log("비디오 소스 스트림 렌더링 시작.");
        }
    };

    // ICE Candidate 획득 시 시그널링 서버로 전송
    currentPeerConnection.onicecandidate = (event) => {
        if (
            event.candidate &&
            socket === signalingSocket &&
            signalingSocket.readyState === WebSocket.OPEN
        ) {
            signalingSocket.send(JSON.stringify({
                type: 'ICE_CANDIDATE',
                payload: event.candidate
            }));
        }
    };

    // WebRTC Offer 생성 및 전송
    try {
        const offer = await currentPeerConnection.createOffer();
        await currentPeerConnection.setLocalDescription(offer);
        if (socket !== signalingSocket || peerConnection !== currentPeerConnection) {
            currentPeerConnection.close();
            return;
        }
        log("WebRTC Local Description (Offer) 생성 및 설정 완료.");

        signalingSocket.send(JSON.stringify({
            type: 'OFFER',
            payload: offer
        }));
        log("Signaling Server로 Offer 전송 완료.");
        startDataUsagePolling();
    } catch (err) {
        log(`WebRTC 기동 중 오류 발생: ${err.message}`);
    }
}

async function addRemoteCandidate(candidate) {
    if (!remoteDescriptionSet || !peerConnection.remoteDescription) {
        pendingRemoteCandidates.push(candidate);
        log("Remote Description 설정 전 ICE Candidate 대기열 저장.");
        return;
    }
    try {
        await peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
        log("신규 ICE Candidate 추가 완수.");
    } catch (err) {
        log(`ICE Candidate 추가 실패: ${err.message}`);
    }
}

async function flushPendingRemoteCandidates() {
    const candidates = pendingRemoteCandidates;
    pendingRemoteCandidates = [];
    for (const candidate of candidates) {
        try {
            await peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
        } catch (err) {
            log(`ICE Candidate 추가 실패: ${err.message}`);
        }
    }
    if (candidates.length > 0) {
        log(`대기 중 ICE Candidate ${candidates.length}개 추가 완료.`);
    }
}

function hasClipboardWriteApi() {
    return Boolean(
        typeof navigator !== 'undefined' &&
        navigator.clipboard &&
        typeof navigator.clipboard.writeText === 'function'
    );
}

function hasClipboardReadApi() {
    return Boolean(
        typeof navigator !== 'undefined' &&
        navigator.clipboard &&
        typeof navigator.clipboard.readText === 'function'
    );
}

function showManualClipboardFallback(text) {
    const toast = showGlowToast("클립보드 수신 (클릭하여 복사)");
    if (!toast) return;
    toast.style.pointerEvents = 'auto';
    toast.style.cursor = 'pointer';
    toast.onclick = () => {
        const textArea = document.createElement('textarea');
        textArea.value = text;
        textArea.setAttribute('readonly', 'readonly');
        textArea.style.position = 'fixed';
        textArea.style.left = '-9999px';
        document.body?.appendChild?.(textArea);
        textArea.focus();
        textArea.select?.();
        try {
            document.execCommand?.('copy');
            showGlowToast("복사 완료!");
        } catch (error) {
            log(`수동 클립보드 복사 실패: ${error.message}`);
        } finally {
            textArea.remove?.();
        }
    };
}

async function writeClipboardFromAndroid(text) {
    if (!hasClipboardWriteApi()) {
        showManualClipboardFallback(text);
        return;
    }

    try {
        await navigator.clipboard.writeText(text);
        showGlowToast(text === "" ? "갤럭시 클립보드 비우기와 동기화되었습니다." : "갤럭시 클립보드와 동기화되었습니다.");
    } catch (error) {
        log(`브라우저 클립보드 쓰기 실패(보안 제약): ${error.message}`);
        showManualClipboardFallback(text);
    }
}

async function readClipboardForAndroid() {
    if (!hasClipboardReadApi()) {
        log("브라우저 클립보드 읽기 API를 사용할 수 없습니다.");
        return null;
    }
    return navigator.clipboard.readText();
}

// 3. DataChannel 이벤트 핸들러 세팅
function setupDataChannelHandlers(channel) {
    channel.onopen = () => {
        if (dataChannel !== channel) return;
        log("WebRTC DataChannel 제어 채널 오픈!");
        controlStatus.innerText = "채널 연결됨";
        setupTouchControl();
        setupKeyControl();
    };

    channel.onclose = () => {
        if (dataChannel !== channel) return;
        log("WebRTC DataChannel 제어 채널 닫힘.");
        controlStatus.innerText = "비활성";
        resetTextControlState();
    };

    channel.onmessage = (event) => {
        if (dataChannel !== channel) return;
        log(`DataChannel 수신 메시지: ${event.data}`);
        try {
            const message = JSON.parse(event.data);
            if (message.type === 'CONTROL_ACK') {
                handleControlAck(message.payload || {});
            } else if (message.type === 'clipboard') {
                const text = message.text;
                if (typeof text === 'string') {
                    writeClipboardFromAndroid(text);
                }
            }
        } catch (error) {
            log(`DataChannel 메시지 처리 실패: ${error.message}`);
        }
    };
}

// ─── Coordinate helper ───────────────────────────────────────────────────────
/**
 * Convert a mouse event into normalized {x, y} coordinates (0.0–1.0)
 * that account for letterbox / pillarbox caused by object-fit: contain.
 */
function getNormalizedCoords(e, surface = remoteVideo) {
    if (!surface) return null;
    const rect   = surface.getBoundingClientRect();
    const xOff   = e.clientX - rect.left;
    const yOff   = e.clientY - rect.top;
    const wElem  = rect.width;
    const hElem  = rect.height;
    const wVideo = surface.videoWidth  || surface.naturalWidth  || remoteVideo.videoWidth  || 1080;
    const hVideo = surface.videoHeight || surface.naturalHeight || remoteVideo.videoHeight || 2400;
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

    if (x < 0 || x > 1 || y < 0 || y > 1) {
        return null;
    }

    return {
        x: parseFloat(x.toFixed(4)),
        y: parseFloat(y.toFixed(4))
    };
}

// 4. 터치/클릭 & 스와이프 제어 세팅
function setupTouchControl() {
    if (touchControlInitialized) return;
    touchControlInitialized = true;
    log("마우스 원격 터치 좌표 리스너 기동 완료.");

    let dragStart   = null;  // { x, y, time }
    let isDragging  = false;
    let startClientX = 0;
    let startClientY = 0;
    const DRAG_THRESHOLD_PX = 8;
    const WHEEL_SWIPE_DELAY_MS = 40;
    const WHEEL_SWIPE_DURATION_MS = 180;
    const WHEEL_SWIPE_MIN_DISTANCE = 0.12;
    const WHEEL_SWIPE_MAX_DISTANCE = 0.45;
    const WHEEL_SWIPE_SCALE = 900;

    function clampNormalized(value) {
        return Math.max(0.02, Math.min(value, 0.98));
    }

    function rounded(value) {
        return parseFloat(value.toFixed(4));
    }

    function buildWheelSwipePayload(coords, deltaX, deltaY) {
        const vertical = Math.abs(deltaY) >= Math.abs(deltaX);
        const dominantDelta = vertical ? deltaY : deltaX;
        if (Math.abs(dominantDelta) < 1) return null;

        const distance = Math.min(
            WHEEL_SWIPE_MAX_DISTANCE,
            Math.max(WHEEL_SWIPE_MIN_DISTANCE, Math.abs(dominantDelta) / WHEEL_SWIPE_SCALE)
        );
        const halfDistance = distance / 2;

        if (vertical) {
            return {
                type: 'swipe',
                x1: coords.x,
                y1: rounded(clampNormalized(coords.y + Math.sign(deltaY) * halfDistance)),
                x2: coords.x,
                y2: rounded(clampNormalized(coords.y - Math.sign(deltaY) * halfDistance)),
                duration: WHEEL_SWIPE_DURATION_MS
            };
        }

        return {
            type: 'swipe',
            x1: rounded(clampNormalized(coords.x + Math.sign(deltaX) * halfDistance)),
            y1: coords.y,
            x2: rounded(clampNormalized(coords.x - Math.sign(deltaX) * halfDistance)),
            y2: coords.y,
            duration: WHEEL_SWIPE_DURATION_MS
        };
    }

    function bindTouchSurface(surface) {
        if (!surface) return;
        const wheelState = {
            deltaX: 0,
            deltaY: 0,
            coords: null,
            timeoutId: null
        };

        surface.addEventListener('mousedown', (e) => {
            e.preventDefault();
            focusKeyboardCapture();
            const coords = getNormalizedCoords(e, surface);
            if (!coords) {
                dragStart = null;
                return;
            }
            dragStart    = { ...coords, time: Date.now() };
            startClientX = e.clientX;
            startClientY = e.clientY;
            isDragging   = false;
        });

        surface.addEventListener('mousemove', (e) => {
            if (e.buttons !== 1 || !dragStart) return;
            const dx = e.clientX - startClientX;
            const dy = e.clientY - startClientY;
            if (!isDragging && Math.sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD_PX) {
                isDragging = true;
            }
        });

        surface.addEventListener('mouseup', (e) => {
            if (!dragStart) return;
            const end      = getNormalizedCoords(e, surface);
            if (!end) {
                dragStart  = null;
                isDragging = false;
                return;
            }
            const duration = Date.now() - dragStart.time;

            if (isDragging) {
                // Swipe gesture
                sendControlPayload({
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
                sendControlPayload({ type: 'tap', x: dragStart.x, y: dragStart.y });
                log(`Tap: (${dragStart.x}, ${dragStart.y})`);
            }

            dragStart  = null;
            isDragging = false;
        });

        surface.addEventListener('mouseleave', () => {
            dragStart  = null;
            isDragging = false;
        });

        surface.addEventListener('wheel', (e) => {
            e.preventDefault();
            focusKeyboardCapture();
            const coords = getNormalizedCoords(e, surface);
            if (!coords) return;

            wheelState.deltaX += e.deltaX || 0;
            wheelState.deltaY += e.deltaY || 0;
            wheelState.coords = coords;

            if (wheelState.timeoutId !== null) {
                clearTimeout(wheelState.timeoutId);
            }

            wheelState.timeoutId = setTimeout(() => {
                const payload = buildWheelSwipePayload(
                    wheelState.coords,
                    wheelState.deltaX,
                    wheelState.deltaY
                );
                wheelState.deltaX = 0;
                wheelState.deltaY = 0;
                wheelState.coords = null;
                wheelState.timeoutId = null;

                if (!payload) return;
                if (sendControlPayload(payload)) {
                    log(
                        `Wheel swipe: (${payload.x1},${payload.y1})→(${payload.x2},${payload.y2}) ${payload.duration}ms`
                    );
                }
            }, WHEEL_SWIPE_DELAY_MS);
        }, { passive: false });
    }

    bindTouchSurface(remoteVideo);
    bindTouchSurface(usbFrame);
}

// 5. 키보드 단축키 → Android 키 이벤트
function setupKeyControl() {
    if (keyControlInitialized) return;
    keyControlInitialized = true;
    log("키보드 단축키 리스너 기동 완료.");

    function sendTextCommit(text) {
        if (sendSequencedTextPayload({ type: 'text', action: 'commit', text })) {
            log(`Text sent: length=${text.length}`);
        } else if (inFlightTextSeq !== null) {
            log(`Text queued: length=${text.length}`);
        }
    }

    function sendTextDeleteBackward(count) {
        if (sendSequencedTextPayload({ type: 'text', action: 'deleteBackward', count })) {
            log(`Text delete sent: count=${count}`);
        } else if (inFlightTextSeq !== null) {
            log(`Text delete queued: count=${count}`);
        }
    }

    if (window.GalaxyMirrorKeyboard && keyboardSink) {
        keyboardControl = window.GalaxyMirrorKeyboard.createKeyboardControl({
            document,
            remoteTarget: remoteVideo,
            keyboardSink,
            sendKey: sendAndroidKey,
            sendTextCommit,
            sendTextDeleteBackward
        });
        keyboardControl.init();
        log("키보드 IME 입력 리스너 기동 완료.");
        return;
    }

    document.addEventListener('keydown', (e) => {
        if (document.activeElement !== remoteVideo) return;
        if (e.isComposing) return;

        switch (e.key) {
            case 'Backspace':
                e.preventDefault();
                sendTextDeleteBackward(1);
                return;
            case 'Home':
                e.preventDefault();
                sendAndroidKey(3);    // Android KEYCODE_HOME
                return;
            case 'F1':
                e.preventDefault();
                sendAndroidKey(187);  // Android KEYCODE_APP_SWITCH (recent apps)
                return;
            case 'Enter':
                e.preventDefault();
                sendTextCommit('\n');
                return;
            case 'Escape':
                e.preventDefault();
                sendAndroidKey(4);    // Android KEYCODE_BACK
                return;
        }

        if (e.metaKey || e.ctrlKey || e.altKey) return;
        if (e.key.length === 1) {
            e.preventDefault();
            sendTextCommit(e.key);
        }
    });
}

function setupNavigationControls() {
    if (navigationControlInitialized) return;
    navigationControlInitialized = true;

    const buttons = [
        { element: navRecentsBtn, keyCode: 187 },
        { element: navHomeBtn, keyCode: 3 },
        { element: navBackBtn, keyCode: 4 }
    ];

    buttons.forEach(({ element, keyCode }) => {
        if (!element) return;
        element.addEventListener('click', (event) => {
            event.preventDefault();
            focusKeyboardCapture();
            sendAndroidKey(keyCode);
        });
    });
}

function setupStreamQualityControls() {
    streamQualityButtons.forEach(({ mode, element }) => {
        if (!element) return;
        element.addEventListener('click', (event) => {
            event?.preventDefault?.();
            setStreamQualityMode(mode);
        });
    });
}

// 6. 연결하기 버튼 이벤트
connectBtn.addEventListener('click', () => {
    shouldAutoReconnect = true;
    statusDetailMessage = "";
    reconnectAttempts = 0;
    isReconnecting = false;
    reconnectCloseInProgress = false;
    if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
    }
    hideReconnectOverlay();

    if (
        selectedTransport === 'tailscale' &&
        socket &&
        (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)
    ) {
        log("기존 연결이 감지되어 세션을 갱신합니다.");
        const oldSocket = socket;
        oldSocket.onclose = null;
        oldSocket.close();
        socket = null;
        cleanupPeerConnection();
        connectMirror();
    } else {
        connectMirror();
    }
});

// ─── Reconnection and Cleanup Helpers ────────────────────────────────────────

function closeUsbSocket() {
    if (!usbSocket) return;
    const oldSocket = usbSocket;
    usbSocket = null;
    oldSocket.onopen = null;
    oldSocket.onmessage = null;
    oldSocket.onerror = null;
    oldSocket.onclose = null;
    try {
        oldSocket.close();
    } catch (e) {
        log(`USB socket close error: ${e.message}`);
    }
}

function closeSignalingSocket() {
    if (!socket) return;
    const oldSocket = socket;
    socket = null;
    oldSocket.onopen = null;
    oldSocket.onclose = null;
    oldSocket.onerror = null;
    oldSocket.onmessage = null;
    try {
        oldSocket.close();
    } catch (e) {
        log(`Signaling socket close error: ${e.message}`);
    }
}

function disconnectCurrentTransport() {
    closeUsbSocket();
    closeSignalingSocket();
    stopDataUsagePolling();
    cleanupPeerConnection();
    clearRemoteVideoFrame();
    clearUsbFrame();
    resetTextControlState();
}

function cleanupPeerConnection() {
    if (peerConnection) {
        peerConnection.ontrack = null;
        peerConnection.onicecandidate = null;
        peerConnection.oniceconnectionstatechange = null;
        peerConnection.onconnectionstatechange = null;
        try {
            peerConnection.close();
        } catch (e) {
            log(`PeerConnection close error: ${e.message}`);
        }
        peerConnection = null;
    }
    if (dataChannel) {
        dataChannel.onopen = null;
        dataChannel.onclose = null;
        dataChannel.onmessage = null;
        try {
            dataChannel.close();
        } catch (e) {
            log(`DataChannel close error: ${e.message}`);
        }
        dataChannel = null;
    }
}

function triggerAutoReconnect() {
    if (selectedTransport !== 'tailscale') return;
    if (isReconnecting || reconnectTimeoutId) return;
    log("네트워크 단절 감지 - 자동 재연결 복원을 시작합니다.");
    isReconnecting = true;
    if (socket && socket.readyState !== WebSocket.CLOSED && socket.readyState !== WebSocket.CLOSING) {
        reconnectCloseInProgress = true;
        socket.close(); // Triggers signalingSocket.onclose
    } else {
        startReconnectSequence();
    }
}

function startReconnectSequence() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        log("최대 자동 재연결 횟수를 초과했습니다.");
        showStatusDetail("자동 재연결에 실패했습니다. 수동으로 다시 시도해주세요.", "warning");
        showReconnectOverlayFailed();
        isReconnecting = false;
        return;
    }

    isReconnecting = true;
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 16000);
    log(`자동 재연결 복원 시도 (${reconnectAttempts + 1}/${MAX_RECONNECT_ATTEMPTS}) - ${delay / 1000}초 후 시도`);

    showReconnectOverlayProgress(delay / 1000, reconnectAttempts + 1);

    if (reconnectTimeoutId) clearTimeout(reconnectTimeoutId);
    reconnectTimeoutId = setTimeout(() => {
        reconnectTimeoutId = null;
        connectMirror();
    }, delay);

    reconnectAttempts++;
}

function showReconnectOverlayProgress(seconds, attempt) {
    const overlay = document.getElementById('reconnectOverlay');
    const sub = document.getElementById('reconnectSub');
    const attempts = document.getElementById('reconnectAttempts');
    if (overlay) overlay.classList.remove('hidden');
    if (sub) sub.textContent = "네트워크 일시 단절로 인해 재연결을 시도하고 있습니다.";
    if (attempts) attempts.textContent = `재시도 대기: ${seconds.toFixed(0)}초 (시도 ${attempt}/${MAX_RECONNECT_ATTEMPTS})`;

    const pulseRing = overlay?.querySelector('.pulse-ring');
    if (pulseRing) {
        pulseRing.style.animation = 'pulse 1.5s infinite ease-in-out';
        pulseRing.style.borderColor = 'var(--accent-color)';
    }
}

function showReconnectOverlayFailed() {
    const overlay = document.getElementById('reconnectOverlay');
    const sub = document.getElementById('reconnectSub');
    const attempts = document.getElementById('reconnectAttempts');
    if (overlay) overlay.classList.remove('hidden');
    if (sub) sub.textContent = "자동 재연결에 실패했습니다. 네트워크 연결을 확인하고 다시 연결해주세요.";
    if (attempts) attempts.textContent = "연결 단절 상태";

    const pulseRing = overlay?.querySelector('.pulse-ring');
    if (pulseRing) {
        pulseRing.style.animation = 'none';
        pulseRing.style.borderColor = '#ef4444';
    }
}

function hideReconnectOverlay() {
    const overlay = document.getElementById('reconnectOverlay');
    if (overlay) overlay.classList.add('hidden');
}

function clearRemoteVideoFrame() {
    if (!remoteVideo) return;
    if (remoteVideo.srcObject) {
        remoteVideo.srcObject = null;
    }
    remoteVideo.removeAttribute?.('src');
    remoteVideo.load?.();
}

function clearUsbFrame() {
    if (lastUsbFrameUrl) {
        URL.revokeObjectURL(lastUsbFrameUrl);
        lastUsbFrameUrl = null;
    }
    usbFrame?.removeAttribute('src');
}

function enterScreenCaptureApprovalWait(message) {
    hideReconnectOverlay();
    isReconnecting = false;
    reconnectCloseInProgress = false;
    if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
    }
    clearRemoteVideoFrame();
    clearUsbFrame();
    controlStatus.innerText = "대기";
    showStatusDetail(message, "warning");
}

// Page Visibility API for fast foreground sync
document.addEventListener('visibilitychange', () => {
    log(`Page Visibility 변경 감지: hidden=${document.hidden}`);
    if (!document.hidden && selectedTransport === 'tailscale') {
        const isSocketClosed = !socket || socket.readyState === WebSocket.CLOSED || socket.readyState === WebSocket.CLOSING;
        if (isSocketClosed && shouldAutoReconnect && reconnectAttempts > 0) {
            log("화면 전면 복귀 감지 - 즉시 자동 재연결 복원을 시도합니다.");
            if (reconnectTimeoutId) {
                clearTimeout(reconnectTimeoutId);
                reconnectTimeoutId = null;
            }
            connectMirror();
        }
    }
});

function showGlowToast(message) {
    const container = document.getElementById('toastContainer');
    if (!container) return null;
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.innerHTML = `<span>🔔</span><span>${message}</span>`;
    container.appendChild(toast);

    // Force a reflow to trigger CSS transitions
    toast.offsetHeight;
    toast.classList.add('show');

    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 400);
    }, 3000);
    return toast;
}

function setupSystemControls() {
    const volUpBtn = document.getElementById('volUpBtn');
    const volDownBtn = document.getElementById('volDownBtn');
    const muteBtn = document.getElementById('muteBtn');
    const powerBtn = document.getElementById('powerBtn');

    if (volUpBtn) volUpBtn.addEventListener('click', () => sendAndroidKey(24));
    if (volDownBtn) volDownBtn.addEventListener('click', () => sendAndroidKey(25));
    if (muteBtn) muteBtn.addEventListener('click', () => sendAndroidKey(164));
    if (powerBtn) powerBtn.addEventListener('click', () => sendAndroidKey(26));
}

function setupClipboardSync() {
    document.addEventListener('copy', () => {
        setTimeout(async () => {
            try {
                const text = await readClipboardForAndroid();
                const usbControlReady = selectedTransport === 'usb' && usbSocket && usbSocket.readyState === WebSocket.OPEN;
                const webRtcControlReady = selectedTransport !== 'usb' && dataChannel && dataChannel.readyState === 'open';
                if (text !== null && (usbControlReady || webRtcControlReady)) {
                    const sent = sendControlPayload({ type: 'clipboard', text });
                    if (sent) {
                        log(`맥 클립보드 원격 전송 성공: length=${text.length}`);
                    }
                }
            } catch (e) {
                log(`맥 클립보드 읽기/전송 실패: ${e.message}`);
            }
        }, 100);
    });
}

let mediaRecorder = null;
let recordedChunks = [];
let isRecording = false;

function selectRecordingOptions() {
    if (typeof MediaRecorder === 'undefined') return null;
    if (typeof MediaRecorder.isTypeSupported !== 'function') return {};

    const candidates = [
        { mimeType: 'video/webm;codecs=vp9' },
        { mimeType: 'video/webm;codecs=vp8' },
        { mimeType: 'video/webm' }
    ];
    return candidates.find(option => MediaRecorder.isTypeSupported(option.mimeType)) || {};
}

function resetRecordingState(recordBtn) {
    isRecording = false;
    mediaRecorder = null;
    if (recordBtn) {
        recordBtn.classList.remove('recording');
        recordBtn.title = "화면 녹화 시작";
        recordBtn.textContent = "⏺️";
    }
}

function setupMediaCapture() {
    const screenshotBtn = document.getElementById('screenshotBtn');
    const recordBtn = document.getElementById('recordBtn');

    if (screenshotBtn) {
        screenshotBtn.addEventListener('click', () => {
            const video = document.getElementById('remoteVideo');
            if (!video || !video.videoWidth) {
                showGlowToast("비디오 스트림이 아직 활성화되지 않았습니다.");
                return;
            }
            const canvas = document.createElement('canvas');
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

            const link = document.createElement('a');
            const date = new Date().toISOString().replace(/[:.]/g, '-');
            link.download = `screenshot_${date}.png`;
            link.href = canvas.toDataURL('image/png');
            link.click();
            showGlowToast("스크린샷이 저장되었습니다.");
        });
    }

    if (recordBtn) {
        recordBtn.addEventListener('click', () => {
            const video = document.getElementById('remoteVideo');
            if (!video || !video.srcObject) {
                showGlowToast("녹화 가능한 활성 비디오 스트림이 없습니다.");
                return;
            }

            if (isRecording) {
                if (mediaRecorder) {
                    mediaRecorder.stop();
                } else {
                    resetRecordingState(recordBtn);
                }
                showGlowToast("녹화를 중지하고 파일을 생성하는 중입니다...");
            } else {
                const stream = video.srcObject;
                recordedChunks = [];
                const options = selectRecordingOptions();
                if (options === null) {
                    showGlowToast("이 브라우저는 화면 녹화를 지원하지 않습니다.");
                    return;
                }

                try {
                    mediaRecorder = new MediaRecorder(stream, options);
                    mediaRecorder.ondataavailable = (e) => {
                         if (e.data && e.data.size > 0) {
                              recordedChunks.push(e.data);
                         }
                    };
                    mediaRecorder.onerror = (event) => {
                         log(`녹화 중 오류 발생: ${event?.error?.message || 'unknown'}`);
                         resetRecordingState(recordBtn);
                         showGlowToast("화면 녹화 중 오류가 발생했습니다.");
                    };
                    mediaRecorder.onstop = () => {
                         if (recordedChunks.length > 0) {
                             const blob = new Blob(recordedChunks, { type: 'video/webm' });
                             const url = URL.createObjectURL(blob);
                             const link = document.createElement('a');
                             const date = new Date().toISOString().replace(/[:.]/g, '-');
                             link.download = `recording_${date}.webm`;
                             link.href = url;
                             link.click();
                             setTimeout(() => URL.revokeObjectURL(url), 1000);
                             showGlowToast("화면 녹화본이 저장되었습니다.");
                         }
                         resetRecordingState(recordBtn);
                    };

                    mediaRecorder.start();
                    isRecording = true;
                    recordBtn.classList.add('recording');
                    recordBtn.title = "화면 녹화 중지";
                    recordBtn.textContent = "⏹️";
                    showGlowToast("화면 녹화를 시작했습니다.");
                } catch (err) {
                    resetRecordingState(recordBtn);
                    log(`녹화 초기화 실패: ${err.message}`);
                    showGlowToast("화면 녹화를 시작할 수 없습니다.");
                }
            }
        });
    }
}

renderTransportSelection();
setupTransportControls();
setupStreamQualityControls();
setupNavigationControls();
loadFavoriteApps();
loadStreamQualityStatus();
setupSystemControls();
setupClipboardSync();
setupMediaCapture();
