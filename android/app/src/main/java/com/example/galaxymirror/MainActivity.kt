package com.example.galaxymirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.galaxymirror.theme.GalaxyMirrorTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
  private companion object {
    const val VIEWER_TOKEN_QUERY = "token"
    const val VIEWER_TOKEN_HEADER = "X-Android-Mirror-Token"
    const val IDLE_QUALITY_DELAY_MS = 6_000L
  }

  private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
  private var mediaProjectionManager: MediaProjectionManager? = null
  private var accessibilityEnabled by mutableStateOf(false)
  private lateinit var favoriteAppsRepository: FavoriteAppsRepository
  private var favoriteApps by mutableStateOf<List<FavoriteApp>>(emptyList())
  private var launchableApps by mutableStateOf<List<FavoriteApp>>(emptyList())
  private lateinit var viewerAccessTokenStore: ViewerAccessTokenStore
  private var viewerAccessToken by mutableStateOf("")
  private lateinit var screenAwakeSettingsStore: ScreenAwakeSettingsStore
  private var screenAwakeSettings by mutableStateOf(ScreenAwakeSettings())
  private lateinit var screenBrightnessController: ScreenBrightnessController
  private var canWriteSystemSettings by mutableStateOf(false)
  private lateinit var streamQualitySettingsStore: StreamQualitySettingsStore
  private lateinit var networkTransportDetector: NetworkTransportDetector
  private var streamQualityMode by mutableStateOf(StreamQualityMode.AUTO)
  private var streamQualityNetwork by mutableStateOf(StreamNetworkTransport.OTHER)
  private var streamQualityProfile by mutableStateOf(
    StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER)
  )
  private var viewerActivityState by mutableStateOf(ViewerActivityState.ACTIVE)
  private var idleQualityJob: Job? = null

  // WebRTC Components
  private var peerConnectionFactory: PeerConnectionFactory? = null
  private var peerConnection: PeerConnection? = null
  private var videoTrack: VideoTrack? = null
  private var videoSender: RtpSender? = null
  private var surfaceTextureHelper: SurfaceTextureHelper? = null
  private var videoCapturer: VideoCapturer? = null
  private var controlChannel: DataChannel? = null
  private var eglBase: org.webrtc.EglBase? = null
  private val sessionCounter = AtomicInteger(0)
  private val sessionLock = Any()
  @Volatile private var mirrorSessionState = MirrorSessionState()
  @Volatile private var activeSessionId: Int = 0
  @Volatile private var remoteDescriptionSet = false
  @Volatile private var screenCaptureRequestInFlight = false
  private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

  // MediaProjection intent stored for WebRTC use
  private var mediaProjectionResultData: Intent? = null
  private var pendingOffer: PendingOffer? = null

  private data class PendingOffer(
    val sessionId: Int,
    val remoteSdp: SessionDescription,
    val sendResponse: (String) -> Unit
  )

  // 화면 캡처 권한 요청 런처
  private val screenCaptureLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    screenCaptureRequestInFlight = false
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      CrashDiagnostics.recordEvent(this, "Screen capture permission granted.")
      startMediaProjectionService(result.resultCode, result.data!!)
    } else {
      CrashDiagnostics.recordEvent(this, "Screen capture permission denied or result data missing.")
      val deniedOffer = synchronized(sessionLock) { pendingOffer }
      deniedOffer?.let { offer ->
        if (isActiveSession(offer.sessionId)) {
          offer.sendResponse(
            buildStatusMessage(
              captureReady = false,
              message = "SCREEN_CAPTURE_PERMISSION_DENIED"
            )
          )
        }
      }
      synchronized(sessionLock) {
        pendingOffer = null
        mirrorSessionState = mirrorSessionState.clearPendingOffer()
      }
      Log.e("GalaxyMirror", "Screen capture permission denied by user.")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    CrashDiagnostics.install(applicationContext)
    CrashDiagnostics.recordProcessExitReasons(applicationContext)
    CrashDiagnostics.recordEvent(this, "MainActivity.onCreate")
    favoriteAppsRepository = FavoriteAppsRepository(applicationContext)
    viewerAccessTokenStore = ViewerAccessTokenStore(applicationContext)
    viewerAccessToken = viewerAccessTokenStore.getOrCreateToken()
    screenAwakeSettingsStore =
      ScreenAwakeSettingsStore(ScreenAwakeSettingsStore.SharedPreferencesStore(applicationContext))
    screenAwakeSettings = screenAwakeSettingsStore.read()
    screenBrightnessController = ScreenBrightnessController(applicationContext)
    streamQualitySettingsStore =
      StreamQualitySettingsStore(StreamQualitySettingsStore.SharedPreferencesStore(applicationContext))
    networkTransportDetector = NetworkTransportDetector(applicationContext)
    streamQualityMode = streamQualitySettingsStore.readMode()
    refreshAccessibilityEnabled()
    refreshWriteSettingsPermission()
    refreshFavoriteApps()
    refreshStreamQualityState()
    applyScreenAwakeWindowFlag()

    mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    enableEdgeToEdge()
    setContent {
      GalaxyMirrorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(
            accessibilityEnabled = accessibilityEnabled,
            viewerAccessToken = viewerAccessToken,
            favoriteApps = favoriteApps,
            launchableApps = launchableApps,
            screenAwakeSettings = screenAwakeSettings,
            canWriteSystemSettings = canWriteSystemSettings,
            streamQualityMode = streamQualityMode,
            streamQualityNetwork = streamQualityNetwork,
            streamQualityProfile = streamQualityProfile,
            onAddFavoriteApp = ::addFavoriteApp,
            onRemoveFavoriteApp = ::removeFavoriteApp,
            onScreenAwakeSettingsChange = ::updateScreenAwakeSettings,
            onStreamQualityModeChange = ::updateStreamQualityMode,
            onOpenAppInfoSettings = ::openAppInfoSettings,
            onOpenAccessibilitySettings = ::openAccessibilitySettings,
            onOpenWriteSettings = ::openWriteSettings,
            onDisconnect = ::disconnectMirror,
          )
        }
      }
    }

    // 1. Ktor 임베디드 웹서버 실행
    startKtorServer()

    // 2. 안드로이드 화면 캡처(MediaProjection) 동의 요청 팝업 즉시 기동
    requestScreenCapturePermission()
  }

  override fun onResume() {
    super.onResume()
    refreshAccessibilityEnabled()
    refreshWriteSettingsPermission()
    refreshFavoriteApps()
    refreshStreamQualityState()
    applyScreenAwakeWindowFlag()
    applyBrightnessMinimizationForCurrentState()
  }

  private fun refreshAccessibilityEnabled() {
    accessibilityEnabled = AccessibilitySettingsState.isGalaxyMirrorServiceEnabled(this)
  }

  private fun refreshWriteSettingsPermission() {
    if (!::screenBrightnessController.isInitialized) return
    canWriteSystemSettings = screenBrightnessController.canWriteSystemSettings()
  }

  private fun refreshFavoriteApps() {
    if (!::favoriteAppsRepository.isInitialized) return
    favoriteApps = favoriteAppsRepository.getFavorites()
    launchableApps = favoriteAppsRepository.getLaunchableApps()
  }

  private fun addFavoriteApp(app: FavoriteApp) {
    favoriteApps = favoriteAppsRepository.addFavorite(app)
    refreshFavoriteApps()
    Toast.makeText(this, "${app.label} 바로가기를 추가했습니다.", Toast.LENGTH_SHORT).show()
  }

  private fun removeFavoriteApp(packageName: String) {
    val removedLabel = favoriteApps.firstOrNull { it.packageName == packageName }?.label ?: "앱"
    favoriteApps = favoriteAppsRepository.removeFavorite(packageName)
    refreshFavoriteApps()
    Toast.makeText(this, "$removedLabel 바로가기를 삭제했습니다.", Toast.LENGTH_SHORT).show()
  }

  private fun updateScreenAwakeSettings(settings: ScreenAwakeSettings) {
    screenAwakeSettings = settings
    screenAwakeSettingsStore.write(settings)
    MediaProjectionService.instance?.setKeepScreenAwake(settings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings()))
    applyScreenAwakeWindowFlag()
    val brightnessResult = applyBrightnessMinimizationForCurrentState(showPermissionToast = true)
    CrashDiagnostics.recordEvent(
      this,
      "Screen awake settings changed: keepAwake=${settings.keepScreenAwakeDuringMirroring}, minimizeBrightness=${settings.minimizeBrightnessDuringMirroring}, brightnessResult=$brightnessResult.",
    )
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

  private fun updateStreamQualityMode(mode: StreamQualityMode) {
    if (::streamQualitySettingsStore.isInitialized) {
      streamQualitySettingsStore.writeMode(mode)
    }
    streamQualityMode = mode
    val profile = refreshStreamQualityState()
    applyStreamQualityProfile(profile, reason = "settings")
    CrashDiagnostics.recordEvent(
      this,
      "Stream quality mode changed: selected=${mode.wireValue}, effective=${profile.mode.wireValue}, ${profile.width}x${profile.height}@${profile.fps}, bitrate=${profile.maxBitrateBps}.",
    )
  }

  private fun isMirroringActiveForScreenSettings(): Boolean {
    return mirrorSessionState.activeSessionId != 0 && MediaProjectionService.isRunning
  }

  private fun applyScreenAwakeWindowFlag() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread { applyScreenAwakeWindowFlag() }
      return
    }

    val shouldKeepScreenAwake =
      screenAwakeSettings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings())
    if (shouldKeepScreenAwake) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  private fun applyBrightnessMinimizationForCurrentState(
    showPermissionToast: Boolean = false,
  ): ScreenBrightnessResult {
    if (!::screenBrightnessController.isInitialized) return ScreenBrightnessResult.UNCHANGED
    val result =
      screenBrightnessController.applyForMirroring(
        settings = screenAwakeSettings,
        isMirroringActive = isMirroringActiveForScreenSettings(),
      )
    updateWriteSettingsPermissionState()
    if (
      showPermissionToast &&
      result == ScreenBrightnessResult.PERMISSION_REQUIRED &&
      screenAwakeSettings.minimizeBrightnessDuringMirroring
    ) {
      runOnMainThread {
        Toast.makeText(
          this,
          "밝기 최소화는 시스템 설정 수정 권한이 필요합니다.",
          Toast.LENGTH_SHORT,
        ).show()
      }
    }
    CrashDiagnostics.recordEvent(this, "Brightness minimize state applied: result=$result.")
    return result
  }

  private fun updateWriteSettingsPermissionState() {
    if (!::screenBrightnessController.isInitialized) return
    val canWrite = screenBrightnessController.canWriteSystemSettings()
    runOnMainThread { canWriteSystemSettings = canWrite }
  }

  private fun runOnMainThread(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
    } else {
      runOnUiThread(action)
    }
  }

  private fun onMirroringSessionStarted() {
    MediaProjectionService.instance?.setKeepScreenAwake(
      screenAwakeSettings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings()),
    )
    applyScreenAwakeWindowFlag()
    applyBrightnessMinimizationForCurrentState(showPermissionToast = true)
    markViewerActivity()
  }

  private fun markViewerActivity() {
    runOnMainThread {
      val shouldRestoreActiveQuality = viewerActivityState != ViewerActivityState.ACTIVE
      viewerActivityState = ViewerActivityState.ACTIVE
      val activeProfile = refreshStreamQualityState()
      if (shouldRestoreActiveQuality) {
        applyStreamQualityProfile(activeProfile, reason = "viewer activity")
      }
      idleQualityJob?.cancel()
      idleQualityJob =
        lifecycleScope.launch {
          delay(IDLE_QUALITY_DELAY_MS)
          viewerActivityState = ViewerActivityState.IDLE
          val idleProfile = refreshStreamQualityState()
          applyStreamQualityProfile(idleProfile, reason = "viewer idle")
          CrashDiagnostics.recordEvent(
            this@MainActivity,
            "Viewer idle stream quality applied: ${idleProfile.width}x${idleProfile.height}@${idleProfile.fps}, bitrate=${idleProfile.maxBitrateBps}.",
          )
        }
    }
  }

  private fun applyStreamQualityProfile(
    profile: StreamQualityProfile,
    reason: String,
  ) {
    try {
      videoCapturer?.changeCaptureFormat(profile.width, profile.height, profile.fps)
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
            "Stream quality applied ($reason): ${profile.width}x${profile.height}@${profile.fps}, bitrate=${profile.maxBitrateBps}, senderApplied=$applied.",
          )
        } else {
          CrashDiagnostics.recordEvent(this, "Stream quality bitrate skipped ($reason): sender has no encodings.")
        }
      } else {
        CrashDiagnostics.recordEvent(
          this,
          "Stream quality capture format applied ($reason) before RTP sender exists: ${profile.width}x${profile.height}@${profile.fps}.",
        )
      }
    } catch (e: Exception) {
      CrashDiagnostics.recordCaughtException(filesDir, "apply stream quality", e)
    }
  }

  private fun requestScreenCapturePermission() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread { requestScreenCapturePermission() }
      return
    }
    if (screenCaptureRequestInFlight) {
      CrashDiagnostics.recordEvent(this, "Screen capture permission request already in flight.")
      return
    }
    CrashDiagnostics.recordEvent(this, "Requesting screen capture permission.")
    mediaProjectionManager?.createScreenCaptureIntent()?.let { intent ->
      screenCaptureRequestInFlight = true
      screenCaptureLauncher.launch(intent)
    } ?: run {
      screenCaptureRequestInFlight = false
      CrashDiagnostics.recordEvent(this, "MediaProjectionManager unavailable for permission request.")
    }
  }

  private fun openAccessibilitySettings() {
    refreshAccessibilityEnabled()
    if (accessibilityEnabled) {
      Toast.makeText(this, "접근성 입력이 이미 활성화되어 있습니다.", Toast.LENGTH_SHORT).show()
      return
    }
    CrashDiagnostics.recordEvent(this, "Opening accessibility settings from app UI.")
    val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    try {
      if (accessibilityIntent.resolveActivity(packageManager) != null) {
        startActivity(accessibilityIntent)
      } else {
        startActivity(Intent(Settings.ACTION_SETTINGS))
      }
    } catch (e: Exception) {
      CrashDiagnostics.recordCaughtException(filesDir, "open accessibility settings", e)
      Toast.makeText(this, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
  }

  private fun openAppInfoSettings() {
    refreshAccessibilityEnabled()
    if (accessibilityEnabled) {
      Toast.makeText(this, "접근성 입력이 이미 활성화되어 있습니다.", Toast.LENGTH_SHORT).show()
      return
    }
    CrashDiagnostics.recordEvent(this, "Opening app info settings from app UI.")
    val appInfoIntent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
      }
    try {
      if (appInfoIntent.resolveActivity(packageManager) != null) {
        startActivity(appInfoIntent)
      } else {
        startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
      }
    } catch (e: Exception) {
      CrashDiagnostics.recordCaughtException(filesDir, "open app info settings", e)
      Toast.makeText(this, "앱 정보 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
  }

  private fun openWriteSettings() {
    refreshWriteSettingsPermission()
    if (canWriteSystemSettings) {
      Toast.makeText(this, "시스템 설정 수정 권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
      return
    }
    CrashDiagnostics.recordEvent(this, "Opening write settings permission from app UI.")
    val writeSettingsIntent =
      Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
      }
    try {
      if (writeSettingsIntent.resolveActivity(packageManager) != null) {
        startActivity(writeSettingsIntent)
      } else {
        startActivity(Intent(Settings.ACTION_SETTINGS))
      }
    } catch (e: Exception) {
      CrashDiagnostics.recordCaughtException(filesDir, "open write settings", e)
      Toast.makeText(this, "시스템 설정 수정 권한 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
  }

  private fun disconnectMirror() {
    CrashDiagnostics.recordEvent(this, "Manual mirror disconnect requested from app UI.")
    synchronized(sessionLock) {
      activeSessionId = 0
      mirrorSessionState = MirrorSessionState()
      pendingOffer = null
    }
    cleanupWebRTCResources(
      stopProjectionService = CleanupPolicy.shouldStopProjection(CleanupReason.EXPLICIT_STOP)
    )
    Toast.makeText(this, "미러링 연결을 해제했습니다.", Toast.LENGTH_SHORT).show()
  }

  private fun startMediaProjectionService(resultCode: Int, data: Intent) {
    mediaProjectionResultData = data  // Store for WebRTC ScreenCapturerAndroid use
    CrashDiagnostics.recordEvent(this, "Starting MediaProjectionService with resultCode=$resultCode.")
    val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
      putExtra("resultCode", resultCode)
      putExtra("resultData", data)
      putExtra(
        MediaProjectionService.EXTRA_KEEP_SCREEN_AWAKE,
        screenAwakeSettings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings()),
      )
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }
    Log.d("GalaxyMirror", "Started MediaProjectionService.")
    waitForCaptureReadyThenResumePendingOffer()
  }

  private fun waitForCaptureReadyThenResumePendingOffer() {
    lifecycleScope.launch(Dispatchers.Main) {
      repeat(20) {
        if (MediaProjectionService.isRunning) {
          MediaProjectionService.instance?.setKeepScreenAwake(
            screenAwakeSettings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings()),
          )
          applyScreenAwakeWindowFlag()
          applyBrightnessMinimizationForCurrentState(showPermissionToast = true)
          resumePendingOfferIfReady()
          return@launch
        }
        delay(100)
      }
      CrashDiagnostics.recordEvent(this@MainActivity, "Timed out waiting for MediaProjectionService readiness.")
      val timedOutOffer = synchronized(sessionLock) { pendingOffer }
      timedOutOffer?.let { offer ->
        if (isActiveSession(offer.sessionId)) {
          offer.sendResponse(
            buildStatusMessage(
              captureReady = false,
              message = "SCREEN_CAPTURE_NOT_READY"
            )
          )
        }
      }
    }
  }

  private fun resumePendingOfferIfReady() {
    val offer = synchronized(sessionLock) { pendingOffer } ?: return
    if (!MediaProjectionService.isRunning) {
      CrashDiagnostics.recordEvent(this, "Pending offer not resumed because capture service is not ready yet.")
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
    captureReady: Boolean = MediaProjectionService.isRunning,
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
        put(
          "brightnessWriteSettingsReady",
          if (::screenBrightnessController.isInitialized) {
            screenBrightnessController.canWriteSystemSettings()
          } else {
            false
          },
        )
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
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        Log.d("KtorServer", "Starting Ktor Server on 0.0.0.0:8080...")
        server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
          install(WebSockets)
          routing {
            // 정적 리소스 서빙 (resources/files/index.html & viewer.js 서빙)
            staticResources("/", "files")
            
            get("/status") {
              call.respondText("Android Mirror Web Server is active. Port: 8080")
            }

            get("/debug/crash") {
              if (!requireViewerAuthorization(call)) return@get
              call.respondText(
                CrashDiagnostics.readDebugReport(this@MainActivity.filesDir),
                ContentType.Text.Plain
              )
            }

            get("/debug/crash/clear") {
              if (!requireViewerAuthorization(call)) return@get
              CrashDiagnostics.clearCrash(this@MainActivity.filesDir)
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
              call.respondText(
                buildStreamQualityStatusJson().toString(),
                ContentType.Application.Json,
              )
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
              call.respondText(
                buildStreamQualityStatusJson().toString(),
                ContentType.Application.Json,
              )
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

              val launched =
                withContext(Dispatchers.Main) {
                  favoriteAppsRepository.launchFavorite(packageName)
                }

              if (launched) {
                CrashDiagnostics.recordEvent(this@MainActivity.filesDir, "Favorite app launched: $packageName.")
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
              } else {
                CrashDiagnostics.recordEvent(this@MainActivity.filesDir, "Favorite app launch failed: $packageName.")
                call.respondText(
                  """{"ok":false,"error":"APP_NOT_FOUND"}""",
                  ContentType.Application.Json,
                  HttpStatusCode.NotFound
                )
              }
            }
            
            // WebRTC 1:1 시그널링 WebSocket
            webSocket("/signaling") {
              if (!isViewerAuthorized(call)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHORIZED_VIEWER"))
                return@webSocket
              }
              val sessionId = beginViewerSession()
              CrashDiagnostics.recordEvent(this@MainActivity.filesDir, "Signaling WebSocket connected: sessionId=$sessionId.")
              Log.d("KtorServer", "New WebRTC signaling WebSocket connection established: $sessionId")
              val statusJob = launch {
                while (isActiveSession(sessionId)) {
                  delay(2_000)
                  try {
                    send(Frame.Text(buildStatusMessage(message = "STATUS_TICK")))
                  } catch (e: Throwable) {
                    CrashDiagnostics.recordCaughtException(this@MainActivity.filesDir, "signaling status tick", e)
                    return@launch
                  }
                }
              }
              try {
                send(Frame.Text(buildStatusMessage(message = "SIGNALING_CONNECTED")))
                for (frame in incoming) {
                  if (frame is Frame.Text) {
                    val text = frame.readText()
                    Log.d("KtorServer", "Signaling packet received: $text")
                    // 브라우저 뷰어가 보낸 OFFER/ANSWER/CANDIDATE 패킷을 내부 WebRTC 로직으로 주입하거나 릴레이
                    handleSignalingMessage(sessionId, text) { response ->
                      if (isActiveSession(sessionId)) {
                        launch { send(Frame.Text(response)) }
                      }
                    }
                  }
                }
              } catch (e: ClosedReceiveChannelException) {
                CrashDiagnostics.recordEvent(this@MainActivity.filesDir, "Signaling connection closed by peer: sessionId=$sessionId.")
                Log.d("KtorServer", "Signaling connection closed by peer: $sessionId")
              } catch (e: Throwable) {
                CrashDiagnostics.recordCaughtException(this@MainActivity.filesDir, "signaling session $sessionId", e)
                Log.e("KtorServer", "Error in signaling session $sessionId: ${e.message}", e)
              } finally {
                statusJob.cancel()
                endViewerSession(sessionId)
              }
            }
          }
        }.start(wait = false)
        CrashDiagnostics.recordEvent(this@MainActivity.filesDir, "Ktor server started on 0.0.0.0:8080.")
        Log.d("KtorServer", "Ktor Server successfully started.")
      } catch (e: Exception) {
        CrashDiagnostics.recordCaughtException(this@MainActivity.filesDir, "Ktor server startup", e)
        Log.e("KtorServer", "Error starting Ktor Server: ${e.message}", e)
      }
    }
  }

  private fun beginViewerSession(): Int {
    val sessionId = sessionCounter.incrementAndGet()
    val replacingSessionId =
      synchronized(sessionLock) {
        val previous = mirrorSessionState.activeSessionId
        pendingOffer = null
        mirrorSessionState = mirrorSessionState.beginSession(sessionId)
        activeSessionId = sessionId
        previous
      }
    if (replacingSessionId != 0) {
      CrashDiagnostics.recordEvent(this, "Replacing active viewer session: $replacingSessionId -> $sessionId.")
      Log.w("WebRTC", "Replacing active viewer session: $replacingSessionId -> $sessionId")
      cleanupWebRTCResources(
        stopProjectionService = CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_REPLACED)
      )
    }
    applyScreenAwakeWindowFlag()
    applyBrightnessMinimizationForCurrentState()
    return sessionId
  }

  private fun endViewerSession(sessionId: Int) {
    if (isActiveSession(sessionId)) {
      CrashDiagnostics.recordEvent(this, "Ending viewer session: $sessionId.")
      synchronized(sessionLock) {
        mirrorSessionState = mirrorSessionState.endSession(sessionId)
        activeSessionId = mirrorSessionState.activeSessionId
        pendingOffer = null
      }
      cleanupWebRTCResources(
        stopProjectionService = CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_SOCKET_CLOSED)
      )
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
    if (!remoteDescriptionSet || peerConnection == null) {
      synchronized(pendingRemoteIceCandidates) {
        pendingRemoteIceCandidates.add(candidate)
      }
      Log.d("WebRTC", "Queued remote ICE candidate until remote description is set.")
      return
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

  // 1:1 시그널링 메시지 처리 및 WebRTC PeerConnection 제어
  private fun handleSignalingMessage(sessionId: Int, message: String, sendResponse: (String) -> Unit) {
    if (!isActiveSession(sessionId)) {
      Log.w("WebRTC", "Ignoring signaling message for inactive session: $sessionId")
      return
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

          when (
            SignalingDecision.onOffer(
              readiness = ProjectionReadiness.from(
                hasProjectionIntent = mediaProjectionResultData != null,
                isServiceRunning = MediaProjectionService.isRunning
              ),
              activeSessionMatches = isActiveSession(sessionId)
            )
          ) {
            SignalingDecision.START_NEGOTIATION -> initializeWebRTC(sessionId, sdpDescription, sendResponse)
            SignalingDecision.QUEUE_AND_REQUEST_PERMISSION -> {
              queuePendingOffer(sessionId, sdpDescription, sendResponse)
              sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
              requestScreenCapturePermission()
            }
            SignalingDecision.QUEUE_AND_SEND_STATUS -> {
              queuePendingOffer(sessionId, sdpDescription, sendResponse)
              sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
              waitForCaptureReadyThenResumePendingOffer()
            }
            SignalingDecision.IGNORE_INACTIVE -> {
              CrashDiagnostics.recordEvent(this, "Ignoring offer for inactive sessionId=$sessionId.")
            }
          }
        }
        "ICE_CANDIDATE" -> {
          CrashDiagnostics.recordEvent(this, "ICE candidate received for sessionId=$sessionId.")
          Log.d("WebRTC", "ICE Candidate received.")
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

  // WebRTC PeerConnection 초기설정 및 SDP Answer 생성
  private fun initializeWebRTC(sessionId: Int, remoteSdp: SessionDescription, sendResponse: (String) -> Unit) {
    val readiness = ProjectionReadiness.from(
      hasProjectionIntent = mediaProjectionResultData != null,
      isServiceRunning = MediaProjectionService.isRunning
    )
    if (readiness != ProjectionReadiness.READY) {
      CrashDiagnostics.recordEvent(this, "Capture not ready; deferring offer for sessionId=$sessionId readiness=$readiness.")
      queuePendingOffer(sessionId, remoteSdp, sendResponse)
      sendResponse(buildStatusMessage(captureReady = false, message = "WAITING_FOR_SCREEN_CAPTURE"))
      if (readiness == ProjectionReadiness.MISSING_PERMISSION) {
        requestScreenCapturePermission()
      } else {
        waitForCaptureReadyThenResumePendingOffer()
      }
      return
    }

    try {
      CrashDiagnostics.recordEvent(this, "Initializing WebRTC for sessionId=$sessionId.")
      // 1. PeerConnectionFactory 초기화
      val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
        .createInitializationOptions()
      PeerConnectionFactory.initialize(initOptions)

      // Create EGL context (required for hardware encoder/decoder and SurfaceTextureHelper)
      eglBase = org.webrtc.EglBase.create()
      val eglContext = eglBase!!.eglBaseContext

      val factoryOptions = PeerConnectionFactory.Options()
      val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
      val decoderFactory = DefaultVideoDecoderFactory(eglContext)
      
      peerConnectionFactory = PeerConnectionFactory.builder()
        .setOptions(factoryOptions)
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(decoderFactory)
        .createPeerConnectionFactory()

      // 2. ICE Servers 설정 (1:1이므로 간단한 STUN 1개면 동작)
      val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
      )
      
      val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
      }
      remoteDescriptionSet = false

      // 3. PeerConnection 생성
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
              Log.w("WebRTC", "Rejected DataChannel with unexpected label: ${dc.label()}")
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
	                  val json = org.json.JSONObject(text)
	                  if (ControlEventValidator.isValid(json)) {
                      markViewerActivity()
                      val service = GalaxyMirrorAccessibilityService.instance
                      if (service == null) {
                        Log.w("WebRTC", "AccessibilityService not connected yet!")
                        sendControlAck(
                          dc,
                          ControlEventResult(
                            seq = json.controlSeq(),
                            type = json.optString("type", "unknown"),
                            applied = false,
                            message = "ACCESSIBILITY_SERVICE_NOT_READY",
                          ),
                        )
                      } else {
                        service.handleControlEvent(json) { result ->
                          sendControlAck(dc, result)
                        }
                      }
	                  } else {
	                    Log.w("WebRTC", "Rejected invalid control event: $text")
                      sendControlAck(
                        dc,
                        ControlEventResult(
                          seq = json.controlSeq(),
                          type = json.optString("type", "unknown"),
                          applied = false,
                          message = "CONTROL_EVENT_REJECTED",
                        ),
                      )
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

      // 4. 화면 미디어 캡처 소스 & 비디오 트랙 생성
      surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
      val videoSource = peerConnectionFactory?.createVideoSource(true)
      videoTrack = peerConnectionFactory?.createVideoTrack("video_track_id", videoSource)

      // 미디어 프로젝션 시스템 연동
      val projectionIntent = mediaProjectionResultData
      if (projectionIntent == null) {
        CrashDiagnostics.recordEvent(this, "MediaProjection intent is null before ScreenCapturerAndroid.")
        Log.e("WebRTC", "MediaProjection intent is null! Cannot start screen capture.")
        return
      }
      videoCapturer = ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {
        override fun onStop() {
          CrashDiagnostics.recordEvent(this@MainActivity.filesDir, "MediaProjection stopped inside ScreenCapturerAndroid callback.")
          Log.d("WebRTC", "MediaProjection stopped inside capturer.")
          handleScreenCaptureReauthorizationRequired(
            sessionId = sessionId,
            sendResponse = sendResponse,
            diagnosticReason = "ScreenCapturerAndroid callback",
            stopCapturer = false,
          )
        }
      })

      videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)

      val streamNetwork = currentStreamNetworkTransport()
      val streamProfile = AdaptiveStreamQuality.resolve(streamQualityMode, streamNetwork, viewerActivityState)
      runOnMainThread {
        streamQualityNetwork = streamNetwork
        streamQualityProfile = streamProfile
      }
      CrashDiagnostics.recordEvent(
        this,
        "Calling ScreenCapturerAndroid.startCapture(${streamProfile.width}, ${streamProfile.height}, ${streamProfile.fps}) for quality=${streamProfile.mode.wireValue}.",
      )
      try {
        videoCapturer?.startCapture(streamProfile.width, streamProfile.height, streamProfile.fps)
      } catch (e: Exception) {
        CrashDiagnostics.recordCaughtException(this.filesDir, "ScreenCapturerAndroid.startCapture", e)
        Log.e("WebRTC", "ScreenCapturerAndroid.startCapture failed: ${e.message}", e)
        handleScreenCaptureReauthorizationRequired(
          sessionId = sessionId,
          sendResponse = sendResponse,
          diagnosticReason = "startCapture failed",
          stopCapturer = false,
        )
        return
      }
      CrashDiagnostics.recordEvent(this, "ScreenCapturerAndroid.startCapture returned successfully.")
      onMirroringSessionStarted()

      // 5. PeerConnection에 비디오 트랙 추가
      videoSender = peerConnection?.addTrack(videoTrack, listOf("video_stream_id"))
      applyStreamQualityProfile(streamProfile, reason = "WebRTC start")

      // 6. Remote Description (Offer) 적용 및 Answer 생성
      peerConnection?.setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {
          remoteDescriptionSet = true
          flushPendingRemoteIceCandidates()
          Log.d("WebRTC", "SetRemoteDescription success. Creating Answer...")
          peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
              desc?.let {
                peerConnection?.setLocalDescription(object : SdpObserver {
                  override fun onCreateSuccess(desc: SessionDescription?) {}
                  override fun onSetSuccess() {
                    Log.d("WebRTC", "SetLocalDescription success. Sending Answer...")
                    val json = org.json.JSONObject().apply {
                      put("type", "ANSWER")
                      put("payload", org.json.JSONObject().apply {
                        put("type", "answer")
                        put("sdp", it.description)
                      })
                    }
                    sendResponse(json.toString())
                  }
                  override fun onCreateFailure(reason: String?) {
                    Log.e("WebRTC", "setLocalDescription onCreateFailure: $reason")
                  }
                  override fun onSetFailure(reason: String?) {
                    Log.e("WebRTC", "setLocalDescription onSetFailure: $reason")
                  }
                }, it)
              }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) {
              Log.e("WebRTC", "createAnswer onCreateFailure: $reason")
            }
            override fun onSetFailure(reason: String?) {
              Log.e("WebRTC", "createAnswer onSetFailure: $reason")
            }
          }, MediaConstraints())
        }
        override fun onCreateFailure(reason: String?) {
          Log.e("WebRTC", "setRemoteDescription onCreateFailure: $reason")
        }
        override fun onSetFailure(reason: String?) {
          Log.e("WebRTC", "setRemoteDescription onSetFailure: $reason")
        }
      }, remoteSdp)

    } catch (e: Exception) {
      CrashDiagnostics.recordCaughtException(this.filesDir, "WebRTC negotiation initialize", e)
      Log.e("WebRTC", "Error during WebRTC negotiation initialize: ${e.message}", e)
    }
  }

  private fun handleScreenCaptureReauthorizationRequired(
    sessionId: Int,
    sendResponse: (String) -> Unit,
    diagnosticReason: String,
    stopCapturer: Boolean,
  ) {
    val shouldHandle =
      synchronized(sessionLock) {
        if (!mirrorSessionState.isActive(sessionId)) {
          false
        } else {
          pendingOffer = null
          mirrorSessionState = mirrorSessionState.projectionStopped(sessionId)
          activeSessionId = mirrorSessionState.activeSessionId
          true
        }
      }

    if (!shouldHandle) {
      CrashDiagnostics.recordEvent(
        this,
        "Ignoring stale MediaProjection reauthorization event for sessionId=$sessionId reason=$diagnosticReason.",
      )
      return
    }

    mediaProjectionResultData = null
    sendResponse(
      buildStatusMessage(
        captureReady = false,
        message = "SCREEN_CAPTURE_REAUTH_REQUIRED",
      ),
    )
    cleanupWebRTCResources(stopProjectionService = true, stopCapturer = stopCapturer)
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

  private fun cleanupWebRTCResources(stopProjectionService: Boolean, stopCapturer: Boolean = true) {
    val failures =
      CleanupStepRunner.run(
        listOf(
          CleanupStep("control channel close") { controlChannel?.close() },
          CleanupStep("video capturer stop") {
            if (stopCapturer) {
              videoCapturer?.stopCapture()
            }
          },
          CleanupStep("video capturer dispose") { videoCapturer?.dispose() },
          CleanupStep("surface texture helper dispose") { surfaceTextureHelper?.dispose() },
          CleanupStep("peer connection close") { peerConnection?.close() },
          CleanupStep("peer connection factory dispose") { peerConnectionFactory?.dispose() },
          CleanupStep("egl release") { eglBase?.release() },
        )
      )

    failures.forEach { failure ->
      CrashDiagnostics.recordCaughtException(filesDir, "WebRTC cleanup ${failure.name}", failure.throwable)
      Log.e("WebRTC", "Error during WebRTC cleanup step ${failure.name}", failure.throwable)
    }

    synchronized(pendingRemoteIceCandidates) {
      pendingRemoteIceCandidates.clear()
    }
    controlChannel = null
    videoSender = null
    videoCapturer = null
    videoTrack = null
    surfaceTextureHelper = null
    peerConnection = null
    peerConnectionFactory = null
    eglBase = null
    remoteDescriptionSet = false

    if (stopProjectionService) {
      stopService(Intent(this, MediaProjectionService::class.java))
      mediaProjectionResultData = null
    } else {
      MediaProjectionService.instance?.setKeepScreenAwake(
        screenAwakeSettings.shouldKeepScreenAwake(isMirroringActiveForScreenSettings()),
      )
    }
    applyScreenAwakeWindowFlag()
    applyBrightnessMinimizationForCurrentState()
    Log.d("WebRTC", "WebRTC resources cleaned up with ${failures.size} cleanup failures.")
  }

  override fun onDestroy() {
    super.onDestroy()
    idleQualityJob?.cancel()
    synchronized(sessionLock) {
      activeSessionId = 0
      mirrorSessionState = MirrorSessionState()
      pendingOffer = null
    }
    
    // Ktor Server stop
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        server?.stop(1000, 2000)
        Log.d("KtorServer", "Ktor Server stopped.")
      } catch (e: Exception) {
        Log.e("KtorServer", "Error stopping Ktor Server", e)
      }
    }

    cleanupWebRTCResources(
      stopProjectionService = CleanupPolicy.shouldStopProjection(CleanupReason.ACTIVITY_DESTROYED)
    )
  }
}
