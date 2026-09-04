import {
  remoteVideo,
  updateVideoAspectRatio,
  clearClipboardBtn,
  handleClearClipboardBtnClick,
  connectBtn,
  renderTransportSelection,
  isAutoFitActive,
} from './ui.js';
import {
  sendAutoFitDisplay,
  setupStreamQualityControls,
  setupNavigationControls,
  setupSystemControls,
  setupClipboardSync,
} from './controls.js';
import {
  handleConnectBtnClick,
  handleVisibilityChange,
  setupTransportControls,
  loadFavoriteApps,
  loadStreamQualityStatus,
} from './signaling.js';


remoteVideo.addEventListener('loadedmetadata', updateVideoAspectRatio);
remoteVideo.addEventListener('resize', updateVideoAspectRatio);
if (clearClipboardBtn) {
  clearClipboardBtn.addEventListener('click', handleClearClipboardBtnClick);
}
connectBtn.addEventListener('click', handleConnectBtnClick);
document.addEventListener('visibilitychange', handleVisibilityChange);

let resizeTimer = null;
function handleViewportResize() {
  if (!isAutoFitActive) return;
  if (resizeTimer) clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    const videoContainer = document.getElementById('videoContainer');
    if (!videoContainer) return;
    const rect = videoContainer.getBoundingClientRect();
    sendAutoFitDisplay(rect.width, rect.height);
  }, 300);
}
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('resize', handleViewportResize);
}

renderTransportSelection();
setupTransportControls();
setupStreamQualityControls();
setupNavigationControls();
loadFavoriteApps();
loadStreamQualityStatus();
setupSystemControls();
setupClipboardSync();
