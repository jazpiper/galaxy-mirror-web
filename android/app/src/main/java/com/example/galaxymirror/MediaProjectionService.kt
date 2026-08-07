package com.example.galaxymirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.RtpParameters

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
            internal set
        var instance: MediaProjectionService? = null
            private set

        fun isValidStartData(resultCode: Int, hasResultData: Boolean): Boolean {
            return resultCode == Activity.RESULT_OK && hasResultData
        }

        private const val IDLE_QUALITY_DELAY_MS = 6_000L
        internal const val MEDIA_PROJECTION_GRANT_POLL_MS = 500L

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
        internal set

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
    lateinit var blackOverlayController: BlackOverlayController
        private set
    var streamQualityMode = StreamQualityMode.AUTO
        private set
    var streamQualityNetwork = StreamNetworkTransport.OTHER
        internal set
    var streamQualityProfile = StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER)
        internal set
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

    internal val sessionCounter = java.util.concurrent.atomic.AtomicInteger(0)
    internal val sessionLock = Any()

    // Domain Managers
    val screenCaptureManager = ScreenCaptureManager(this)
    val webRtcManager = WebRtcManager(this)

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
        blackOverlayController = BlackOverlayController(applicationContext) {
            mainHandler.post { updateServiceState() }
        }
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
        if (::blackOverlayController.isInitialized) {
            blackOverlayController.hideOverlay()
        }
        isRunning = false
        releaseWakeLock()

        // Stop Ktor server in a non-activity lifecycle scope
        val serverToStop = server
        if (serverToStop != null) {
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
            blackOverlayEnabled = if (::blackOverlayController.isInitialized) blackOverlayController.isShowing() else false,
            overlayPermissionReady = if (::blackOverlayController.isInitialized) blackOverlayController.canDrawOverlays() else false,
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

    fun setBlackOverlayEnabled(enabled: Boolean): Boolean {
        if (!::blackOverlayController.isInitialized) return false
        val success = if (enabled) {
            blackOverlayController.showOverlay()
        } else {
            blackOverlayController.hideOverlay()
        }
        updateServiceState()
        return success
    }

    fun disconnectMirror() {
        CrashDiagnostics.recordEvent(this@MediaProjectionService, "Manual mirror disconnect requested.")
        if (::blackOverlayController.isInitialized) {
            blackOverlayController.hideOverlay()
        }
        synchronized(sessionLock) {
            activeSessionId = 0
            mirrorSessionState = MirrorSessionState()
            screenCaptureManager.pendingOffer = null
            screenCaptureManager.activeUsbProjectionSessionId = 0
        }
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
        redacted = redacted.replace(REDACT_IPV4, "[REDACTED_IP]")
        redacted = redacted.replace(REDACT_MAC, "[REDACTED_MAC]")
        redacted = redacted.replace(REDACT_PATH, "[REDACTED_PATH]")
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
            val isStateChanged = viewerActivityState != ViewerActivityState.ACTIVE
            viewerActivityState = ViewerActivityState.ACTIVE
            idleQualityJob?.cancel()
            
            if (isStateChanged) {
                val activeProfile = refreshStreamQualityState()
                applyStreamQualityProfile(activeProfile, reason = "viewer activity")
                updateServiceState()
            }
            
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
        val overlayShowing = if (::blackOverlayController.isInitialized) blackOverlayController.isShowing() else false
        val overlayReady = if (::blackOverlayController.isInitialized) blackOverlayController.canDrawOverlays() else false
        return "{\"type\":\"STATUS\",\"payload\":{" +
                "\"captureReady\":$captureReady," +
                "\"accessibilityReady\":$accessibilityReady," +
                "\"keepScreenAwake\":${screenAwakeSettings.keepScreenAwakeDuringMirroring}," +
                "\"brightnessMinimizeEnabled\":${screenAwakeSettings.minimizeBrightnessDuringMirroring}," +
                "\"brightnessWriteSettingsReady\":${screenBrightnessController.canWriteSystemSettings()}," +
                "\"blackOverlayEnabled\":$overlayShowing," +
                "\"overlayPermissionReady\":$overlayReady," +
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
        kotlinx.coroutines.withContext(Dispatchers.Main) {
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
                CrashDiagnostics.recordEvent(this@MediaProjectionService, "Replacing active viewer session: $replacingSessionId -> $sessionId with CleanupReason.VIEWER_REPLACED.")
                Log.w("WebRTC", "Replacing active viewer session: $replacingSessionId -> $sessionId")
                // MediaProjection grants are single-use (Android 14+): releasing the consumed
                // token here forces the replacing viewer through the SCREEN_CAPTURE_REAUTH flow
                // instead of silently reusing a dead grant.
                stopProjectionCaptureForPolicy(CleanupReason.VIEWER_REPLACED)
            }
            applyScreenAwakeEffectsForCurrentState()
            updateServiceState()
        }
        return sessionId
    }

    internal suspend fun endViewerSession(sessionId: Int) {
        kotlinx.coroutines.withContext(Dispatchers.Main) {
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
                val hasNewActiveSession = synchronized(sessionLock) { mirrorSessionState.activeSessionId != 0 }
                if (!hasNewActiveSession) {
                    stopProjectionCaptureForPolicy(CleanupReason.VIEWER_SOCKET_CLOSED)
                }
                updateServiceState()
            }
        }
    }

    internal fun isActiveSession(sessionId: Int): Boolean = mirrorSessionState.isActive(sessionId)

    internal fun isActiveSession(sessionId: Int, transport: MirrorTransport): Boolean =
        mirrorSessionState.isActive(sessionId, transport)

    internal fun queuePendingOffer(
        sessionId: Int,
        remoteSdp: org.webrtc.SessionDescription,
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

    internal fun handleSignalingMessage(sessionId: Int, message: String, sendResponse: (String) -> Unit) {
        webRtcManager.handleSignalingMessage(sessionId, message, sendResponse)
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
    val blackOverlayEnabled: Boolean = false,
    val overlayPermissionReady: Boolean = false,
    val screenCapturePermissionRequired: Boolean = false,
    val isMirroringActive: Boolean = false
)
