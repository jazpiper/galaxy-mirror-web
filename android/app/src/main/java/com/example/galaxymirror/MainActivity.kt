package com.example.galaxymirror

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
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
import com.example.galaxymirror.theme.GalaxyMirrorTheme

class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "MainActivity"
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var accessibilityEnabled by mutableStateOf(false)
    private lateinit var favoriteAppsRepository: FavoriteAppsRepository
    private var favoriteApps by mutableStateOf<List<FavoriteApp>>(emptyList())
    private var launchableApps by mutableStateOf<List<FavoriteApp>>(emptyList())

    private var viewerAccessToken by mutableStateOf("")
    private var screenAwakeSettings by mutableStateOf(ScreenAwakeSettings())
    private var canWriteSystemSettings by mutableStateOf(false)
    private var streamQualityMode by mutableStateOf(StreamQualityMode.AUTO)
    private var streamQualityNetwork by mutableStateOf(StreamNetworkTransport.OTHER)
    private var streamQualityProfile by mutableStateOf(
        StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER)
    )
    private var mirrorSessionState by mutableStateOf(MirrorSessionState())
    private var activeSessionId by mutableStateOf(0)

    // Service binding
    private var mediaProjectionService: MediaProjectionService? = null
    private var isBound = false
    private var screenCaptureRequestInFlight = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaProjectionService.LocalBinder
            val s = binder.getService()
            mediaProjectionService = s
            isBound = true
            s.registerListener(serviceStateListener)
            Log.d(TAG, "MediaProjectionService bound successfully.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaProjectionService?.unregisterListener(serviceStateListener)
            mediaProjectionService = null
            isBound = false
            Log.d(TAG, "MediaProjectionService unbound.")
        }
    }

    private val serviceStateListener = object : MediaProjectionService.StateListener {
        override fun onStateChanged() {
            val s = mediaProjectionService ?: return
            streamQualityMode = s.streamQualityMode
            streamQualityNetwork = s.streamQualityNetwork
            streamQualityProfile = s.streamQualityProfile
            viewerAccessToken = s.viewerAccessToken
            mirrorSessionState = s.mirrorSessionState
            activeSessionId = s.activeSessionId
            screenAwakeSettings = s.screenAwakeSettings
            accessibilityEnabled = AccessibilitySettingsState.isGalaxyMirrorServiceEnabled(this@MainActivity)
            canWriteSystemSettings = s.screenBrightnessController.canWriteSystemSettings()
            applyScreenAwakeWindowFlag()
            if (s.screenCapturePermissionRequired && !screenCaptureRequestInFlight) {
                requestScreenCapturePermission()
            }
        }

        override fun onScreenCapturePermissionRequired() {
            requestScreenCapturePermission()
        }
    }

    // Screen capture permission request launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        screenCaptureRequestInFlight = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CrashDiagnostics.recordEvent(this, "Screen capture permission granted.")
            startMediaProjectionService(result.resultCode, result.data!!)
        } else {
            CrashDiagnostics.recordEvent(this, "Screen capture permission denied by user.")
            Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            mediaProjectionService?.disconnectMirror()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.install(applicationContext)
        CrashDiagnostics.recordProcessExitReasons(applicationContext)
        CrashDiagnostics.recordEvent(this, "MainActivity.onCreate")

        favoriteAppsRepository = FavoriteAppsRepository(applicationContext)
        refreshAccessibilityEnabled()
        refreshFavoriteApps()

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

        // Start Ktor and signaling hosting in MediaProjectionService immediately
        val startServiceIntent = Intent(this, MediaProjectionService::class.java)
        startService(startServiceIntent)
        bindService(startServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Request screen capture permission immediately
        requestScreenCapturePermission()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityEnabled()
        refreshFavoriteApps()
        applyScreenAwakeWindowFlag()
        mediaProjectionService?.notifyStateChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            mediaProjectionService?.unregisterListener(serviceStateListener)
            unbindService(serviceConnection)
            isBound = false
        }
        CrashDiagnostics.recordEvent(this, "MainActivity.onDestroy")
    }

    private fun refreshAccessibilityEnabled() {
        accessibilityEnabled = AccessibilitySettingsState.isGalaxyMirrorServiceEnabled(this)
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
        mediaProjectionService?.updateScreenAwakeSettings(settings)
        applyScreenAwakeWindowFlag()
    }

    private fun updateStreamQualityMode(mode: StreamQualityMode) {
        mediaProjectionService?.updateStreamQualityMode(mode)
    }

    private fun applyScreenAwakeWindowFlag() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { applyScreenAwakeWindowFlag() }
            return
        }

        val service = mediaProjectionService
        val shouldKeepScreenAwake = if (service != null) {
            service.screenAwakeSettings.shouldKeepScreenAwake(service.isMirroringActive())
        } else {
            false
        }

        if (shouldKeepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        if (mediaProjectionService?.isMirroringActive() == true) {
            Log.d(TAG, "Screen capture request skipped: Mirroring already active.")
            return
        }
        CrashDiagnostics.recordEvent(this, "Requesting screen capture permission.")
        mediaProjectionManager?.createScreenCaptureIntent()?.let { intent ->
            screenCaptureRequestInFlight = true
            screenCaptureLauncher.launch(intent)
        } ?: run {
            screenCaptureRequestInFlight = false
            CrashDiagnostics.recordEvent(this, "MediaProjectionManager unavailable.")
        }
    }

    private fun openAccessibilitySettings() {
        refreshAccessibilityEnabled()
        if (accessibilityEnabled) {
            Toast.makeText(this, "접근성 입력이 이미 활성화되어 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        CrashDiagnostics.recordEvent(this, "Opening accessibility settings.")
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
        CrashDiagnostics.recordEvent(this, "Opening app info settings.")
        val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
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
        val s = mediaProjectionService ?: return
        if (s.screenBrightnessController.canWriteSystemSettings()) {
            Toast.makeText(this, "시스템 설정 수정 권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        CrashDiagnostics.recordEvent(this, "Opening write settings permission screen.")
        val writeSettingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
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
        mediaProjectionService?.disconnectMirror()
    }

    private fun startMediaProjectionService(resultCode: Int, data: Intent) {
        CrashDiagnostics.recordEvent(this, "Starting MediaProjectionService foreground mode with resultCode=$resultCode.")
        val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("resultData", data)
            putExtra(
                MediaProjectionService.EXTRA_KEEP_SCREEN_AWAKE,
                mediaProjectionService?.screenAwakeSettings?.shouldKeepScreenAwake(true) ?: false
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
