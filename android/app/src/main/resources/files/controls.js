import { remoteVideo, keyboardSink, connectBtn, wsIndicator, wsStatus, rtcStatus, streamStatusLabel, rtcLatencyItem, controlStatus, accessibilityStatus, favoriteAppsList, statusDetail, logBox, uploadUsage, downloadUsage, rtcLatency, usbCanvas, connectionPlaceholder, usbCanvasCtx, getUsbCanvasContext, usbFrame, transportTailscaleBtn, transportUsbBtn, qualityMode, qualityEffective, qualityNetwork, qualityNetworkItem, usbCoolingStatusItem, usbCoolingStatus, toolsPanel, qualityAutoBtn, qualityDataSaverBtn, qualityStandardBtn, qualityHighBtn, navRecentsBtn, navHomeBtn, navBackBtn, clipboardHistory, MAX_CLIPBOARD_HISTORY, updateVideoAspectRatio, streamQualityButtons, logQueue, logFrameRequested, flushLogs, log, showStatusDetail, formatMegabytes, lastUploadUsageText, lastDownloadUsageText, updateDataUsageDisplay, resetDataUsageStats, formatBitrate, formatBytesPerSecond, renderStreamQualityStatus, renderUsbCoolingStatus, hideUsbCoolingStatus, setHidden, renderTransportSelection, updateConnectButtonState, showConnectionPlaceholder, hideConnectionPlaceholder, resetConnectionStatus, renderFavoriteApps, renderClipboardHistory, clearClipboardBtn, handleClearClipboardBtnClick, addClipboardToHistory, clearRemoteVideoFrame, clearUsbFrame, showGlowToast } from './ui.js';
import { peerConnection, dataChannel, remoteDescriptionSet, pendingRemoteCandidates, dataUsagePollId, lastNetworkBytes, accumulatedNetworkBytes, rtcConfig, resetNetworkBytes, extractNetworkBytes, sampleWebRtcStats, startDataUsagePolling, stopDataUsagePolling, setupWebRTC, addRemoteCandidate, flushPendingRemoteCandidates, setupDataChannelHandlers, cleanupPeerConnection } from './webrtc.js';
import { socket, usbSocket, usbPerfPollId, selectedTransport, lastUsbFrameUrl, activeUsbCodec, forceUsbJpegFallback, usbVideoDecoder, usbVideoDecoderConfigured, usbVideoConfig, usbH264SawKeyframe, shouldAutoReconnect, statusDetailMessage, reconnectAttempts, MAX_RECONNECT_ATTEMPTS, reconnectTimeoutId, isReconnecting, reconnectCloseInProgress, initialTransport, sampleUsbPerfStatus, startUsbPerfPolling, stopUsbPerfPolling, isSocketActive, isMirrorConnectionActive, disconnectMirrorFromButton, setTransport, setupTransportControls, loadStreamQualityStatus, setStreamQualityMode, connectSignaling, handleSignalingMessage, connectMirror, usbSessionUrl, connectUsbSession, handleUsbTextMessage, hasUsbH264Support, preferredUsbCodec, handleUsbVideoConfig, reconnectUsbAsJpeg, closeUsbVideoDecoder, normalizeArrayBuffer, decodeUsbH264Packet, drawDecodedUsbFrame, renderUsbFrame, handleStatusMessage, applyAndroidStatusMessage, loadFavoriteApps, launchFavoriteApp, handleConnectBtnClick, closeUsbSocket, closeSignalingSocket, disconnectCurrentTransport, triggerAutoReconnect, startReconnectSequence, showReconnectOverlayProgress, showReconnectOverlayFailed, hideReconnectOverlay, enterScreenCaptureApprovalWait, handleVisibilityChange } from './signaling.js';

export let bindTouchSurface = null;
export function _set_bindTouchSurface(val) { bindTouchSurface = val; }
export function _get_bindTouchSurface() { return bindTouchSurface; }
export let accessibilityReady = false;
export function _set_accessibilityReady(val) { accessibilityReady = val; }
export function _get_accessibilityReady() { return accessibilityReady; }
export let touchControlInitialized = false;
export function _set_touchControlInitialized(val) { touchControlInitialized = val; }
export function _get_touchControlInitialized() { return touchControlInitialized; }
export let keyControlInitialized = false;
export function _set_keyControlInitialized(val) { keyControlInitialized = val; }
export function _get_keyControlInitialized() { return keyControlInitialized; }
export let navigationControlInitialized = false;
export function _set_navigationControlInitialized(val) { navigationControlInitialized = val; }
export function _get_navigationControlInitialized() { return navigationControlInitialized; }
export let keyboardControl = null;
export function _set_keyboardControl(val) { keyboardControl = val; }
export function _get_keyboardControl() { return keyboardControl; }
export let nextTextSeq = 1;
export function _set_nextTextSeq(val) { nextTextSeq = val; }
export function _get_nextTextSeq() { return nextTextSeq; }
export let inFlightTextSeq = null;
export function _set_inFlightTextSeq(val) { inFlightTextSeq = val; }
export function _get_inFlightTextSeq() { return inFlightTextSeq; }
export let queuedTextPayloads = [];
export function _set_queuedTextPayloads(val) { queuedTextPayloads = val; }
export function _get_queuedTextPayloads() { return queuedTextPayloads; }
export let ackTimeoutId = null;
export function _set_ackTimeoutId(val) { ackTimeoutId = val; }
export function _get_ackTimeoutId() { return ackTimeoutId; }
export function focusKeyboardCapture() {
  if (keyboardControl) {
    keyboardControl.focus();
    return;
  }
  remoteVideo.focus();
}
export function sendControlPayload(payload) {
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
export function sendAndroidKey(keyCode) {
  if (sendControlPayload({
    type: 'key',
    keyCode
  })) {
    log(`Key sent: keyCode=${keyCode}`);
  }
}
export function sendSequencedTextPayload(payload) {
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
    sent = sendControlPayload({
      ...payload,
      seq
    });
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
export function resetTextControlState() {
  inFlightTextSeq = null;
  queuedTextPayloads = [];
  nextTextSeq = 1;
  if (ackTimeoutId !== null) {
    clearTimeout(ackTimeoutId);
    ackTimeoutId = null;
  }
}
export function flushNextQueuedTextPayload() {
  if (inFlightTextSeq !== null || queuedTextPayloads.length === 0) return;
  const nextPayload = queuedTextPayloads.shift();
  sendSequencedTextPayload(nextPayload);
}
export function handleControlAck(payload = {}) {
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
export function hasClipboardWriteApi() {
  return Boolean(typeof navigator !== 'undefined' && navigator.clipboard && typeof navigator.clipboard.writeText === 'function');
}
export function hasClipboardReadApi() {
  return Boolean(typeof navigator !== 'undefined' && navigator.clipboard && typeof navigator.clipboard.readText === 'function');
}
export function showManualClipboardFallback(text) {
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
export async function writeClipboardFromAndroid(text) {
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
export async function readClipboardForAndroid() {
  if (!hasClipboardReadApi()) {
    log("브라우저 클립보드 읽기 API를 사용할 수 없습니다.");
    return null;
  }
  return navigator.clipboard.readText();
}
export function getNormalizedCoords(e, surface = remoteVideo) {
  if (!surface) return null;
  const rect = surface.getBoundingClientRect();
  const xOff = e.clientX - rect.left;
  const yOff = e.clientY - rect.top;
  const wElem = rect.width;
  const hElem = rect.height;
  const wVideo = surface.videoWidth || surface.naturalWidth || surface.width || remoteVideo.videoWidth || 1080;
  const hVideo = surface.videoHeight || surface.naturalHeight || surface.height || remoteVideo.videoHeight || 2400;
  const rVideo = wVideo / hVideo;
  const rElem = wElem / hElem;
  let x, y;
  if (rElem > rVideo) {
    const wAct = hElem * rVideo;
    const wMargin = (wElem - wAct) / 2;
    x = (xOff - wMargin) / wAct;
    y = yOff / hElem;
  } else {
    const hAct = wElem / rVideo;
    const hMargin = (hElem - hAct) / 2;
    x = xOff / wElem;
    y = (yOff - hMargin) / hAct;
  }
  if (x < 0 || x > 1 || y < 0 || y > 1) {
    return null;
  }
  return {
    x: Math.round(x * 10000) / 10000,
    y: Math.round(y * 10000) / 10000
  };
}
export function unbindTouchSurface(surface) {
  if (!surface || !surface._touchListeners) return;
  const listeners = surface._touchListeners;
  surface.removeEventListener('mousedown', listeners.mousedown);
  surface.removeEventListener('mousemove', listeners.mousemove);
  surface.removeEventListener('mouseup', listeners.mouseup);
  surface.removeEventListener('mouseleave', listeners.mouseleave);
  surface.removeEventListener('wheel', listeners.wheel);
  delete surface._touchListeners;
}
export function destroyTouchControl() {
  if (!touchControlInitialized) return;
  touchControlInitialized = false;
  unbindTouchSurface(remoteVideo);
  if (usbCanvas) unbindTouchSurface(usbCanvas);
  if (usbFrame) unbindTouchSurface(usbFrame);
  log("마우스 원격 터치 좌표 리스너 해제 완료.");
}
export function setupTouchControl() {
  if (touchControlInitialized) return;
  touchControlInitialized = true;
  log("마우스 원격 터치 좌표 리스너 기동 완료.");
  let dragStart = null;
  let isDragging = false;
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
  function handleSwipe(start, end, duration) {
    sendControlPayload({
      type: 'swipe',
      x1: start.x,
      y1: start.y,
      x2: end.x,
      y2: end.y,
      duration: Math.max(100, Math.min(duration, 1500))
    });
    log(`Swipe: (${start.x},${start.y})→(${end.x},${end.y}) ${duration}ms`);
  }
  function handleTap(start) {
    sendControlPayload({
      type: 'tap',
      x: start.x,
      y: start.y
    });
    log(`Tap: (${start.x}, ${start.y})`);
  }
  function handleWheelSwipe(payload) {
    if (!payload) return;
    if (sendControlPayload(payload)) {
      log(`Wheel swipe: (${payload.x1},${payload.y1})→(${payload.x2},${payload.y2}) ${payload.duration}ms`);
    }
  }
  function buildWheelSwipePayload(coords, deltaX, deltaY) {
    const vertical = Math.abs(deltaY) >= Math.abs(deltaX);
    const dominantDelta = vertical ? deltaY : deltaX;
    if (Math.abs(dominantDelta) < 1) return null;
    const distance = Math.min(WHEEL_SWIPE_MAX_DISTANCE, Math.max(WHEEL_SWIPE_MIN_DISTANCE, Math.abs(dominantDelta) / WHEEL_SWIPE_SCALE));
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
  bindTouchSurface = function (surface) {
    if (!surface) return;
    unbindTouchSurface(surface);
    const wheelState = {
      deltaX: 0,
      deltaY: 0,
      coords: null,
      timeoutId: null
    };
    const mousedownHandler = e => {
      e.preventDefault();
      focusKeyboardCapture();
      const coords = getNormalizedCoords(e, surface);
      if (!coords) {
        dragStart = null;
        return;
      }
      dragStart = {
        ...coords,
        time: Date.now()
      };
      startClientX = e.clientX;
      startClientY = e.clientY;
      isDragging = false;
    };
    const mousemoveHandler = e => {
      if (e.buttons !== 1 || !dragStart) return;
      const dx = e.clientX - startClientX;
      const dy = e.clientY - startClientY;
      if (!isDragging && dx * dx + dy * dy > DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) {
        isDragging = true;
      }
    };
    const mouseupHandler = e => {
      if (!dragStart) return;
      const end = getNormalizedCoords(e, surface);
      if (!end) {
        dragStart = null;
        isDragging = false;
        return;
      }
      const duration = Date.now() - dragStart.time;
      if (isDragging) {
        handleSwipe(dragStart, end, duration);
      } else {
        handleTap(dragStart);
      }
      dragStart = null;
      isDragging = false;
    };
    const mouseleaveHandler = () => {
      dragStart = null;
      isDragging = false;
    };
    const wheelHandler = e => {
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
        const payload = buildWheelSwipePayload(wheelState.coords, wheelState.deltaX, wheelState.deltaY);
        wheelState.deltaX = 0;
        wheelState.deltaY = 0;
        wheelState.coords = null;
        wheelState.timeoutId = null;
        handleWheelSwipe(payload);
      }, WHEEL_SWIPE_DELAY_MS);
    };
    surface.addEventListener('mousedown', mousedownHandler);
    surface.addEventListener('mousemove', mousemoveHandler);
    surface.addEventListener('mouseup', mouseupHandler);
    surface.addEventListener('mouseleave', mouseleaveHandler);
    surface.addEventListener('wheel', wheelHandler, {
      passive: false
    });
    surface._touchListeners = {
      mousedown: mousedownHandler,
      mousemove: mousemoveHandler,
      mouseup: mouseupHandler,
      mouseleave: mouseleaveHandler,
      wheel: wheelHandler
    };
  };
  bindTouchSurface(remoteVideo);
  if (usbCanvas) bindTouchSurface(usbCanvas);
  if (usbFrame) bindTouchSurface(usbFrame);
}
export let documentKeydownHandler = null;
export function _set_documentKeydownHandler(val) { documentKeydownHandler = val; }
export function _get_documentKeydownHandler() { return documentKeydownHandler; }
export let keyboardListeners = [];
export function _set_keyboardListeners(val) { keyboardListeners = val; }
export function _get_keyboardListeners() { return keyboardListeners; }
export function createEventInterceptor(targetObject, onAdd) {
  if (!targetObject) return targetObject;
  return new Proxy(targetObject, {
    get(target, prop) {
      if (prop === '__target__') {
        return target;
      }
      if (prop === 'addEventListener') {
        return function (type, listener, options) {
          onAdd(target, type, listener, options);
          return target.addEventListener(type, listener, options);
        };
      }
      const value = target[prop];
      if (typeof value === 'function') {
        return value.bind(target);
      }
      return value;
    },
    set(target, prop, value) {
      target[prop] = value;
      return true;
    }
  });
}
export function interceptKeyboardControl() {
  if (!window.GalaxyMirrorKeyboard || !window.GalaxyMirrorKeyboard.createKeyboardControl) return;
  if (window.GalaxyMirrorKeyboard._isIntercepted) return;
  window.GalaxyMirrorKeyboard._isIntercepted = true;
  const originalCreate = window.GalaxyMirrorKeyboard.createKeyboardControl;
  window.GalaxyMirrorKeyboard.createKeyboardControl = function (options) {
    const onAdd = (element, type, listener, opts) => {
      keyboardListeners.push({
        element,
        type,
        listener,
        opts
      });
    };
    const interceptedDoc = createEventInterceptor(options.document, onAdd);
    const interceptedTarget = createEventInterceptor(options.remoteTarget, onAdd);
    const interceptedSink = createEventInterceptor(options.keyboardSink, onAdd);
    const ctrl = originalCreate({
      ...options,
      document: interceptedDoc,
      remoteTarget: interceptedTarget,
      keyboardSink: interceptedSink
    });
    ctrl.destroy = function () {
      keyboardListeners.forEach(({element, type, listener, opts}) => {
        try {
          element.removeEventListener(type, listener, opts);
        } catch (e) {
          console.error("Failed to remove event listener", e);
        }
      });
      keyboardListeners = [];
    };
    return ctrl;
  };
}
export function destroyKeyControl() {
  if (!keyControlInitialized) return;
  keyControlInitialized = false;
  if (keyboardControl && typeof keyboardControl.destroy === 'function') {
    keyboardControl.destroy();
    keyboardControl = null;
  }
  if (documentKeydownHandler) {
    document.removeEventListener('keydown', documentKeydownHandler);
    documentKeydownHandler = null;
  }
  log("키보드 단축키 리스너 해제 완료.");
}
export function setupKeyControl() {
  if (keyControlInitialized) return;
  keyControlInitialized = true;
  log("키보드 단축키 리스너 기동 완료.");
  setupClipboardSync();
  interceptKeyboardControl();
  interceptKeyboardControl();
  function sendTextCommit(text) {
    if (sendSequencedTextPayload({
      type: 'text',
      action: 'commit',
      text
    })) {
      log(`Text sent: length=${text.length}`);
    } else if (inFlightTextSeq !== null) {
      log(`Text queued: length=${text.length}`);
    }
  }
  function sendTextDeleteBackward(count) {
    if (sendSequencedTextPayload({
      type: 'text',
      action: 'deleteBackward',
      count
    })) {
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
  documentKeydownHandler = e => {
    if (document.activeElement !== remoteVideo) return;
    if (e.isComposing) return;
    switch (e.key) {
      case 'Backspace':
        e.preventDefault();
        sendTextDeleteBackward(1);
        return;
      case 'Home':
        e.preventDefault();
        sendAndroidKey(3);
        return;
      case 'F1':
        e.preventDefault();
        sendAndroidKey(187);
        return;
      case 'Enter':
        e.preventDefault();
        sendTextCommit('\n');
        return;
      case 'Escape':
        e.preventDefault();
        sendAndroidKey(4);
        return;
    }
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key.length === 1) {
      e.preventDefault();
      sendTextCommit(e.key);
    }
  };
  document.addEventListener('keydown', documentKeydownHandler);
}
export function setupNavigationControls() {
  if (navigationControlInitialized) return;
  navigationControlInitialized = true;
  const buttons = [{
    element: navRecentsBtn,
    keyCode: 187
  }, {
    element: navHomeBtn,
    keyCode: 3
  }, {
    element: navBackBtn,
    keyCode: 4
  }];
  buttons.forEach(({element, keyCode}) => {
    if (!element) return;
    element.addEventListener('click', event => {
      event.preventDefault();
      focusKeyboardCapture();
      sendAndroidKey(keyCode);
    });
  });
}
export function setupStreamQualityControls() {
  streamQualityButtons.forEach(({mode, element}) => {
    if (!element) return;
    element.addEventListener('click', event => {
      event?.preventDefault?.();
      setStreamQualityMode(mode);
    });
  });
}
export function setupSystemControls() {
  const powerBtn = document.getElementById('powerBtn');
  if (powerBtn) powerBtn.addEventListener('click', () => sendAndroidKey(26));
}
export let documentCopyHandler = null;
export function _set_documentCopyHandler(val) { documentCopyHandler = val; }
export function _get_documentCopyHandler() { return documentCopyHandler; }
export function destroyClipboardSync() {
  if (documentCopyHandler) {
    document.removeEventListener('copy', documentCopyHandler);
    documentCopyHandler = null;
  }
}
export function setupClipboardSync() {
  if (documentCopyHandler) {
    document.removeEventListener('copy', documentCopyHandler);
  }
  documentCopyHandler = () => {
    setTimeout(async () => {
      try {
        const text = await readClipboardForAndroid();
        const usbControlReady = selectedTransport === 'usb' && usbSocket && usbSocket.readyState === WebSocket.OPEN;
        const webRtcControlReady = selectedTransport !== 'usb' && dataChannel && dataChannel.readyState === 'open';
        if (text !== null && (usbControlReady || webRtcControlReady)) {
          const sent = sendControlPayload({
            type: 'clipboard',
            text
          });
          if (sent) {
            log(`맥 클립보드 원격 전송 성공: length=${text.length}`);
          }
        }
      } catch (e) {
        log(`맥 클립보드 읽기/전송 실패: ${e.message}`);
      }
    }, 100);
  };
  document.addEventListener('copy', documentCopyHandler);
}

// Attach to globalThis
if (typeof globalThis !== 'undefined') globalThis.bindTouchSurface = bindTouchSurface;
if (typeof globalThis !== 'undefined') globalThis.accessibilityReady = accessibilityReady;
if (typeof globalThis !== 'undefined') globalThis.touchControlInitialized = touchControlInitialized;
if (typeof globalThis !== 'undefined') globalThis.keyControlInitialized = keyControlInitialized;
if (typeof globalThis !== 'undefined') globalThis.navigationControlInitialized = navigationControlInitialized;
if (typeof globalThis !== 'undefined') globalThis.keyboardControl = keyboardControl;
if (typeof globalThis !== 'undefined') globalThis.nextTextSeq = nextTextSeq;
if (typeof globalThis !== 'undefined') globalThis.inFlightTextSeq = inFlightTextSeq;
if (typeof globalThis !== 'undefined') globalThis.queuedTextPayloads = queuedTextPayloads;
if (typeof globalThis !== 'undefined') globalThis.ackTimeoutId = ackTimeoutId;
if (typeof globalThis !== 'undefined') globalThis.focusKeyboardCapture = focusKeyboardCapture;
if (typeof globalThis !== 'undefined') globalThis.sendControlPayload = sendControlPayload;
if (typeof globalThis !== 'undefined') globalThis.sendAndroidKey = sendAndroidKey;
if (typeof globalThis !== 'undefined') globalThis.sendSequencedTextPayload = sendSequencedTextPayload;
if (typeof globalThis !== 'undefined') globalThis.resetTextControlState = resetTextControlState;
if (typeof globalThis !== 'undefined') globalThis.flushNextQueuedTextPayload = flushNextQueuedTextPayload;
if (typeof globalThis !== 'undefined') globalThis.handleControlAck = handleControlAck;
if (typeof globalThis !== 'undefined') globalThis.hasClipboardWriteApi = hasClipboardWriteApi;
if (typeof globalThis !== 'undefined') globalThis.hasClipboardReadApi = hasClipboardReadApi;
if (typeof globalThis !== 'undefined') globalThis.showManualClipboardFallback = showManualClipboardFallback;
if (typeof globalThis !== 'undefined') globalThis.writeClipboardFromAndroid = writeClipboardFromAndroid;
if (typeof globalThis !== 'undefined') globalThis.readClipboardForAndroid = readClipboardForAndroid;
if (typeof globalThis !== 'undefined') globalThis.getNormalizedCoords = getNormalizedCoords;
if (typeof globalThis !== 'undefined') globalThis.unbindTouchSurface = unbindTouchSurface;
if (typeof globalThis !== 'undefined') globalThis.destroyTouchControl = destroyTouchControl;
if (typeof globalThis !== 'undefined') globalThis.setupTouchControl = setupTouchControl;
if (typeof globalThis !== 'undefined') globalThis.documentKeydownHandler = documentKeydownHandler;
if (typeof globalThis !== 'undefined') globalThis.keyboardListeners = keyboardListeners;
if (typeof globalThis !== 'undefined') globalThis.createEventInterceptor = createEventInterceptor;
if (typeof globalThis !== 'undefined') globalThis.interceptKeyboardControl = interceptKeyboardControl;
if (typeof globalThis !== 'undefined') globalThis.destroyKeyControl = destroyKeyControl;
if (typeof globalThis !== 'undefined') globalThis.setupKeyControl = setupKeyControl;
if (typeof globalThis !== 'undefined') globalThis.setupNavigationControls = setupNavigationControls;
if (typeof globalThis !== 'undefined') globalThis.setupStreamQualityControls = setupStreamQualityControls;
if (typeof globalThis !== 'undefined') globalThis.setupSystemControls = setupSystemControls;
if (typeof globalThis !== 'undefined') globalThis.documentCopyHandler = documentCopyHandler;
if (typeof globalThis !== 'undefined') globalThis.destroyClipboardSync = destroyClipboardSync;
if (typeof globalThis !== 'undefined') globalThis.setupClipboardSync = setupClipboardSync;
if (typeof globalThis !== 'undefined') { globalThis._set_bindTouchSurface = _set_bindTouchSurface; globalThis._get_bindTouchSurface = _get_bindTouchSurface; }
if (typeof globalThis !== 'undefined') { globalThis._set_accessibilityReady = _set_accessibilityReady; globalThis._get_accessibilityReady = _get_accessibilityReady; }
if (typeof globalThis !== 'undefined') { globalThis._set_touchControlInitialized = _set_touchControlInitialized; globalThis._get_touchControlInitialized = _get_touchControlInitialized; }
if (typeof globalThis !== 'undefined') { globalThis._set_keyControlInitialized = _set_keyControlInitialized; globalThis._get_keyControlInitialized = _get_keyControlInitialized; }
if (typeof globalThis !== 'undefined') { globalThis._set_navigationControlInitialized = _set_navigationControlInitialized; globalThis._get_navigationControlInitialized = _get_navigationControlInitialized; }
if (typeof globalThis !== 'undefined') { globalThis._set_keyboardControl = _set_keyboardControl; globalThis._get_keyboardControl = _get_keyboardControl; }
if (typeof globalThis !== 'undefined') { globalThis._set_nextTextSeq = _set_nextTextSeq; globalThis._get_nextTextSeq = _get_nextTextSeq; }
if (typeof globalThis !== 'undefined') { globalThis._set_inFlightTextSeq = _set_inFlightTextSeq; globalThis._get_inFlightTextSeq = _get_inFlightTextSeq; }
if (typeof globalThis !== 'undefined') { globalThis._set_queuedTextPayloads = _set_queuedTextPayloads; globalThis._get_queuedTextPayloads = _get_queuedTextPayloads; }
if (typeof globalThis !== 'undefined') { globalThis._set_ackTimeoutId = _set_ackTimeoutId; globalThis._get_ackTimeoutId = _get_ackTimeoutId; }
if (typeof globalThis !== 'undefined') { globalThis._set_documentKeydownHandler = _set_documentKeydownHandler; globalThis._get_documentKeydownHandler = _get_documentKeydownHandler; }
if (typeof globalThis !== 'undefined') { globalThis._set_keyboardListeners = _set_keyboardListeners; globalThis._get_keyboardListeners = _get_keyboardListeners; }
if (typeof globalThis !== 'undefined') { globalThis._set_documentCopyHandler = _set_documentCopyHandler; globalThis._get_documentCopyHandler = _get_documentCopyHandler; }
