package com.example.galaxymirror

import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * PeerConnectionFactory, PeerConnection, DataChannel 및 WebRTC SDP/ICE 시그널링 처리를 전담하는 매니저 클래스.
 */
class WebRtcManager(
    private val service: MediaProjectionService
) {
    @Volatile internal var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile var peerConnection: PeerConnection? = null
    @Volatile internal var videoSource: VideoSource? = null
    @Volatile internal var videoTrack: VideoTrack? = null
    @Volatile internal var videoSender: RtpSender? = null
    @Volatile internal var surfaceTextureHelper: SurfaceTextureHelper? = null
    @Volatile internal var videoCapturer: VideoCapturer? = null
    @Volatile var controlChannel: DataChannel? = null
    @Volatile internal var eglBase: EglBase? = null
    @Volatile internal var remoteDescriptionSet = false
    internal val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    internal var videoCapturerLastWidth = 0
    internal var videoCapturerLastHeight = 0

    internal fun initializeWebRTC(sessionId: Int, remoteSdp: SessionDescription, sendResponse: (String) -> Unit) {
        if (!service.isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
            CrashDiagnostics.recordEvent(service, "Skipping WebRTC initialization for inactive sessionId=$sessionId.")
            return
        }

        val readiness = ProjectionReadiness.from(
            hasProjectionIntent = service.screenCaptureManager.mediaProjectionResultData != null,
            isServiceRunning = MediaProjectionService.isRunning
        )
        if (readiness != ProjectionReadiness.READY) {
            CrashDiagnostics.recordEvent(service, "Capture not ready; deferring offer for sessionId=$sessionId.")
            service.queuePendingOffer(sessionId, remoteSdp, sendResponse)
            if (readiness == ProjectionReadiness.MISSING_PERMISSION) {
                service.requestScreenCapturePermissionFromActivity("Negotiation attempted without active MediaProjection grant")
            }
            sendResponse(service.buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
            return
        }

        try {
            CrashDiagnostics.recordEvent(service, "Initializing WebRTC for sessionId=$sessionId.")

            // 1. Initialize PeerConnectionFactory if needed
            initializePeerConnectionFactoryIfNeeded()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            remoteDescriptionSet = false

            // 2. Create PeerConnection
            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                createPeerConnectionObserver(sessionId, sendResponse)
            )

            // 3. Setup Screen Capture pipeline (Reuses capture elements if active)
            if (!setupScreenCapturePipeline(sessionId, sendResponse)) {
                return
            }

            val streamNetwork = service.currentStreamNetworkTransport()
            val streamProfile = AdaptiveStreamQuality.resolve(service.streamQualityMode, streamNetwork, service.viewerActivityState)
            service.streamQualityNetwork = streamNetwork
            service.streamQualityProfile = streamProfile

            // 4. Add video track to PeerConnection
            videoSender = peerConnection?.addTrack(videoTrack, listOf("video_stream_id"))
            service.applyStreamQualityProfile(streamProfile, reason = "WebRTC start")

            // 5 & 6. Handle SDP Exchange
            handleSdpExchange(sessionId, remoteSdp, sendResponse)

        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(service.filesDir, "WebRTC initialization", e)
            Log.e("WebRTC", "Error during WebRTC initialization: ${e.message}", e)
        }
    }

    internal fun cleanupWebRTCResources(stopProjectionService: Boolean, stopCapturer: Boolean) {
        val steps = mutableListOf<CleanupStep>()
        steps.add(CleanupStep("control channel close") { controlChannel?.close() })
        steps.add(CleanupStep("control channel dispose") { controlChannel?.dispose() })

        if (stopCapturer) {
            steps.add(CleanupStep("video capturer stop") { videoCapturer?.stopCapture() })
            steps.add(CleanupStep("video capturer dispose") { videoCapturer?.dispose() })
            steps.add(CleanupStep("video track dispose") { videoTrack?.dispose() })
            steps.add(CleanupStep("video source dispose") { videoSource?.dispose() })
            steps.add(CleanupStep("surface texture helper dispose") { surfaceTextureHelper?.dispose() })
        }

        steps.add(CleanupStep("peer connection close") { peerConnection?.close() })
        steps.add(CleanupStep("peer connection dispose") { peerConnection?.dispose() })

        if (stopCapturer) {
            steps.add(CleanupStep("peer connection factory dispose") { peerConnectionFactory?.dispose() })
            steps.add(CleanupStep("egl release") { eglBase?.release() })
        }

        val failures = CleanupStepRunner.run(steps)
        failures.forEach { failure ->
            CrashDiagnostics.recordCaughtException(service.filesDir, "WebRTC cleanup ${failure.name}", failure.throwable)
            Log.e("MediaProjectionService", "Error during WebRTC cleanup step ${failure.name}", failure.throwable)
        }

        synchronized(pendingRemoteIceCandidates) {
            pendingRemoteIceCandidates.clear()
        }
        controlChannel = null
        videoSender = null
        peerConnection = null
        remoteDescriptionSet = false

        if (stopCapturer) {
            videoCapturer = null
            videoTrack = null
            videoSource = null
            surfaceTextureHelper = null
            peerConnectionFactory = null
            eglBase = null
            videoCapturerLastWidth = 0
            videoCapturerLastHeight = 0
        }

        if (stopProjectionService) {
            service.screenCaptureManager.mediaProjectionResultCode = null
            service.screenCaptureManager.mediaProjectionResultData = null
        }

        Log.d("WebRTC", "WebRTC session clean up completed with ${failures.size} failures. stopCapturer=$stopCapturer")
    }

    internal fun initializePeerConnectionFactoryIfNeeded() {
        if (peerConnectionFactory == null) {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(service.applicationContext)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            eglBase = EglBase.create()
            val eglContext = eglBase!!.eglBaseContext

            val factoryOptions = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
        }
    }

    internal fun createPeerConnectionObserver(sessionId: Int, sendResponse: (String) -> Unit): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    if (!service.isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                    val json = org.json.JSONObject().apply {
                        put("type", "ICE_CANDIDATE")
                        put("payload", org.json.JSONObject().apply {
                            put("candidate", it.sdp)
                            put("sdpMid", it.sdpMid)
                            put("sdpMLineIndex", it.sdpMLineIndex)
                        })
                    }
                    sendResponse(json.toString())
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let { dc ->
                    if (!service.isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
                        dc.close()
                        return
                    }
                    if (!ControlEventValidator.isControlChannel(dc.label())) {
                        Log.w("WebRTC", "Rejected DataChannel: ${dc.label()}")
                        dc.close()
                        return
                    }
                    controlChannel = dc
                    sendResponse(service.buildStatusMessage(message = "CONTROL_CHANNEL_ACCEPTED"))
                    Log.d("WebRTC", "DataChannel received: ${dc.label()}")
                    dc.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {
                            Log.d("WebRTC", "DataChannel state: ${dc.state()}")
                        }
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            try {
                                if (!service.isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                                val bytes = ByteArray(buffer.data.remaining())
                                buffer.data.get(bytes)
                                val text = String(bytes, Charsets.UTF_8)
                                Log.d("WebRTC", "DataChannel message: $text")
                                service.controlEventDispatcher.dispatch(text) { result ->
                                    sendControlAck(dc, result)
                                }
                            } catch (e: Exception) {
                                Log.e("WebRTC", "Error processing DataChannel message: ${e.message}", e)
                            }
                        }
                    })
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }
    }

    internal fun setupScreenCapturePipeline(sessionId: Int, sendResponse: (String) -> Unit): Boolean {
        if (videoCapturer == null) {
            val projectionIntent = service.screenCaptureManager.mediaProjectionResultData ?: return false
            videoSource = peerConnectionFactory?.createVideoSource(true)
            videoCapturer = ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {
                override fun onStop() {
                    CrashDiagnostics.recordEvent(service.filesDir, "MediaProjection stopped inside service.")
                    service.handleScreenCaptureReauthorizationRequired(
                        sessionId = sessionId,
                        sendResponse = sendResponse,
                        diagnosticReason = "ScreenCapturerAndroid callback",
                        stopCapturer = true,
                    )
                }
            })
            videoCapturer?.initialize(surfaceTextureHelper, service.applicationContext, videoSource?.capturerObserver)

            val streamNetwork = service.currentStreamNetworkTransport()
            val streamProfile = AdaptiveStreamQuality.resolve(service.streamQualityMode, streamNetwork, service.viewerActivityState)

            videoCapturerLastWidth = streamProfile.width
            videoCapturerLastHeight = streamProfile.height
            try {
                videoCapturer?.startCapture(streamProfile.width, streamProfile.height, streamProfile.fps)
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(service.filesDir, "ScreenCapturerAndroid.startCapture", e)
                service.handleScreenCaptureReauthorizationRequired(
                    sessionId = sessionId,
                    sendResponse = sendResponse,
                    diagnosticReason = "ScreenCapturerAndroid.startCapture failure",
                    stopCapturer = true,
                )
                return false
            }
            videoTrack = peerConnectionFactory?.createVideoTrack("video_track_id", videoSource)
        }
        return true
    }

    private fun isActiveSession(sessionId: Int, transport: MirrorTransport): Boolean =
        service.isActiveSession(sessionId, transport)

    internal fun handleSdpExchange(sessionId: Int, remoteSdp: SessionDescription, sendResponse: (String) -> Unit) {
        // 5. Apply H.264 optimization to Offer SDP (SDP Munging)
        val modifiedOfferSdp = preferH264Codec(remoteSdp.description)
        val modifiedRemoteSdp = SessionDescription(remoteSdp.type, modifiedOfferSdp)

        // 6. Set Remote Description
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                remoteDescriptionSet = true
                flushPendingRemoteIceCandidates()

                // Create local SDP Answer
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                        desc?.let {
                            // Apply H.264 optimization to local Answer SDP (SDP Munging)
                            val modifiedAnswerSdp = preferH264Codec(it.description)
                            val modifiedLocalSdp = SessionDescription(it.type, modifiedAnswerSdp)

                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(desc: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                                    Log.d("WebRTC", "SetLocalDescription success. Sending modified Answer...")
                                    val json = org.json.JSONObject().apply {
                                        put("type", "ANSWER")
                                        put("payload", org.json.JSONObject().apply {
                                            put("type", "answer")
                                            put("sdp", modifiedLocalSdp.description)
                                        })
                                    }
                                    sendResponse(json.toString())
                                }
                                override fun onCreateFailure(reason: String?) {}
                                override fun onSetFailure(reason: String?) {}
                            }, modifiedLocalSdp)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(reason: String?) {}
                    override fun onSetFailure(reason: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateFailure(reason: String?) {}
            override fun onSetFailure(reason: String?) {}
        }, modifiedRemoteSdp)
    }

    internal fun preferH264Codec(sdpDescription: String): String {
        val lines = sdpDescription.split("\r\n")
        val videoMediaLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoMediaLineIndex == -1) return sdpDescription

        val videoMediaLine = lines[videoMediaLineIndex]
        val parts = videoMediaLine.split(" ")
        if (parts.size < 4) return sdpDescription

        val h264PayloadTypes = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                val partsRtpmap = line.substringAfter("a=rtpmap:").split(" ")
                if (partsRtpmap.isNotEmpty()) {
                    h264PayloadTypes.add(partsRtpmap[0])
                }
            }
        }

        if (h264PayloadTypes.isEmpty()) return sdpDescription

        val proto = parts[2]
        val otherPayloads = parts.subList(3, parts.size).filter { it !in h264PayloadTypes }
        val newPayloadOrder = h264PayloadTypes + otherPayloads
        val newVideoMediaLine = "${parts[0]} ${parts[1]} $proto ${newPayloadOrder.joinToString(" ")}"

        val newLines = lines.toMutableList()
        newLines[videoMediaLineIndex] = newVideoMediaLine
        return newLines.joinToString("\r\n")
    }

    internal fun addRemoteIceCandidate(candidate: IceCandidate) {
        synchronized(pendingRemoteIceCandidates) {
            if (!remoteDescriptionSet || peerConnection == null) {
                pendingRemoteIceCandidates.add(candidate)
                Log.d("WebRTC", "Queued remote ICE candidate until remote description is set.")
                return
            }
        }
        peerConnection?.addIceCandidate(candidate)
    }

    internal fun flushPendingRemoteIceCandidates() {
        val candidates = synchronized(pendingRemoteIceCandidates) {
            pendingRemoteIceCandidates.toList().also { pendingRemoteIceCandidates.clear() }
        }
        candidates.forEach { peerConnection?.addIceCandidate(it) }
        if (candidates.isNotEmpty()) {
            Log.d("WebRTC", "Flushed ${candidates.size} queued remote ICE candidates.")
        }
    }

    internal fun handleSignalingMessage(sessionId: Int, message: String, sendResponse: (String) -> Unit) {
        service.serviceScope.launch(Dispatchers.Default) {
            val isActive = withContext(Dispatchers.Main) {
                service.isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)
            }
            if (!isActive) {
                Log.w("WebRTC", "Ignoring signaling message for inactive session: $sessionId")
                return@launch
            }

            try {
                val json = org.json.JSONObject(message)
                val type = json.getString("type")

                when (type) {
                    "OFFER" -> handleOfferMessage(sessionId, json, sendResponse)
                    "ICE_CANDIDATE" -> handleIceCandidateMessage(sessionId, json)
                    "black_overlay" -> handleBlackOverlayMessage(json, sendResponse)
                    "resize_display" -> handleResizeDisplayMessage(json, sendResponse)
                }
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(service.filesDir, "signaling JSON parse", e)
                Log.e("WebRTC", "Error parsing signaling JSON: ${e.message}", e)
            }
        }
    }


    private suspend fun handleOfferMessage(sessionId: Int, json: org.json.JSONObject, sendResponse: (String) -> Unit) {
        CrashDiagnostics.recordEvent(service, "Offer received for sessionId=$sessionId.")
        Log.d("WebRTC", "Offer received. Creating Answer...")
        val sdpObj = json.getJSONObject("payload")
        val sdpType = SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type"))
        val originalSdp = sdpObj.getString("sdp")

        // SDP Munging inside Dispatchers.Default
        val modifiedOfferSdp = preferH264Codec(originalSdp)
        val mungedOfferSdp = SessionDescription(sdpType, modifiedOfferSdp)

        withContext(Dispatchers.Main) {
            val isActiveMain = service.isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)
            val readiness = ProjectionReadiness.from(
                hasProjectionIntent = service.screenCaptureManager.mediaProjectionResultData != null,
                isServiceRunning = MediaProjectionService.isRunning
            )
            val decision = SignalingDecision.onOffer(readiness, isActiveMain)
            CrashDiagnostics.recordEvent(service, "Signaling decision on OFFER: $decision.")

            when (decision) {
                SignalingDecision.START_NEGOTIATION -> {
                    initializeWebRTC(sessionId, mungedOfferSdp, sendResponse)
                }
                SignalingDecision.QUEUE_AND_REQUEST_PERMISSION -> {
                    service.queuePendingOffer(sessionId, mungedOfferSdp, sendResponse)
                    service.requestScreenCapturePermissionFromActivity("Offer received without active MediaProjection grant")
                    sendResponse(service.buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                }
                SignalingDecision.QUEUE_AND_SEND_STATUS -> {
                    service.queuePendingOffer(sessionId, mungedOfferSdp, sendResponse)
                    sendResponse(service.buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                    service.resumePendingOfferIfReady()
                }
                SignalingDecision.IGNORE_INACTIVE -> {
                    CrashDiagnostics.recordEvent(service, "Ignoring offer for inactive sessionId=$sessionId.")
                }
            }
        }
    }

    private suspend fun handleIceCandidateMessage(sessionId: Int, json: org.json.JSONObject) {
        CrashDiagnostics.recordEvent(service, "ICE candidate received for sessionId=$sessionId.")
        val candidateObj = json.getJSONObject("payload")
        val candidate = IceCandidate(
            candidateObj.getString("sdpMid"),
            candidateObj.getInt("sdpMLineIndex"),
            candidateObj.getString("candidate")
        )
        withContext(Dispatchers.Main) {
            addRemoteIceCandidate(candidate)
        }
    }

    private suspend fun handleBlackOverlayMessage(json: org.json.JSONObject, sendResponse: (String) -> Unit) {
        val enabled = if (json.has("payload")) {
            json.getJSONObject("payload").optBoolean("enabled", false)
        } else {
            json.optBoolean("enabled", false)
        }
        withContext(Dispatchers.Main) {
            service.setBlackOverlayEnabled(enabled)
        }
        sendResponse(service.buildStatusMessage(message = "OVERLAY_UPDATED"))
    }

    private suspend fun handleResizeDisplayMessage(json: org.json.JSONObject, sendResponse: (String) -> Unit) {
        val payload = if (json.has("payload")) json.getJSONObject("payload") else json
        val reqWidth = payload.optInt("width", 1080)
        val reqHeight = payload.optInt("height", 1920)
        withContext(Dispatchers.Main) {
            changeVirtualDisplaySize(reqWidth, reqHeight)
        }
        sendResponse(service.buildStatusMessage(message = "DISPLAY_RESIZED"))
    }

    internal fun changeVirtualDisplaySize(targetWidth: Int, targetHeight: Int) {
        val clampedWidth = targetWidth.coerceIn(480, 2560)
        val clampedHeight = targetHeight.coerceIn(480, 2560)
        if (videoCapturerLastWidth == clampedWidth && videoCapturerLastHeight == clampedHeight) return

        try {
            val fps = service.streamQualityProfile.fps
            videoCapturer?.changeCaptureFormat(clampedWidth, clampedHeight, fps)
            videoCapturerLastWidth = clampedWidth
            videoCapturerLastHeight = clampedHeight
            service.screenCaptureManager.usbH264ScreenStreamer?.changeResolution(clampedWidth, clampedHeight)
            Log.d("WebRTC", "Dynamic VirtualDisplay size updated: ${clampedWidth}x${clampedHeight}@${fps}")
        } catch (e: Exception) {
            Log.e("WebRTC", "Error changing VirtualDisplay capture format: ${e.message}", e)
        }
    }

    internal fun sendControlAck(channel: DataChannel, result: ControlEventResult) {
        if (result.seq == null || channel.state() != DataChannel.State.OPEN) return
        try {
            channel.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap(result.toAckJson().toByteArray(Charsets.UTF_8)),
                    false
                )
            )
        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(service.filesDir, "control ack send", e)
            Log.e("WebRTC", "Error sending control ACK: ${e.message}", e)
        }
    }
}
