package com.example.galaxymirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTransportTest {

    @Test
    fun enumValues_haveExpectedProperties() {
        assertEquals("tailscale", MirrorTransport.TAILSCALE_WEBRTC.wireValue)
        assertEquals("Tailscale", MirrorTransport.TAILSCALE_WEBRTC.koreanLabel)

        assertEquals("usb", MirrorTransport.USB_JPEG.wireValue)
        assertEquals("USB JPEG", MirrorTransport.USB_JPEG.koreanLabel)

        assertEquals("usb_h264", MirrorTransport.USB_H264.wireValue)
        assertEquals("USB H.264", MirrorTransport.USB_H264.koreanLabel)
    }

    @Test
    fun fromWireValue_returnsCorrectTransport_forValidValues() {
        assertEquals(MirrorTransport.TAILSCALE_WEBRTC, MirrorTransport.fromWireValue("tailscale"))
        assertEquals(MirrorTransport.USB_JPEG, MirrorTransport.fromWireValue("usb"))
        assertEquals(MirrorTransport.USB_H264, MirrorTransport.fromWireValue("usb_h264"))
    }

    @Test
    fun fromWireValue_handlesWhitespaceAndCaseSensitivity() {
        assertEquals(MirrorTransport.TAILSCALE_WEBRTC, MirrorTransport.fromWireValue("  TAILSCALE  "))
        assertEquals(MirrorTransport.USB_JPEG, MirrorTransport.fromWireValue("USB"))
        assertEquals(MirrorTransport.USB_H264, MirrorTransport.fromWireValue("Usb_H264"))
    }

    @Test
    fun fromWireValue_returnsNull_forInvalidOrNullValues() {
        assertNull(MirrorTransport.fromWireValue(null))
        assertNull(MirrorTransport.fromWireValue(""))
        assertNull(MirrorTransport.fromWireValue("   "))
        assertNull(MirrorTransport.fromWireValue("unknown_transport"))
    }
}
