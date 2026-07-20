package com.example.galaxymirror

import org.json.JSONObject

data class UsbH264StreamProfile(
    val tier: UsbStreamProfileTier,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val keyFrameIntervalSeconds: Int = 1,
    val mime: String = "video/avc",
    val policy: String = "hardware-h264",
)

object UsbH264StreamProfilePolicy {
    fun resolve(selectedMode: StreamQualityMode): UsbH264StreamProfile =
        when (selectedMode) {
            StreamQualityMode.DATA_SAVER ->
                resolveTier(UsbStreamProfileTier.COOL)
            StreamQualityMode.HIGH ->
                resolveTier(UsbStreamProfileTier.CLEAR)
            StreamQualityMode.AUTO,
            StreamQualityMode.STANDARD ->
                resolveTier(UsbStreamProfileTier.BALANCED)
        }

    fun resolveTier(tier: UsbStreamProfileTier): UsbH264StreamProfile =
        when (tier) {
            UsbStreamProfileTier.COOL ->
                UsbH264StreamProfile(
                    tier = tier,
                    width = 540,
                    height = 1200,
                    fps = 18,
                    bitrateBps = 1_800_000,
                )
            UsbStreamProfileTier.BALANCED ->
                UsbH264StreamProfile(
                    tier = tier,
                    width = 720,
                    height = 1600,
                    fps = 24,
                    bitrateBps = 3_000_000,
                )
            UsbStreamProfileTier.CLEAR ->
                UsbH264StreamProfile(
                    tier = tier,
                    width = 1080,
                    height = 2400,
                    fps = 30,
                    bitrateBps = 6_000_000,
                )
        }
}

object UsbH264StreamProfileCodec {
    fun toStatusJson(selectedMode: StreamQualityMode): String {
        val effectiveMode =
            if (selectedMode == StreamQualityMode.AUTO) {
                StreamQualityMode.STANDARD
            } else {
                selectedMode
            }
        val profile = UsbH264StreamProfilePolicy.resolve(selectedMode)

        return JSONObject()
            .put("codec", UsbVideoCodec.H264.wireValue)
            .put("codecLabel", UsbVideoCodec.H264.label)
            .put("selectedMode", selectedMode.wireValue)
            .put("selectedLabel", selectedMode.koreanLabel)
            .put("effectiveMode", effectiveMode.wireValue)
            .put("effectiveLabel", effectiveMode.koreanLabel)
            .put("effectiveTier", profile.tier.name)
            .put("effectiveWidth", profile.width)
            .put("effectiveHeight", profile.height)
            .put("effectiveFps", profile.fps)
            .put("width", profile.width)
            .put("height", profile.height)
            .put("fps", profile.fps)
            .put("bitrateBps", profile.bitrateBps)
            .put("keyFrameIntervalSeconds", profile.keyFrameIntervalSeconds)
            .put("mime", profile.mime)
            .put("policy", profile.policy)
            .toString()
    }
}
