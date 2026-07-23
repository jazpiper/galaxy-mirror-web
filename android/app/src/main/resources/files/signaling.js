import { remoteVideo, keyboardSink, connectBtn, wsIndicator, wsStatus, rtcStatus, streamStatusLabel, rtcLatencyItem, controlStatus, accessibilityStatus, favoriteAppsList, statusDetail, logBox, uploadUsage, downloadUsage, rtcLatency, usbCanvas, connectionPlaceholder, usbCanvasCtx, getUsbCanvasContext, usbFrame, transportTailscaleBtn, transportUsbBtn, qualityMode, qualityEffective, qualityNetwork, qualityNetworkItem, usbCoolingStatusItem, usbCoolingStatus, toolsPanel, qualityAutoBtn, qualityDataSaverBtn, qualityStandardBtn, qualityHighBtn, navRecentsBtn, navHomeBtn, navBackBtn, clipboardHistory, MAX_CLIPBOARD_HISTORY, updateVideoAspectRatio, streamQualityButtons, logQueue, logFrameRequested, flushLogs, log, showStatusDetail, formatMegabytes, lastUploadUsageText, lastDownloadUsageText, updateDataUsageDisplay, resetDataUsageStats, formatBitrate, formatBytesPerSecond, renderStreamQualityStatus, renderUsbCoolingStatus, hideUsbCoolingStatus, setHidden, renderTransportSelection, updateConnectButtonState, showConnectionPlaceholder, hideConnectionPlaceholder, resetConnectionStatus, renderFavoriteApps, renderClipboardHistory, clearClipboardBtn, handleClearClipboardBtnClick, addClipboardToHistory, clearRemoteVideoFrame, clearUsbFrame, showGlowToast, updateBlackOverlayStatus, isAutoFitActive } from './ui.js';
import { peerConnection, dataChannel, remoteDescriptionSet, pendingRemoteCandidates, dataUsagePollId, lastNetworkBytes, accumulatedNetworkBytes, rtcConfig, resetNetworkBytes, extractNetworkBytes, sampleWebRtcStats, startDataUsagePolling, stopDataUsagePolling, setupWebRTC, addRemoteCandidate, flushPendingRemoteCandidates, setupDataChannelHandlers, cleanupPeerConnection } from './webrtc.js';
import { bindTouchSurface, accessibilityReady, touchControlInitialized, keyControlInitialized, navigationControlInitialized, keyboardControl, nextTextSeq, inFlightTextSeq, queuedTextPayloads, ackTimeoutId, focusKeyboardCapture, sendControlPayload, sendAndroidKey, sendSequencedTextPayload, resetTextControlState, flushNextQueuedTextPayload, handleControlAck, hasClipboardWriteApi, hasClipboardReadApi, showManualClipboardFallback, writeClipboardFromAndroid, readClipboardForAndroid, getNormalizedCoords, unbindTouchSurface, destroyTouchControl, setupTouchControl, documentKeydownHandler, keyboardListeners, createEventInterceptor, interceptKeyboardControl, destroyKeyControl, setupKeyControl, setupNavigationControls, setupStreamQualityControls, setupSystemControls, documentCopyHandler, destroyClipboardSync, setupClipboardSync, _set_accessibilityReady } from './controls.js';

export let socket = null;
export function _set_socket(val) { socket = val; }
export function _get_socket() { return socket; }
export let usbSocket = null;
export function _set_usbSocket(val) { usbSocket = val; }
export function _get_usbSocket() { return usbSocket; }
export let usbPerfPollId = null;
export function _set_usbPerfPollId(val) { usbPerfPollId = val; }
export function _get_usbPerfPollId() { return usbPerfPollId; }
export let selectedTransport = initialTransport();
export function _set_selectedTransport(val) { selectedTransport = val; }
export function _get_selectedTransport() { return selectedTransport; }
export let lastUsbFrameUrl = null;
export function _set_lastUsbFrameUrl(val) { lastUsbFrameUrl = val; }
export function _get_lastUsbFrameUrl() { return lastUsbFrameUrl; }
export let activeUsbCodec = 'jpeg';
export function _set_activeUsbCodec(val) { activeUsbCodec = val; }
export function _get_activeUsbCodec() { return activeUsbCodec; }
export let forceUsbJpegFallback = false;
export function _set_forceUsbJpegFallback(val) { forceUsbJpegFallback = val; }
export function _get_forceUsbJpegFallback() { return forceUsbJpegFallback; }
export let usbVideoDecoder = null;
export function _set_usbVideoDecoder(val) { usbVideoDecoder = val; }
export function _get_usbVideoDecoder() { return usbVideoDecoder; }
export let usbVideoDecoderConfigured = false;
export function _set_usbVideoDecoderConfigured(val) { usbVideoDecoderConfigured = val; }
export function _get_usbVideoDecoderConfigured() { return usbVideoDecoderConfigured; }
export let usbVideoConfig = null;
export function _set_usbVideoConfig(val) { usbVideoConfig = val; }
export function _get_usbVideoConfig() { return usbVideoConfig; }
export let usbH264SawKeyframe = false;
export function _set_usbH264SawKeyframe(val) { usbH264SawKeyframe = val; }
export function _get_usbH264SawKeyframe() { return usbH264SawKeyframe; }
export let shouldAutoReconnect = true;
export function _set_shouldAutoReconnect(val) { shouldAutoReconnect = val; }
export function _get_shouldAutoReconnect() { return shouldAutoReconnect; }
export let statusDetailMessage = "";
export function _set_statusDetailMessage(val) { statusDetailMessage = val; }
export function _get_statusDetailMessage() { return statusDetailMessage; }
export let reconnectAttempts = 0;
export function _set_reconnectAttempts(val) { reconnectAttempts = val; }
export function _get_reconnectAttempts() { return reconnectAttempts; }
export const MAX_RECONNECT_ATTEMPTS = 8;
export function _set_MAX_RECONNECT_ATTEMPTS(val) { MAX_RECONNECT_ATTEMPTS = val; }
export function _get_MAX_RECONNECT_ATTEMPTS() { return MAX_RECONNECT_ATTEMPTS; }
export let reconnectTimeoutId = null;
export function _set_reconnectTimeoutId(val) { reconnectTimeoutId = val; }
export function _get_reconnectTimeoutId() { return reconnectTimeoutId; }
export let isReconnecting = false;
export function _set_isReconnecting(val) { isReconnecting = val; }
export function _get_isReconnecting() { return isReconnecting; }
export let reconnectCloseInProgress = false;
export function _set_reconnectCloseInProgress(val) { reconnectCloseInProgress = val; }
export function _get_reconnectCloseInProgress() { return reconnectCloseInProgress; }
export function initialTransport() {
  const params = new URLSearchParams(window.location.search);
  const requested = (params.get('transport') || '').toLowerCase();
  if (requested === 'usb' || requested === 'tailscale') return requested;
  const localHosts = ['127.0.0.1', 'localhost', '::1', '[::1]'];
  return localHosts.includes(window.location.hostname) ? 'usb' : 'tailscale';
}
export async function sampleUsbPerfStatus() {
  if (selectedTransport !== 'usb' || !isSocketActive(usbSocket)) return;
  try {
    const response = await fetch('/debug/perf', {
      cache: 'no-store'
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const perf = await response.json();
    if (perf.profile) {
      renderUsbCoolingStatus(perf.profile, perf);
    }
  } catch (error) {
    log(`USB 성능 상태 로드 실패: ${error.message}`);
  }
}
export function startUsbPerfPolling() {
  stopUsbPerfPolling();
  sampleUsbPerfStatus();
  usbPerfPollId = setInterval(sampleUsbPerfStatus, 2000);
}
export function stopUsbPerfPolling() {
  if (usbPerfPollId) {
    clearInterval(usbPerfPollId);
    usbPerfPollId = null;
  }
}
export function isSocketActive(currentSocket) {
  return currentSocket && (currentSocket.readyState === WebSocket.OPEN || currentSocket.readyState === WebSocket.CONNECTING);
}
export function isMirrorConnectionActive() {
  return isSocketActive(socket) || isSocketActive(usbSocket);
}
export function disconnectMirrorFromButton() {
  shouldAutoReconnect = false;
  statusDetailMessage = '사용자가 미러링 연결을 해제했습니다.';
  reconnectAttempts = 0;
  isReconnecting = false;
  reconnectCloseInProgress = false;
  if (reconnectTimeoutId) {
    clearTimeout(reconnectTimeoutId);
    reconnectTimeoutId = null;
  }
  hideReconnectOverlay();
  disconnectCurrentTransport();
  resetConnectionStatus();
}
export function setTransport(transport) {
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
  showStatusDetail(transport === 'usb' ? 'USB 모드로 연결합니다.' : 'Tailscale 모드는 Android MagicDNS 주소에서 WebRTC로 연결합니다.');
}
export function setupTransportControls() {
  transportTailscaleBtn?.addEventListener('click', () => setTransport('tailscale'));
  transportUsbBtn?.addEventListener('click', () => setTransport('usb'));
}
export async function loadStreamQualityStatus() {
  try {
    const response = await fetch('/stream/quality', {
      cache: 'no-store'
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    renderStreamQualityStatus(await response.json());
  } catch (error) {
    log(`스트림 화질 상태 로드 실패: ${error.message}`);
  }
}
export async function setStreamQualityMode(mode) {
  try {
    const response = await fetch('/stream/quality', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        mode
      })
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
export function connectSignaling() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}/signaling`;
  resetTextControlState();
  log(`Signaling WebSocket 연결 시도 중: ${wsUrl}`);
  closeUsbSocket();
  clearUsbFrame();
  closeSignalingSocket();
  cleanupPeerConnection();
  const signalingSocket = new WebSocket(wsUrl);
  socket = signalingSocket;
  updateConnectButtonState();
  signalingSocket.onopen = () => {
    if (socket !== signalingSocket) return;
    log("Signaling WebSocket 연결 성공!");
    wsIndicator.classList.add('online');
    wsStatus.innerHTML = `<span class="indicator online" id="wsIndicator"></span>Online`;
    updateConnectButtonState();
    hideConnectionPlaceholder();
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
  signalingSocket.onclose = event => {
    if (socket !== signalingSocket) return;
    log(`Signaling WebSocket 연결이 종료되었습니다. Code: ${event.code}, Reason: ${event.reason || '없음'}`);
    stopDataUsagePolling();
    cleanupPeerConnection();
    wsIndicator.classList.remove('online');
    wsStatus.innerHTML = `<span class="indicator" id="wsIndicator"></span>Offline`;
    rtcStatus.innerText = "Offline";
    controlStatus.innerText = "비활성";
    accessibilityStatus.innerText = "확인 중";
    _set_accessibilityReady(false);
    resetTextControlState();
    resetDataUsageStats();
    updateConnectButtonState();
    const latencyEl = document.getElementById('rtcLatency');
    if (latencyEl) latencyEl.textContent = 'Offline';
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
  signalingSocket.onerror = err => {
    if (socket !== signalingSocket) return;
    log(`WebSocket 에러 발생: ${err.message || '네트워크 오류'}`);
  };
  signalingSocket.onmessage = event => handleSignalingMessage(event, signalingSocket);
}
export async function handleSignalingMessage(event, signalingSocket) {
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
        handleStatusMessage(message.payload || ({}));
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
}
export function connectMirror() {
  if (selectedTransport === 'usb') {
    connectUsbSession();
    return;
  }
  connectSignaling();
}
export function usbSessionUrl(codecOverride) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const codec = codecOverride || preferredUsbCodec();
  return `${protocol}//${window.location.host}/usb/session?codec=${encodeURIComponent(codec)}`;
}
export function connectUsbSession(codecOverride) {
  disconnectCurrentTransport();
  resetTextControlState();
  resetDataUsageStats();
  hideReconnectOverlay();
  forceUsbJpegFallback = codecOverride === 'jpeg';
  activeUsbCodec = codecOverride || preferredUsbCodec();
  const wsUrl = usbSessionUrl(activeUsbCodec);
  log(`USB session 연결 시도 중: ${wsUrl}`);
  const sessionSocket = new WebSocket(wsUrl);
  sessionSocket.binaryType = activeUsbCodec === 'h264' ? 'arraybuffer' : 'blob';
  usbSocket = sessionSocket;
  updateConnectButtonState();
  sessionSocket.onopen = () => {
    if (usbSocket !== sessionSocket) return;
    wsIndicator.classList.add('online');
    wsStatus.innerHTML = `<span class="indicator online" id="wsIndicator"></span>Online`;
    rtcStatus.innerText = 'USB 스트림 대기';
    controlStatus.innerText = 'USB';
    updateConnectButtonState();
    hideConnectionPlaceholder();
    startUsbPerfPolling();
    setupTouchControl();
    setupKeyControl();
    showStatusDetail('USB 연결이 열렸습니다. Android 화면 공유 승인을 기다립니다.');
  };
  sessionSocket.onmessage = event => {
    if (usbSocket !== sessionSocket) return;
    if (typeof event.data === 'string') {
      handleUsbTextMessage(event.data);
      return;
    }
    if (activeUsbCodec === 'h264') {
      decodeUsbH264Packet(event.data);
    } else {
      renderUsbFrame(event.data);
    }
  };
  sessionSocket.onclose = event => {
    if (usbSocket !== sessionSocket) return;
    log(`USB session 연결이 종료되었습니다. Code: ${event.code}, Reason: ${event.reason || '없음'}`);
    usbSocket = null;
    wsIndicator.classList.remove('online');
    wsStatus.innerHTML = `<span class="indicator" id="wsIndicator"></span>Offline`;
    rtcStatus.innerText = 'USB 연결 종료';
    controlStatus.innerText = '비활성';
    accessibilityStatus.innerText = '확인 중';
    _set_accessibilityReady(false);
    stopUsbPerfPolling();
    resetTextControlState();
    closeUsbVideoDecoder();
    clearUsbFrame();
    updateConnectButtonState();
    showStatusDetail('USB 연결이 종료되었습니다. 다시 연결하려면 미러링 연결하기를 누르세요.');
  };
  sessionSocket.onerror = () => {
    if (usbSocket !== sessionSocket) return;
    log('USB session WebSocket 에러 발생');
    showStatusDetail('USB 연결 오류가 발생했습니다. 연결 상태를 확인하세요.', 'warning');
  };
}
export function handleUsbTextMessage(text) {
  try {
    const message = JSON.parse(text);
    if (message.type === 'USB_STATUS') {
      const payload = message.payload || ({});
      if (typeof payload.captureReady === 'boolean') {
        rtcStatus.innerText = payload.captureReady ? 'USB 캡처 준비' : '화면 공유 대기';
      }
      if (typeof payload.accessibilityReady === 'boolean') {
        _set_accessibilityReady(payload.accessibilityReady);
        accessibilityStatus.innerText = accessibilityReady ? '활성화' : '권한 필요';
      }
      if (payload.streamQuality) {
        renderStreamQualityStatus(payload.streamQuality);
        renderUsbCoolingStatus(payload.streamQuality, payload.usbPerf || ({}));
      }
      if (payload.message === 'USB_STREAMING') {
        rtcStatus.innerText = 'USB 스트리밍';
        showStatusDetail('USB 화면 전송 중입니다.', 'success');
      } else if (payload.message === 'H264_START_FAILED') {
        rtcStatus.innerText = 'USB JPEG 전환';
        reconnectUsbAsJpeg('Android H.264 인코더 시작 실패로 JPEG로 전환합니다.');
      } else if (payload.message === 'WAITING_FOR_SCREEN_CAPTURE') {
        rtcStatus.innerText = '화면 공유 대기';
        clearUsbFrame();
        enterScreenCaptureApprovalWait('Android 기기에서 화면 공유 권한을 승인하면 USB 미러링이 시작됩니다.');
      } else if (payload.message) {
        showStatusDetail(`USB 상태: ${payload.message}`);
      }
      return;
    }
    if (message.type === 'USB_VIDEO_CONFIG') {
      handleUsbVideoConfig(message.payload || ({}));
      return;
    }
    if (message.type === 'CONTROL_ACK') {
      handleControlAck(message.payload || ({}));
    }
  } catch (error) {
    log(`USB 메시지 처리 실패: ${error.message}`);
  }
}
export function hasUsbH264Support() {
  return typeof window.VideoDecoder === 'function' && typeof window.EncodedVideoChunk === 'function';
}
export function preferredUsbCodec() {
  return !forceUsbJpegFallback && hasUsbH264Support() ? 'h264' : 'jpeg';
}
export async function handleUsbVideoConfig(payload) {
  if (activeUsbCodec !== 'h264') return;
  if (!hasUsbH264Support()) {
    reconnectUsbAsJpeg('WebCodecs를 사용할 수 없어 JPEG로 전환합니다.');
    return;
  }
  const codec = payload.codecString || payload.avcCodec || payload.codec || 'avc1.42E01F';
  const codedWidth = Number(payload.codedWidth || payload.width || payload.effectiveWidth);
  const codedHeight = Number(payload.codedHeight || payload.height || payload.effectiveHeight);
  if (!codec || !Number.isFinite(codedWidth) || !Number.isFinite(codedHeight) || codedWidth <= 0 || codedHeight <= 0) {
    reconnectUsbAsJpeg('H.264 스트림 설정이 올바르지 않아 JPEG로 전환합니다.');
    return;
  }
  const config = {
    codec,
    codedWidth,
    codedHeight,
    optimizeForLatency: true
  };
  if (payload.description) {
    try {
      const match = payload.description.match(/.{1,2}/g);
      if (match) {
        config.description = new Uint8Array(match.map(byte => parseInt(byte, 16)));
      }
    } catch (ignored) {}
  }
  if (String(payload.chunkFormat || '').toLowerCase() === 'annexb') {
    config.avc = {
      format: 'annexb'
    };
  }
  try {
    const support = await window.VideoDecoder.isConfigSupported(config);
    if (!support?.supported) {
      reconnectUsbAsJpeg('브라우저가 USB H.264 설정을 지원하지 않아 JPEG로 전환합니다.');
      return;
    }
    closeUsbVideoDecoder();
    usbVideoConfig = {
      ...payload,
      codec,
      codedWidth,
      codedHeight
    };
    usbVideoDecoder = new window.VideoDecoder({
      output: drawDecodedUsbFrame,
      error: error => {
        log(`USB H.264 decoder error: ${error?.message || error}`);
        reconnectUsbAsJpeg('USB H.264 디코더 오류로 JPEG로 전환합니다.');
      }
    });
    usbVideoDecoder.configure(support.config || config);
    usbVideoDecoderConfigured = true;
    usbH264SawKeyframe = false;
    renderUsbCoolingStatus({
      ...payload,
      codec: 'h264',
      width: codedWidth,
      height: codedHeight,
      fps: payload.fps,
      maxBitrateBps: payload.maxBitrateBps
    }, {});
    log(`USB H.264 decoder configured: ${codec} ${codedWidth}x${codedHeight}`);
  } catch (error) {
    log(`USB H.264 config failed: ${error.message}`);
    reconnectUsbAsJpeg('USB H.264 설정 실패로 JPEG로 전환합니다.');
  }
}
export function reconnectUsbAsJpeg(reason) {
  if (activeUsbCodec === 'jpeg' && forceUsbJpegFallback) return;
  forceUsbJpegFallback = true;
  closeUsbVideoDecoder();
  log(reason);
  showStatusDetail(reason, 'warning');
  if (selectedTransport !== 'usb') return;
  const oldSocket = usbSocket;
  usbSocket = null;
  if (oldSocket) {
    oldSocket.onopen = null;
    oldSocket.onmessage = null;
    oldSocket.onerror = null;
    oldSocket.onclose = null;
    try {
      oldSocket.close();
    } catch (error) {
      log(`USB H.264 fallback close failed: ${error.message}`);
    }
  }
  connectUsbSession('jpeg');
}
export function closeUsbVideoDecoder() {
  usbVideoDecoderConfigured = false;
  usbVideoConfig = null;
  usbH264SawKeyframe = false;
  pendingSpsPpsBuffer = null;
  if (!usbVideoDecoder) return;
  const decoder = usbVideoDecoder;
  usbVideoDecoder = null;
  try {
    decoder.close?.();
  } catch (error) {
    log(`USB H.264 decoder close failed: ${error.message}`);
  }
}
export function normalizeArrayBuffer(data) {
  if (data instanceof ArrayBuffer) return data;
  if (ArrayBuffer.isView(data)) {
    return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
  }
  return null;
}
let pendingSpsPpsBuffer = null;
export function decodeUsbH264Packet(data) {
  if (selectedTransport !== 'usb') return;
  const rawBuffer = normalizeArrayBuffer(data);
  if (!rawBuffer || rawBuffer.byteLength < 16) {
    return;
  }
  accumulatedNetworkBytes.received += rawBuffer.byteLength;
  updateDataUsageDisplay();

  const bytes = new Uint8Array(rawBuffer);
  if (bytes[0] !== 0x47 || bytes[1] !== 0x48 || bytes[2] !== 0x32 || bytes[3] !== 0x36) {
    return;
  }
  if (!hasUsbH264Support()) {
    return;
  }
  if (!usbVideoDecoder || !usbVideoDecoderConfigured) {
    log('USB H.264 packet ignored: decoder is not configured');
    return;
  }

  const flags = bytes[6];
  const keyframe = (flags & 0x01) !== 0;
  const codecConfig = (flags & 0x02) !== 0;
  const timestamp = Number(new DataView(rawBuffer).getBigInt64(8, false));
  const payload = bytes.slice(16);

  if (codecConfig && !keyframe) {
    pendingSpsPpsBuffer = payload;
    return;
  }

  if (!keyframe && !usbH264SawKeyframe) {
    return;
  }

  if (!keyframe && usbVideoDecoder.decodeQueueSize > 2) {
    return;
  }

  try {
    let chunkData = payload;
    if (keyframe && pendingSpsPpsBuffer) {
      const merged = new Uint8Array(pendingSpsPpsBuffer.length + payload.length);
      merged.set(pendingSpsPpsBuffer, 0);
      merged.set(payload, pendingSpsPpsBuffer.length);
      chunkData = merged;
      pendingSpsPpsBuffer = null;
    }

    const chunk = new window.EncodedVideoChunk({
      type: keyframe ? 'key' : 'delta',
      timestamp,
      data: chunkData
    });
    usbVideoDecoder.decode(chunk);
    if (keyframe) {
      usbH264SawKeyframe = true;
    }
  } catch (error) {
    log(`USB H.264 decode chunk skipped: ${error.message}`);
  }
}
export function drawDecodedUsbFrame(frame) {
  if (selectedTransport !== 'usb') {
    frame.close?.();
    return;
  }
  try {
    if (usbFrame) {
      usbFrame.classList.add('hidden');
    }
    if (!usbCanvas) return;
    const width = frame.displayWidth || frame.codedWidth || frame.visibleRect?.width || usbVideoConfig?.codedWidth;
    const height = frame.displayHeight || frame.codedHeight || frame.visibleRect?.height || usbVideoConfig?.codedHeight;
    if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
      if (usbCanvas.width !== width || usbCanvas.height !== height) {
        usbCanvas.width = width;
        usbCanvas.height = height;
        const videoContainer = document.getElementById('videoContainer');
        if (videoContainer && !isAutoFitActive) {
          videoContainer.style.aspectRatio = `${width} / ${height}`;
        }
      }
    }
    hideConnectionPlaceholder();
    usbCanvas.classList.remove('hidden');
    remoteVideo?.classList.add('hidden');
    rtcStatus.innerText = 'USB 스트리밍';
    const ctx = getUsbCanvasContext();
    ctx.drawImage(frame, 0, 0, usbCanvas.width, usbCanvas.height);
  } catch (error) {
    log(`USB H.264 frame render failed: ${error.message}`);
    reconnectUsbAsJpeg('USB H.264 렌더링 실패로 JPEG로 전환합니다.');
  } finally {
    frame.close?.();
  }
}
export async function renderUsbFrame(blob) {
  if (selectedTransport !== 'usb') return;
  if (typeof createImageBitmap !== 'undefined') {
    if (usbFrame) {
      usbFrame.classList.add('hidden');
    }
    if (!usbCanvas) return;
    hideConnectionPlaceholder();
    usbCanvas.classList.remove('hidden');
    remoteVideo?.classList.add('hidden');
    rtcStatus.innerText = 'USB 스트리밍';
    accumulatedNetworkBytes.received += blob?.size || 0;
    updateDataUsageDisplay();
    try {
      const imageBitmap = await createImageBitmap(blob);
      const ctx = getUsbCanvasContext();
      if (usbCanvas.width !== imageBitmap.width || usbCanvas.height !== imageBitmap.height) {
        usbCanvas.width = imageBitmap.width;
        usbCanvas.height = imageBitmap.height;
        const videoContainer = document.getElementById('videoContainer');
        if (videoContainer) {
          videoContainer.style.aspectRatio = `${imageBitmap.width} / ${imageBitmap.height}`;
        }
      }
      ctx.drawImage(imageBitmap, 0, 0);
      imageBitmap.close();
    } catch (error) {
      log(`createImageBitmap rendering failed: ${error.message}`);
    }
  } else {
    if (!usbCanvas) return;
    usbCanvas.classList.add('hidden');
    if (!usbFrame) {
      usbFrame = document.createElement('img');
      usbFrame.id = 'usbFrame';
      usbFrame.style.width = '100%';
      usbFrame.style.height = '100%';
      usbFrame.style.objectFit = 'contain';
      usbFrame.style.display = 'block';
      usbCanvas.parentNode.insertBefore(usbFrame, usbCanvas);
      bindTouchSurface?.(usbFrame);
    }
    hideConnectionPlaceholder();
    usbFrame.classList.remove('hidden');
    remoteVideo?.classList.add('hidden');
    rtcStatus.innerText = 'USB 스트리밍';
    accumulatedNetworkBytes.received += blob?.size || 0;
    updateDataUsageDisplay();
    if (lastUsbFrameUrl) {
      URL.revokeObjectURL(lastUsbFrameUrl);
    }
    lastUsbFrameUrl = URL.createObjectURL(blob);
    usbFrame.src = lastUsbFrameUrl;
    usbFrame.onload = () => {
      const videoContainer = document.getElementById('videoContainer');
      if (videoContainer && usbFrame.naturalWidth && usbFrame.naturalHeight) {
        videoContainer.style.aspectRatio = `${usbFrame.naturalWidth} / ${usbFrame.naturalHeight}`;
      }
    };
  }
}
export function handleStatusMessage(payload) {
  if (typeof payload.captureReady === 'boolean') {
    rtcStatus.innerText = payload.captureReady ? "Capture Ready" : "화면 공유 대기";
  }
  if (typeof payload.accessibilityReady === 'boolean') {
    _set_accessibilityReady(payload.accessibilityReady);
    accessibilityStatus.innerText = accessibilityReady ? "활성화" : "권한 필요";
  }
  if (typeof payload.blackOverlayEnabled === 'boolean') {
    updateBlackOverlayStatus(payload.blackOverlayEnabled);
  }
  if (typeof payload.brightnessWriteSettingsReady === 'boolean' && payload.brightnessMinimizeEnabled) {
    const message = payload.brightnessWriteSettingsReady ? '밝기 최소화 준비됨' : '밝기 최소화 권한 필요';
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
export function applyAndroidStatusMessage(message) {
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
export async function loadFavoriteApps() {
  if (!favoriteAppsList) return;
  try {
    const response = await fetch('/apps/favorites', {
      cache: 'no-store'
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
export async function launchFavoriteApp(packageName, label) {
  if (!packageName) return;
  try {
    const response = await fetch('/apps/launch', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        packageName
      })
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
export function handleConnectBtnClick() {
  if (isMirrorConnectionActive()) {
    log("사용자 요청으로 미러링 연결을 해제합니다.");
    disconnectMirrorFromButton();
    return;
  }
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
  if (selectedTransport === 'tailscale' && socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
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
}
export function closeUsbSocket() {
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
  updateConnectButtonState();
}
export function closeSignalingSocket() {
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
  updateConnectButtonState();
}
export function disconnectCurrentTransport() {
  closeUsbSocket();
  closeUsbVideoDecoder();
  closeSignalingSocket();
  stopDataUsagePolling();
  stopUsbPerfPolling();
  cleanupPeerConnection();
  clearRemoteVideoFrame();
  clearUsbFrame();
  resetTextControlState();
  destroyTouchControl();
  destroyKeyControl();
  destroyClipboardSync();
}
export function triggerAutoReconnect() {
  if (selectedTransport !== 'tailscale') return;
  if (isReconnecting || reconnectTimeoutId) return;
  log("네트워크 단절 감지 - 자동 재연결 복원을 시작합니다.");
  isReconnecting = true;
  if (socket && socket.readyState !== WebSocket.CLOSED && socket.readyState !== WebSocket.CLOSING) {
    reconnectCloseInProgress = true;
    socket.close();
  } else {
    startReconnectSequence();
  }
}
export function startReconnectSequence() {
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
export function showReconnectOverlayProgress(seconds, attempt) {
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
export function showReconnectOverlayFailed() {
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
export function hideReconnectOverlay() {
  const overlay = document.getElementById('reconnectOverlay');
  if (overlay) overlay.classList.add('hidden');
}
export function enterScreenCaptureApprovalWait(message) {
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
  showConnectionPlaceholder(message);
  showStatusDetail(message, "warning");
}
export function handleVisibilityChange() {
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
}

// Attach to globalThis
if (typeof globalThis !== 'undefined') globalThis.socket = socket;
if (typeof globalThis !== 'undefined') globalThis.usbSocket = usbSocket;
if (typeof globalThis !== 'undefined') globalThis.usbPerfPollId = usbPerfPollId;
if (typeof globalThis !== 'undefined') globalThis.selectedTransport = selectedTransport;
if (typeof globalThis !== 'undefined') globalThis.lastUsbFrameUrl = lastUsbFrameUrl;
if (typeof globalThis !== 'undefined') globalThis.activeUsbCodec = activeUsbCodec;
if (typeof globalThis !== 'undefined') globalThis.forceUsbJpegFallback = forceUsbJpegFallback;
if (typeof globalThis !== 'undefined') globalThis.usbVideoDecoder = usbVideoDecoder;
if (typeof globalThis !== 'undefined') globalThis.usbVideoDecoderConfigured = usbVideoDecoderConfigured;
if (typeof globalThis !== 'undefined') globalThis.usbVideoConfig = usbVideoConfig;
if (typeof globalThis !== 'undefined') globalThis.usbH264SawKeyframe = usbH264SawKeyframe;
if (typeof globalThis !== 'undefined') globalThis.shouldAutoReconnect = shouldAutoReconnect;
if (typeof globalThis !== 'undefined') globalThis.statusDetailMessage = statusDetailMessage;
if (typeof globalThis !== 'undefined') globalThis.reconnectAttempts = reconnectAttempts;
if (typeof globalThis !== 'undefined') globalThis.MAX_RECONNECT_ATTEMPTS = MAX_RECONNECT_ATTEMPTS;
if (typeof globalThis !== 'undefined') globalThis.reconnectTimeoutId = reconnectTimeoutId;
if (typeof globalThis !== 'undefined') globalThis.isReconnecting = isReconnecting;
if (typeof globalThis !== 'undefined') globalThis.reconnectCloseInProgress = reconnectCloseInProgress;
if (typeof globalThis !== 'undefined') globalThis.initialTransport = initialTransport;
if (typeof globalThis !== 'undefined') globalThis.sampleUsbPerfStatus = sampleUsbPerfStatus;
if (typeof globalThis !== 'undefined') globalThis.startUsbPerfPolling = startUsbPerfPolling;
if (typeof globalThis !== 'undefined') globalThis.stopUsbPerfPolling = stopUsbPerfPolling;
if (typeof globalThis !== 'undefined') globalThis.isSocketActive = isSocketActive;
if (typeof globalThis !== 'undefined') globalThis.isMirrorConnectionActive = isMirrorConnectionActive;
if (typeof globalThis !== 'undefined') globalThis.disconnectMirrorFromButton = disconnectMirrorFromButton;
if (typeof globalThis !== 'undefined') globalThis.setTransport = setTransport;
if (typeof globalThis !== 'undefined') globalThis.setupTransportControls = setupTransportControls;
if (typeof globalThis !== 'undefined') globalThis.loadStreamQualityStatus = loadStreamQualityStatus;
if (typeof globalThis !== 'undefined') globalThis.setStreamQualityMode = setStreamQualityMode;
if (typeof globalThis !== 'undefined') globalThis.connectSignaling = connectSignaling;
if (typeof globalThis !== 'undefined') globalThis.handleSignalingMessage = handleSignalingMessage;
if (typeof globalThis !== 'undefined') globalThis.connectMirror = connectMirror;
if (typeof globalThis !== 'undefined') globalThis.usbSessionUrl = usbSessionUrl;
if (typeof globalThis !== 'undefined') globalThis.connectUsbSession = connectUsbSession;
if (typeof globalThis !== 'undefined') globalThis.handleUsbTextMessage = handleUsbTextMessage;
if (typeof globalThis !== 'undefined') globalThis.hasUsbH264Support = hasUsbH264Support;
if (typeof globalThis !== 'undefined') globalThis.preferredUsbCodec = preferredUsbCodec;
if (typeof globalThis !== 'undefined') globalThis.handleUsbVideoConfig = handleUsbVideoConfig;
if (typeof globalThis !== 'undefined') globalThis.reconnectUsbAsJpeg = reconnectUsbAsJpeg;
if (typeof globalThis !== 'undefined') globalThis.closeUsbVideoDecoder = closeUsbVideoDecoder;
if (typeof globalThis !== 'undefined') globalThis.normalizeArrayBuffer = normalizeArrayBuffer;
if (typeof globalThis !== 'undefined') globalThis.decodeUsbH264Packet = decodeUsbH264Packet;
if (typeof globalThis !== 'undefined') globalThis.drawDecodedUsbFrame = drawDecodedUsbFrame;
if (typeof globalThis !== 'undefined') globalThis.renderUsbFrame = renderUsbFrame;
if (typeof globalThis !== 'undefined') globalThis.handleStatusMessage = handleStatusMessage;
if (typeof globalThis !== 'undefined') globalThis.applyAndroidStatusMessage = applyAndroidStatusMessage;
if (typeof globalThis !== 'undefined') globalThis.loadFavoriteApps = loadFavoriteApps;
if (typeof globalThis !== 'undefined') globalThis.launchFavoriteApp = launchFavoriteApp;
if (typeof globalThis !== 'undefined') globalThis.handleConnectBtnClick = handleConnectBtnClick;
if (typeof globalThis !== 'undefined') globalThis.closeUsbSocket = closeUsbSocket;
if (typeof globalThis !== 'undefined') globalThis.closeSignalingSocket = closeSignalingSocket;
if (typeof globalThis !== 'undefined') globalThis.disconnectCurrentTransport = disconnectCurrentTransport;
if (typeof globalThis !== 'undefined') globalThis.triggerAutoReconnect = triggerAutoReconnect;
if (typeof globalThis !== 'undefined') globalThis.startReconnectSequence = startReconnectSequence;
if (typeof globalThis !== 'undefined') globalThis.showReconnectOverlayProgress = showReconnectOverlayProgress;
if (typeof globalThis !== 'undefined') globalThis.showReconnectOverlayFailed = showReconnectOverlayFailed;
if (typeof globalThis !== 'undefined') globalThis.hideReconnectOverlay = hideReconnectOverlay;
if (typeof globalThis !== 'undefined') globalThis.enterScreenCaptureApprovalWait = enterScreenCaptureApprovalWait;
if (typeof globalThis !== 'undefined') globalThis.handleVisibilityChange = handleVisibilityChange;
if (typeof globalThis !== 'undefined') { globalThis._set_socket = _set_socket; globalThis._get_socket = _get_socket; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbSocket = _set_usbSocket; globalThis._get_usbSocket = _get_usbSocket; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbPerfPollId = _set_usbPerfPollId; globalThis._get_usbPerfPollId = _get_usbPerfPollId; }
if (typeof globalThis !== 'undefined') { globalThis._set_selectedTransport = _set_selectedTransport; globalThis._get_selectedTransport = _get_selectedTransport; }
if (typeof globalThis !== 'undefined') { globalThis._set_lastUsbFrameUrl = _set_lastUsbFrameUrl; globalThis._get_lastUsbFrameUrl = _get_lastUsbFrameUrl; }
if (typeof globalThis !== 'undefined') { globalThis._set_activeUsbCodec = _set_activeUsbCodec; globalThis._get_activeUsbCodec = _get_activeUsbCodec; }
if (typeof globalThis !== 'undefined') { globalThis._set_forceUsbJpegFallback = _set_forceUsbJpegFallback; globalThis._get_forceUsbJpegFallback = _get_forceUsbJpegFallback; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbVideoDecoder = _set_usbVideoDecoder; globalThis._get_usbVideoDecoder = _get_usbVideoDecoder; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbVideoDecoderConfigured = _set_usbVideoDecoderConfigured; globalThis._get_usbVideoDecoderConfigured = _get_usbVideoDecoderConfigured; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbVideoConfig = _set_usbVideoConfig; globalThis._get_usbVideoConfig = _get_usbVideoConfig; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbH264SawKeyframe = _set_usbH264SawKeyframe; globalThis._get_usbH264SawKeyframe = _get_usbH264SawKeyframe; }
if (typeof globalThis !== 'undefined') { globalThis._set_shouldAutoReconnect = _set_shouldAutoReconnect; globalThis._get_shouldAutoReconnect = _get_shouldAutoReconnect; }
if (typeof globalThis !== 'undefined') { globalThis._set_statusDetailMessage = _set_statusDetailMessage; globalThis._get_statusDetailMessage = _get_statusDetailMessage; }
if (typeof globalThis !== 'undefined') { globalThis._set_reconnectAttempts = _set_reconnectAttempts; globalThis._get_reconnectAttempts = _get_reconnectAttempts; }
if (typeof globalThis !== 'undefined') { globalThis._set_MAX_RECONNECT_ATTEMPTS = _set_MAX_RECONNECT_ATTEMPTS; globalThis._get_MAX_RECONNECT_ATTEMPTS = _get_MAX_RECONNECT_ATTEMPTS; }
if (typeof globalThis !== 'undefined') { globalThis._set_reconnectTimeoutId = _set_reconnectTimeoutId; globalThis._get_reconnectTimeoutId = _get_reconnectTimeoutId; }
if (typeof globalThis !== 'undefined') { globalThis._set_isReconnecting = _set_isReconnecting; globalThis._get_isReconnecting = _get_isReconnecting; }
if (typeof globalThis !== 'undefined') { globalThis._set_reconnectCloseInProgress = _set_reconnectCloseInProgress; globalThis._get_reconnectCloseInProgress = _get_reconnectCloseInProgress; }
