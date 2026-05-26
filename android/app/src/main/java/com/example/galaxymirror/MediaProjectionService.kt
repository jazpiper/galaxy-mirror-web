package com.example.galaxymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MediaProjectionService : Service() {

    private val binder = LocalBinder()

    companion object {
        private const val CHANNEL_ID = "GalaxyMirrorCaptureChannel"
        private const val NOTIFICATION_ID = 2026
        private const val TAG = "MediaProjectionService"
        
        var isRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called.")
        
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val resultData =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("resultData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("resultData")
            }

        if (resultCode != -1 && resultData != null) {
            try {
                // Foreground Service 시작 (안드로이드 10+ 미디어 프로젝션 타입 명시)
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                isRunning = true
                Log.d(TAG, "MediaProjection foreground service is ready.")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MediaProjection foreground service: ${e.message}", e)
                stopSelf()
            }
        } else {
            Log.e(TAG, "Invalid intent data provided to start command.")
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
        Log.d(TAG, "MediaProjectionService stopped.")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Galaxy Mirror Active")
            .setContentText("실시간 맥북 화면 미러링 및 제어 서비스 작동 중")
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
                description = "갤럭시 미러링 화면 캡처 상태 알림 채널"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
