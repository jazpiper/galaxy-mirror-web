package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.json.JSONObject
import org.junit.Test

class UsbStreamProfileTest {
    @Test
    fun videoCodecDefaultsToJpegForUnknownWireValues() {
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.preferredForChrome())
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.fromWireValue("H264"))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue(null))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("unknown"))
    }

    @Test
    fun autoResolvesToBalancedCoolingProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO)

        assertEquals(UsbStreamProfileTier.BALANCED, profile.tier)
        assertEquals(540, profile.width)
        assertEquals(1200, profile.height)
        assertEquals(18, profile.fps)
        assertEquals(75, profile.jpegQuality)
    }

    @Test
    fun dataSaverResolvesToCoolProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)

        assertEquals(UsbStreamProfileTier.COOL, profile.tier)
        assertEquals(360, profile.width)
        assertEquals(800, profile.height)
        assertEquals(12, profile.fps)
        assertEquals(65, profile.jpegQuality)
    }

    @Test
    fun highResolvesToClearCoolingProfile() {
        val profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.HIGH)

        assertEquals(UsbStreamProfileTier.CLEAR, profile.tier)
        assertEquals(720, profile.width)
        assertEquals(1600, profile.height)
        assertEquals(24, profile.fps)
        assertEquals(85, profile.jpegQuality)
    }

    @Test
    fun statusJsonReportsActualUsbProfileForAutoMode() {
        val json = JSONObject(UsbStreamProfileCodec.toStatusJson(StreamQualityMode.AUTO))

        assertEquals("AUTO", json.getString("selectedMode"))
        assertEquals("STANDARD", json.getString("effectiveMode"))
        assertEquals("표준", json.getString("effectiveLabel"))
        assertEquals("BALANCED", json.getString("effectiveTier"))
        assertEquals(540, json.getInt("effectiveWidth"))
        assertEquals(1200, json.getInt("effectiveHeight"))
        assertEquals(18, json.getInt("effectiveFps"))
        assertEquals(75, json.getInt("jpegQuality"))
        assertEquals("heat-first", json.getString("policy"))
    }

    @Test
    fun h264ModeLadderMapsQualityModesToEncoderProfiles() {
        val dataSaver = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)
        val auto = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.AUTO)
        val standard = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.STANDARD)
        val high = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.HIGH)

        assertEquals(UsbStreamProfileTier.COOL, dataSaver.tier)
        assertEquals(540, dataSaver.width)
        assertEquals(1200, dataSaver.height)
        assertEquals(24, dataSaver.fps)
        assertEquals(2_500_000, dataSaver.bitrateBps)

        assertEquals(UsbStreamProfileTier.BALANCED, auto.tier)
        assertEquals(UsbStreamProfileTier.BALANCED, standard.tier)
        assertEquals(720, standard.width)
        assertEquals(1600, standard.height)
        assertEquals(30, standard.fps)
        assertEquals(4_500_000, standard.bitrateBps)

        assertEquals(UsbStreamProfileTier.CLEAR, high.tier)
        assertEquals(1080, high.width)
        assertEquals(2400, high.height)
        assertEquals(60, high.fps)
        assertEquals(8_000_000, high.bitrateBps)
    }

    @Test
    fun h264StatusJsonReportsSelectedModeAndEncoderProfile() {
        val json = JSONObject(UsbH264StreamProfileCodec.toStatusJson(StreamQualityMode.AUTO))

        assertEquals("h264", json.getString("codec"))
        assertEquals("AUTO", json.getString("selectedMode"))
        assertEquals("자동", json.getString("selectedLabel"))
        assertEquals("STANDARD", json.getString("effectiveMode"))
        assertEquals("표준", json.getString("effectiveLabel"))
        assertEquals("BALANCED", json.getString("effectiveTier"))
        assertEquals(720, json.getInt("width"))
        assertEquals(1600, json.getInt("height"))
        assertEquals(30, json.getInt("fps"))
        assertEquals(4_500_000, json.getInt("bitrateBps"))
        assertEquals(1, json.getInt("keyFrameIntervalSeconds"))
        assertEquals("video/avc", json.getString("mime"))
        assertEquals("hardware-h264", json.getString("policy"))
    }
}
