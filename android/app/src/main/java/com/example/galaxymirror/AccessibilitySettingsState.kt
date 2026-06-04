package com.example.galaxymirror

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilitySettingsState {
    fun isGalaxyMirrorServiceEnabled(context: Context): Boolean {
        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        val service = ComponentName(context, GalaxyMirrorAccessibilityService::class.java)
        return containsEnabledService(
            enabledServices,
            service.flattenToString(),
            service.flattenToShortString()
        )
    }

    internal fun containsEnabledService(
        enabledServices: String?,
        vararg expectedComponents: String
    ): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        val expected = expectedComponents.toSet()
        return enabledServices.split(':').any { service -> service in expected }
    }
}
