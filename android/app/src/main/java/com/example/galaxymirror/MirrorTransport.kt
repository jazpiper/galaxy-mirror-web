package com.example.galaxymirror

enum class MirrorTransport(
    val wireValue: String,
    val koreanLabel: String,
) {
    TAILSCALE_WEBRTC("tailscale", "Tailscale"),
    USB_JPEG("usb", "USB");

    companion object {
        fun fromWireValue(value: String?): MirrorTransport? =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) }
    }
}
