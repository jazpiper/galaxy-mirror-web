package com.example.galaxymirror

import org.json.JSONObject

data class UsbStreamProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
)

object UsbStreamProfilePolicy {
    fun resolve(selectedMode: StreamQualityMode): UsbStreamProfile =
        when (selectedMode) {
            StreamQualityMode.DATA_SAVER ->
                UsbStreamProfile(width = 540, height = 1200, fps = 8, jpegQuality = 65)
            StreamQualityMode.HIGH ->
                UsbStreamProfile(width = 1080, height = 2400, fps = 12, jpegQuality = 75)
            StreamQualityMode.AUTO,
            StreamQualityMode.STANDARD ->
                UsbStreamProfile(width = 720, height = 1600, fps = 10, jpegQuality = 70)
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
            .put("width", profile.width)
            .put("height", profile.height)
            .put("fps", profile.fps)
            .put("jpegQuality", profile.jpegQuality)
            .toString()
    }
}
