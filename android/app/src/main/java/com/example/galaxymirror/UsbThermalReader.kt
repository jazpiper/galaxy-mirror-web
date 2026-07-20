package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

class UsbThermalReader(private val context: Context) {
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    fun readStatus(): UsbThermalStatus =
        mapStatus(powerManager?.currentThermalStatus ?: UNKNOWN_ANDROID_THERMAL_STATUS)

    fun readHeadroom(): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { powerManager?.getThermalHeadroom(10) }
                .getOrNull()
                ?.takeIf { it.isFinite() }
        } else {
            null
        }

    fun readBatteryTemperatureC(): Float? {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
        val temperatureTenthsC =
            batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (temperatureTenthsC == Int.MIN_VALUE) {
            null
        } else {
            temperatureTenthsC / 10f
        }
    }

    companion object {
        private const val UNKNOWN_ANDROID_THERMAL_STATUS = -1

        fun mapStatus(status: Int): UsbThermalStatus =
            when (status) {
                PowerManager.THERMAL_STATUS_NONE -> UsbThermalStatus.NORMAL
                PowerManager.THERMAL_STATUS_LIGHT -> UsbThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> UsbThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> UsbThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> UsbThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> UsbThermalStatus.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> UsbThermalStatus.SHUTDOWN
                else -> UsbThermalStatus.UNKNOWN
            }
    }
}
