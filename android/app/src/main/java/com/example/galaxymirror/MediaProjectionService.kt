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
import androidx.core.app.NotificationCompat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.http.content.staticResources
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class MediaProjectionService : Service() {

    internal val binder = LocalBinder()
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val permissionGrantChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    internal val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    internal val controlEventDispatcher =
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

        private const val IDLE_QUALITY_DELAY_MS = 6_000L
        internal const val MEDIA_PROJECTION_GRANT_POLL_MS = 500L

        // Compiled once and reused; redactSensitiveInfo previously recompiled these on every call.
        internal val REDACT_IPV4 =
            Regex("(?<!\\d)(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?!\\d)")
        internal val REDACT_MAC = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
        internal val REDACT_PATH =
            Regex("(/data/user/\\d+/|/data/data/|/sdcard/|/storage/emulated/\\d+/)[\\w\\-./]+")
        internal val REDACT_STACK_FRAME = Regex("(?m)^\\s*at .*\\(.*\\)$")
    }

    // Service state structure
    internal val _serviceState = MutableStateFlow(MirrorServiceState())
    val serviceState: StateFlow<MirrorServiceState> = _serviceState.asStateFlow()

    var screenCapturePermissionRequired = false
        private set

    // Service Managed States
    var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
        private set
    lateinit var favoriteAppsRepository: FavoriteAppsRepository
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
    var streamQualityMode = StreamQualityMode.AUTO
        private set
    var streamQualityNetwork = StreamNetworkTransport.OTHER
        private set
    var streamQualityProfile = StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER)
        private set
                var viewerActivityState = ViewerActivityState.ACTIVE
        private set
    @Volatile var mirrorSessionState = MirrorSessionState()
        private set
    var activeSessionId = 0
        private set

    internal var keepScreenAwake = false
    internal var wakeLock: PowerManager.WakeLock? = null
    internal var idleQualityJob: Job? = null
    internal var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // WebRTC and Capturer cache
                                        internal val sessionCounter = AtomicInteger(0)
    internal val sessionLock = Any()
        
                    
    internal data class PendingOffer(
        val sessionId: Int,
        val remoteSdp: SessionDescription,
        val sendResponse: (String) -> Unit
    )

    
    val webRtcManager = InnerWebRtcManager()
    val screenCaptureManager = InnerScreenCaptureManager()

    inner class InnerWebRtcManager {
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
        if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Skipping WebRTC initialization for inactive sessionId=$sessionId.")
            return
        }

        val readiness = ProjectionReadiness.from(
            hasProjectionIntent = screenCaptureManager.mediaProjectionResultData != null,
            isServiceRunning = isRunning
        )
        if (readiness != ProjectionReadiness.READY) {
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Capture not ready; deferring offer for sessionId=$sessionId.")
            queuePendingOffer(sessionId, remoteSdp, sendResponse)
            if (readiness == ProjectionReadiness.MISSING_PERMISSION) {
                requestScreenCapturePermissionFromActivity("Negotiation attempted without active MediaProjection grant")
            }
            sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
            return
        }

        try {
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Initializing WebRTC for sessionId=$sessionId.")

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

            val streamNetwork = currentStreamNetworkTransport()
            val streamProfile = AdaptiveStreamQuality.resolve(streamQualityMode, streamNetwork, viewerActivityState)
            streamQualityNetwork = streamNetwork
            streamQualityProfile = streamProfile

            // 4. Add video track to PeerConnection
            videoSender = peerConnection?.addTrack(videoTrack, listOf("video_stream_id"))
            applyStreamQualityProfile(streamProfile, reason = "WebRTC start")

            // 5 & 6. Handle SDP Exchange
            handleSdpExchange(sessionId, remoteSdp, sendResponse)

        } catch (e: Exception) {
            CrashDiagnostics.recordCaughtException(this@MediaProjectionService.filesDir, "WebRTC initialization", e)
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
            screenCaptureManager.mediaProjectionResultCode = null
            screenCaptureManager.mediaProjectionResultData = null
        }

        Log.d("WebRTC", "WebRTC session clean up completed with ${failures.size} failures. stopCapturer=$stopCapturer")
    }

    }

    inner class InnerScreenCaptureManager {
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
        val profile =
            UsbThermalPolicy.resolve(
                selectedMode = streamQualityMode,
                thermalStatus = currentUsbThermalStatus(),
                viewerIdle = viewerActivityState == ViewerActivityState.IDLE,
            )
        lastUsbProfile = profile
        return profile
    }
internal fun resolveCurrentUsbH264Profile(): UsbH264StreamProfile {
        val selected = UsbH264StreamProfilePolicy.resolve(streamQualityMode)
        val thermalStatus = currentUsbThermalStatus()
        val tier =
            when {
                viewerActivityState == ViewerActivityState.IDLE -> UsbStreamProfileTier.COOL
                thermalStatus == UsbThermalStatus.LIGHT -> minOfUsbTier(selected.tier, UsbStreamProfileTier.BALANCED)
                thermalStatus == UsbThermalStatus.MODERATE -> UsbStreamProfileTier.COOL
                isUsbThermalSevereOrWorse(thermalStatus) -> UsbStreamProfileTier.COOL
                else -> selected.tier
            }
        val profile = UsbH264StreamProfilePolicy.resolveTier(tier)
        lastUsbH264Profile = profile
        return profile
    }
internal fun createUsbScreenStreamer(sessionId: Int): UsbScreenStreamer =
        UsbScreenStreamer(applicationContext) {
            handleUsbProjectionStopped(sessionId)
        }
internal fun createUsbH264ScreenStreamer(sessionId: Int): UsbH264ScreenStreamer =
        UsbH264ScreenStreamer(applicationContext) {
            handleUsbProjectionStopped(sessionId)
        }
internal fun prepareUsbScreenStreamerForSession(sessionId: Int) {
        synchronized(sessionLock) {
            activeUsbProjectionSessionId = sessionId
        }
        usbScreenStreamer = createUsbScreenStreamer(sessionId)
    }
internal fun prepareUsbH264ScreenStreamerForSession(sessionId: Int): UsbH264ScreenStreamer {
        synchronized(sessionLock) {
            activeUsbProjectionSessionId = sessionId
        }
        val streamer = createUsbH264ScreenStreamer(sessionId)
        usbH264ScreenStreamer = streamer
        return streamer
    }
internal fun clearActiveUsbProjectionSession(sessionId: Int) {
        synchronized(sessionLock) {
            if (activeUsbProjectionSessionId == sessionId) {
                activeUsbProjectionSessionId = 0
            }
        }
    }
internal fun handleUsbProjectionStopped(sessionId: Int) {
        mainHandler.post {
            val shouldHandle =
                synchronized(sessionLock) {
                    activeUsbProjectionSessionId == sessionId &&
                        (
                            mirrorSessionState.isActive(sessionId, MirrorTransport.USB_JPEG) ||
                                mirrorSessionState.isActive(sessionId, MirrorTransport.USB_H264)
                        )
                }
            if (!shouldHandle) {
                CrashDiagnostics.recordEvent(this@MediaProjectionService, "Ignoring stale USB MediaProjection stop for sessionId=$sessionId.")
                return@post
            }

            clearActiveUsbProjectionSession(sessionId)
            mediaProjectionResultCode = null
            mediaProjectionResultData = null
            screenCapturePermissionRequired = true
            isRunning = false
            applyScreenAwakeEffectsForCurrentState()
            updateServiceState()
            permissionGrantChannel.trySend(Unit)
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

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "MediaProjectionService.onCreate")
        createNotificationChannel()

        // Initialize repositories and state stores
        favoriteAppsRepository = FavoriteAppsRepository(applicationContext)
        screenAwakeSettingsStore = ScreenAwakeSettingsStore(ScreenAwakeSettingsStore.SharedPreferencesStore(applicationContext))
        screenAwakeSettings = screenAwakeSettingsStore.read()
        screenBrightnessController = ScreenBrightnessController(applicationContext)
        streamQualitySettingsStore = StreamQualitySettingsStore(StreamQualitySettingsStore.SharedPreferencesStore(applicationContext))
        networkTransportDetector = NetworkTransportDetector(applicationContext)
        screenCaptureManager.usbThermalReader = UsbThermalReader(applicationContext)
        screenCaptureManager.usbScreenStreamer = screenCaptureManager.createUsbScreenStreamer(sessionId = 0)
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
                        updateServiceState()
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)

        updateServiceState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "MediaProjectionService.onStartCommand startId=$startId.")
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

            webRtcManager.cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
            screenCaptureManager.mediaProjectionResultCode = resultCode
            screenCaptureManager.mediaProjectionResultData = resultData
            screenCapturePermissionRequired = false
            isRunning = true
            applyScreenAwakeEffectsForCurrentState()
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "MediaProjection foreground service is ready.")
            permissionGrantChannel.trySend(Unit)

            // Resume any waiting Offer
            mainHandler.post {
                resumePendingOfferIfReady()
                updateServiceState()
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
        if (screenCaptureManager.isUsbScreenStreamerInitialized()) {
            screenCaptureManager.usbScreenStreamer.stop()
        }
        screenCaptureManager.usbH264ScreenStreamer?.stop()
        synchronized(sessionLock) {
            screenCaptureManager.activeUsbProjectionSessionId = 0
        }
        webRtcManager.cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)

        try {
            screenBrightnessController.applyForMirroring(
                settings = screenAwakeSettingsStore.read(),
                isMirroringActive = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring brightness in onDestroy: ${e.message}", e)
        }

        CrashDiagnostics.recordEvent(this@MediaProjectionService, "MediaProjectionService.onDestroy")
        Log.d(TAG, "MediaProjectionService stopped.")
    }

    // ─── Service State Publisher ──────────────────────────────────────────────────

    fun updateServiceState() {
        _serviceState.value = MirrorServiceState(
            isKtorRunning = server != null,
            streamQualityMode = streamQualityMode,
            streamQualityNetwork = streamQualityNetwork,
            streamQualityProfile = streamQualityProfile,
            mirrorSessionState = mirrorSessionState,
            activeSessionId = activeSessionId,
            screenAwakeSettings = screenAwakeSettings,
            canWriteSystemSettings = if (::screenBrightnessController.isInitialized) screenBrightnessController.canWriteSystemSettings() else false,
            screenCapturePermissionRequired = screenCapturePermissionRequired,
            isMirroringActive = isMirroringActive()
        )
    }

    internal fun requestScreenCapturePermissionFromActivity(reason: String) {
        screenCapturePermissionRequired = true
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Screen capture permission request required: $reason.")
        updateServiceState()
    }

    // ─── Public Control API for MainActivity ─────────────────────────────────────

    fun updateScreenAwakeSettings(settings: ScreenAwakeSettings) {
        screenAwakeSettings = settings
        screenAwakeSettingsStore.write(settings)
        applyScreenAwakeEffectsForCurrentState()
        updateServiceState()
    }

    fun updateStreamQualityMode(mode: StreamQualityMode) {
        streamQualitySettingsStore.writeMode(mode)
        streamQualityMode = mode
        val profile = refreshStreamQualityState()
        applyStreamQualityProfile(profile, reason = "settings")
        updateServiceState()
    }

    fun disconnectMirror() {
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Manual mirror disconnect requested.")
        synchronized(sessionLock) {
            activeSessionId = 0
            mirrorSessionState = MirrorSessionState()
            screenCaptureManager.pendingOffer = null
            screenCaptureManager.activeUsbProjectionSessionId = 0
        }
        // Do not stop service entirely, keep Ktor running. Just stop capturing and WebRTC session
        stopForeground(true)
        if (screenCaptureManager.isUsbScreenStreamerInitialized()) {
            screenCaptureManager.usbScreenStreamer.stop()
        }
        screenCaptureManager.usbH264ScreenStreamer?.stop()
        webRtcManager.cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
        screenCaptureManager.mediaProjectionResultCode = null
        screenCaptureManager.mediaProjectionResultData = null
        screenCapturePermissionRequired = false
        isRunning = false
        applyScreenAwakeEffectsForCurrentState()
        updateServiceState()
        permissionGrantChannel.trySend(Unit)
    }

    internal fun stopProjectionCaptureForPolicy(reason: CleanupReason) {
        if (!CleanupPolicy.shouldStopProjection(reason)) return
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Stopping projection capture for cleanup reason: $reason.")
        stopForeground(true)
        webRtcManager.cleanupWebRTCResources(stopProjectionService = true, stopCapturer = true)
        isRunning = false
        screenCapturePermissionRequired = false
        applyScreenAwakeEffectsForCurrentState()
        updateServiceState()
    }

    fun isMirroringActive(): Boolean {
        return activeSessionId != 0 && isRunning
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        keepScreenAwake = enabled
        updateWakeLock()
    }

    // ─── Internal Helper Methods ───────────────────────────────────────────────

    

    internal fun refreshStreamQualityState(): StreamQualityProfile {
        val network = currentStreamNetworkTransport()
        val profile = AdaptiveStreamQuality.resolve(streamQualityMode, network, viewerActivityState)
        streamQualityNetwork = network
        streamQualityProfile = profile
        return profile
    }

    

    

    internal fun minOfUsbTier(
        first: UsbStreamProfileTier,
        second: UsbStreamProfileTier,
    ): UsbStreamProfileTier =
        if (first.ordinal <= second.ordinal) first else second

    internal fun isUsbThermalSevereOrWorse(status: UsbThermalStatus): Boolean =
        when (status) {
            UsbThermalStatus.SEVERE,
            UsbThermalStatus.CRITICAL,
            UsbThermalStatus.EMERGENCY,
            UsbThermalStatus.SHUTDOWN -> true
            UsbThermalStatus.UNKNOWN,
            UsbThermalStatus.NORMAL,
            UsbThermalStatus.LIGHT,
            UsbThermalStatus.MODERATE -> false
        }

    internal fun currentUsbThermalStatus(): UsbThermalStatus =
        if (screenCaptureManager.isUsbThermalReaderInitialized()) {
            screenCaptureManager.usbThermalReader.readStatus()
        } else {
            UsbThermalStatus.UNKNOWN
        }

    internal fun currentUsbPerfSnapshot(): UsbPerfSnapshot =
        screenCaptureManager.usbPerfMonitor.snapshot(
            profile = screenCaptureManager.lastUsbProfile,
            thermalStatus = currentUsbThermalStatus(),
            thermalHeadroom = if (screenCaptureManager.isUsbThermalReaderInitialized()) screenCaptureManager.usbThermalReader.readHeadroom() else null,
            batteryTemperatureC = if (screenCaptureManager.isUsbThermalReaderInitialized()) screenCaptureManager.usbThermalReader.readBatteryTemperatureC() else null,
            codec = screenCaptureManager.lastUsbCodec,
            h264Profile = if (screenCaptureManager.lastUsbCodec == UsbVideoCodec.H264) screenCaptureManager.lastUsbH264Profile else null,
        )

    internal fun currentStreamNetworkTransport(): StreamNetworkTransport =
        if (::networkTransportDetector.isInitialized) {
            networkTransportDetector.currentTransport()
        } else {
            StreamNetworkTransport.OTHER
        }

    internal fun redactSensitiveInfo(input: String?): String {
        if (input == null) return ""
        var redacted = input

        // Redact IP addresses (IPv4)
        redacted = redacted.replace(REDACT_IPV4, "[REDACTED_IP]")

        // Redact MAC addresses
        redacted = redacted.replace(REDACT_MAC, "[REDACTED_MAC]")

        // Redact file paths (Android specific)
        redacted = redacted.replace(REDACT_PATH, "[REDACTED_PATH]")

        // Redact Stack traces. Hide stack frames starting with "at "
        redacted = redacted.replace(REDACT_STACK_FRAME, "\t[REDACTED_STACK_FRAME]")

        return redacted
    }

    internal fun buildStreamQualityStatusString(): String {
        val network = currentStreamNetworkTransport()
        val profile = AdaptiveStreamQuality.resolve(streamQualityMode, network, viewerActivityState)
        return StreamQualityCodec.toStatusJson(
            selectedMode = streamQualityMode,
            networkTransport = network,
            profile = profile,
            activityState = viewerActivityState,
        )
    }

    internal fun applyBrightnessMinimizationForCurrentState() {
        try {
            screenBrightnessController.applyForMirroring(
                settings = screenAwakeSettings,
                isMirroringActive = isMirroringActive(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applying brightness minimization", e)
        }
    }

    internal fun applyScreenAwakeEffectsForCurrentState() {
        setKeepScreenAwake(screenAwakeSettings.shouldKeepScreenAwake(isMirroringActive()))
        applyBrightnessMinimizationForCurrentState()
    }

    

    

    

    

    

    

    internal fun markViewerActivity() {
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
                updateServiceState()
                CrashDiagnostics.recordEvent(
                    this@MediaProjectionService,
                    "Viewer idle stream quality applied: ${idleProfile.width}x${idleProfile.height}@${idleProfile.fps}, bitrate=${idleProfile.maxBitrateBps}.",
                )
            }
            updateServiceState()
        }
    }

    internal fun applyStreamQualityProfile(profile: StreamQualityProfile, reason: String) {
        try {
            val isResolutionChange = webRtcManager.videoCapturerLastWidth != profile.width || webRtcManager.videoCapturerLastHeight != profile.height
            if (isResolutionChange) {
                webRtcManager.videoCapturer?.changeCaptureFormat(profile.width, profile.height, profile.fps)
                webRtcManager.videoCapturerLastWidth = profile.width
                webRtcManager.videoCapturerLastHeight = profile.height
            }
            val sender = webRtcManager.videoSender
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
                    CrashDiagnostics.recordEvent(this@MediaProjectionService, "Stream quality bitrate skipped ($reason): sender has no encodings.")
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

    internal fun resumePendingOfferIfReady() {
        val offer = synchronized(sessionLock) { screenCaptureManager.pendingOffer } ?: return
        if (!isRunning) {
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Pending offer not resumed because capture is not ready yet.")
            return
        }
        if (!isActiveSession(offer.sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Dropping pending offer for inactive sessionId=${offer.sessionId}.")
            synchronized(sessionLock) {
                screenCaptureManager.pendingOffer = null
                mirrorSessionState = mirrorSessionState.clearPendingOffer()
            }
            return
        }

        synchronized(sessionLock) {
            screenCaptureManager.pendingOffer = null
            mirrorSessionState = mirrorSessionState.clearPendingOffer()
        }
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Resuming pending offer for sessionId=${offer.sessionId}.")
        offer.sendResponse(buildStatusMessage(captureReady = true, message = "SCREEN_CAPTURE_READY"))
        webRtcManager.initializeWebRTC(offer.sessionId, offer.remoteSdp, offer.sendResponse)
    }

    internal fun buildStatusMessage(
        captureReady: Boolean = isRunning,
        accessibilityReady: Boolean = GalaxyMirrorAccessibilityService.isReadyForRemoteInput(),
        message: String
    ): String {
        val streamQualityJsonStr = buildStreamQualityStatusString()
        return "{\"type\":\"STATUS\",\"payload\":{" +
                "\"captureReady\":$captureReady," +
                "\"accessibilityReady\":$accessibilityReady," +
                "\"keepScreenAwake\":${screenAwakeSettings.keepScreenAwakeDuringMirroring}," +
                "\"brightnessMinimizeEnabled\":${screenAwakeSettings.minimizeBrightnessDuringMirroring}," +
                "\"brightnessWriteSettingsReady\":${screenBrightnessController.canWriteSystemSettings()}," +
                "\"streamQuality\":$streamQualityJsonStr," +
                "\"message\":\"$message\"" +
                "}}"
    }

    internal fun buildUsbStatusMessage(
        message: String,
        captureReady: Boolean = isRunning,
    ): String {
        val streamQualityJson =
            if (screenCaptureManager.lastUsbCodec == UsbVideoCodec.H264) {
                JSONObject(UsbH264StreamProfileCodec.toStatusJson(streamQualityMode))
                    .put("effectiveTier", screenCaptureManager.lastUsbH264Profile.tier.name)
                    .put("effectiveWidth", screenCaptureManager.lastUsbH264Profile.width)
                    .put("effectiveHeight", screenCaptureManager.lastUsbH264Profile.height)
                    .put("effectiveFps", screenCaptureManager.lastUsbH264Profile.fps)
                    .put("width", screenCaptureManager.lastUsbH264Profile.width)
                    .put("height", screenCaptureManager.lastUsbH264Profile.height)
                    .put("fps", screenCaptureManager.lastUsbH264Profile.fps)
                    .put("bitrateBps", screenCaptureManager.lastUsbH264Profile.bitrateBps)
                    .put("policy", screenCaptureManager.lastUsbH264Profile.policy)
            } else {
                JSONObject(UsbStreamProfileCodec.toStatusJson(streamQualityMode))
                    .put("codec", UsbVideoCodec.JPEG.wireValue)
                    .put("effectiveTier", screenCaptureManager.lastUsbProfile.tier.name)
                    .put("effectiveWidth", screenCaptureManager.lastUsbProfile.width)
                    .put("effectiveHeight", screenCaptureManager.lastUsbProfile.height)
                    .put("effectiveFps", screenCaptureManager.lastUsbProfile.fps)
                    .put("width", screenCaptureManager.lastUsbProfile.width)
                    .put("height", screenCaptureManager.lastUsbProfile.height)
                    .put("fps", screenCaptureManager.lastUsbProfile.fps)
                    .put("jpegQuality", screenCaptureManager.lastUsbProfile.jpegQuality)
                    .put("policy", screenCaptureManager.lastUsbProfile.policy)
            }
        val usbPerfJson = currentUsbPerfSnapshot().toJson()
        val accessibilityReady = GalaxyMirrorAccessibilityService.isReadyForRemoteInput()
        val transportWireValue =
            if (screenCaptureManager.lastUsbCodec == UsbVideoCodec.H264) {
                MirrorTransport.USB_H264.wireValue
            } else {
                MirrorTransport.USB_JPEG.wireValue
            }
        return "{\"type\":\"USB_STATUS\",\"payload\":{" +
                "\"transport\":\"$transportWireValue\"," +
                "\"captureReady\":$captureReady," +
                "\"accessibilityReady\":$accessibilityReady," +
                "\"streamQuality\":$streamQualityJson," +
                "\"usbPerf\":$usbPerfJson," +
                "\"message\":\"$message\"" +
                "}}"
    }

    internal fun startKtorServer() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d("KtorServer", "Starting Ktor Server on 0.0.0.0:8080...")
                server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
                    install(WebSockets)
                    routing {
                        setupMirrorRouting(this@MediaProjectionService)
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



    internal suspend fun beginViewerSession(transport: MirrorTransport): Int {
        val sessionId = sessionCounter.incrementAndGet()
        withContext(Dispatchers.Main) {
            val previousSessionId = synchronized(sessionLock) { mirrorSessionState.activeSessionId }
            val previousTransport = synchronized(sessionLock) { mirrorSessionState.activeTransport }
            when (previousTransport) {
                MirrorTransport.TAILSCALE_WEBRTC ->
                    webRtcManager.cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
                MirrorTransport.USB_JPEG -> {
                    if (screenCaptureManager.isUsbScreenStreamerInitialized()) {
                        screenCaptureManager.usbScreenStreamer.stop()
                    }
                    screenCaptureManager.clearActiveUsbProjectionSession(previousSessionId)
                }
                MirrorTransport.USB_H264 -> {
                    screenCaptureManager.usbH264ScreenStreamer?.stop()
                    screenCaptureManager.clearActiveUsbProjectionSession(previousSessionId)
                }
                null -> Unit
            }

            when (transport) {
                MirrorTransport.TAILSCALE_WEBRTC -> {
                    if (
                        previousTransport != MirrorTransport.USB_JPEG &&
                        previousTransport != MirrorTransport.USB_H264 &&
                        screenCaptureManager.isUsbScreenStreamerInitialized()
                    ) {
                        screenCaptureManager.usbScreenStreamer.stop()
                    }
                }
                MirrorTransport.USB_JPEG,
                MirrorTransport.USB_H264 -> {
                    if (previousTransport != MirrorTransport.TAILSCALE_WEBRTC) {
                        webRtcManager.cleanupWebRTCResources(stopProjectionService = false, stopCapturer = true)
                    }
                }
            }

            val replacingSessionId = synchronized(sessionLock) {
                val previous = mirrorSessionState.activeSessionId
                screenCaptureManager.pendingOffer = null
                mirrorSessionState = mirrorSessionState.beginSession(sessionId, transport)
                activeSessionId = sessionId
                previous
            }
            if (replacingSessionId != 0) {
                CrashDiagnostics.recordEvent(this@MediaProjectionService, "Replacing active viewer session: $replacingSessionId -> $sessionId.")
                Log.w("WebRTC", "Replacing active viewer session: $replacingSessionId -> $sessionId")
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_REPLACED)
            }
            applyScreenAwakeEffectsForCurrentState()
            updateServiceState()
        }
        return sessionId
    }

    internal suspend fun endViewerSession(sessionId: Int) {
        withContext(Dispatchers.Main) {
            val shouldStopProjection = synchronized(sessionLock) {
                if (isActiveSession(sessionId)) {
                    CrashDiagnostics.recordEvent(this@MediaProjectionService, "Ending viewer session: $sessionId.")
                    mirrorSessionState = mirrorSessionState.endSession(sessionId)
                    activeSessionId = mirrorSessionState.activeSessionId
                    screenCaptureManager.pendingOffer = null
                    true
                } else {
                    false
                }
            }

            if (shouldStopProjection) {
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_SOCKET_CLOSED)
                updateServiceState()
            }
        }
    }

    internal fun isActiveSession(sessionId: Int): Boolean = mirrorSessionState.isActive(sessionId)

    internal fun isActiveSession(sessionId: Int, transport: MirrorTransport): Boolean =
        mirrorSessionState.isActive(sessionId, transport)

    internal fun queuePendingOffer(
        sessionId: Int,
        remoteSdp: SessionDescription,
        sendResponse: (String) -> Unit
    ) {
        if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Not queueing offer for inactive sessionId=$sessionId.")
            return
        }
        synchronized(sessionLock) {
            screenCaptureManager.pendingOffer = PendingOffer(sessionId, remoteSdp, sendResponse)
            mirrorSessionState = mirrorSessionState.queueOffer(sessionId)
        }
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Queued offer until capture is ready for sessionId=$sessionId.")
    }

    internal fun addRemoteIceCandidate(candidate: IceCandidate) {
        synchronized(webRtcManager.pendingRemoteIceCandidates) {
            if (!webRtcManager.remoteDescriptionSet || webRtcManager.peerConnection == null) {
                webRtcManager.pendingRemoteIceCandidates.add(candidate)
                Log.d("WebRTC", "Queued remote ICE candidate until remote description is set.")
                return
            }
        }
        webRtcManager.peerConnection?.addIceCandidate(candidate)
    }

    internal fun flushPendingRemoteIceCandidates() {
        val candidates = synchronized(webRtcManager.pendingRemoteIceCandidates) {
            webRtcManager.pendingRemoteIceCandidates.toList().also { webRtcManager.pendingRemoteIceCandidates.clear() }
        }
        candidates.forEach { webRtcManager.peerConnection?.addIceCandidate(it) }
        if (candidates.isNotEmpty()) {
            Log.d("WebRTC", "Flushed ${candidates.size} queued remote ICE candidates.")
        }
    }

    internal fun handleSignalingMessage(sessionId: Int, message: String, sendResponse: (String) -> Unit) {
        serviceScope.launch(Dispatchers.Default) {
            val isActive = withContext(Dispatchers.Main) {
                isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)
            }
            if (!isActive) {
                Log.w("WebRTC", "Ignoring signaling message for inactive session: $sessionId")
                return@launch
            }

            try {
                val json = org.json.JSONObject(message)
                val type = json.getString("type")

                when (type) {
                    "OFFER" -> {
                        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Offer received for sessionId=$sessionId.")
                        Log.d("WebRTC", "Offer received. Creating Answer...")
                        val sdpObj = json.getJSONObject("payload")
                        val sdpType = SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type"))
                        val originalSdp = sdpObj.getString("sdp")

                        // SDP Munging inside Dispatchers.Default
                        val modifiedOfferSdp = preferH264Codec(originalSdp)
                        val mungedOfferSdp = SessionDescription(sdpType, modifiedOfferSdp)

                        withContext(Dispatchers.Main) {
                            val isActiveMain = isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)
                            val readiness = ProjectionReadiness.from(
                                hasProjectionIntent = screenCaptureManager.mediaProjectionResultData != null,
                                isServiceRunning = isRunning
                            )
                            val decision = SignalingDecision.onOffer(readiness, isActiveMain)
                            CrashDiagnostics.recordEvent(this@MediaProjectionService, "Signaling decision on OFFER: $decision.")

                            when (decision) {
                                SignalingDecision.START_NEGOTIATION -> {
                                    webRtcManager.initializeWebRTC(sessionId, mungedOfferSdp, sendResponse)
                                }
                                SignalingDecision.QUEUE_AND_REQUEST_PERMISSION -> {
                                    queuePendingOffer(sessionId, mungedOfferSdp, sendResponse)
                                    requestScreenCapturePermissionFromActivity("Offer received without active MediaProjection grant")
                                    sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                                }
                                SignalingDecision.QUEUE_AND_SEND_STATUS -> {
                                    queuePendingOffer(sessionId, mungedOfferSdp, sendResponse)
                                    sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
                                    resumePendingOfferIfReady()
                                }
                                SignalingDecision.IGNORE_INACTIVE -> {
                                    CrashDiagnostics.recordEvent(this@MediaProjectionService, "Ignoring offer for inactive sessionId=$sessionId.")
                                }
                            }
                        }
                    }
                    "ICE_CANDIDATE" -> {
                        CrashDiagnostics.recordEvent(this@MediaProjectionService, "ICE candidate received for sessionId=$sessionId.")
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
                }
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(this@MediaProjectionService.filesDir, "signaling JSON parse", e)
                Log.e("WebRTC", "Error parsing signaling JSON: ${e.message}", e)
            }
        }
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

    internal fun initializePeerConnectionFactoryIfNeeded() {
        if (webRtcManager.peerConnectionFactory == null) {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            webRtcManager.eglBase = EglBase.create()
            val eglContext = webRtcManager.eglBase!!.eglBaseContext

            val factoryOptions = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)

            webRtcManager.peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            webRtcManager.surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
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
                    if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
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
                    if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) {
                        dc.close()
                        return
                    }
                    if (!ControlEventValidator.isControlChannel(dc.label())) {
                        Log.w("WebRTC", "Rejected DataChannel: ${dc.label()}")
                        dc.close()
                        return
                    }
                    webRtcManager.controlChannel = dc
                    sendResponse(buildStatusMessage(message = "CONTROL_CHANNEL_ACCEPTED"))
                    Log.d("WebRTC", "DataChannel received: ${dc.label()}")
                    dc.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {
                            Log.d("WebRTC", "DataChannel state: ${dc.state()}")
                        }
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            try {
                                if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
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
        }
    }

    internal fun setupScreenCapturePipeline(sessionId: Int, sendResponse: (String) -> Unit): Boolean {
        if (webRtcManager.videoCapturer == null) {
            val projectionIntent = screenCaptureManager.mediaProjectionResultData ?: return false
            webRtcManager.videoSource = webRtcManager.peerConnectionFactory?.createVideoSource(true)
            webRtcManager.videoCapturer = ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {
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
            webRtcManager.videoCapturer?.initialize(webRtcManager.surfaceTextureHelper, applicationContext, webRtcManager.videoSource?.capturerObserver)

            val streamNetwork = currentStreamNetworkTransport()
            val streamProfile = AdaptiveStreamQuality.resolve(streamQualityMode, streamNetwork, viewerActivityState)

            webRtcManager.videoCapturerLastWidth = streamProfile.width
            webRtcManager.videoCapturerLastHeight = streamProfile.height
            try {
                webRtcManager.videoCapturer?.startCapture(streamProfile.width, streamProfile.height, streamProfile.fps)
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(filesDir, "ScreenCapturerAndroid.startCapture", e)
                handleScreenCaptureReauthorizationRequired(
                    sessionId = sessionId,
                    sendResponse = sendResponse,
                    diagnosticReason = "ScreenCapturerAndroid.startCapture failure",
                    stopCapturer = true,
                )
                return false
            }
            webRtcManager.videoTrack = webRtcManager.peerConnectionFactory?.createVideoTrack("video_track_id", webRtcManager.videoSource)
        }
        return true
    }

    internal fun handleSdpExchange(sessionId: Int, remoteSdp: SessionDescription, sendResponse: (String) -> Unit) {
        // 5. Apply H.264 optimization to Offer SDP (SDP Munging)
        val modifiedOfferSdp = preferH264Codec(remoteSdp.description)
        val modifiedRemoteSdp = SessionDescription(remoteSdp.type, modifiedOfferSdp)

        // 6. Set Remote Description
        webRtcManager.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                webRtcManager.remoteDescriptionSet = true
                flushPendingRemoteIceCandidates()

                // Create local SDP Answer
                webRtcManager.peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (!isActiveSession(sessionId, MirrorTransport.TAILSCALE_WEBRTC)) return
                        desc?.let {
                            // Apply H.264 optimization to local Answer SDP (SDP Munging)
                            val modifiedAnswerSdp = preferH264Codec(it.description)
                            val modifiedLocalSdp = SessionDescription(it.type, modifiedAnswerSdp)

                            webRtcManager.peerConnection?.setLocalDescription(object : SdpObserver {
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

    

    internal fun handleScreenCaptureReauthorizationRequired(
        sessionId: Int,
        sendResponse: (String) -> Unit,
        diagnosticReason: String,
        stopCapturer: Boolean,
    ) {
        mainHandler.post {
            val shouldHandle = synchronized(sessionLock) {
                mirrorSessionState.isActive(sessionId, MirrorTransport.TAILSCALE_WEBRTC)
            }

            if (!shouldHandle) {
                CrashDiagnostics.recordEvent(
                    this,
                    "Ignoring stale MediaProjection event for sessionId=$sessionId reason=$diagnosticReason.",
                )
                return@post
            }

            screenCaptureManager.mediaProjectionResultCode = null
            screenCaptureManager.mediaProjectionResultData = null
            screenCapturePermissionRequired = true
            sendResponse(
                buildStatusMessage(
                    captureReady = false,
                    message = "SCREEN_CAPTURE_REAUTH_REQUIRED",
                ),
            )
            synchronized(sessionLock) {
                screenCaptureManager.pendingOffer = null
                mirrorSessionState = mirrorSessionState.projectionStopped(sessionId)
                activeSessionId = mirrorSessionState.activeSessionId
            }
            webRtcManager.cleanupWebRTCResources(stopProjectionService = false, stopCapturer = stopCapturer)
            requestScreenCapturePermissionFromActivity(diagnosticReason)
            isRunning = false
            applyScreenAwakeEffectsForCurrentState()
            updateServiceState()
        }
    }

    internal fun sendControlAck(channel: DataChannel, result: ControlEventResult) {
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

    internal fun org.json.JSONObject.controlSeq(): Long? =
        if (has("seq")) {
            optLong("seq")
        } else {
            null
        }

    

    @Suppress("DEPRECATION")
    internal fun updateWakeLock() {
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
            CrashDiagnostics.recordEvent(this@MediaProjectionService, "MediaProjection screen bright wake lock acquired.")
            return
        }

        releaseWakeLock()
    }

    internal fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                CrashDiagnostics.recordEvent(this@MediaProjectionService, "MediaProjection partial wake lock released.")
            }
        }
        wakeLock = null
    }

    internal fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Mirror Active")
            .setContentText("실시간 Mac 브라우저 미러링 및 제어 서비스 작동 중")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    internal fun createNotificationChannel() {
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

data class MirrorServiceState(
    val isKtorRunning: Boolean = false,
    val streamQualityMode: StreamQualityMode = StreamQualityMode.AUTO,
    val streamQualityNetwork: StreamNetworkTransport = StreamNetworkTransport.OTHER,
    val streamQualityProfile: StreamQualityProfile = StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER),
    val mirrorSessionState: MirrorSessionState = MirrorSessionState(),
    val activeSessionId: Int = 0,
    val screenAwakeSettings: ScreenAwakeSettings = ScreenAwakeSettings(),
    val canWriteSystemSettings: Boolean = false,
    val screenCapturePermissionRequired: Boolean = false,
    val isMirroringActive: Boolean = false
)
