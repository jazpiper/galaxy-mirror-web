package com.example.galaxymirror

enum class UsbThermalStatus {
    UNKNOWN,
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

object UsbThermalPolicy {
    fun resolve(
        selectedMode: StreamQualityMode,
        thermalStatus: UsbThermalStatus,
        viewerIdle: Boolean,
    ): UsbStreamProfile {
        val selected = UsbStreamProfilePolicy.resolve(selectedMode)
        val maxTier =
            when {
                viewerIdle -> UsbStreamProfileTier.COOL
                thermalStatus == UsbThermalStatus.LIGHT -> UsbStreamProfileTier.BALANCED
                thermalStatus == UsbThermalStatus.MODERATE -> UsbStreamProfileTier.COOL
                thermalStatus.isSevereOrWorse() -> UsbStreamProfileTier.COOL
                else -> selected.tier
            }
        val targetTier = selected.tier.coerceAtMost(maxTier)
        return UsbStreamProfilePolicy.resolveTier(
            tier = targetTier,
            emergencyFps = thermalStatus.isSevereOrWorse(),
        )
    }
}

private fun UsbThermalStatus.isSevereOrWorse(): Boolean =
    when (this) {
        UsbThermalStatus.SEVERE,
        UsbThermalStatus.CRITICAL,
        UsbThermalStatus.EMERGENCY,
        UsbThermalStatus.SHUTDOWN -> true
        UsbThermalStatus.UNKNOWN,
        UsbThermalStatus.NORMAL,
        UsbThermalStatus.LIGHT,
        UsbThermalStatus.MODERATE -> false
    }
