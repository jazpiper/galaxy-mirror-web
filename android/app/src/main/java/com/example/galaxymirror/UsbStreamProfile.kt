package com.example.galaxymirror

import org.json.JSONObject

data class UsbStreamProfile(
    val tier: UsbStreamProfileTier,
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
    val policy: String = "heat-first",
)

enum class UsbStreamProfileTier {
    COOL,
    BALANCED,
    CLEAR,
}

object UsbStreamProfilePolicy {
    fun resolve(selectedMode: StreamQualityMode): UsbStreamProfile =
        when (selectedMode) {
            StreamQualityMode.DATA_SAVER ->
                resolveTier(UsbStreamProfileTier.COOL)
            StreamQualityMode.HIGH ->
                resolveTier(UsbStreamProfileTier.CLEAR)
            StreamQualityMode.AUTO,
            StreamQualityMode.STANDARD ->
                resolveTier(UsbStreamProfileTier.BALANCED)
        }

    fun resolveTier(
        tier: UsbStreamProfileTier,
        emergencyFps: Boolean = false,
    ): UsbStreamProfile =
        when (tier) {
            UsbStreamProfileTier.COOL ->
                UsbStreamProfile(
                    tier = tier,
                    width = 360,
                    height = 800,
                    fps = if (emergencyFps) 6 else 12,
                    jpegQuality = 65,
                )
            UsbStreamProfileTier.BALANCED ->
                UsbStreamProfile(
                    tier = tier,
                    width = 540,
                    height = 1200,
                    fps = 18,
                    jpegQuality = 75,
                )
            UsbStreamProfileTier.CLEAR ->
                UsbStreamProfile(
                    tier = tier,
                    width = 720,
                    height = 1600,
                    fps = 24,
                    jpegQuality = 85,
                )
        }
}

object UsbStreamProfileCodec {
    fun toStatusJson(selectedMode: StreamQualityMode): String {
        val effectiveMode =
            if (selectedMode == StreamQualityMode.AUTO) {
                StreamQualityMode.STANDARD
            } else {
                selectedMode
            }
        val profile = UsbStreamProfilePolicy.resolve(selectedMode)

        return JSONObject()
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
            .put("jpegQuality", profile.jpegQuality)
            .put("policy", profile.policy)
            .toString()
    }
}
