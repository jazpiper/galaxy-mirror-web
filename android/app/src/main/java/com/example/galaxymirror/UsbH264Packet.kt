package com.example.galaxymirror

import java.nio.ByteBuffer

data class DecodedUsbH264Packet(
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val isCodecConfig: Boolean,
    val payload: ByteArray,
)

object UsbH264Packet {
    const val HEADER_SIZE: Int = 16

    private val magic = byteArrayOf('G'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte(), '6'.code.toByte())
    private const val VERSION: Byte = 1
    private const val CODEC_H264: Byte = 1
    private const val FLAG_KEY_FRAME: Int = 0x01
    private const val FLAG_CODEC_CONFIG: Int = 0x02

    fun encode(
        presentationTimeUs: Long,
        isKeyFrame: Boolean,
        isCodecConfig: Boolean,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(HEADER_SIZE + payload.size)
        val buffer = ByteBuffer.wrap(packet)
        buffer.put(magic)
        buffer.put(VERSION)
        buffer.put(CODEC_H264)
        buffer.put(flagsByte(isKeyFrame = isKeyFrame, isCodecConfig = isCodecConfig))
        buffer.put(0)
        buffer.putLong(presentationTimeUs)
        buffer.put(payload)
        return packet
    }

    fun decode(packet: ByteArray): DecodedUsbH264Packet {
        require(packet.size >= HEADER_SIZE) { "USB H.264 packet is shorter than $HEADER_SIZE byte header" }
        require(packet.copyOfRange(0, magic.size).contentEquals(magic)) { "USB H.264 packet has invalid magic" }
        require(packet[4] == VERSION) { "USB H.264 packet has unsupported version ${packet[4]}" }
        require(packet[5] == CODEC_H264) { "USB H.264 packet has unsupported codec ${packet[5]}" }
        require(packet[7] == 0.toByte()) { "USB H.264 packet reserved byte must be zero" }

        val flags = packet[6].toInt()
        val presentationTimeUs = ByteBuffer.wrap(packet, 8, Long.SIZE_BYTES).long
        val payload = packet.copyOfRange(HEADER_SIZE, packet.size)

        return DecodedUsbH264Packet(
            presentationTimeUs = presentationTimeUs,
            isKeyFrame = flags and FLAG_KEY_FRAME != 0,
            isCodecConfig = flags and FLAG_CODEC_CONFIG != 0,
            payload = payload,
        )
    }

    private fun flagsByte(
        isKeyFrame: Boolean,
        isCodecConfig: Boolean,
    ): Byte {
        var flags = 0
        if (isKeyFrame) {
            flags = flags or FLAG_KEY_FRAME
        }
        if (isCodecConfig) {
            flags = flags or FLAG_CODEC_CONFIG
        }
        return flags.toByte()
    }
}
