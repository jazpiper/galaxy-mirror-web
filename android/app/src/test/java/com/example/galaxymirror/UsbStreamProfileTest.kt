package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.json.JSONObject
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

    @Test
    fun statusJsonReportsActualUsbProfileForAutoMode() {
        val json = JSONObject(UsbStreamProfileCodec.toStatusJson(StreamQualityMode.AUTO))

        assertEquals("AUTO", json.getString("selectedMode"))
        assertEquals("STANDARD", json.getString("effectiveMode"))
        assertEquals("표준", json.getString("effectiveLabel"))
        assertEquals(720, json.getInt("width"))
        assertEquals(1600, json.getInt("height"))
        assertEquals(10, json.getInt("fps"))
        assertEquals(70, json.getInt("jpegQuality"))
    }
}
