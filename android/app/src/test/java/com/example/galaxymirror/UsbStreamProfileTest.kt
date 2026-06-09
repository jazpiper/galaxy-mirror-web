package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class UsbStreamProfileTest {
    @Test
    fun autoUsesStandardUsbProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO)

        assertEquals(720, profile.width)
        assertEquals(1600, profile.height)
        assertEquals(10, profile.fps)
        assertEquals(70, profile.jpegQuality)
    }

    @Test
    fun dataSaverUsesLowerUsbProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)

        assertEquals(540, profile.width)
        assertEquals(1200, profile.height)
        assertEquals(8, profile.fps)
        assertEquals(65, profile.jpegQuality)
    }

    @Test
    fun highUsesCappedUsbProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.HIGH)

        assertEquals(1080, profile.width)
        assertEquals(2400, profile.height)
        assertEquals(12, profile.fps)
        assertEquals(75, profile.jpegQuality)
    }
}
