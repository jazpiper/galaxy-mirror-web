package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class UsbH264PacketTest {
    @Test
    fun encodeWritesSixteenByteHeaderAndPayload() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11)

        val encoded =
            UsbH264Packet.encode(
                presentationTimeUs = 123_456_789L,
                isKeyFrame = true,
                isCodecConfig = false,
                payload = payload,
            )

        assertEquals(UsbH264Packet.HEADER_SIZE + payload.size, encoded.size)
        assertEquals('G'.code.toByte(), encoded[0])
        assertEquals('H'.code.toByte(), encoded[1])
        assertEquals('2'.code.toByte(), encoded[2])
        assertEquals('6'.code.toByte(), encoded[3])
        assertEquals(1.toByte(), encoded[4])
        assertEquals(1.toByte(), encoded[5])
        assertEquals(0x01.toByte(), encoded[6])
        assertEquals(0.toByte(), encoded[7])
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x07, 0x5b, 0xcd.toByte(), 0x15),
            encoded.copyOfRange(8, UsbH264Packet.HEADER_SIZE),
        )
        assertArrayEquals(payload, encoded.copyOfRange(UsbH264Packet.HEADER_SIZE, encoded.size))
    }

    @Test
    fun decodeRoundTripsFlagsTimestampAndPayload() {
        val payload = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val encoded =
            UsbH264Packet.encode(
                presentationTimeUs = -42L,
                isKeyFrame = false,
                isCodecConfig = true,
                payload = payload,
            )

        val decoded = UsbH264Packet.decode(encoded)

        assertEquals(-42L, decoded.presentationTimeUs)
        assertFalse(decoded.isKeyFrame)
        assertTrue(decoded.isCodecConfig)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsInvalidMagic() {
        val encoded =
            UsbH264Packet.encode(
                presentationTimeUs = 0L,
                isKeyFrame = false,
                isCodecConfig = false,
                payload = byteArrayOf(0x01),
            )

        encoded[0] = 'B'.code.toByte()

        UsbH264Packet.decode(encoded)
    }
}
