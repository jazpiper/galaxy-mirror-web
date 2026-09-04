package com.example.galaxymirror

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbVideoCodecTest {

    @Test
    fun enumPropertiesMatchExpectedValues() {
        assertEquals("h264", UsbVideoCodec.H264.wireValue)
        assertEquals("H.264", UsbVideoCodec.H264.label)

        assertEquals("jpeg", UsbVideoCodec.JPEG.wireValue)
        assertEquals("JPEG", UsbVideoCodec.JPEG.label)
    }

    @Test
    fun preferredForChromeReturnsH264() {
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.preferredForChrome())
    }

    @Test
    fun fromWireValueParsesValidWireValues() {
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.fromWireValue("h264"))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("jpeg"))
    }

    @Test
    fun fromWireValueIsCaseInsensitive() {
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.fromWireValue("H264"))
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.fromWireValue("H264"))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("JPEG"))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("Jpeg"))
    }

    @Test
    fun fromWireValueTrimsWhitespace() {
        assertEquals(UsbVideoCodec.H264, UsbVideoCodec.fromWireValue("  h264  "))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("\tjpeg\n"))
    }

    @Test
    fun fromWireValueDefaultsToJpegForNullOrUnknown() {
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue(null))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue(""))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("    "))
        assertEquals(UsbVideoCodec.JPEG, UsbVideoCodec.fromWireValue("unknown_codec"))
    }
}
