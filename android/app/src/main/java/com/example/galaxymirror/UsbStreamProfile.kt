package com.example.galaxymirror

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
