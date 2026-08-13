package com.example.galaxymirror

import android.content.Intent
import org.webrtc.SessionDescription

/**
 * MediaProjection 권한 토큰, USB 화면 스트리밍 및 발열/성능 정책 관리를 전담하는 매니저 클래스.
 */
class ScreenCaptureManager(
    private val service: MediaProjectionService
) {
    lateinit var usbScreenStreamer: UsbScreenStreamer
    internal fun isUsbScreenStreamerInitialized() = this::usbScreenStreamer.isInitialized

    internal var usbH264ScreenStreamer: UsbH264ScreenStreamer? = null
    lateinit var usbThermalReader: UsbThermalReader
    internal fun isUsbThermalReaderInitialized() = this::usbThermalReader.isInitialized

    internal val usbPerfMonitor = UsbPerfMonitor()
    internal var activeUsbProjectionSessionId = 0
    var mediaProjectionResultCode: Int? = null
    var mediaProjectionResultData: Intent? = null

    @Volatile internal var lastUsbProfile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO)
    @Volatile internal var lastUsbCodec = UsbVideoCodec.JPEG
    @Volatile internal var lastUsbH264Profile = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.AUTO)
    internal var pendingOffer: PendingOffer? = null

    internal fun resolveCurrentUsbProfile(): UsbStreamProfile {
        val profile = UsbThermalPolicy.resolve(
            selectedMode = service.streamQualityMode,
            thermalStatus = service.currentUsbThermalStatus(),
            viewerIdle = service.viewerActivityState == ViewerActivityState.IDLE
        )
        lastUsbProfile = profile
        return profile
    }

    internal fun resolveCurrentUsbH264Profile(): UsbH264StreamProfile {
        val selected = UsbH264StreamProfilePolicy.resolve(service.streamQualityMode)
        val thermalStatus = service.currentUsbThermalStatus()
        val tier = when {
            service.viewerActivityState == ViewerActivityState.IDLE -> UsbStreamProfileTier.COOL
            thermalStatus == UsbThermalStatus.LIGHT -> service.minOfUsbTier(selected.tier, UsbStreamProfileTier.BALANCED)
            thermalStatus == UsbThermalStatus.MODERATE -> UsbStreamProfileTier.COOL
            thermalStatus.isSevereOrWorse() -> service.minOfUsbTier(selected.tier, UsbStreamProfileTier.COOL)
            else -> selected.tier
        }
        val profile = UsbH264StreamProfilePolicy.resolveTier(tier)
        lastUsbH264Profile = profile
        return profile
    }

    internal fun createUsbScreenStreamer(sessionId: Int): UsbScreenStreamer =
        UsbScreenStreamer(service.applicationContext) {
            handleUsbProjectionStopped(sessionId)
        }

    internal fun createUsbH264ScreenStreamer(sessionId: Int): UsbH264ScreenStreamer =
        UsbH264ScreenStreamer(service.applicationContext) {
            handleUsbProjectionStopped(sessionId)
        }

    internal fun prepareUsbScreenStreamerForSession(sessionId: Int) {
        synchronized(service.sessionLock) {
            activeUsbProjectionSessionId = sessionId
        }
        usbScreenStreamer = createUsbScreenStreamer(sessionId)
    }

    internal fun prepareUsbH264ScreenStreamerForSession(sessionId: Int): UsbH264ScreenStreamer {
        synchronized(service.sessionLock) {
            activeUsbProjectionSessionId = sessionId
        }
        val streamer = createUsbH264ScreenStreamer(sessionId)
        usbH264ScreenStreamer = streamer
        return streamer
    }

    internal fun clearActiveUsbProjectionSession(sessionId: Int) {
        synchronized(service.sessionLock) {
            if (activeUsbProjectionSessionId == sessionId) {
                activeUsbProjectionSessionId = 0
            }
        }
    }

    internal fun handleUsbProjectionStopped(sessionId: Int) {
        service.mainHandler.post {
            val shouldHandle = synchronized(service.sessionLock) {
                activeUsbProjectionSessionId == sessionId &&
                    (service.mirrorSessionState.isActive(sessionId, MirrorTransport.USB_JPEG) ||
                     service.mirrorSessionState.isActive(sessionId, MirrorTransport.USB_H264))
            }
            if (!shouldHandle) {
                CrashDiagnostics.recordEvent(service, "Ignoring stale USB MediaProjection stop for sessionId=$sessionId.")
                return@post
            }
            val hasActiveSession = synchronized(service.sessionLock) { service.mirrorSessionState.activeSessionId != 0 }
            if (hasActiveSession) {
                CrashDiagnostics.recordEvent(service, "Preserving MediaProjection token during transport transition for sessionId=$sessionId.")
                return@post
            }
            service.stopProjectionCaptureForPolicy(CleanupReason.VIEWER_SOCKET_CLOSED)
            service.updateServiceState()
        }
    }

    internal fun consumeMediaProjectionGrant(): Pair<Int, Intent>? {
        val resultCode = mediaProjectionResultCode
        val resultData = mediaProjectionResultData
        if (resultCode == null || resultData == null) return null
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        return resultCode to resultData
    }
}

internal data class PendingOffer(
    val sessionId: Int,
    val remoteSdp: SessionDescription,
    val sendResponse: (String) -> Unit
)
