package com.example.galaxymirror

import org.junit.Test
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals

class WebRtcManagerBenchmarkTest {

    fun original(sdpDescription: String): String {
        val lines = sdpDescription.split("\r\n")
        val videoMediaLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoMediaLineIndex == -1) return sdpDescription

        val videoMediaLine = lines[videoMediaLineIndex]
        val parts = videoMediaLine.split(" ")
        if (parts.size < 4) return sdpDescription

        val h264PayloadTypes = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                val partsRtpmap = line.substringAfter("a=rtpmap:").split(" ")
                if (partsRtpmap.isNotEmpty()) {
                    h264PayloadTypes.add(partsRtpmap[0])
                }
            }
        }

        if (h264PayloadTypes.isEmpty()) return sdpDescription

        val proto = parts[2]
        val otherPayloads = parts.subList(3, parts.size).filter { it !in h264PayloadTypes }
        val newPayloadOrder = h264PayloadTypes + otherPayloads
        val newVideoMediaLine = "${parts[0]} ${parts[1]} $proto ${newPayloadOrder.joinToString(" ")}"

        val newLines = lines.toMutableList()
        newLines[videoMediaLineIndex] = newVideoMediaLine
        return newLines.joinToString("\r\n")
    }

    fun optimized(sdpDescription: String): String {
        val lines = sdpDescription.split("\r\n")
        val videoMediaLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoMediaLineIndex == -1) return sdpDescription

        val videoMediaLine = lines[videoMediaLineIndex]
        val parts = videoMediaLine.split(" ")
        if (parts.size < 4) return sdpDescription

        val h264PayloadTypes = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                val spaceIndex = line.indexOf(' ', 9)
                val payloadType = if (spaceIndex != -1) {
                    line.substring(9, spaceIndex)
                } else {
                    line.substring(9)
                }
                if (payloadType.isNotEmpty()) {
                    h264PayloadTypes.add(payloadType)
                }
            }
        }

        if (h264PayloadTypes.isEmpty()) return sdpDescription

        val proto = parts[2]
        val otherPayloads = parts.subList(3, parts.size).filter { it !in h264PayloadTypes }
        val newPayloadOrder = h264PayloadTypes + otherPayloads
        val newVideoMediaLine = "${parts[0]} ${parts[1]} $proto ${newPayloadOrder.joinToString(" ")}"

        val newLines = lines.toMutableList()
        newLines[videoMediaLineIndex] = newVideoMediaLine
        return newLines.joinToString("\r\n")
    }

    @Test
    fun benchmarkH264Prefer() {
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100 101
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:96 VP8/90000
a=rtpmap:97 rtx/90000
a=rtpmap:98 VP9/90000
a=rtpmap:99 rtx/90000
a=rtpmap:100 H264/90000
a=rtpmap:101 rtx/90000
a=rtpmap:120 H264/90000"""

        // Assert correctness first
        assertEquals(original(sdp), optimized(sdp))

        // warmup
        for (i in 0..10000) {
            original(sdp)
            optimized(sdp)
        }

        val origTime = measureTimeMillis {
            for (i in 0..100000) {
                original(sdp)
            }
        }

        val optTime = measureTimeMillis {
            for (i in 0..100000) {
                optimized(sdp)
            }
        }

        println("BENCHMARK Original time: ${origTime}ms")
        println("BENCHMARK Optimized time: ${optTime}ms")
    }
}
