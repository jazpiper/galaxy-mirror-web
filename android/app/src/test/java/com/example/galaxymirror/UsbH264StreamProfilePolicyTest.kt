package com.example.galaxymirror

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbH264StreamProfilePolicyTest {

    @Test
    fun resolveMapsQualityModesToExpectedStreamProfiles() {
        val dataSaver = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER)
        assertEquals(UsbStreamProfileTier.COOL, dataSaver.tier)
        assertEquals(540, dataSaver.width)
        assertEquals(1200, dataSaver.height)
        assertEquals(24, dataSaver.fps)
        assertEquals(2_500_000, dataSaver.bitrateBps)

        val auto = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.AUTO)
        assertEquals(UsbStreamProfileTier.BALANCED, auto.tier)
        assertEquals(720, auto.width)
        assertEquals(1600, auto.height)
        assertEquals(30, auto.fps)
        assertEquals(4_500_000, auto.bitrateBps)

        val standard = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.STANDARD)
        assertEquals(UsbStreamProfileTier.BALANCED, standard.tier)
        assertEquals(720, standard.width)
        assertEquals(1600, standard.height)
        assertEquals(30, standard.fps)
        assertEquals(4_500_000, standard.bitrateBps)

        val high = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.HIGH)
        assertEquals(UsbStreamProfileTier.CLEAR, high.tier)
        assertEquals(1080, high.width)
        assertEquals(2400, high.height)
        assertEquals(60, high.fps)
        assertEquals(8_000_000, high.bitrateBps)
    }

    @Test
    fun resolveTierReturnsCorrectStreamProfileParameters() {
        val cool = UsbH264StreamProfilePolicy.resolveTier(UsbStreamProfileTier.COOL)
        assertEquals(UsbStreamProfileTier.COOL, cool.tier)
        assertEquals(540, cool.width)
        assertEquals(1200, cool.height)
        assertEquals(24, cool.fps)
        assertEquals(2_500_000, cool.bitrateBps)
        assertEquals(1, cool.keyFrameIntervalSeconds)
        assertEquals("video/avc", cool.mime)
        assertEquals("hardware-h264", cool.policy)

        val balanced = UsbH264StreamProfilePolicy.resolveTier(UsbStreamProfileTier.BALANCED)
        assertEquals(UsbStreamProfileTier.BALANCED, balanced.tier)
        assertEquals(720, balanced.width)
        assertEquals(1600, balanced.height)
        assertEquals(30, balanced.fps)
        assertEquals(4_500_000, balanced.bitrateBps)
        assertEquals(1, balanced.keyFrameIntervalSeconds)
        assertEquals("video/avc", balanced.mime)
        assertEquals("hardware-h264", balanced.policy)

        val clear = UsbH264StreamProfilePolicy.resolveTier(UsbStreamProfileTier.CLEAR)
        assertEquals(UsbStreamProfileTier.CLEAR, clear.tier)
        assertEquals(1080, clear.width)
        assertEquals(2400, clear.height)
        assertEquals(60, clear.fps)
        assertEquals(8_000_000, clear.bitrateBps)
        assertEquals(1, clear.keyFrameIntervalSeconds)
        assertEquals("video/avc", clear.mime)
        assertEquals("hardware-h264", clear.policy)
    }

    @Test
    fun statusJsonSerializesAllQualityModesCorrectly() {
        // Auto mode resolves to STANDARD as effectiveMode
        val autoJson = JSONObject(UsbH264StreamProfileCodec.toStatusJson(StreamQualityMode.AUTO))
        assertEquals("h264", autoJson.getString("codec"))
        assertEquals("H.264", autoJson.getString("codecLabel"))
        assertEquals("AUTO", autoJson.getString("selectedMode"))
        assertEquals("자동", autoJson.getString("selectedLabel"))
        assertEquals("STANDARD", autoJson.getString("effectiveMode"))
        assertEquals("표준", autoJson.getString("effectiveLabel"))
        assertEquals("BALANCED", autoJson.getString("effectiveTier"))
        assertEquals(720, autoJson.getInt("effectiveWidth"))
        assertEquals(1600, autoJson.getInt("effectiveHeight"))
        assertEquals(30, autoJson.getInt("effectiveFps"))
        assertEquals(720, autoJson.getInt("width"))
        assertEquals(1600, autoJson.getInt("height"))
        assertEquals(30, autoJson.getInt("fps"))
        assertEquals(4_500_000, autoJson.getInt("bitrateBps"))
        assertEquals(1, autoJson.getInt("keyFrameIntervalSeconds"))
        assertEquals("video/avc", autoJson.getString("mime"))
        assertEquals("hardware-h264", autoJson.getString("policy"))

        // Data saver mode
        val dataSaverJson = JSONObject(UsbH264StreamProfileCodec.toStatusJson(StreamQualityMode.DATA_SAVER))
        assertEquals("DATA_SAVER", dataSaverJson.getString("selectedMode"))
        assertEquals("저데이터", dataSaverJson.getString("selectedLabel"))
        assertEquals("DATA_SAVER", dataSaverJson.getString("effectiveMode"))
        assertEquals("저데이터", dataSaverJson.getString("effectiveLabel"))
        assertEquals("COOL", dataSaverJson.getString("effectiveTier"))
        assertEquals(540, dataSaverJson.getInt("width"))
        assertEquals(1200, dataSaverJson.getInt("height"))
        assertEquals(24, dataSaverJson.getInt("fps"))
        assertEquals(2_500_000, dataSaverJson.getInt("bitrateBps"))

        // High mode
        val highJson = JSONObject(UsbH264StreamProfileCodec.toStatusJson(StreamQualityMode.HIGH))
        assertEquals("HIGH", highJson.getString("selectedMode"))
        assertEquals("고화질", highJson.getString("selectedLabel"))
        assertEquals("HIGH", highJson.getString("effectiveMode"))
        assertEquals("고화질", highJson.getString("effectiveLabel"))
        assertEquals("CLEAR", highJson.getString("effectiveTier"))
        assertEquals(1080, highJson.getInt("width"))
        assertEquals(2400, highJson.getInt("height"))
        assertEquals(60, highJson.getInt("fps"))
        assertEquals(8_000_000, highJson.getInt("bitrateBps"))

        // Standard mode
        val standardJson = JSONObject(UsbH264StreamProfileCodec.toStatusJson(StreamQualityMode.STANDARD))
        assertEquals("STANDARD", standardJson.getString("selectedMode"))
        assertEquals("표준", standardJson.getString("selectedLabel"))
        assertEquals("STANDARD", standardJson.getString("effectiveMode"))
        assertEquals("표준", standardJson.getString("effectiveLabel"))
        assertEquals("BALANCED", standardJson.getString("effectiveTier"))
        assertEquals(720, standardJson.getInt("width"))
        assertEquals(1600, standardJson.getInt("height"))
        assertEquals(30, standardJson.getInt("fps"))
        assertEquals(4_500_000, standardJson.getInt("bitrateBps"))
    }
}
