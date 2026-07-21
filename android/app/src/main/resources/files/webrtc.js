import { remoteVideo, keyboardSink, connectBtn, wsIndicator, wsStatus, rtcStatus, streamStatusLabel, rtcLatencyItem, controlStatus, accessibilityStatus, favoriteAppsList, statusDetail, logBox, uploadUsage, downloadUsage, rtcLatency, usbCanvas, connectionPlaceholder, usbCanvasCtx, getUsbCanvasContext, usbFrame, transportTailscaleBtn, transportUsbBtn, qualityMode, qualityEffective, qualityNetwork, qualityNetworkItem, usbCoolingStatusItem, usbCoolingStatus, toolsPanel, qualityAutoBtn, qualityDataSaverBtn, qualityStandardBtn, qualityHighBtn, navRecentsBtn, navHomeBtn, navBackBtn, clipboardHistory, MAX_CLIPBOARD_HISTORY, updateVideoAspectRatio, streamQualityButtons, logQueue, logFrameRequested, flushLogs, log, showStatusDetail, formatMegabytes, lastUploadUsageText, lastDownloadUsageText, updateDataUsageDisplay, resetDataUsageStats, formatBitrate, formatBytesPerSecond, renderStreamQualityStatus, renderUsbCoolingStatus, hideUsbCoolingStatus, setHidden, renderTransportSelection, updateConnectButtonState, showConnectionPlaceholder, hideConnectionPlaceholder, resetConnectionStatus, renderFavoriteApps, renderClipboardHistory, clearClipboardBtn, handleClearClipboardBtnClick, addClipboardToHistory, clearRemoteVideoFrame, clearUsbFrame, showGlowToast } from './ui.js';
import { bindTouchSurface, accessibilityReady, touchControlInitialized, keyControlInitialized, navigationControlInitialized, keyboardControl, nextTextSeq, inFlightTextSeq, queuedTextPayloads, ackTimeoutId, focusKeyboardCapture, sendControlPayload, sendAndroidKey, sendSequencedTextPayload, resetTextControlState, flushNextQueuedTextPayload, handleControlAck, hasClipboardWriteApi, hasClipboardReadApi, showManualClipboardFallback, writeClipboardFromAndroid, readClipboardForAndroid, getNormalizedCoords, unbindTouchSurface, destroyTouchControl, setupTouchControl, documentKeydownHandler, keyboardListeners, createEventInterceptor, interceptKeyboardControl, destroyKeyControl, setupKeyControl, setupNavigationControls, setupStreamQualityControls, setupSystemControls, documentCopyHandler, destroyClipboardSync, setupClipboardSync } from './controls.js';
import { socket, usbSocket, usbPerfPollId, selectedTransport, lastUsbFrameUrl, activeUsbCodec, forceUsbJpegFallback, usbVideoDecoder, usbVideoDecoderConfigured, usbVideoConfig, usbH264SawKeyframe, shouldAutoReconnect, statusDetailMessage, reconnectAttempts, MAX_RECONNECT_ATTEMPTS, reconnectTimeoutId, isReconnecting, reconnectCloseInProgress, initialTransport, sampleUsbPerfStatus, startUsbPerfPolling, stopUsbPerfPolling, isSocketActive, isMirrorConnectionActive, disconnectMirrorFromButton, setTransport, setupTransportControls, loadStreamQualityStatus, setStreamQualityMode, connectSignaling, handleSignalingMessage, connectMirror, usbSessionUrl, connectUsbSession, handleUsbTextMessage, hasUsbH264Support, preferredUsbCodec, handleUsbVideoConfig, reconnectUsbAsJpeg, closeUsbVideoDecoder, normalizeArrayBuffer, decodeUsbH264Packet, drawDecodedUsbFrame, renderUsbFrame, handleStatusMessage, applyAndroidStatusMessage, loadFavoriteApps, launchFavoriteApp, handleConnectBtnClick, closeUsbSocket, closeSignalingSocket, disconnectCurrentTransport, triggerAutoReconnect, startReconnectSequence, showReconnectOverlayProgress, showReconnectOverlayFailed, hideReconnectOverlay, enterScreenCaptureApprovalWait, handleVisibilityChange } from './signaling.js';

export let peerConnection = null;
export function _set_peerConnection(val) { peerConnection = val; }
export function _get_peerConnection() { return peerConnection; }
export let dataChannel = null;
export function _set_dataChannel(val) { dataChannel = val; }
export function _get_dataChannel() { return dataChannel; }
export let remoteDescriptionSet = false;
export function _set_remoteDescriptionSet(val) { remoteDescriptionSet = val; }
export function _get_remoteDescriptionSet() { return remoteDescriptionSet; }
export let pendingRemoteCandidates = [];
export function _set_pendingRemoteCandidates(val) { pendingRemoteCandidates = val; }
export function _get_pendingRemoteCandidates() { return pendingRemoteCandidates; }
export let dataUsagePollId = null;
export function _set_dataUsagePollId(val) { dataUsagePollId = val; }
export function _get_dataUsagePollId() { return dataUsagePollId; }
export let lastNetworkBytes = null;
export function _set_lastNetworkBytes(val) { lastNetworkBytes = val; }
export function _get_lastNetworkBytes() { return lastNetworkBytes; }
export let accumulatedNetworkBytes = {
  sent: 0,
  received: 0
};
export function _set_accumulatedNetworkBytes(val) { accumulatedNetworkBytes = val; }
export function _get_accumulatedNetworkBytes() { return accumulatedNetworkBytes; }
export const rtcConfig = {
  iceServers: [{
    urls: 'stun:stun.l.google.com:19302'
  }]
};
export function _set_rtcConfig(val) { rtcConfig = val; }
export function _get_rtcConfig() { return rtcConfig; }
export function resetNetworkBytes() {
  accumulatedNetworkBytes = {
    sent: 0,
    received: 0
  };
  lastNetworkBytes = null;
}
export function extractNetworkBytes(stats) {
  let selectedCandidatePairId = null;
  let selectedPair = null;
  let fallbackSent = 0;
  let fallbackReceived = 0;
  stats.forEach(report => {
    if (report.type === 'transport' && report.selectedCandidatePairId) {
      selectedCandidatePairId = report.selectedCandidatePairId;
    }
  });
  if (selectedCandidatePairId && typeof stats.get === 'function') {
    selectedPair = stats.get(selectedCandidatePairId);
  }
  stats.forEach(report => {
    if (!selectedPair && report.type === 'candidate-pair' && report.state === 'succeeded' && (report.selected || report.nominated)) {
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
export async function sampleWebRtcStats() {
  if (!peerConnection || typeof peerConnection.getStats !== 'function') return;
  try {
    const stats = await peerConnection.getStats();
    const current = extractNetworkBytes(stats);
    let rtt = null;
    stats.forEach(report => {
      if (report.type === 'candidate-pair' && report.state === 'succeeded' && (report.selected || report.nominated)) {
        if (typeof report.currentRoundTripTime === 'number') {
          rtt = report.currentRoundTripTime;
        }
      }
    });
    if (rtcLatency) {
      if (typeof rtt === 'number') {
        rtcLatency.textContent = `${(rtt * 1000).toFixed(0)} ms`;
      } else {
        rtcLatency.textContent = '확인 중';
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
export function startDataUsagePolling() {
  stopDataUsagePolling();
  resetDataUsageStats();
  dataUsagePollId = setInterval(sampleWebRtcStats, 1000);
  sampleWebRtcStats();
}
export function stopDataUsagePolling() {
  if (dataUsagePollId) {
    clearInterval(dataUsagePollId);
    dataUsagePollId = null;
  }
}
export async function setupWebRTC(signalingSocket = socket) {
  log("WebRTC PeerConnection 생성 및 초기화 중...");
  stopDataUsagePolling();
  resetDataUsageStats();
  const currentPeerConnection = new RTCPeerConnection(rtcConfig);
  peerConnection = currentPeerConnection;
  remoteDescriptionSet = false;
  pendingRemoteCandidates = [];
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
  currentPeerConnection.addTransceiver('video', {
    direction: 'recvonly'
  });
  dataChannel = currentPeerConnection.createDataChannel('control', {
    ordered: true
  });
  setupDataChannelHandlers(dataChannel);
  currentPeerConnection.ontrack = event => {
    if (peerConnection !== currentPeerConnection) return;
    log("Android 실시간 화면 비디오 트랙 감지!");
    if (event.streams && event.streams[0]) {
      remoteVideo.srcObject = event.streams[0];
      hideConnectionPlaceholder();
      rtcStatus.innerText = "Streaming Active";
      focusKeyboardCapture();
      log("비디오 소스 스트림 렌더링 시작.");
    }
  };
  currentPeerConnection.onicecandidate = event => {
    if (event.candidate && socket === signalingSocket && signalingSocket.readyState === WebSocket.OPEN) {
      signalingSocket.send(JSON.stringify({
        type: 'ICE_CANDIDATE',
        payload: event.candidate
      }));
    }
  };
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
export async function addRemoteCandidate(candidate) {
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
export async function flushPendingRemoteCandidates() {
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
export function setupDataChannelHandlers(channel) {
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
  channel.onmessage = event => {
    if (dataChannel !== channel) return;
    log(`DataChannel 수신 메시지: ${event.data}`);
    try {
      const message = JSON.parse(event.data);
      if (message.type === 'CONTROL_ACK') {
        handleControlAck(message.payload || ({}));
      } else if (message.type === 'clipboard') {
        const text = message.text;
        if (typeof text === 'string') {
          addClipboardToHistory(text);
          writeClipboardFromAndroid(text);
        }
      }
    } catch (error) {
      log(`DataChannel 메시지 처리 실패: ${error.message}`);
    }
  };
}
export function cleanupPeerConnection() {
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
  destroyTouchControl();
  destroyKeyControl();
}

// Attach to globalThis
if (typeof globalThis !== 'undefined') globalThis.peerConnection = peerConnection;
if (typeof globalThis !== 'undefined') globalThis.dataChannel = dataChannel;
if (typeof globalThis !== 'undefined') globalThis.remoteDescriptionSet = remoteDescriptionSet;
if (typeof globalThis !== 'undefined') globalThis.pendingRemoteCandidates = pendingRemoteCandidates;
if (typeof globalThis !== 'undefined') globalThis.dataUsagePollId = dataUsagePollId;
if (typeof globalThis !== 'undefined') globalThis.lastNetworkBytes = lastNetworkBytes;
if (typeof globalThis !== 'undefined') globalThis.accumulatedNetworkBytes = accumulatedNetworkBytes;
if (typeof globalThis !== 'undefined') globalThis.rtcConfig = rtcConfig;
if (typeof globalThis !== 'undefined') globalThis.resetNetworkBytes = resetNetworkBytes;
if (typeof globalThis !== 'undefined') globalThis.extractNetworkBytes = extractNetworkBytes;
if (typeof globalThis !== 'undefined') globalThis.sampleWebRtcStats = sampleWebRtcStats;
if (typeof globalThis !== 'undefined') globalThis.startDataUsagePolling = startDataUsagePolling;
if (typeof globalThis !== 'undefined') globalThis.stopDataUsagePolling = stopDataUsagePolling;
if (typeof globalThis !== 'undefined') globalThis.setupWebRTC = setupWebRTC;
if (typeof globalThis !== 'undefined') globalThis.addRemoteCandidate = addRemoteCandidate;
if (typeof globalThis !== 'undefined') globalThis.flushPendingRemoteCandidates = flushPendingRemoteCandidates;
if (typeof globalThis !== 'undefined') globalThis.setupDataChannelHandlers = setupDataChannelHandlers;
if (typeof globalThis !== 'undefined') globalThis.cleanupPeerConnection = cleanupPeerConnection;
if (typeof globalThis !== 'undefined') { globalThis._set_peerConnection = _set_peerConnection; globalThis._get_peerConnection = _get_peerConnection; }
if (typeof globalThis !== 'undefined') { globalThis._set_dataChannel = _set_dataChannel; globalThis._get_dataChannel = _get_dataChannel; }
if (typeof globalThis !== 'undefined') { globalThis._set_remoteDescriptionSet = _set_remoteDescriptionSet; globalThis._get_remoteDescriptionSet = _get_remoteDescriptionSet; }
if (typeof globalThis !== 'undefined') { globalThis._set_pendingRemoteCandidates = _set_pendingRemoteCandidates; globalThis._get_pendingRemoteCandidates = _get_pendingRemoteCandidates; }
if (typeof globalThis !== 'undefined') { globalThis._set_dataUsagePollId = _set_dataUsagePollId; globalThis._get_dataUsagePollId = _get_dataUsagePollId; }
if (typeof globalThis !== 'undefined') { globalThis._set_lastNetworkBytes = _set_lastNetworkBytes; globalThis._get_lastNetworkBytes = _get_lastNetworkBytes; }
if (typeof globalThis !== 'undefined') { globalThis._set_accumulatedNetworkBytes = _set_accumulatedNetworkBytes; globalThis._get_accumulatedNetworkBytes = _get_accumulatedNetworkBytes; }
if (typeof globalThis !== 'undefined') { globalThis._set_rtcConfig = _set_rtcConfig; globalThis._get_rtcConfig = _get_rtcConfig; }
