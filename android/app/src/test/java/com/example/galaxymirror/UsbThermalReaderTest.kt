package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class UsbThermalReaderTest {
    @Test
    fun mapsAndroidThermalStatusConstants() {
        assertEquals(UsbThermalStatus.NORMAL, UsbThermalReader.mapStatus(0))
        assertEquals(UsbThermalStatus.LIGHT, UsbThermalReader.mapStatus(1))
        assertEquals(UsbThermalStatus.MODERATE, UsbThermalReader.mapStatus(2))
        assertEquals(UsbThermalStatus.SEVERE, UsbThermalReader.mapStatus(3))
        assertEquals(UsbThermalStatus.CRITICAL, UsbThermalReader.mapStatus(4))
        assertEquals(UsbThermalStatus.EMERGENCY, UsbThermalReader.mapStatus(5))
        assertEquals(UsbThermalStatus.SHUTDOWN, UsbThermalReader.mapStatus(6))
        assertEquals(UsbThermalStatus.UNKNOWN, UsbThermalReader.mapStatus(999))
    }
}
