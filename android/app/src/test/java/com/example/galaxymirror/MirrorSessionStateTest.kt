package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MirrorSessionStateTest {
    @Test
    fun beginSessionReplacesPreviousSessionAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(1)
                .queueOffer(1)
                .beginSession(2)

        assertEquals(2, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
        assertFalse(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun beginUsbSessionRecordsTransportAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(1, MirrorTransport.TAILSCALE_WEBRTC)
                .queueOffer(1)
                .beginSession(2, MirrorTransport.USB_JPEG)

        assertEquals(2, state.activeSessionId)
        assertEquals(MirrorTransport.USB_JPEG, state.activeTransport)
        assertFalse(state.hasPendingOffer)
        assertFalse(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun startingTailscaleAfterUsbReplacesTransport() {
        val state =
            MirrorSessionState()
                .beginSession(10, MirrorTransport.USB_JPEG)
                .beginSession(11, MirrorTransport.TAILSCALE_WEBRTC)

        assertEquals(11, state.activeSessionId)
        assertEquals(MirrorTransport.TAILSCALE_WEBRTC, state.activeTransport)
    }

    @Test
    fun endActiveSessionClearsSessionState() {
        val state =
            MirrorSessionState()
                .beginSession(7)
                .queueOffer(7)
                .endSession(7)

        assertEquals(0, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
    }

    @Test
    fun endingUsbSessionClearsTransport() {
        val state =
            MirrorSessionState()
                .beginSession(9, MirrorTransport.USB_JPEG)
                .endSession(9)

        assertEquals(0, state.activeSessionId)
        assertNull(state.activeTransport)
    }

    @Test
    fun endingInactiveSessionDoesNotClearActiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(7)
                .endSession(6)

        assertEquals(7, state.activeSessionId)
    }

    @Test
    fun activeCheckCanRequireTransportMatch() {
        val state = MirrorSessionState().beginSession(5, MirrorTransport.USB_JPEG)

        assertTrue(state.isActive(5, MirrorTransport.USB_JPEG))
        assertFalse(state.isActive(5, MirrorTransport.TAILSCALE_WEBRTC))
        assertFalse(state.isActive(4, MirrorTransport.USB_JPEG))
    }

    @Test
    fun h264UsbSessionUsesDedicatedTransportOwnership() {
        val state = MirrorSessionState().beginSession(22, MirrorTransport.USB_H264)

        assertTrue(state.isActive(22, MirrorTransport.USB_H264))
        assertFalse(state.isActive(22, MirrorTransport.USB_JPEG))
        assertEquals(MirrorTransport.USB_H264, state.activeTransport)
    }

    @Test
    fun projectionStoppedRequiresReauthorizationAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(3)
                .queueOffer(3)
                .projectionStopped(3)

        assertEquals(0, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
        assertTrue(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun projectionStoppedForReplacedSessionDoesNotClearNewActiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(1)
                .beginSession(2)
                .projectionStopped(1)

        assertEquals(2, state.activeSessionId)
        assertFalse(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun queueOfferIgnoresInactiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(3)
                .queueOffer(4)

        assertNull(state.pendingOfferSessionId)
        assertFalse(state.hasPendingOffer)
    }

    @Test
    fun webRtcAnswerCallbacksRequireActiveTailscaleSession() {
        val source = readMediaProjectionServiceSource()
        val activeGuard = "if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return"
        val createAnswerStart = source.indexOf("peerConnection?.createAnswer(object : SdpObserver {")
        assertTrue("createAnswer observer should exist", createAnswerStart >= 0)

        val remoteOnSetSuccessStart = source.lastIndexOf("override fun onSetSuccess() {", createAnswerStart)
        assertTrue("remote onSetSuccess should exist before createAnswer", remoteOnSetSuccessStart >= 0)
        val remoteOnSetSuccessBeforeCreateAnswer = source.substring(remoteOnSetSuccessStart, createAnswerStart)
        assertTrue(
            "Remote description onSetSuccess must re-check active Tailscale session before createAnswer.",
            remoteOnSetSuccessBeforeCreateAnswer.contains(activeGuard),
        )

        val answerOnCreateSuccessStart =
            source.indexOf("override fun onCreateSuccess(desc: SessionDescription?) {", createAnswerStart)
        assertTrue("answer onCreateSuccess should exist", answerOnCreateSuccessStart >= 0)
        val answerProcessingStart = source.indexOf("val modifiedAnswerSdp", answerOnCreateSuccessStart)
        assertTrue("answer SDP processing should exist", answerProcessingStart >= 0)
        val answerOnCreateSuccessBeforeProcessing = source.substring(answerOnCreateSuccessStart, answerProcessingStart)
        assertTrue(
            "Answer onCreateSuccess must re-check active Tailscale session before processing SDP.",
            answerOnCreateSuccessBeforeProcessing.contains(activeGuard),
        )

        val setLocalDescriptionStart =
            source.indexOf("peerConnection?.setLocalDescription(object : SdpObserver {", answerOnCreateSuccessStart)
        assertTrue("setLocalDescription observer should exist", setLocalDescriptionStart >= 0)
        val localOnSetSuccessStart = source.indexOf("override fun onSetSuccess() {", setLocalDescriptionStart)
        assertTrue("local onSetSuccess should exist", localOnSetSuccessStart >= 0)
        val answerSendStart = source.indexOf("sendResponse(json.toString())", localOnSetSuccessStart)
        assertTrue("answer send should exist", answerSendStart >= 0)
        val localOnSetSuccessBeforeSend = source.substring(localOnSetSuccessStart, answerSendStart)
        assertTrue(
            "Local description onSetSuccess must re-check active Tailscale session before sending ANSWER.",
            localOnSetSuccessBeforeSend.contains(activeGuard),
        )
    }

    @Test
    fun usbFinallyStopsStreamerOnlyForActiveUsbSession() {
        val source = readMediaProjectionServiceSource()
        val finallyStart = source.indexOf("USB session \$sessionId\", e)")
        assertTrue("USB session error handler should exist before cleanup", finallyStart >= 0)
        val cleanupStart = source.indexOf("withContext(NonCancellable)", finallyStart)
        assertTrue("USB cleanup should use NonCancellable", cleanupStart >= 0)
        val stopStart = source.indexOf("usbScreenStreamer.stop()", cleanupStart)
        assertTrue("USB cleanup should stop streamer somewhere", stopStart >= 0)
        val cleanupBeforeStop = source.substring(cleanupStart, stopStart)

        assertTrue(
            "Stale USB cleanup must check ownership before stopping the global streamer.",
            cleanupBeforeStop.contains("isActiveSession(sessionId, MirrorTransport.USB_JPEG)"),
        )
        assertTrue(
            "USB cleanup must clear projection ownership only for its own session.",
            source.substring(cleanupStart, source.indexOf("endViewerSession(sessionId)", cleanupStart))
                .contains("clearActiveUsbProjectionSession(sessionId)"),
        )
    }

    @Test
    fun usbReplacementStopsPreviousUsbStreamerBeforeAssigningNewSession() {
        val source = readMediaProjectionServiceSource()
        val beginStart = source.indexOf("private suspend fun beginViewerSession(transport: MirrorTransport)")
        assertTrue("transport-aware beginViewerSession should exist", beginStart >= 0)
        val assignStart = source.indexOf("mirrorSessionState = mirrorSessionState.beginSession(sessionId, transport)", beginStart)
        assertTrue("beginViewerSession should assign transport-aware session state", assignStart >= 0)
        val beforeAssign = source.substring(beginStart, assignStart)

        assertTrue(
            "beginViewerSession should capture previous session id before assigning the new session.",
            beforeAssign.contains("val previousSessionId =") &&
                beforeAssign.contains("mirrorSessionState.activeSessionId"),
        )
        assertTrue(
            "beginViewerSession should capture previous transport before assigning the new session.",
            beforeAssign.contains("val previousTransport =") &&
                beforeAssign.contains("mirrorSessionState.activeTransport"),
        )
        assertTrue(
            "Starting a replacement session after USB should deliberately stop the previous USB streamer.",
            beforeAssign.contains("MirrorTransport.USB_JPEG ->") &&
                beforeAssign.contains("usbScreenStreamer.stop()"),
        )
        assertTrue(
            "USB replacement must clear projection ownership only for the replaced session.",
            beforeAssign.contains("clearActiveUsbProjectionSession(previousSessionId)"),
        )
    }

    @Test
    fun usbFramesUseBoundedLatestFrameSender() {
        val source = readMediaProjectionServiceSource()
        val usbRouteStart = source.indexOf("webSocket(\"/usb/session\")")
        assertTrue("USB route should exist", usbRouteStart >= 0)
        val usbRouteEnd = source.indexOf("private suspend fun beginViewerSession", usbRouteStart)
        assertTrue("USB route should appear before beginViewerSession", usbRouteEnd >= 0)
        val usbRoute = source.substring(usbRouteStart, usbRouteEnd)

        assertTrue(
            "USB route should use a bounded frame channel.",
            usbRoute.contains("Channel<ByteArray>(") &&
                usbRoute.contains("capacity = 1") &&
                usbRoute.contains("onBufferOverflow = BufferOverflow.DROP_OLDEST"),
        )
        assertTrue(
            "USB frame callback should enqueue latest frames instead of launching a sender per frame.",
            usbRoute.contains("frameChannel.trySend(frameBytes)"),
        )
        assertFalse(
            "USB frame callback must not launch a coroutine for every JPEG frame.",
            usbRoute.contains(") { frameBytes ->\n                                        socketSession.launch"),
        )
    }

    @Test
    fun usbProjectionStopCallbackIsSessionAware() {
        val source = readMediaProjectionServiceSource()
        val usbRouteStart = source.indexOf("webSocket(\"/usb/session\")")
        assertTrue("USB route should exist", usbRouteStart >= 0)
        val streamerStart = source.indexOf("usbScreenStreamer.start(", usbRouteStart)
        assertTrue("USB streamer should be started from the USB route", streamerStart >= 0)
        val beforeStreamerStart = source.substring(usbRouteStart, streamerStart)

        assertTrue(
            "USB route should bind projection ownership before starting the streamer.",
            beforeStreamerStart.contains("prepareUsbScreenStreamerForSession(sessionId)"),
        )
        assertTrue(
            "Service should track the USB projection owner session id.",
            source.contains("activeUsbProjectionSessionId"),
        )
        assertTrue(
            "USB streamer callback should capture and pass a session id.",
            source.contains("private fun createUsbScreenStreamer(sessionId: Int): UsbScreenStreamer") &&
                source.contains("handleUsbProjectionStopped(sessionId)"),
        )
        assertTrue(
            "USB projection stop handler should be session-aware.",
            source.contains("private fun handleUsbProjectionStopped(sessionId: Int)"),
        )

        val handlerStart = source.indexOf("private fun handleUsbProjectionStopped(sessionId: Int)")
        assertTrue("session-aware USB projection stop handler should exist", handlerStart >= 0)
        val handlerEnd = source.indexOf("private fun markViewerActivity", handlerStart)
        assertTrue("USB projection stop handler should appear before markViewerActivity", handlerEnd >= 0)
        val handler = source.substring(handlerStart, handlerEnd)
        assertTrue(
            "USB projection stop handler must ignore callbacks not owned by the active USB session.",
            handler.contains("activeUsbProjectionSessionId == sessionId") &&
            handler.contains("mirrorSessionState.isActive(sessionId, MirrorTransport.USB_JPEG)") &&
            handler.contains("mirrorSessionState.isActive(sessionId, MirrorTransport.USB_H264)"),
        )
    }

    @Test
    fun serviceExposesUsbPerfDebugEndpoint() {
        val source = readMediaProjectionServiceSource()

        assertTrue(source.contains("private val usbPerfMonitor"))
        assertTrue(source.contains("get(\"/debug/perf\")"))
        assertTrue(source.contains("currentUsbPerfSnapshot().toJson().toString()"))
    }

    @Test
    fun serviceStartsUsbStreamerWithThermalPolicyProfileProvider() {
        val source = readMediaProjectionServiceSource()

        assertTrue(source.contains("UsbThermalPolicy.resolve("))
        assertTrue(source.contains("profileProvider = ::resolveCurrentUsbProfile"))
        assertTrue(source.contains("perfMonitor = usbPerfMonitor"))
    }

    @Test
    fun serviceRoutesUsbSessionCodecQueryToH264Streamer() {
        val source = readMediaProjectionServiceSource()

        assertTrue(source.contains("call.request.queryParameters[\"codec\"]"))
        assertTrue(source.contains("UsbVideoCodec.fromWireValue"))
        assertTrue(source.contains("UsbH264ScreenStreamer"))
        assertTrue(source.contains("onVideoConfig = { configJson ->"))
        assertTrue(source.contains("send(Frame.Text(configJson))"))
        assertTrue(source.contains("MirrorTransport.USB_H264"))
        assertTrue(source.contains("profileProvider = ::resolveCurrentUsbH264Profile"))
    }

    @Test
    fun usbGrantWaitLoopPollsCachedGrantWhenStaleSessionConsumesSignal() {
        val source = readMediaProjectionServiceSource()
        val usbRouteStart = source.indexOf("webSocket(\"/usb/session\")")
        assertTrue("USB route should exist", usbRouteStart >= 0)
        val waitLoopStart = source.indexOf("while (true) {", usbRouteStart)
        assertTrue("USB grant wait loop should exist", waitLoopStart >= 0)
        val consumeStart = source.indexOf("consumeMediaProjectionGrant()", waitLoopStart)
        assertTrue("USB grant consumption should exist after wait loop", consumeStart >= 0)
        val waitLoop = source.substring(waitLoopStart, consumeStart)

        assertTrue(
            "USB grant wait must periodically re-check cached MediaProjection data even if a stale session consumes the grant signal.",
            waitLoop.contains("mediaProjectionResultCode != null && mediaProjectionResultData != null") &&
                waitLoop.contains("withTimeoutOrNull(MEDIA_PROJECTION_GRANT_POLL_MS)") &&
                waitLoop.contains("permissionGrantChannel.receive()"),
        )
    }

    private fun readMediaProjectionServiceSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/galaxymirror/MediaProjectionService.kt"),
            Path.of("app/src/main/java/com/example/galaxymirror/MediaProjectionService.kt"),
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("MediaProjectionService.kt source not found")
        return path.toFile().readText()
    }
}
