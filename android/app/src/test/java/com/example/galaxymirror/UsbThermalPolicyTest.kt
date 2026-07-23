package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class UsbThermalPolicyTest {
    @Test
    fun normalThermalKeepsSelectedProfile() {
        val profile =
            UsbThermalPolicy.resolve(
                selectedMode = StreamQualityMode.HIGH,
                thermalStatus = UsbThermalStatus.NORMAL,
                viewerIdle = false,
            )

        assertEquals(UsbStreamProfileTier.CLEAR, profile.tier)
        assertEquals(24, profile.fps)
    }

    @Test
    fun lightThermalClampsHighToBalanced() {
        val profile =
            UsbThermalPolicy.resolve(
                selectedMode = StreamQualityMode.HIGH,
                thermalStatus = UsbThermalStatus.LIGHT,
                viewerIdle = false,
            )

        assertEquals(UsbStreamProfileTier.BALANCED, profile.tier)
    }

    @Test
    fun moderateThermalClampsToCool() {
        val profile =
            UsbThermalPolicy.resolve(
                selectedMode = StreamQualityMode.HIGH,
                thermalStatus = UsbThermalStatus.MODERATE,
                viewerIdle = false,
            )

        assertEquals(UsbStreamProfileTier.COOL, profile.tier)
        assertEquals(12, profile.fps)
    }

    @Test
    fun severeThermalUsesEmergencyCoolFps() {
        val profile =
            UsbThermalPolicy.resolve(
                selectedMode = StreamQualityMode.HIGH,
                thermalStatus = UsbThermalStatus.SEVERE,
                viewerIdle = false,
            )

        assertEquals(UsbStreamProfileTier.COOL, profile.tier)
        assertEquals(6, profile.fps)
    }

    @Test
    fun idleViewerClampsToCool() {
        val profile =
            UsbThermalPolicy.resolve(
                selectedMode = StreamQualityMode.HIGH,
                thermalStatus = UsbThermalStatus.NORMAL,
                viewerIdle = true,
            )

        assertEquals(UsbStreamProfileTier.COOL, profile.tier)
    }
}
