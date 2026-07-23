import { peerConnection, dataChannel, remoteDescriptionSet, pendingRemoteCandidates, dataUsagePollId, lastNetworkBytes, accumulatedNetworkBytes, rtcConfig, resetNetworkBytes, extractNetworkBytes, sampleWebRtcStats, startDataUsagePolling, stopDataUsagePolling, setupWebRTC, addRemoteCandidate, flushPendingRemoteCandidates, setupDataChannelHandlers, cleanupPeerConnection } from './webrtc.js';
import { bindTouchSurface, accessibilityReady, touchControlInitialized, keyControlInitialized, navigationControlInitialized, keyboardControl, nextTextSeq, inFlightTextSeq, queuedTextPayloads, ackTimeoutId, focusKeyboardCapture, sendControlPayload, sendAndroidKey, sendSequencedTextPayload, resetTextControlState, flushNextQueuedTextPayload, handleControlAck, hasClipboardWriteApi, hasClipboardReadApi, showManualClipboardFallback, writeClipboardFromAndroid, readClipboardForAndroid, getNormalizedCoords, unbindTouchSurface, destroyTouchControl, setupTouchControl, documentKeydownHandler, keyboardListeners, createEventInterceptor, interceptKeyboardControl, destroyKeyControl, setupKeyControl, setupNavigationControls, setupStreamQualityControls, setupSystemControls, documentCopyHandler, destroyClipboardSync, setupClipboardSync, _set_accessibilityReady } from './controls.js';
import { socket, usbSocket, usbPerfPollId, selectedTransport, lastUsbFrameUrl, activeUsbCodec, forceUsbJpegFallback, usbVideoDecoder, usbVideoDecoderConfigured, usbVideoConfig, usbH264SawKeyframe, shouldAutoReconnect, statusDetailMessage, reconnectAttempts, MAX_RECONNECT_ATTEMPTS, reconnectTimeoutId, isReconnecting, reconnectCloseInProgress, initialTransport, sampleUsbPerfStatus, startUsbPerfPolling, stopUsbPerfPolling, isSocketActive, isMirrorConnectionActive, disconnectMirrorFromButton, setTransport, setupTransportControls, loadStreamQualityStatus, setStreamQualityMode, connectSignaling, handleSignalingMessage, connectMirror, usbSessionUrl, connectUsbSession, handleUsbTextMessage, hasUsbH264Support, preferredUsbCodec, handleUsbVideoConfig, reconnectUsbAsJpeg, closeUsbVideoDecoder, normalizeArrayBuffer, decodeUsbH264Packet, drawDecodedUsbFrame, renderUsbFrame, handleStatusMessage, applyAndroidStatusMessage, loadFavoriteApps, launchFavoriteApp, handleConnectBtnClick, closeUsbSocket, closeSignalingSocket, disconnectCurrentTransport, triggerAutoReconnect, startReconnectSequence, showReconnectOverlayProgress, showReconnectOverlayFailed, hideReconnectOverlay, enterScreenCaptureApprovalWait, handleVisibilityChange } from './signaling.js';

export const remoteVideo = document.getElementById('remoteVideo');
export function _set_remoteVideo(val) { remoteVideo = val; }
export function _get_remoteVideo() { return remoteVideo; }
export const keyboardSink = document.getElementById('keyboardSink');
export function _set_keyboardSink(val) { keyboardSink = val; }
export function _get_keyboardSink() { return keyboardSink; }
export const connectBtn = document.getElementById('connectBtn');
export function _set_connectBtn(val) { connectBtn = val; }
export function _get_connectBtn() { return connectBtn; }
export const wsIndicator = document.getElementById('wsIndicator');
export function _set_wsIndicator(val) { wsIndicator = val; }
export function _get_wsIndicator() { return wsIndicator; }
export const wsStatus = document.getElementById('wsStatus');
export function _set_wsStatus(val) { wsStatus = val; }
export function _get_wsStatus() { return wsStatus; }
export const rtcStatus = document.getElementById('rtcStatus');
export function _set_rtcStatus(val) { rtcStatus = val; }
export function _get_rtcStatus() { return rtcStatus; }
export const streamStatusLabel = document.getElementById('streamStatusLabel');
export function _set_streamStatusLabel(val) { streamStatusLabel = val; }
export function _get_streamStatusLabel() { return streamStatusLabel; }
export const rtcLatencyItem = document.getElementById('rtcLatencyItem');
export function _set_rtcLatencyItem(val) { rtcLatencyItem = val; }
export function _get_rtcLatencyItem() { return rtcLatencyItem; }
export const controlStatus = document.getElementById('controlStatus');
export function _set_controlStatus(val) { controlStatus = val; }
export function _get_controlStatus() { return controlStatus; }
export const accessibilityStatus = document.getElementById('accessibilityStatus');
export function _set_accessibilityStatus(val) { accessibilityStatus = val; }
export function _get_accessibilityStatus() { return accessibilityStatus; }
export const favoriteAppsList = document.getElementById('favoriteAppsList');
export function _set_favoriteAppsList(val) { favoriteAppsList = val; }
export function _get_favoriteAppsList() { return favoriteAppsList; }
export const statusDetail = document.getElementById('statusDetail');
export function _set_statusDetail(val) { statusDetail = val; }
export function _get_statusDetail() { return statusDetail; }
export const logBox = document.getElementById('logBox');
export function _set_logBox(val) { logBox = val; }
export function _get_logBox() { return logBox; }
export const uploadUsage = document.getElementById('uploadUsage');
export function _set_uploadUsage(val) { uploadUsage = val; }
export function _get_uploadUsage() { return uploadUsage; }
export const downloadUsage = document.getElementById('downloadUsage');
export function _set_downloadUsage(val) { downloadUsage = val; }
export function _get_downloadUsage() { return downloadUsage; }
export const rtcLatency = document.getElementById('rtcLatency');
export function _set_rtcLatency(val) { rtcLatency = val; }
export function _get_rtcLatency() { return rtcLatency; }
export const usbCanvas = document.getElementById('usbCanvas');
export function _set_usbCanvas(val) { usbCanvas = val; }
export function _get_usbCanvas() { return usbCanvas; }
export const connectionPlaceholder = document.getElementById('connectionPlaceholder');
export function _set_connectionPlaceholder(val) { connectionPlaceholder = val; }
export function _get_connectionPlaceholder() { return connectionPlaceholder; }
export let usbCanvasCtx = null;
export function _set_usbCanvasCtx(val) { usbCanvasCtx = val; }
export function _get_usbCanvasCtx() { return usbCanvasCtx; }
export function getUsbCanvasContext() {
  if (!usbCanvasCtx && usbCanvas) usbCanvasCtx = usbCanvas.getContext('2d');
  return usbCanvasCtx;
}
export let usbFrame = null;
export function _set_usbFrame(val) { usbFrame = val; }
export function _get_usbFrame() { return usbFrame; }
export const transportTailscaleBtn = document.getElementById('transportTailscaleBtn');
export function _set_transportTailscaleBtn(val) { transportTailscaleBtn = val; }
export function _get_transportTailscaleBtn() { return transportTailscaleBtn; }
export const transportUsbBtn = document.getElementById('transportUsbBtn');
export function _set_transportUsbBtn(val) { transportUsbBtn = val; }
export function _get_transportUsbBtn() { return transportUsbBtn; }
export const qualityMode = document.getElementById('qualityMode');
export function _set_qualityMode(val) { qualityMode = val; }
export function _get_qualityMode() { return qualityMode; }
export const qualityEffective = document.getElementById('qualityEffective');
export function _set_qualityEffective(val) { qualityEffective = val; }
export function _get_qualityEffective() { return qualityEffective; }
export const qualityNetwork = document.getElementById('qualityNetwork');
export function _set_qualityNetwork(val) { qualityNetwork = val; }
export function _get_qualityNetwork() { return qualityNetwork; }
export const qualityNetworkItem = document.getElementById('qualityNetworkItem');
export function _set_qualityNetworkItem(val) { qualityNetworkItem = val; }
export function _get_qualityNetworkItem() { return qualityNetworkItem; }
export const usbCoolingStatusItem = document.getElementById('usbCoolingStatusItem');
export function _set_usbCoolingStatusItem(val) { usbCoolingStatusItem = val; }
export function _get_usbCoolingStatusItem() { return usbCoolingStatusItem; }
export const usbCoolingStatus = document.getElementById('usbCoolingStatus');
export function _set_usbCoolingStatus(val) { usbCoolingStatus = val; }
export function _get_usbCoolingStatus() { return usbCoolingStatus; }
export const toolsPanel = document.getElementById('toolsPanel');
export function _set_toolsPanel(val) { toolsPanel = val; }
export function _get_toolsPanel() { return toolsPanel; }
export const qualityAutoBtn = document.getElementById('qualityAutoBtn');
export function _set_qualityAutoBtn(val) { qualityAutoBtn = val; }
export function _get_qualityAutoBtn() { return qualityAutoBtn; }
export const qualityDataSaverBtn = document.getElementById('qualityDataSaverBtn');
export function _set_qualityDataSaverBtn(val) { qualityDataSaverBtn = val; }
export function _get_qualityDataSaverBtn() { return qualityDataSaverBtn; }
export const qualityStandardBtn = document.getElementById('qualityStandardBtn');
export function _set_qualityStandardBtn(val) { qualityStandardBtn = val; }
export function _get_qualityStandardBtn() { return qualityStandardBtn; }
export const qualityHighBtn = document.getElementById('qualityHighBtn');
export function _set_qualityHighBtn(val) { qualityHighBtn = val; }
export function _get_qualityHighBtn() { return qualityHighBtn; }
export const btnBlackOverlay = document.getElementById('btn-black-overlay');
export function _set_btnBlackOverlay(val) { btnBlackOverlay = val; }
export function _get_btnBlackOverlay() { return btnBlackOverlay; }
export let isBlackOverlayActive = false;
export function _set_isBlackOverlayActive(val) { isBlackOverlayActive = val; }
export function _get_isBlackOverlayActive() { return isBlackOverlayActive; }
export function updateBlackOverlayStatus(enabled) {
  isBlackOverlayActive = Boolean(enabled);
  if (!btnBlackOverlay) return;
  if (isBlackOverlayActive) {
    btnBlackOverlay.classList.add('active');
    btnBlackOverlay.title = "블랙 오버레이 차단 ON (클릭 시 해제)";
  } else {
    btnBlackOverlay.classList.remove('active');
    btnBlackOverlay.title = "블랙 오버레이 차단 OFF (클릭 시 차단)";
  }
}
export const btnAutoFit = document.getElementById('btn-auto-fit');
export function _set_btnAutoFit(val) { btnAutoFit = val; }
export function _get_btnAutoFit() { return btnAutoFit; }
export let isAutoFitActive = false;
export function _set_isAutoFitActive(val) { isAutoFitActive = val; }
export function _get_isAutoFitActive() { return isAutoFitActive; }
export function updateAutoFitStatus(enabled) {
  isAutoFitActive = Boolean(enabled);
  if (!btnAutoFit) return;
  if (isAutoFitActive) {
    btnAutoFit.classList.add('active');
    btnAutoFit.title = "창 맞춤 미러링 ON (클릭 시 폰 비율 고정)";
  } else {
    btnAutoFit.classList.remove('active');
    btnAutoFit.title = "창 맞춤 미러링 OFF (클릭 시 창 맞춤 전환)";
  }
}
export const navRecentsBtn = document.getElementById('navRecentsBtn');
export function _set_navRecentsBtn(val) { navRecentsBtn = val; }
export function _get_navRecentsBtn() { return navRecentsBtn; }
export const navHomeBtn = document.getElementById('navHomeBtn');
export function _set_navHomeBtn(val) { navHomeBtn = val; }
export function _get_navHomeBtn() { return navHomeBtn; }
export const navBackBtn = document.getElementById('navBackBtn');
export function _set_navBackBtn(val) { navBackBtn = val; }
export function _get_navBackBtn() { return navBackBtn; }
export let clipboardHistory = [];
export function _set_clipboardHistory(val) { clipboardHistory = val; }
export function _get_clipboardHistory() { return clipboardHistory; }
export const MAX_CLIPBOARD_HISTORY = 30;
export function _set_MAX_CLIPBOARD_HISTORY(val) { MAX_CLIPBOARD_HISTORY = val; }
export function _get_MAX_CLIPBOARD_HISTORY() { return MAX_CLIPBOARD_HISTORY; }
export function updateVideoAspectRatio() {
  const videoContainer = document.getElementById('videoContainer');
  if (!videoContainer || !remoteVideo || !remoteVideo.videoWidth || !remoteVideo.videoHeight) return;
  videoContainer.style.aspectRatio = `${remoteVideo.videoWidth} / ${remoteVideo.videoHeight}`;
  log(`비디오 컨테이너 aspect-ratio 갱신: ${remoteVideo.videoWidth}x${remoteVideo.videoHeight}`);
}
export const streamQualityButtons = [{
  mode: 'AUTO',
  element: qualityAutoBtn
}, {
  mode: 'DATA_SAVER',
  element: qualityDataSaverBtn
}, {
  mode: 'STANDARD',
  element: qualityStandardBtn
}, {
  mode: 'HIGH',
  element: qualityHighBtn
}];
export function _set_streamQualityButtons(val) { streamQualityButtons = val; }
export function _get_streamQualityButtons() { return streamQualityButtons; }
export let logQueue = [];
export function _set_logQueue(val) { logQueue = val; }
export function _get_logQueue() { return logQueue; }
export let logFrameRequested = false;
export function _set_logFrameRequested(val) { logFrameRequested = val; }
export function _get_logFrameRequested() { return logFrameRequested; }
export function flushLogs() {
  logFrameRequested = false;
  if (logQueue.length === 0) return;
  const fragment = document.createDocumentFragment();
  for (const msg of logQueue) {
    const time = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.textContent = `[${time}] ${msg}`;
    fragment.appendChild(entry);
  }
  logQueue = [];
  logBox.appendChild(fragment);
  while (logBox.childElementCount > 200) {
    logBox.removeChild(logBox.firstChild);
  }
  logBox.scrollTop = logBox.scrollHeight;
}
export function log(msg) {
  console.log(msg);
  logQueue.push(msg);
  if (!logFrameRequested) {
    logFrameRequested = true;
    requestAnimationFrame(flushLogs);
  }
}
export function showStatusDetail(text, tone = '') {
  if (!statusDetail) return;
  statusDetail.className = `status-detail${tone ? ` ${tone}` : ''}`;
  statusDetail.textContent = text;
}
export function formatMegabytes(bytes) {
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
export let lastUploadUsageText = null;
export function _set_lastUploadUsageText(val) { lastUploadUsageText = val; }
export function _get_lastUploadUsageText() { return lastUploadUsageText; }
export let lastDownloadUsageText = null;
export function _set_lastDownloadUsageText(val) { lastDownloadUsageText = val; }
export function _get_lastDownloadUsageText() { return lastDownloadUsageText; }
export function updateDataUsageDisplay() {
  if (uploadUsage) {
    const text = formatMegabytes(accumulatedNetworkBytes.sent);
    if (text !== lastUploadUsageText) {
      uploadUsage.textContent = text;
      lastUploadUsageText = text;
    }
  }
  if (downloadUsage) {
    const text = formatMegabytes(accumulatedNetworkBytes.received);
    if (text !== lastDownloadUsageText) {
      downloadUsage.textContent = text;
      lastDownloadUsageText = text;
    }
  }
}
export function resetDataUsageStats() {
  resetNetworkBytes();
  lastUploadUsageText = null;
  lastDownloadUsageText = null;
  updateDataUsageDisplay();
}
export function formatBitrate(maxBitrateBps) {
  if (typeof maxBitrateBps !== 'number' || Number.isNaN(maxBitrateBps)) return '';
  return `${(maxBitrateBps / 1_000_000).toFixed(1)}Mbps`;
}
export function formatBytesPerSecond(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return '0.0 MB/s';
  return `${(value / 1_000_000).toFixed(1)} MB/s`;
}
export function renderStreamQualityStatus(payload = {}) {
  const selectedMode = payload.selectedMode || 'AUTO';
  const selectedLabel = payload.selectedLabel || selectedMode;
  const effectiveLabel = payload.effectiveLabel || payload.effectiveMode || '확인 중';
  const networkLabel = payload.networkLabel || payload.networkTransport || '확인 중';
  const activityLabel = payload.activityState === 'IDLE' ? '대기 절약 중' : '활성';
  const resolution = payload.width && payload.height && payload.fps ? `${payload.width}x${payload.height} ${payload.fps}fps` : '';
  const bitrate = formatBitrate(payload.maxBitrateBps);
  const effectiveText = [effectiveLabel, activityLabel, resolution, bitrate].filter(Boolean).join(' · ');
  if (qualityMode) qualityMode.textContent = selectedLabel;
  if (qualityEffective) qualityEffective.textContent = effectiveText || '확인 중';
  if (qualityNetwork) qualityNetwork.textContent = networkLabel;
  streamQualityButtons.forEach(({mode, element}) => {
    if (!element) return;
    if (mode === selectedMode) {
      element.classList.add('active');
    } else {
      element.classList.remove('active');
    }
  });
}
export function renderUsbCoolingStatus(streamQuality, usbPerf) {
  if (!usbCoolingStatus || !usbCoolingStatusItem || !streamQuality) return;
  const tier = streamQuality.effectiveTier || streamQuality.tier || 'USB';
  const width = streamQuality.effectiveWidth || streamQuality.width || '-';
  const height = streamQuality.effectiveHeight || streamQuality.height || '-';
  const fps = streamQuality.effectiveFps || streamQuality.fps || '-';
  const jpegQuality = streamQuality.jpegQuality || '-';
  const codec = String(streamQuality.effectiveCodec || streamQuality.codec || streamQuality.videoCodec || '').toLowerCase();
  const isH264 = codec === 'h264' || codec === 'h.264' || codec.startsWith('avc1');
  const bitrate = formatBitrate(streamQuality.effectiveMaxBitrateBps || streamQuality.maxBitrateBps || streamQuality.bitrateBps || 0);
  const thermalStatus = usbPerf?.thermalStatus || 'UNKNOWN';
  const bytesPerSecond = formatBytesPerSecond(usbPerf?.bytesPerSecond || 0);
  const encodeMillis = Number.isFinite(usbPerf?.lastEncodeMillis) ? `encode ${usbPerf.lastEncodeMillis}ms` : 'encode -';
  const labels = isH264 ? [`USB ${tier}`, 'H.264', `${width}x${height}`, `${fps}fps`, bitrate, `thermal ${thermalStatus}`] : [`USB ${tier}`, `${width}x${height}`, `${fps}fps`, `q${jpegQuality}`, bytesPerSecond, encodeMillis, `thermal ${thermalStatus}`];
  usbCoolingStatus.replaceChildren(...labels.filter(Boolean).map(label => {
    const chip = document.createElement('span');
    chip.className = 'metric-chip';
    chip.textContent = label;
    return chip;
  }));
  usbCoolingStatusItem.hidden = false;
}
export function hideUsbCoolingStatus() {
  if (usbCoolingStatusItem) usbCoolingStatusItem.hidden = true;
}
export function setHidden(element, hidden) {
  if (element) element.hidden = hidden;
}
export function renderTransportSelection() {
  const isUsb = selectedTransport === 'usb';
  transportTailscaleBtn?.classList.toggle('active', !isUsb);
  transportUsbBtn?.classList.toggle('active', isUsb);
  setHidden(rtcLatencyItem, isUsb);
  setHidden(qualityNetworkItem, isUsb);
  if (streamStatusLabel) streamStatusLabel.textContent = isUsb ? 'USB 스트림' : 'WebRTC 스트림';
  if (toolsPanel) toolsPanel.open = false;
  if (!isUsb) {
    hideUsbCoolingStatus();
  }
  remoteVideo?.classList.toggle('hidden', isUsb);
  usbCanvas?.classList.toggle('hidden', !isUsb);
  if (usbFrame) {
    usbFrame.classList.toggle('hidden', !isUsb);
  }
  updateConnectButtonState();
}
export function updateConnectButtonState() {
  if (!connectBtn) return;
  const connected = isMirrorConnectionActive();
  connectBtn.textContent = connected ? '미러링 연결 해제' : '미러링 연결하기';
  if (connected) {
    connectBtn.classList.add('disconnect');
  } else {
    connectBtn.classList.remove('disconnect');
  }
  connectBtn.setAttribute('aria-pressed', connected ? 'true' : 'false');
}
export function showConnectionPlaceholder(message) {
  if (!connectionPlaceholder) return;
  connectionPlaceholder.textContent = message;
  connectionPlaceholder.classList.remove('hidden');
}
export function hideConnectionPlaceholder() {
  connectionPlaceholder?.classList.add('hidden');
}
export function resetConnectionStatus(message = '미러링 연결이 해제되었습니다. 다시 연결하려면 미러링 연결하기를 누르세요.') {
  wsIndicator.classList.remove('online');
  wsStatus.innerHTML = `<span class="indicator" id="wsIndicator"></span>Offline`;
  rtcStatus.innerText = 'Offline';
  controlStatus.innerText = '비활성';
  accessibilityStatus.innerText = '확인 중';
  _set_accessibilityReady(false);
  const latencyEl = document.getElementById('rtcLatency');
  if (latencyEl) latencyEl.textContent = 'Offline';
  updateConnectButtonState();
  showConnectionPlaceholder('연결이 해제되었습니다. 미러링 연결하기를 누르면 다시 시작됩니다.');
  showStatusDetail(message);
}
export function renderFavoriteApps(apps) {
  if (!favoriteAppsList) return;
  const safeApps = Array.isArray(apps) ? apps : [];
  if (safeApps.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'shortcut-empty';
    empty.textContent = 'Android 앱에서 자주 쓰는 앱을 추가하세요.';
    favoriteAppsList.replaceChildren(empty);
    return;
  }
  const buttons = safeApps.map(app => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'shortcut-btn';
    button.textContent = app?.label || app?.packageName || '알 수 없는 앱';
    button.addEventListener('click', () => launchFavoriteApp(app?.packageName, app?.label));
    return button;
  });
  favoriteAppsList.replaceChildren(...buttons);
}
export function renderClipboardHistory() {
  const listContainer = document.getElementById('clipboardHistoryList');
  if (!listContainer) return;
  listContainer.innerHTML = '';
  if (clipboardHistory.length === 0) {
    listContainer.innerHTML = '<div class="clipboard-empty">수신된 클립보드 내역이 없습니다.</div>';
    return;
  }
  clipboardHistory.forEach(text => {
    const item = document.createElement('div');
    item.className = 'clipboard-item';
    item.textContent = text;
    item.title = text;
    item.onclick = () => {
      writeClipboardFromAndroid(text);
    };
    listContainer.appendChild(item);
  });
}
export const clearClipboardBtn = document.getElementById('clearClipboardBtn');
export function _set_clearClipboardBtn(val) { clearClipboardBtn = val; }
export function _get_clearClipboardBtn() { return clearClipboardBtn; }
export function handleClearClipboardBtnClick() {
  clipboardHistory = [];
  renderClipboardHistory();
  showGlowToast("클립보드 내역이 비워졌습니다.");
}
export function addClipboardToHistory(text) {
  if (!text || text.trim() === "") return;
  if (clipboardHistory.length > 0 && clipboardHistory[0] === text) return;
  clipboardHistory.unshift(text);
  if (clipboardHistory.length > MAX_CLIPBOARD_HISTORY) {
    clipboardHistory.pop();
  }
  renderClipboardHistory();
}
export function clearRemoteVideoFrame() {
  if (!remoteVideo) return;
  if (remoteVideo.srcObject) {
    remoteVideo.srcObject = null;
  }
  remoteVideo.removeAttribute?.('src');
  remoteVideo.load?.();
}
export function clearUsbFrame() {
  if (lastUsbFrameUrl) {
    URL.revokeObjectURL(lastUsbFrameUrl);
    lastUsbFrameUrl = null;
  }
  if (usbFrame) {
    usbFrame.removeAttribute('src');
  }
  if (usbCanvas) {
    const ctx = usbCanvas.getContext('2d');
    ctx.clearRect(0, 0, usbCanvas.width, usbCanvas.height);
  }
}
export function showGlowToast(message) {
  const container = document.getElementById('toastContainer');
  if (!container) return null;
  const toast = document.createElement('div');
  toast.className = 'toast';
  const iconSpan = document.createElement('span');
  iconSpan.textContent = '🔔';
  const textSpan = document.createElement('span');
  textSpan.textContent = message;
  toast.appendChild(iconSpan);
  toast.appendChild(textSpan);
  container.appendChild(toast);
  toast.offsetHeight;
  toast.classList.add('show');
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 400);
  }, 3000);
  return toast;
}

// Attach to globalThis
if (typeof globalThis !== 'undefined') globalThis.remoteVideo = remoteVideo;
if (typeof globalThis !== 'undefined') globalThis.keyboardSink = keyboardSink;
if (typeof globalThis !== 'undefined') globalThis.connectBtn = connectBtn;
if (typeof globalThis !== 'undefined') globalThis.wsIndicator = wsIndicator;
if (typeof globalThis !== 'undefined') globalThis.wsStatus = wsStatus;
if (typeof globalThis !== 'undefined') globalThis.rtcStatus = rtcStatus;
if (typeof globalThis !== 'undefined') globalThis.streamStatusLabel = streamStatusLabel;
if (typeof globalThis !== 'undefined') globalThis.rtcLatencyItem = rtcLatencyItem;
if (typeof globalThis !== 'undefined') globalThis.controlStatus = controlStatus;
if (typeof globalThis !== 'undefined') globalThis.accessibilityStatus = accessibilityStatus;
if (typeof globalThis !== 'undefined') globalThis.favoriteAppsList = favoriteAppsList;
if (typeof globalThis !== 'undefined') globalThis.statusDetail = statusDetail;
if (typeof globalThis !== 'undefined') globalThis.logBox = logBox;
if (typeof globalThis !== 'undefined') globalThis.uploadUsage = uploadUsage;
if (typeof globalThis !== 'undefined') globalThis.downloadUsage = downloadUsage;
if (typeof globalThis !== 'undefined') globalThis.rtcLatency = rtcLatency;
if (typeof globalThis !== 'undefined') globalThis.usbCanvas = usbCanvas;
if (typeof globalThis !== 'undefined') globalThis.connectionPlaceholder = connectionPlaceholder;
if (typeof globalThis !== 'undefined') globalThis.usbCanvasCtx = usbCanvasCtx;
if (typeof globalThis !== 'undefined') globalThis.getUsbCanvasContext = getUsbCanvasContext;
if (typeof globalThis !== 'undefined') globalThis.usbFrame = usbFrame;
if (typeof globalThis !== 'undefined') globalThis.transportTailscaleBtn = transportTailscaleBtn;
if (typeof globalThis !== 'undefined') globalThis.transportUsbBtn = transportUsbBtn;
if (typeof globalThis !== 'undefined') globalThis.qualityMode = qualityMode;
if (typeof globalThis !== 'undefined') globalThis.qualityEffective = qualityEffective;
if (typeof globalThis !== 'undefined') globalThis.qualityNetwork = qualityNetwork;
if (typeof globalThis !== 'undefined') globalThis.qualityNetworkItem = qualityNetworkItem;
if (typeof globalThis !== 'undefined') globalThis.usbCoolingStatusItem = usbCoolingStatusItem;
if (typeof globalThis !== 'undefined') globalThis.usbCoolingStatus = usbCoolingStatus;
if (typeof globalThis !== 'undefined') globalThis.toolsPanel = toolsPanel;
if (typeof globalThis !== 'undefined') globalThis.qualityAutoBtn = qualityAutoBtn;
if (typeof globalThis !== 'undefined') globalThis.qualityDataSaverBtn = qualityDataSaverBtn;
if (typeof globalThis !== 'undefined') globalThis.qualityStandardBtn = qualityStandardBtn;
if (typeof globalThis !== 'undefined') globalThis.qualityHighBtn = qualityHighBtn;
if (typeof globalThis !== 'undefined') globalThis.navRecentsBtn = navRecentsBtn;
if (typeof globalThis !== 'undefined') globalThis.navHomeBtn = navHomeBtn;
if (typeof globalThis !== 'undefined') globalThis.navBackBtn = navBackBtn;
if (typeof globalThis !== 'undefined') globalThis.clipboardHistory = clipboardHistory;
if (typeof globalThis !== 'undefined') globalThis.MAX_CLIPBOARD_HISTORY = MAX_CLIPBOARD_HISTORY;
if (typeof globalThis !== 'undefined') globalThis.updateVideoAspectRatio = updateVideoAspectRatio;
if (typeof globalThis !== 'undefined') globalThis.streamQualityButtons = streamQualityButtons;
if (typeof globalThis !== 'undefined') globalThis.logQueue = logQueue;
if (typeof globalThis !== 'undefined') globalThis.logFrameRequested = logFrameRequested;
if (typeof globalThis !== 'undefined') globalThis.flushLogs = flushLogs;
if (typeof globalThis !== 'undefined') globalThis.log = log;
if (typeof globalThis !== 'undefined') globalThis.showStatusDetail = showStatusDetail;
if (typeof globalThis !== 'undefined') globalThis.formatMegabytes = formatMegabytes;
if (typeof globalThis !== 'undefined') globalThis.lastUploadUsageText = lastUploadUsageText;
if (typeof globalThis !== 'undefined') globalThis.lastDownloadUsageText = lastDownloadUsageText;
if (typeof globalThis !== 'undefined') globalThis.updateDataUsageDisplay = updateDataUsageDisplay;
if (typeof globalThis !== 'undefined') globalThis.resetDataUsageStats = resetDataUsageStats;
if (typeof globalThis !== 'undefined') globalThis.formatBitrate = formatBitrate;
if (typeof globalThis !== 'undefined') globalThis.formatBytesPerSecond = formatBytesPerSecond;
if (typeof globalThis !== 'undefined') globalThis.updateBlackOverlayStatus = updateBlackOverlayStatus;
if (typeof globalThis !== 'undefined') globalThis.isBlackOverlayActive = isBlackOverlayActive;
if (typeof globalThis !== 'undefined') globalThis.btnBlackOverlay = btnBlackOverlay;
if (typeof globalThis !== 'undefined') globalThis.updateAutoFitStatus = updateAutoFitStatus;
if (typeof globalThis !== 'undefined') globalThis.isAutoFitActive = isAutoFitActive;
if (typeof globalThis !== 'undefined') globalThis.btnAutoFit = btnAutoFit;
if (typeof globalThis !== 'undefined') globalThis.renderStreamQualityStatus = renderStreamQualityStatus;
if (typeof globalThis !== 'undefined') globalThis.renderUsbCoolingStatus = renderUsbCoolingStatus;
if (typeof globalThis !== 'undefined') globalThis.hideUsbCoolingStatus = hideUsbCoolingStatus;
if (typeof globalThis !== 'undefined') globalThis.setHidden = setHidden;
if (typeof globalThis !== 'undefined') globalThis.renderTransportSelection = renderTransportSelection;
if (typeof globalThis !== 'undefined') globalThis.updateConnectButtonState = updateConnectButtonState;
if (typeof globalThis !== 'undefined') globalThis.showConnectionPlaceholder = showConnectionPlaceholder;
if (typeof globalThis !== 'undefined') globalThis.hideConnectionPlaceholder = hideConnectionPlaceholder;
if (typeof globalThis !== 'undefined') globalThis.resetConnectionStatus = resetConnectionStatus;
if (typeof globalThis !== 'undefined') globalThis.renderFavoriteApps = renderFavoriteApps;
if (typeof globalThis !== 'undefined') globalThis.renderClipboardHistory = renderClipboardHistory;
if (typeof globalThis !== 'undefined') globalThis.clearClipboardBtn = clearClipboardBtn;
if (typeof globalThis !== 'undefined') globalThis.handleClearClipboardBtnClick = handleClearClipboardBtnClick;
if (typeof globalThis !== 'undefined') globalThis.addClipboardToHistory = addClipboardToHistory;
if (typeof globalThis !== 'undefined') globalThis.clearRemoteVideoFrame = clearRemoteVideoFrame;
if (typeof globalThis !== 'undefined') globalThis.clearUsbFrame = clearUsbFrame;
if (typeof globalThis !== 'undefined') globalThis.showGlowToast = showGlowToast;
if (typeof globalThis !== 'undefined') { globalThis._set_remoteVideo = _set_remoteVideo; globalThis._get_remoteVideo = _get_remoteVideo; }
if (typeof globalThis !== 'undefined') { globalThis._set_keyboardSink = _set_keyboardSink; globalThis._get_keyboardSink = _get_keyboardSink; }
if (typeof globalThis !== 'undefined') { globalThis._set_connectBtn = _set_connectBtn; globalThis._get_connectBtn = _get_connectBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_wsIndicator = _set_wsIndicator; globalThis._get_wsIndicator = _get_wsIndicator; }
if (typeof globalThis !== 'undefined') { globalThis._set_wsStatus = _set_wsStatus; globalThis._get_wsStatus = _get_wsStatus; }
if (typeof globalThis !== 'undefined') { globalThis._set_rtcStatus = _set_rtcStatus; globalThis._get_rtcStatus = _get_rtcStatus; }
if (typeof globalThis !== 'undefined') { globalThis._set_streamStatusLabel = _set_streamStatusLabel; globalThis._get_streamStatusLabel = _get_streamStatusLabel; }
if (typeof globalThis !== 'undefined') { globalThis._set_rtcLatencyItem = _set_rtcLatencyItem; globalThis._get_rtcLatencyItem = _get_rtcLatencyItem; }
if (typeof globalThis !== 'undefined') { globalThis._set_controlStatus = _set_controlStatus; globalThis._get_controlStatus = _get_controlStatus; }
if (typeof globalThis !== 'undefined') { globalThis._set_accessibilityStatus = _set_accessibilityStatus; globalThis._get_accessibilityStatus = _get_accessibilityStatus; }
if (typeof globalThis !== 'undefined') { globalThis._set_favoriteAppsList = _set_favoriteAppsList; globalThis._get_favoriteAppsList = _get_favoriteAppsList; }
if (typeof globalThis !== 'undefined') { globalThis._set_statusDetail = _set_statusDetail; globalThis._get_statusDetail = _get_statusDetail; }
if (typeof globalThis !== 'undefined') { globalThis._set_logBox = _set_logBox; globalThis._get_logBox = _get_logBox; }
if (typeof globalThis !== 'undefined') { globalThis._set_uploadUsage = _set_uploadUsage; globalThis._get_uploadUsage = _get_uploadUsage; }
if (typeof globalThis !== 'undefined') { globalThis._set_downloadUsage = _set_downloadUsage; globalThis._get_downloadUsage = _get_downloadUsage; }
if (typeof globalThis !== 'undefined') { globalThis._set_rtcLatency = _set_rtcLatency; globalThis._get_rtcLatency = _get_rtcLatency; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbCanvas = _set_usbCanvas; globalThis._get_usbCanvas = _get_usbCanvas; }
if (typeof globalThis !== 'undefined') { globalThis._set_connectionPlaceholder = _set_connectionPlaceholder; globalThis._get_connectionPlaceholder = _get_connectionPlaceholder; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbCanvasCtx = _set_usbCanvasCtx; globalThis._get_usbCanvasCtx = _get_usbCanvasCtx; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbFrame = _set_usbFrame; globalThis._get_usbFrame = _get_usbFrame; }
if (typeof globalThis !== 'undefined') { globalThis._set_transportTailscaleBtn = _set_transportTailscaleBtn; globalThis._get_transportTailscaleBtn = _get_transportTailscaleBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_transportUsbBtn = _set_transportUsbBtn; globalThis._get_transportUsbBtn = _get_transportUsbBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityMode = _set_qualityMode; globalThis._get_qualityMode = _get_qualityMode; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityEffective = _set_qualityEffective; globalThis._get_qualityEffective = _get_qualityEffective; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityNetwork = _set_qualityNetwork; globalThis._get_qualityNetwork = _get_qualityNetwork; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityNetworkItem = _set_qualityNetworkItem; globalThis._get_qualityNetworkItem = _get_qualityNetworkItem; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbCoolingStatusItem = _set_usbCoolingStatusItem; globalThis._get_usbCoolingStatusItem = _get_usbCoolingStatusItem; }
if (typeof globalThis !== 'undefined') { globalThis._set_usbCoolingStatus = _set_usbCoolingStatus; globalThis._get_usbCoolingStatus = _get_usbCoolingStatus; }
if (typeof globalThis !== 'undefined') { globalThis._set_toolsPanel = _set_toolsPanel; globalThis._get_toolsPanel = _get_toolsPanel; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityAutoBtn = _set_qualityAutoBtn; globalThis._get_qualityAutoBtn = _get_qualityAutoBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityDataSaverBtn = _set_qualityDataSaverBtn; globalThis._get_qualityDataSaverBtn = _get_qualityDataSaverBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityStandardBtn = _set_qualityStandardBtn; globalThis._get_qualityStandardBtn = _get_qualityStandardBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_qualityHighBtn = _set_qualityHighBtn; globalThis._get_qualityHighBtn = _get_qualityHighBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_navRecentsBtn = _set_navRecentsBtn; globalThis._get_navRecentsBtn = _get_navRecentsBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_navHomeBtn = _set_navHomeBtn; globalThis._get_navHomeBtn = _get_navHomeBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_navBackBtn = _set_navBackBtn; globalThis._get_navBackBtn = _get_navBackBtn; }
if (typeof globalThis !== 'undefined') { globalThis._set_clipboardHistory = _set_clipboardHistory; globalThis._get_clipboardHistory = _get_clipboardHistory; }
if (typeof globalThis !== 'undefined') { globalThis._set_MAX_CLIPBOARD_HISTORY = _set_MAX_CLIPBOARD_HISTORY; globalThis._get_MAX_CLIPBOARD_HISTORY = _get_MAX_CLIPBOARD_HISTORY; }
if (typeof globalThis !== 'undefined') { globalThis._set_streamQualityButtons = _set_streamQualityButtons; globalThis._get_streamQualityButtons = _get_streamQualityButtons; }
if (typeof globalThis !== 'undefined') { globalThis._set_logQueue = _set_logQueue; globalThis._get_logQueue = _get_logQueue; }
if (typeof globalThis !== 'undefined') { globalThis._set_logFrameRequested = _set_logFrameRequested; globalThis._get_logFrameRequested = _get_logFrameRequested; }
if (typeof globalThis !== 'undefined') { globalThis._set_lastUploadUsageText = _set_lastUploadUsageText; globalThis._get_lastUploadUsageText = _get_lastUploadUsageText; }
if (typeof globalThis !== 'undefined') { globalThis._set_lastDownloadUsageText = _set_lastDownloadUsageText; globalThis._get_lastDownloadUsageText = _get_lastDownloadUsageText; }
if (typeof globalThis !== 'undefined') { globalThis._set_clearClipboardBtn = _set_clearClipboardBtn; globalThis._get_clearClipboardBtn = _get_clearClipboardBtn; }
