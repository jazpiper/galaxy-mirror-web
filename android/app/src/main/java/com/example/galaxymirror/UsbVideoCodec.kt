package com.example.galaxymirror

enum class UsbVideoCodec(
    val wireValue: String,
    val label: String,
) {
    H264("h264", "H.264"),
    JPEG("jpeg", "JPEG");

    companion object {
        fun preferredForChrome(): UsbVideoCodec = H264

        fun fromWireValue(value: String?): UsbVideoCodec =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: JPEG
    }
}
