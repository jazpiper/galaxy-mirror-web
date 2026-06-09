package com.example.galaxymirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.http.content.staticResources
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class MediaProjectionService : Service() {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val controlEventDispatcher =
        ControlEventDispatcher(
            serviceProvider = { GalaxyMirrorAccessibilityService.instance },
            onViewerActivity = { markViewerActivity() },
        )

    companion object {
        private const val CHANNEL_ID = "GalaxyMirrorCaptureChannel"
        private const val NOTIFICATION_ID = 2026
        private const val TAG = "MediaProjectionService"
        const val EXTRA_KEEP_SCREEN_AWAKE = "keepScreenAwake"
        const val RESULT_CODE_MISSING = Int.MIN_VALUE

        var isRunning = false
            private set
        var instance: MediaProjectionService? = null
            private set

        fun isValidStartData(resultCode: Int, hasResultData: Boolean): Boolean {
            return resultCode == Activity.RESULT_OK && hasResultData
        }

        private const val VIEWER_TOKEN_QUERY = "token"
        private const val VIEWER_TOKEN_HEADER = "X-Android-Mirror-Token"
        private const val IDLE_QUALITY_DELAY_MS = 6_000L
    }

    // Binder interface for UI communication
    interface StateListener {
        fun onStateChanged()
        fun onScreenCapturePermissionRequired() {}
    }
    private val listeners = mutableListOf<StateListener>()

    var screenCapturePermissionRequired = false
        private set

    // Service Managed States
    var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
        private set
    lateinit var favoriteAppsRepository: FavoriteAppsRepository
        private set
    lateinit var viewerAccessTokenStore: ViewerAccessTokenStore
        private set
    var viewerAccessToken = ""
        private set
    lateinit var screenAwakeSettingsStore: ScreenAwakeSettingsStore
        private set
    var screenAwakeSettings = ScreenAwakeSettings()
        private set
    lateinit var screenBrightnessController: ScreenBrightnessController
        private set
    lateinit var streamQualitySettingsStore: StreamQualitySettingsStore
        private set
    lateinit var networkTransportDetector: NetworkTransportDetector
        private set
    private lateinit var usbScreenStreamer: UsbScreenStreamer

    var streamQualityMode = StreamQualityMode.AUTO
        private set
    var streamQualityNetwork = StreamNetworkTransport.OTHER
        private set
    var streamQualityProfile = StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER)
        private set
    var viewerActivityState = ViewerActivityState.ACTIVE
        private set
    var mirrorSessionState = MirrorSessionState()
        private set
    var activeSessionId = 0
        private set

    private var keepScreenAwake = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var idleQualityJob: Job? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // WebRTC and Capturer cache (Cached for Android 14+ token preservation)
    @Volatile private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile var peerConnection: PeerConnection? = null
        private set
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var videoSender: RtpSender? = null
    @Volatile private var surfaceTextureHelper: SurfaceTextureHelper? = null
    @Volatile private var videoCapturer: VideoCapturer? = null
    @Volatile var controlChannel: DataChannel? = null
        private set
    @Volatile private var eglBase: EglBase? = null
    private val sessionCounter = AtomicInteger(0)
    private val sessionLock = Any()
    @Volatile private var remoteDescriptionSet = false
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    var mediaProjectionResultCode: Int? = null
        private set
    var mediaProjectionResultData: Intent? = null
        private set
    private var pendingOffer: PendingOffer? = null
    private var videoCapturerLastWidth = 0
    private var videoCapturerLastHeight = 0

    private data class PendingOffer(
        val sessionId: Int,
        val remoteSdp: SessionDescription,
        val sendResponse: (String) -> Unit
    )

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashDiagnostics.recordEvent(this, "MediaProjectionService.onCreate")
        createNotificationChannel()

        // Initialize repositories and state stores
        favoriteAppsRepository = FavoriteAppsRepository(applicationContext)
        viewerAccessTokenStore = ViewerAccessTokenStore(applicationContext)
        viewerAccessToken = viewerAccessTokenStore.getOrCreateToken()
        screenAwakeSettingsStore = ScreenAwakeSettingsStore(ScreenAwakeSettingsStore.SharedPreferencesStore(applicationContext))
        screenAwakeSettings = screenAwakeSettingsStore.read()
        screenBrightnessController = ScreenBrightnessController(applicationContext)
        streamQualitySettingsStore = StreamQualitySettingsStore(StreamQualitySettingsStore.SharedPreferencesStore(applicationContext))
        networkTransportDetector = NetworkTransportDetector(applicationContext)
        usbScreenStreamer =
            UsbScreenStreamer(applicationContext) {
                handleUsbProjectionStopped()
            }
        streamQualityMode = streamQualitySettingsStore.readMode()

        refreshStreamQualityState()

        // 1. Start Ktor Embedded Server
        startKtorServer()

        // 2. Register Network Change listener
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                mainHandler.post {
                    val newNetwork = currentStreamNetworkTransport()
                    if (newNetwork != streamQualityNetwork) {
                        streamQualityNetwork = newNetwork
                        val newProfile = AdaptiveStreamQuality.resolve(streamQualityMode, newNetwork, viewerActivityState)
                        streamQualityProfile = newProfile
                        applyStreamQualityProfile(newProfile, reason = "Network handoff callback")
                        notifyStateChanged()
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashDiagnostics.recordEvent(this, "MediaProjectionService.onStartCommand startId=$startId.")
        Log.d(TAG, "onStartCommand called.")

        val resultCode = intent?.getIntExtra("resultCode", RESULT_CODE_MISSING) ?: RESULT_CODE_MISSING
        keepScreenAwake = intent?.getBooleanExtra(EXTRA_KEEP_SCREEN_AWAKE, false) ?: false
        val resultData =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("resultData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("resultData")
            }

        if (isValidStartData(resultCode, resultData != null)) {
            // Enter foreground state since we are starting projection capture
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
            mediaProjectionResultCode = resultCode
            mediaProjectionResultData = resultData
            screenCapturePermissionRequired = false
            isRunning = true
            updateWakeLock()
            CrashDiagnostics.recordEvent(this, "MediaProjection foreground service is ready.")

            // Resume any waiting Offer
            mainHandler.post {
                resumePendingOfferIfReady()
                notifyStateChanged()
            }
        } else {
            // Started without screen capture intent data (just run Ktor server)
            Log.d(TAG, "MediaProjectionService started in background mode for hosting.")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        releaseWakeLock()

        // Stop Ktor server in a non-activity lifecycle scope
        val serverToStop = server
        if (serverToStop != null) {
            // CIO engine stop is non-blocking, safe to call on main thread
            try {
                serverToStop.stop(1000, 2000)
                Log.d(TAG, "Ktor Server stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Ktor Server in onDestroy: ${e.message}", e)
            }
        }

        networkCallback?.let { callback ->
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }

        serviceScope.cancel()
        idleQualityJob?.cancel()

        if (instance === this) {
            instance = null
        }

        // Cleanup WebRTC (explicit close)
        if (::usbScreenStreamer.isInitialized) {
            usbScreenStreamer.stop()
        }
        cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)

        try {
            screenBrightnessController.applyForMirroring(
                settings = screenAwakeSettingsStore.read(),
                isMirroringActive = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring brightness in onDestroy: ${e.message}", e)
        }

        listeners.clear()
        CrashDiagnostics.recordEvent(this, "MediaProjectionService.onDestroy")
        Log.d(TAG, "MediaProjectionService stopped.")
    }

    // ─── Binder State Listeners ──────────────────────────────────────────────────

    fun registerListener(listener: StateListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        listener.onStateChanged()
    }

    fun unregisterListener(listener: StateListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun notifyStateChanged() {
        val targets = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            targets.forEach { it.onStateChanged() }
        }
    }

    private fun notifyScreenCapturePermissionRequired() {
        val targets = synchronized(listeners) { listeners.toList() }
        mainHandler.post {
            targets.forEach { it.onScreenCapturePermissionRequired() }
        }
    }

    private fun requestScreenCapturePermissionFromActivity(reason: String) {
        screenCapturePermissionRequired = true
        CrashDiagnostics.recordEvent(this, "Screen capture permission request required: $reason.")
        notifyScreenCapturePermissionRequired()
        notifyStateChanged()
    }

    // ─── Public Control API for MainActivity ─────────────────────────────────────

    fun updateScreenAwakeSettings(settings: ScreenAwakeSettings) {
        screenAwakeSettings = settings
        screenAwakeSettingsStore.write(settings)
        setKeepScreenAwake(settings.shouldKeepScreenAwake(isMirroringActive()))
        applyBrightnessMinimizationForCurrentState()
        notifyStateChanged()
    }

    fun updateStreamQualityMode(mode: StreamQualityMode) {
        streamQualitySettingsStore.writeMode(mode)
        streamQualityMode = mode
        val profile = refreshStreamQualityState()
        applyStreamQualityProfile(profile, reason = "settings")
        notifyStateChanged()
    }

    fun disconnectMirror() {
        CrashDiagnostics.recordEvent(this, "Manual mirror disconnect requested.")
        synchronized(sessionLock) {
            activeSessionId = 0
            mirrorSessionState = MirrorSessionState()
            pendingOffer = null
        }
        // Do not stop service entirely, keep Ktor running. Just stop capturing and WebRTC session
        stopForeground(true)
        if (::usbScreenStreamer.isInitialized) {
            usbScreenStreamer.stop()
        }
        cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        screenCapturePermissionRequired = false
        isRunning = false
        updateWakeLock()
        applyBrightnessMinimizationForCurrentState()
        notifyStateChanged()
    }

    private fun stopProjectionCaptureForPolicy(reason: CleanupReason) {
        if (!CleanupPolicy.shouldStopProjection(reason)) return
        CrashDiagnostics.recordEvent(this, "Stopping projection capture for cleanup reason: $reason.")
        stopForeground(true)
        cleanupWebRTCResources(stopProjectionService = true, stopCapturer = true)
        isRunning = false
        screenCapturePermissionRequired = false
        updateWakeLock()
        applyBrightnessMinimizationForCurrentState()
    }

    fun isMirroringActive(): Boolean {
        return activeSessionId != 0 && isRunning
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        keepScreenAwake = enabled
        updateWakeLock()
    }

    // ─── Internal Helper Methods ───────────────────────────────────────────────

    private fun consumeMediaProjectionGrant(): Pair<Int, Intent>? {
        val resultCode = mediaProjectionResultCode
        val resultData = mediaProjectionResultData
        if (resultCode == null || resultData == null) return null
        mediaProjectionResultCode = null
        mediaProjectionResultData = null
        return resultCode to resultData
    }

    private fun refreshStreamQualityState(): StreamQualityProfile {
        val network = currentStreamNetworkTransport()
        val profile = AdaptiveStreamQuality.resolve(streamQualityMode, network, viewerActivityState)
        streamQualityNetwork = network
        streamQualityProfile = profile
        return profile
    }

    private fun currentStreamNetworkTransport(): StreamNetworkTransport =
        if (::networkTransportDetector.isInitialized) {
            networkTransportDetector.currentTransport()
        } else {
            StreamNetworkTransport.OTHER
        }

    private fun buildStreamQualityStatusJson(): org.json.JSONObject {
        val network = currentStreamNetworkTransport()
        val profile = AdaptiveStreamQuality.resolve(streamQualityMode, network, viewerActivityState)
        return org.json.JSONObject(
            StreamQualityCodec.toStatusJson(
                selectedMode = streamQualityMode,
                networkTransport = network,
                profile = profile,
                activityState = viewerActivityState,
            )
        )
    }

    private fun applyBrightnessMinimizationForCurrentState() {
        try {
            screenBrightnessController.applyForMirroring(
                settings = screenAwakeSettings,
                isMirroringActive = isMirroringActive(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying brightness minimization", e)
        }
    }

    private fun handleUsbProjectionStopped() {
        mainHandler.post {
            mediaProjectionResultCode = null
            mediaProjectionResultData = null
            screenCapturePermissionRequired = true
            isRunning = false
            updateWakeLock()
            applyBrightnessMinimizationForCurrentState()
            notifyStateChanged()
        }
    }

    private fun markViewerActivity() {
        mainHandler.post {
            val shouldRestoreActiveQuality = viewerActivityState != ViewerActivityState.ACTIVE
            viewerActivityState = ViewerActivityState.ACTIVE
            val activeProfile = refreshStreamQualityState()
            if (shouldRestoreActiveQuality) {
                applyStreamQualityProfile(activeProfile, reason = "viewer activity")
            }
            idleQualityJob?.cancel()
            idleQualityJob = serviceScope.launch {
                delay(IDLE_QUALITY_DELAY_MS)
                viewerActivityState = ViewerActivityState.IDLE
                val idleProfile = refreshStreamQualityState()
                applyStreamQualityProfile(idleProfile, reason = "viewer idle")
                notifyStateChanged()
                CrashDiagnostics.recordEvent(
                    this@MediaProjectionService,
                    "Viewer idle stream quality applied: ${idleProfile.width}x${idleProfile.height}@${idleProfile.fps}, bitrate=${idleProfile.maxBitrateBps}.",
                )
            }
            notifyStateChanged()
        }
    }

    private fun applyStreamQualityProfile(profile: StreamQualityProfile, reason: String) {
        try {
            val isResolutionChange = videoCapturerLastWidth != profile.width || videoCapturerLastHeight != profile.height
            if (isResolutionChange) {
                videoCapturer?.changeCaptureFormat(profile.width, profile.height, profile.fps)
                videoCapturerLastWidth = profile.width
                videoCapturerLastHeight = profile.height
            }
            val sender = videoSender
            if (sender != null) {
                val parameters = sender.parameters
                val encoding = parameters.encodings.firstOrNull()
                if (encoding != null) {
                    encoding.maxBitrateBps = profile.maxBitrateBps
                    encoding.maxFramerate = profile.fps
                    encoding.scaleResolutionDownBy = 1.0
                    parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
                    val applied = sender.setParameters(parameters)
                    CrashDiagnostics.recordEvent(
                        this,
                        "Stream quality applied ($reason): ${profile.width}x${profile.height}@${profile.fps}, bitrate=${profile.maxBitrateBps}, senderApplied=$applied, resolutionChanged=$isResolutionChange.",
                    )
                } else {
                    CrashDiagnostics.recordEvent(this, "Stream quality bitrate skipped ($reason): sender has no encodings.")
                }
            } else {
                CrashDiagnostics.recordEvent(
                    this,
                    "Stream quality capture format applied ($reason) before RTP sender exists: ${profile.width}x${profile.height}@${profile.fps}, resolutionChanged=$isResolutionChange.",
                )
            }
        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(filesDir, "apply stream quality", e)
        }
    }

    private fun resumePendingOfferIfReady() {
        val offer = synchronized(sessionLock) { pendingOffer } ?: return
        if (!isRunning) {
            CrashDiagnostics.recordEvent(this, "Pending offer not resumed because capture is not ready yet.")
            return
        }
        if (!isActiveSession(offer.sessionId)) {
            CrashDiagnostics.recordEvent(this, "Dropping pending offer for inactive sessionId=${offer.sessionId}.")
            synchronized(sessionLock) {
                pendingOffer = null
                mirrorSessionState = mirrorSessionState.clearPendingOffer()
            }
            return
        }

        synchronized(sessionLock) {
            pendingOffer = null
            mirrorSessionState = mirrorSessionState.clearPendingOffer()
        }
        CrashDiagnostics.recordEvent(this, "Resuming pending offer for sessionId=${offer.sessionId}.")
        offer.sendResponse(buildStatusMessage(captureReady = true, message = "SCREEN_CAPTURE_READY"))
        initializeWebRTC(offer.sessionId, offer.remoteSdp, offer.sendResponse)
    }

    private fun buildStatusMessage(
        captureReady: Boolean = isRunning,
        accessibilityReady: Boolean = GalaxyMirrorAccessibilityService.isReadyForRemoteInput(),
        message: String
    ): String {
        return org.json.JSONObject().apply {
            put("type", "STATUS")
            put("payload", org.json.JSONObject().apply {
                put("captureReady", captureReady)
                put("accessibilityReady", accessibilityReady)
                put("keepScreenAwake", screenAwakeSettings.keepScreenAwakeDuringMirroring)
                put("brightnessMinimizeEnabled", screenAwakeSettings.minimizeBrightnessDuringMirroring)
                put("brightnessWriteSettingsReady", screenBrightnessController.canWriteSystemSettings())
                put("streamQuality", buildStreamQualityStatusJson())
                put("message", message)
            })
        }.toString()
    }

    private fun isViewerAuthorized(call: ApplicationCall): Boolean =
        ViewerAccessGuard(viewerAccessToken).isAllowed(
            queryToken = call.request.queryParameters[VIEWER_TOKEN_QUERY],
            headerToken = call.request.headers[VIEWER_TOKEN_HEADER],
        )

    private suspend fun requireViewerAuthorization(call: ApplicationCall): Boolean {
        if (isViewerAuthorized(call)) return true
        call.respondText(
            """{"ok":false,"error":"UNAUTHORIZED_VIEWER"}""",
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
        return false
    }

    private fun startKtorServer() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d("KtorServer", "Starting Ktor Server on 0.0.0.0:8080...")
                server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
                    install(WebSockets)
                    routing {
                        staticResources("/", "files")

                        get("/status") {
                            call.respondText("Android Mirror Web Server is active. Port: 8080")
                        }

                        get("/debug/crash") {
                            if (!requireViewerAuthorization(call)) return@get
                            call.respondText(
                                CrashDiagnostics.readDebugReport(this@MediaProjectionService.filesDir),
                                ContentType.Text.Plain
                            )
                        }

                        get("/debug/crash/clear") {
                            if (!requireViewerAuthorization(call)) return@get
                            CrashDiagnostics.clearCrash(this@MediaProjectionService.filesDir)
                            call.respondText(
                                "Cleared saved crash and caught exception. Recent events were kept.\n",
                                ContentType.Text.Plain
                            )
                        }

                        get("/apps/favorites") {
                            if (!requireViewerAuthorization(call)) return@get
                            call.respondText(
                                favoriteAppsRepository.getFavoritesResponseJson(),
                                ContentType.Application.Json
                            )
                        }

                        get("/stream/quality") {
                            if (!requireViewerAuthorization(call)) return@get
                            val statusJson = withContext(Dispatchers.Main) {
                                buildStreamQualityStatusJson().toString()
                            }
                            call.respondText(statusJson, ContentType.Application.Json)
                        }

                        post("/stream/quality") {
                            if (!requireViewerAuthorization(call)) return@post
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
                                buildStreamQualityStatusJson().toString()
                            }
                            call.respondText(statusJson, ContentType.Application.Json)
                        }

                        post("/apps/launch") {
                            if (!requireViewerAuthorization(call)) return@post
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
                                CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "Favorite app launched: $packageName.")
                                call.respondText("""{"ok":true}""", ContentType.Application.Json)
                            } else {
                                CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "Favorite app launch failed: $packageName.")
                                call.respondText(
                                    """{"ok":false,"error":"APP_NOT_FOUND"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.NotFound
                                )
                            }
                        }

                        webSocket("/signaling") {
                            if (!isViewerAuthorized(call)) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHORIZED_VIEWER"))
                                return@webSocket
                            }
                            val sessionId = beginViewerSession()
                            CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "Signaling WebSocket connected: sessionId=$sessionId.")
                            Log.d("KtorServer", "New WebRTC signaling WebSocket connection established: $sessionId")

                            val statusJob = launch {
                                while (isActiveSession(sessionId)) {
                                    delay(2_000)
                                    try {
                                        val statusMsg = withContext(Dispatchers.Main) {
                                            buildStatusMessage(message = "STATUS_TICK")
                                        }
                                        send(Frame.Text(statusMsg))
                                    } catch (e: Throwable) {
                                        CrashDiagnostics.recordCaughtException(this@MediaProjectionService.filesDir, "signaling status tick", e)
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
                                            if (isActiveSession(sessionId)) {
                                                launch { send(Frame.Text(response)) }
                                            }
                                        }
                                    }
                                }
                            } catch (e: ClosedReceiveChannelException) {
                                CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "Signaling connection closed by peer: sessionId=$sessionId.")
                            } catch (e: Throwable) {
                                CrashDiagnostics.recordCaughtException(this@MediaProjectionService.filesDir, "signaling session $sessionId", e)
                                Log.e("KtorServer", "Error in signaling session $sessionId: ${e.message}", e)
                            } finally {
                                statusJob.cancel()
                                endViewerSession(sessionId)
                            }
                        }
                    }
                }.start(wait = false)
                CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "Ktor server started on 0.0.0.0:8080.")
                Log.d("KtorServer", "Ktor Server successfully started.")
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(this@MediaProjectionService.filesDir, "Ktor server startup", e)
                Log.e("KtorServer", "Error starting Ktor Server: ${e.message}", e)
            }
        }
    }

    private suspend fun beginViewerSession(): Int {
        val sessionId = sessionCounter.incrementAndGet()
        withContext(Dispatchers.Main) {
            val replacingSessionId = synchronized(sessionLock) {
                val previous = mirrorSessionState.activeSessionId
                pendingOffer = null
                mirrorSessionState = mirrorSessionState.beginSession(sessionId)
                activeSessionId = sessionId
                previous
            }
            if (replacingSessionId != 0) {
                CrashDiagnostics.recordEvent(this@MediaProjectionService, "Replacing active viewer session: $replacingSessionId -> $sessionId.")
                Log.w("WebRTC", "Replacing active viewer session: $replacingSessionId -> $sessionId")
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_REPLACED)
            }
            applyBrightnessMinimizationForCurrentState()
            notifyStateChanged()
        }
        return sessionId
    }

    private suspend fun endViewerSession(sessionId: Int) {
        withContext(Dispatchers.Main) {
            val shouldStopProjection = synchronized(sessionLock) {
                if (isActiveSession(sessionId)) {
                    CrashDiagnostics.recordEvent(this@MediaProjectionService, "Ending viewer session: $sessionId.")
                    mirrorSessionState = mirrorSessionState.endSession(sessionId)
                    activeSessionId = mirrorSessionState.activeSessionId
                    pendingOffer = null
                    true
                } else {
                    false
                }
            }

            if (shouldStopProjection) {
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_SOCKET_CLOSED)
                notifyStateChanged()
            }
        }
    }

    private fun isActiveSession(sessionId: Int): Boolean = mirrorSessionState.isActive(sessionId)

    private fun queuePendingOffer(
        sessionId: Int,
        remoteSdp: SessionDescription,
        sendResponse: (String) -> Unit
    ) {
        if (!isActiveSession(sessionId)) {
            CrashDiagnostics.recordEvent(this, "Not queueing offer for inactive sessionId=$sessionId.")
            return
        }
        synchronized(sessionLock) {
            pendingOffer = PendingOffer(sessionId, remoteSdp, sendResponse)
            mirrorSessionState = mirrorSessionState.queueOffer(sessionId)
        }
        CrashDiagnostics.recordEvent(this, "Queued offer until capture is ready for sessionId=$sessionId.")
    }

    private fun addRemoteIceCandidate(candidate: IceCandidate) {
        synchronized(pendingRemoteIceCandidates) {
            if (!remoteDescriptionSet || peerConnection == null) {
                pendingRemoteIceCandidates.add(candidate)
                Log.d("WebRTC", "Queued remote ICE candidate until remote description is set.")
                return
            }
        }
        peerConnection?.addIceCandidate(candidate)
    }

    private fun flushPendingRemoteIceCandidates() {
        val candidates = synchronized(pendingRemoteIceCandidates) {
            pendingRemoteIceCandidates.toList().also { pendingRemoteIceCandidates.clear() }
        }
        candidates.forEach { peerConnection?.addIceCandidate(it) }
        if (candidates.isNotEmpty()) {
            Log.d("WebRTC", "Flushed ${candidates.size} queued remote ICE candidates.")
        }
    }

    private fun handleSignalingMessage(sessionId: Int, message: String, sendResponse: (String) -> Unit) {
        mainHandler.post {
            if (!isActiveSession(sessionId)) {
                Log.w("WebRTC", "Ignoring signaling message for inactive session: $sessionId")
                return@post
            }

            try {
                val json = org.json.JSONObject(message)
                val type = json.getString("type")

                when (type) {
                    "OFFER" -> {
                        CrashDiagnostics.recordEvent(this, "Offer received for sessionId=$sessionId.")
                        Log.d("WebRTC", "Offer received. Creating Answer...")
                        val sdpObj = json.getJSONObject("payload")
                        val sdpDescription = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
                            sdpObj.getString("sdp")
                        )

                        val readiness = ProjectionReadiness.from(
                            hasProjectionIntent = mediaProjectionResultData != null,
                            isServiceRunning = isRunning
                        )
                        val decision = SignalingDecision.onOffer(readiness, isActiveSession(sessionId))
                        CrashDiagnostics.recordEvent(this, "Signaling decision on OFFER: $decision.")
                        when (decision) {
                            SignalingDecision.START_NEGOTIATION -> initializeWebRTC(sessionId, sdpDescription, sendResponse)
                            SignalingDecision.QUEUE_AND_REQUEST_PERMISSION -> {
                                queuePendingOffer(sessionId, sdpDescription, sendResponse)
                                requestScreenCapturePermissionFromActivity("Offer received without active MediaProjection grant")
                                sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                            }
                            SignalingDecision.QUEUE_AND_SEND_STATUS -> {
                                queuePendingOffer(sessionId, sdpDescription, sendResponse)
                                sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                                resumePendingOfferIfReady()
                            }
                            SignalingDecision.IGNORE_INACTIVE -> {
                                CrashDiagnostics.recordEvent(this, "Ignoring offer for inactive sessionId=$sessionId.")
                            }
                        }
                    }
                    "ICE_CANDIDATE" -> {
                        CrashDiagnostics.recordEvent(this, "ICE candidate received for sessionId=$sessionId.")
                        val candidateObj = json.getJSONObject("payload")
                        val candidate = IceCandidate(
                            candidateObj.getString("sdpMid"),
                            candidateObj.getInt("sdpMLineIndex"),
                            candidateObj.getString("candidate")
                        )
                        addRemoteIceCandidate(candidate)
                    }
                }
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(this.filesDir, "signaling JSON parse", e)
                Log.e("WebRTC", "Error parsing signaling JSON: ${e.message}", e)
            }
        }
    }

    private fun preferH264Codec(sdpDescription: String): String {
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

    private fun initializeWebRTC(sessionId: Int, remoteSdp: SessionDescription, sendResponse: (String) -> Unit) {
        val readiness = ProjectionReadiness.from(
            hasProjectionIntent = mediaProjectionResultData != null,
            isServiceRunning = isRunning
        )
        if (readiness != ProjectionReadiness.READY) {
            CrashDiagnostics.recordEvent(this, "Capture not ready; deferring offer for sessionId=$sessionId.")
            queuePendingOffer(sessionId, remoteSdp, sendResponse)
            if (readiness == ProjectionReadiness.MISSING_PERMISSION) {
                requestScreenCapturePermissionFromActivity("Negotiation attempted without active MediaProjection grant")
            }
            sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
            return
        }

        try {
            CrashDiagnostics.recordEvent(this, "Initializing WebRTC for sessionId=$sessionId.")

            // 1. Initialize PeerConnectionFactory if needed
            if (peerConnectionFactory == null) {
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
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

            val eglContext = eglBase!!.eglBaseContext
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            remoteDescriptionSet = false

            // 2. Create PeerConnection
            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        if (!isActiveSession(sessionId)) return
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
                        if (!isActiveSession(sessionId)) {
                            dc.close()
                            return
                        }
                        if (!ControlEventValidator.isControlChannel(dc.label())) {
                            Log.w("WebRTC", "Rejected DataChannel: ${dc.label()}")
                            dc.close()
                            return
                        }
                        controlChannel = dc
                        sendResponse(buildStatusMessage(message = "CONTROL_CHANNEL_ACCEPTED"))
                        Log.d("WebRTC", "DataChannel received: ${dc.label()}")
                        dc.registerObserver(object : DataChannel.Observer {
                            override fun onBufferedAmountChange(previousAmount: Long) {}
                            override fun onStateChange() {
                                Log.d("WebRTC", "DataChannel state: ${dc.state()}")
                            }
                            override fun onMessage(buffer: DataChannel.Buffer) {
                                try {
                                    if (!isActiveSession(sessionId)) return
                                    val bytes = ByteArray(buffer.data.remaining())
                                    buffer.data.get(bytes)
                                    val text = String(bytes, Charsets.UTF_8)
                                    Log.d("WebRTC", "DataChannel message: $text")
                                    controlEventDispatcher.dispatch(text) { result ->
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
            })

            // 3. Setup Screen Capture pipeline (Reuses capture elements if active)
            if (videoCapturer == null) {
                val projectionIntent = mediaProjectionResultData ?: return
                videoSource = peerConnectionFactory?.createVideoSource(true)
                videoCapturer = ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {
                    override fun onStop() {
                        CrashDiagnostics.recordEvent(this@MediaProjectionService.filesDir, "MediaProjection stopped inside service.")
                        handleScreenCaptureReauthorizationRequired(
                            sessionId = sessionId,
                            sendResponse = sendResponse,
                            diagnosticReason = "ScreenCapturerAndroid callback",
                            stopCapturer = true,
                        )
                    }
                })
                videoCapturer?.initialize(surfaceTextureHelper, applicationContext, videoSource?.capturerObserver)

                val streamNetwork = currentStreamNetworkTransport()
                val streamProfile = AdaptiveStreamQuality.resolve(streamQualityMode, streamNetwork, viewerActivityState)

                videoCapturerLastWidth = streamProfile.width
                videoCapturerLastHeight = streamProfile.height
                try {
                    videoCapturer?.startCapture(streamProfile.width, streamProfile.height, streamProfile.fps)
                } catch (e: Exception) {
                    CrashDiagnostics.recordCaughtException(filesDir, "ScreenCapturerAndroid.startCapture", e)
                    handleScreenCaptureReauthorizationRequired(
                        sessionId = sessionId,
                        sendResponse = sendResponse,
                        diagnosticReason = "ScreenCapturerAndroid.startCapture failure",
                        stopCapturer = true,
                    )
                    return
                }
                videoTrack = peerConnectionFactory?.createVideoTrack("video_track_id", videoSource)
            }

            val streamNetwork = currentStreamNetworkTransport()
            val streamProfile = AdaptiveStreamQuality.resolve(streamQualityMode, streamNetwork, viewerActivityState)
            streamQualityNetwork = streamNetwork
            streamQualityProfile = streamProfile

            // 4. Add video track to PeerConnection
            videoSender = peerConnection?.addTrack(videoTrack, listOf("video_stream_id"))
            applyStreamQualityProfile(streamProfile, reason = "WebRTC start")

            // 5. Apply H.264 optimization to Offer SDP (SDP Munging)
            val modifiedOfferSdp = preferH264Codec(remoteSdp.description)
            val modifiedRemoteSdp = SessionDescription(remoteSdp.type, modifiedOfferSdp)

            // 6. Set Remote Description
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingRemoteIceCandidates()

                    // Create local SDP Answer
                    peerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            desc?.let {
                                // Apply H.264 optimization to local Answer SDP (SDP Munging)
                                val modifiedAnswerSdp = preferH264Codec(it.description)
                                val modifiedLocalSdp = SessionDescription(it.type, modifiedAnswerSdp)

                                peerConnection?.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(desc: SessionDescription?) {}
                                    override fun onSetSuccess() {
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

        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(this.filesDir, "WebRTC initialization", e)
            Log.e("WebRTC", "Error during WebRTC initialization: ${e.message}", e)
        }
    }

    private fun handleScreenCaptureReauthorizationRequired(
        sessionId: Int,
        sendResponse: (String) -> Unit,
        diagnosticReason: String,
        stopCapturer: Boolean,
    ) {
        mainHandler.post {
            val shouldHandle = synchronized(sessionLock) {
                mirrorSessionState.isActive(sessionId)
            }

            if (!shouldHandle) {
                CrashDiagnostics.recordEvent(
                    this,
                    "Ignoring stale MediaProjection event for sessionId=$sessionId reason=$diagnosticReason.",
                )
                return@post
            }

            mediaProjectionResultCode = null
            mediaProjectionResultData = null
            screenCapturePermissionRequired = true
            sendResponse(
                buildStatusMessage(
                    captureReady = false,
                    message = "SCREEN_CAPTURE_REAUTH_REQUIRED",
                ),
            )
            synchronized(sessionLock) {
                pendingOffer = null
                mirrorSessionState = mirrorSessionState.projectionStopped(sessionId)
                activeSessionId = mirrorSessionState.activeSessionId
            }
            cleanupWebRTCResources(stopProjectionService = false, stopCapturer = stopCapturer)
            requestScreenCapturePermissionFromActivity(diagnosticReason)
            isRunning = false
            updateWakeLock()
            applyBrightnessMinimizationForCurrentState()
            notifyStateChanged()
        }
    }

    private fun sendControlAck(channel: DataChannel, result: ControlEventResult) {
        if (result.seq == null || channel.state() != DataChannel.State.OPEN) return
        try {
            channel.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap(result.toAckJson().toByteArray(Charsets.UTF_8)),
                    false,
                )
            )
        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(filesDir, "control ack send", e)
            Log.e("WebRTC", "Error sending control ACK: ${e.message}", e)
        }
    }

    private fun org.json.JSONObject.controlSeq(): Long? =
        if (has("seq")) {
            optLong("seq")
        } else {
            null
        }

    private fun cleanupWebRTCResources(stopProjectionService: Boolean, stopCapturer: Boolean) {
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
            CrashDiagnostics.recordCaughtException(filesDir, "WebRTC cleanup ${failure.name}", failure.throwable)
            Log.e(TAG, "Error during WebRTC cleanup step ${failure.name}", failure.throwable)
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
            mediaProjectionResultCode = null
            mediaProjectionResultData = null
        }

        Log.d("WebRTC", "WebRTC session clean up completed with ${failures.size} failures. stopCapturer=$stopCapturer")
    }

    @Suppress("DEPRECATION")
    private fun updateWakeLock() {
        val shouldHoldWakeLock =
            MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
                serviceRunning = isRunning,
                keepAwakeEnabled = keepScreenAwake,
            )
        if (shouldHoldWakeLock) {
            val currentWakeLock = wakeLock
            if (currentWakeLock?.isHeld == true) return
            val lockType = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            wakeLock =
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(lockType, "AndroidMirror:Projection")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            CrashDiagnostics.recordEvent(this, "MediaProjection screen bright wake lock acquired.")
            return
        }

        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                CrashDiagnostics.recordEvent(this, "MediaProjection partial wake lock released.")
            }
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Mirror Active")
            .setContentText("실시간 Mac 브라우저 미러링 및 제어 서비스 작동 중")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mirroring Capture Notification Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Android 미러링 화면 캡처 상태 알림 채널"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
