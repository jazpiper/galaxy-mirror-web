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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class MediaProjectionService : Service() {

    private val binder = LocalBinder()

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
    }

    private var keepScreenAwake = false
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashDiagnostics.recordEvent(this, "MediaProjectionService.onCreate")
        createNotificationChannel()
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
            try {
                // Foreground Service 시작 (안드로이드 10+ 미디어 프로젝션 타입 명시)
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                isRunning = true
                updateWakeLock()
                CrashDiagnostics.recordEvent(this, "MediaProjection foreground service is ready.")
                Log.d(TAG, "MediaProjection foreground service is ready.")
            } catch (e: Exception) {
                CrashDiagnostics.recordCaughtException(filesDir, "MediaProjection foreground service startup", e)
                Log.e(TAG, "Error starting MediaProjection foreground service: ${e.message}", e)
                stopSelf()
            }
        } else {
            CrashDiagnostics.recordEvent(
                this,
                "Invalid MediaProjectionService start data: resultCode=$resultCode, hasResultData=${resultData != null}."
            )
            Log.e(TAG, "Invalid intent data provided to start command. resultCode=$resultCode, hasResultData=${resultData != null}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        releaseWakeLock()
        if (instance === this) {
            instance = null
        }
        CrashDiagnostics.recordEvent(this, "MediaProjectionService.onDestroy")
        Log.d(TAG, "MediaProjectionService stopped.")
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        keepScreenAwake = enabled
        updateWakeLock()
    }

    private fun updateWakeLock() {
        val shouldHoldWakeLock =
            MediaProjectionWakeLockPolicy.shouldHoldWakeLock(
                serviceRunning = isRunning,
                keepAwakeEnabled = keepScreenAwake,
            )
        if (shouldHoldWakeLock) {
            val currentWakeLock = wakeLock
            if (currentWakeLock?.isHeld == true) return
            wakeLock =
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidMirror:Projection")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            CrashDiagnostics.recordEvent(this, "MediaProjection partial wake lock acquired.")
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
