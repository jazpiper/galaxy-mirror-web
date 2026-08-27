@file:Suppress("UNUSED_IMPORT")
package com.example.galaxymirror

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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

    // Service binding and reactive state flow
    private var mediaProjectionService by mutableStateOf<MediaProjectionService?>(null)
    private var isBound = false
    private var screenCaptureRequestInFlight = false
    private var serviceStateJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaProjectionService.LocalBinder
            val s = binder.getService()
            mediaProjectionService = s
            isBound = true
            Log.d(TAG, "MediaProjectionService bound successfully.")

            // Start observing the serviceState flow reactively
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    s.serviceState.collect { state ->
                        accessibilityEnabled = AccessibilitySettingsState.isGalaxyMirrorServiceEnabled(this@MainActivity)
                        applyScreenAwakeWindowFlag()
                        if (state.screenCapturePermissionRequired && !screenCaptureRequestInFlight) {
                            requestScreenCapturePermission()
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceStateJob?.cancel()
            serviceStateJob = null
            mediaProjectionService = null
            isBound = false
            Log.d(TAG, "MediaProjectionService unbound.")
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
                    val service = mediaProjectionService
                    val serviceState by if (service != null) {
                        service.serviceState.collectAsStateWithLifecycle()
                    } else {
                        remember { mutableStateOf(MirrorServiceState()) }
                    }

                    MainNavigation(
                        accessibilityEnabled = accessibilityEnabled,
                        favoriteApps = favoriteApps,
                        launchableApps = launchableApps,
                        screenAwakeSettings = serviceState.screenAwakeSettings,
                        canWriteSystemSettings = serviceState.canWriteSystemSettings,
                        streamQualityMode = serviceState.streamQualityMode,
                        streamQualityNetwork = serviceState.streamQualityNetwork,
                        streamQualityProfile = serviceState.streamQualityProfile,
                        isMirroringActive = serviceState.isMirroringActive,
                        blackOverlayEnabled = serviceState.blackOverlayEnabled,
                        overlayPermissionReady = serviceState.overlayPermissionReady,
                        onAddFavoriteApp = ::addFavoriteApp,
                        onRemoveFavoriteApp = ::removeFavoriteApp,
                        onScreenAwakeSettingsChange = ::updateScreenAwakeSettings,
                        onStreamQualityModeChange = ::updateStreamQualityMode,
                        onToggleBlackOverlay = ::toggleBlackOverlay,
                        onOpenAppInfoSettings = ::openAppInfoSettings,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onOpenWriteSettings = ::openWriteSettings,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onDisconnect = ::disconnectMirror,
                    )
                }
            }
        }

        // Start Ktor and signaling hosting in MediaProjectionService immediately
        val startServiceIntent = Intent(this, MediaProjectionService::class.java)
        startService(startServiceIntent)
        bindService(startServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityEnabled()
        refreshFavoriteApps()
        applyScreenAwakeWindowFlag()
        mediaProjectionService?.updateServiceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceStateJob?.cancel()
        serviceStateJob = null
        if (isBound) {
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
            service.serviceState.value.screenAwakeSettings.shouldKeepScreenAwake(service.serviceState.value.isMirroringActive)
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
        val manager = mediaProjectionManager ?: return
        try {
            screenCaptureRequestInFlight = true
            CrashDiagnostics.recordEvent(this, "Launching screen capture permission intent.")
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            screenCaptureRequestInFlight = false
            CrashDiagnostics.recordCaughtException(filesDir, "requestScreenCapturePermission", e)
            Log.e(TAG, "Error launching screen capture permission intent: ${e.message}", e)
            Toast.makeText(this, "화면 공유 권한 요청을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleBlackOverlay(enabled: Boolean) {
        mediaProjectionService?.setBlackOverlayEnabled(enabled)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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
        if (s.serviceState.value.canWriteSystemSettings) {
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
                mediaProjectionService?.serviceState?.value?.screenAwakeSettings?.shouldKeepScreenAwake(true) ?: false
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
