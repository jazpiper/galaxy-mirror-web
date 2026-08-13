package com.example.galaxymirror

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

fun Routing.setupMirrorRouting(service: MediaProjectionService) {
    with(service) {
        staticResources("/", "files")

        setupApiRoutes(this)
        setupWebRtcSignalingRoute(this)
        setupUsbSessionRoute(this)
    }
}

private fun ApplicationCall.isCrossOriginRequest(): Boolean =
    !ViewerOriginGuard.isAllowed(
        request.headers[HttpHeaders.Origin],
        request.headers[HttpHeaders.Host],
    )

/**
 * 크로스 오리진 요청이면 403으로 응답하고 true를 반환한다. 호출부는 true일 때 즉시 return 해야 한다.
 */
private suspend fun ApplicationCall.rejectIfCrossOrigin(): Boolean {
    if (!isCrossOriginRequest()) return false
    respondText(
        """{"ok":false,"error":"CROSS_ORIGIN_REJECTED"}""",
        ContentType.Application.Json,
        HttpStatusCode.Forbidden,
    )
    return true
}

private fun Routing.setupApiRoutes(service: MediaProjectionService) {
    with(service) {

        get("/status") {
            call.respondText("Android Mirror Web Server is active. Port: 8080")
        }

        get("/debug/crash") {
            if (call.rejectIfCrossOrigin()) return@get
            call.respondText(
                redactSensitiveInfo(CrashDiagnostics.readDebugReport(service.filesDir)),
                ContentType.Text.Plain
            )
        }

        // 상태를 변경하므로 POST여야 한다. GET이면 <img src=...> 한 줄로 진단 기록이 지워진다.
        post("/debug/crash/clear") {
            if (call.rejectIfCrossOrigin()) return@post
            CrashDiagnostics.clearCrash(service.filesDir)
            call.respondText(
                "Cleared saved crash and caught exception. Recent events were kept.\n",
                ContentType.Text.Plain
            )
        }

        get("/debug/perf") {
            if (call.rejectIfCrossOrigin()) return@get
            val statusJson = withContext(Dispatchers.Main) {
                currentUsbPerfSnapshot().toJson().toString()
            }
            call.respondText(statusJson, ContentType.Application.Json)
        }

        get("/apps/favorites") {
            if (call.rejectIfCrossOrigin()) return@get
            call.respondText(
                favoriteAppsRepository.getFavoritesResponseJson(),
                ContentType.Application.Json
            )
        }

        get("/stream/quality") {
            val statusJson = withContext(Dispatchers.Main) {
                buildStreamQualityStatusString()
            }
            call.respondText(statusJson, ContentType.Application.Json)
        }

        post("/stream/quality") {
            if (call.rejectIfCrossOrigin()) return@post
            val mode = StreamQualityCodec.parseMode(call.receiveText())
            if (mode == null) {
                call.respondText(
                    """{"ok":false,"error":"INVALID_STREAM_QUALITY_MODE"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                    )
                return@post
            }

            withContext(Dispatchers.Main) {
                updateStreamQualityMode(mode)
            }
            val statusJson = withContext(Dispatchers.Main) {
                buildStreamQualityStatusString()
            }
            call.respondText(statusJson, ContentType.Application.Json)
        }

        post("/stream/overlay") {
            if (call.rejectIfCrossOrigin()) return@post
            val bodyText = call.receiveText()
            val enabled = try {
                JSONObject(bodyText).optBoolean("enabled", false)
            } catch (e: Exception) {
                false
            }

            val success = withContext(Dispatchers.Main) {
                setBlackOverlayEnabled(enabled)
            }
            val statusJson = withContext(Dispatchers.Main) {
                buildStatusMessage(message = if (success) "OVERLAY_UPDATED" else "OVERLAY_FAILED")
            }
            call.respondText(statusJson, ContentType.Application.Json)
        }

        post("/apps/launch") {
            if (call.rejectIfCrossOrigin()) return@post
            val packageName = FavoriteAppsCodec.parseLaunchPackageName(call.receiveText())
            if (packageName == null) {
                call.respondText(
                    """{"ok":false,"error":"INVALID_PACKAGE"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val launched = withContext(Dispatchers.Main) {
                favoriteAppsRepository.launchFavorite(packageName)
            }

            if (launched) {
                CrashDiagnostics.recordEvent(service.filesDir, "Favorite app launched: $packageName.")
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            } else {
                CrashDiagnostics.recordEvent(service.filesDir, "Favorite app launch failed: $packageName.")
                call.respondText(
                    """{"ok":false,"error":"APP_NOT_FOUND"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
            }
        }
    }
}

private fun Routing.setupWebRtcSignalingRoute(service: MediaProjectionService) {
    with(service) {

        webSocket("/signaling") {
            // beginViewerSession 이전에 막아야 한다. 세션을 먼저 시작하면 그 자체로 기존 뷰어 세션이
            // 밀려나고 MediaProjection 재승인 프롬프트가 뜬다.
            if (call.isCrossOriginRequest()) {
                CrashDiagnostics.recordEvent(service.filesDir, "Rejected cross-origin /signaling handshake.")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "cross-origin rejected"))
                return@webSocket
            }
            val sessionId = beginViewerSession(MirrorTransport.TAILSCALE_WEBRTC)
            CrashDiagnostics.recordEvent(service.filesDir, "Signaling WebSocket connected: sessionId=$sessionId.")
            Log.d("KtorServer", "New WebRTC signaling WebSocket connection established: $sessionId")

            val statusJob = launch {
                while (isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
                    delay(2_000)
                    try {
                        val statusMsg = withContext(Dispatchers.Main) {
                            buildStatusMessage(message = "STATUS_TICK")
                        }
                        send(Frame.Text(statusMsg))
                    } catch (e: Throwable) {
                        CrashDiagnostics.recordCaughtException(service.filesDir, "signaling status tick", e)
                        return@launch
                    }
                }
            }

            try {
                val connectedMsg = withContext(Dispatchers.Main) {
                    buildStatusMessage(message = "SIGNALING_CONNECTED")
                }
                send(Frame.Text(connectedMsg))
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        Log.d("KtorServer", "Signaling packet received: $text")
                        handleSignalingMessage(sessionId, text) { response ->
                            if (isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
                                launch { send(Frame.Text(response)) }
                            }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                CrashDiagnostics.recordEvent(service.filesDir, "Signaling connection closed by peer: sessionId=$sessionId.")
            } catch (e: Throwable) {
                CrashDiagnostics.recordCaughtException(service.filesDir, "signaling session $sessionId", e)
                Log.e("KtorServer", "Error in signaling session $sessionId: ${e.message}", e)
            } finally {
                statusJob.cancel()
                endViewerSession(sessionId)
            }
        }
    }
}

private fun Routing.setupUsbSessionRoute(service: MediaProjectionService) {
    with(service) {

        webSocket("/usb/session") {
            if (call.isCrossOriginRequest()) {
                CrashDiagnostics.recordEvent(service.filesDir, "Rejected cross-origin /usb/session handshake.")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "cross-origin rejected"))
                return@webSocket
            }
            val socketSession = this
            val requestedCodec = UsbVideoCodec.fromWireValue(call.request.queryParameters["codec"])
            val sessionTransport =
                when (requestedCodec) {
                    UsbVideoCodec.H264 -> MirrorTransport.USB_H264
                    UsbVideoCodec.JPEG -> MirrorTransport.USB_JPEG
                }
            val sessionId = beginViewerSession(sessionTransport)
            val videoConfigSent = CompletableDeferred<Unit>()
            if (sessionTransport == MirrorTransport.USB_JPEG) {
                videoConfigSent.complete(Unit)
            }
            val frameChannel =
                Channel<ByteArray>(
                    capacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            val frameSenderJob = launch {
                for (frameBytes in frameChannel) {
                    val active = isActiveSession(sessionId, sessionTransport)
                    if (!active) break
                    videoConfigSent.await()
                    try {
                        send(Frame.Binary(fin = true, data = frameBytes))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        CrashDiagnostics.recordCaughtException(
                            service.filesDir,
                            "USB frame send $sessionId",
                            e,
                        )
                        Log.e("KtorServer", "USB frame send error: ${e.message}", e)
                        break
                    }
                }
            }
            CrashDiagnostics.recordEvent(service.filesDir, "USB session connected: sessionId=$sessionId.")
            Log.d("KtorServer", "USB session connected: sessionId=$sessionId.")

            try {
                val hasCachedGrant = withContext(Dispatchers.Main) {
                    screenCaptureManager.mediaProjectionResultCode != null && screenCaptureManager.mediaProjectionResultData != null
                }
                if (!hasCachedGrant) {
                    val waitingStatus = withContext(Dispatchers.Main) {
                        buildUsbStatusMessage("WAITING_FOR_SCREEN_CAPTURE", captureReady = false)
                    }
                    send(Frame.Text(waitingStatus))
                    withContext(Dispatchers.Main) {
                        requestScreenCapturePermissionFromActivity("USB session requested MediaProjection grant")
                    }
                }

                // Clear any stale messages in the channel
                while (permissionGrantChannel.tryReceive().isSuccess) { /* clear */ }

                while (true) {
                    val isActive = isActiveSession(sessionId, sessionTransport)
                    if (!isActive) break

                    val hasGrant = withContext(Dispatchers.Main) {
                        screenCaptureManager.mediaProjectionResultCode != null && screenCaptureManager.mediaProjectionResultData != null
                    }
                    if (hasGrant) break

                    withTimeoutOrNull(MediaProjectionService.MEDIA_PROJECTION_GRANT_POLL_MS) {
                        permissionGrantChannel.receive()
                    }
                }

                val grant = withContext(Dispatchers.Main) {
                    if (isActiveSession(sessionId, sessionTransport)) {
                        screenCaptureManager.consumeMediaProjectionGrant()
                    } else {
                        null
                    }
                }
                if (grant == null) {
                    val reauthStatus = withContext(Dispatchers.Main) {
                        buildUsbStatusMessage("SCREEN_CAPTURE_REAUTH_REQUIRED", captureReady = false)
                    }
                    send(Frame.Text(reauthStatus))
                    return@webSocket
                }

                val (resultCode, resultData) = grant
                val startingStatus = withContext(Dispatchers.Main) {
                    screenCaptureManager.lastUsbCodec = requestedCodec
                    if (requestedCodec == UsbVideoCodec.H264) {
                        screenCaptureManager.lastUsbH264Profile = screenCaptureManager.resolveCurrentUsbH264Profile()
                    } else {
                        screenCaptureManager.lastUsbProfile = screenCaptureManager.resolveCurrentUsbProfile()
                    }
                    screenCaptureManager.usbPerfMonitor.reset()
                    buildUsbStatusMessage("USB_STREAM_STARTING", captureReady = true)
                }
                send(Frame.Text(startingStatus))
                var h264ConfigJson: String? = null
                try {
                    withContext(Dispatchers.Main) {
                        if (requestedCodec == UsbVideoCodec.H264) {
                            val h264Streamer = screenCaptureManager.prepareUsbH264ScreenStreamerForSession(sessionId)
                            h264Streamer.start(
                                resultCode = resultCode,
                                resultData = resultData,
                                profileProvider = screenCaptureManager::resolveCurrentUsbH264Profile,
                                perfMonitor = screenCaptureManager.usbPerfMonitor,
                                onVideoConfig = { configJson ->
                                    h264ConfigJson = configJson
                                },
                                onChunk = { frameBytes ->
                                    frameChannel.trySend(frameBytes)
                                },
                            )
                        } else {
                            screenCaptureManager.prepareUsbScreenStreamerForSession(sessionId)
                            screenCaptureManager.usbScreenStreamer.start(
                                resultCode = resultCode,
                                resultData = resultData,
                                profileProvider = screenCaptureManager::resolveCurrentUsbProfile,
                                perfMonitor = screenCaptureManager.usbPerfMonitor,
                            ) { frameBytes ->
                                frameChannel.trySend(frameBytes)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (requestedCodec == UsbVideoCodec.H264) {
                        CrashDiagnostics.recordCaughtException(service.filesDir, "USB H.264 start $sessionId", e)
                        Log.e("KtorServer", "USB H.264 start failed: ${e.message}", e)
                        val fallbackStatus = withContext(Dispatchers.Main) {
                            screenCaptureManager.lastUsbCodec = UsbVideoCodec.JPEG
                            buildUsbStatusMessage("H264_START_FAILED", captureReady = false)
                        }
                        send(Frame.Text(fallbackStatus))
                        return@webSocket
                    }
                    throw e
                }
                if (requestedCodec == UsbVideoCodec.H264) {
                    val configJson = h264ConfigJson
                    if (configJson != null) {
                        send(Frame.Text(configJson))
                    } else {
                        val fallbackStatus = withContext(Dispatchers.Main) {
                            screenCaptureManager.lastUsbCodec = UsbVideoCodec.JPEG
                            buildUsbStatusMessage("H264_START_FAILED", captureReady = false)
                        }
                        send(Frame.Text(fallbackStatus))
                        return@webSocket
                    }
                    videoConfigSent.complete(Unit)
                }
                val streamingStatus = withContext(Dispatchers.Main) {
                    buildUsbStatusMessage("USB_STREAMING", captureReady = true)
                }
                send(Frame.Text(streamingStatus))

                for (frame in incoming) {
                    val active = isActiveSession(sessionId, sessionTransport)
                    if (!active) break
                    if (frame is Frame.Text) {
                        controlEventDispatcher.dispatch(frame.readText()) { result ->
                            socketSession.launch {
                                try {
                                    if (isActiveSession(sessionId, sessionTransport)) {
                                        socketSession.send(Frame.Text(result.toAckJson()))
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    CrashDiagnostics.recordCaughtException(
                                        service.filesDir,
                                        "USB control ack send $sessionId",
                                        e,
                                    )
                                    Log.e("KtorServer", "USB control ACK send error: ${e.message}", e)
                                }
                            }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                CrashDiagnostics.recordEvent(service.filesDir, "USB session connection closed by peer: sessionId=$sessionId.")
            } catch (e: Throwable) {
                CrashDiagnostics.recordCaughtException(service.filesDir, "USB session $sessionId", e)
                Log.e("KtorServer", "USB session error: ${e.message}", e)
            } finally {
                frameChannel.close()
                withContext(NonCancellable) {
                    frameSenderJob.cancelAndJoin()
                    withContext(Dispatchers.Main) {
                        when (sessionTransport) {
                            MirrorTransport.USB_JPEG ->
                                if (
                                    isActiveSession(sessionId, MirrorTransport.USB_JPEG) &&
                                    screenCaptureManager.isUsbScreenStreamerInitialized()
                                ) {
                                    screenCaptureManager.usbScreenStreamer.stop()
                                }
                            MirrorTransport.USB_H264 ->
                                if (isActiveSession(sessionId, MirrorTransport.USB_H264)) {
                                    screenCaptureManager.usbH264ScreenStreamer?.stop()
                                }
                            MirrorTransport.TAILSCALE_WEBRTC -> Unit
                        }
                    }
                    screenCaptureManager.clearActiveUsbProjectionSession(sessionId)
                    endViewerSession(sessionId)
                    CrashDiagnostics.recordEvent(service.filesDir, "USB session ended: sessionId=$sessionId.")
                }
            }
        }
    }
}
