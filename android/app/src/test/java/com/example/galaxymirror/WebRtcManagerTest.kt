package com.example.galaxymirror

import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*

class WebRtcManagerTest {

    @Test
    fun preferH264Codec_movesH264PayloadsToFront() {
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100 101 120
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:96 VP8/90000
a=rtpmap:97 rtx/90000
a=rtpmap:98 VP9/90000
a=rtpmap:99 rtx/90000
a=rtpmap:100 H264/90000
a=rtpmap:101 rtx/90000
a=rtpmap:120 H264/90000""".replace("\n", "\r\n")

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        val expectedLine = "m=video 9 UDP/TLS/RTP/SAVPF 100 120 96 97 98 99 101"
        assertTrue("SDP should contain the correctly reordered video line", newSdp.contains(expectedLine))
    }

    @Test
    fun preferH264Codec_noH264_returnsOriginalSdp() {
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:96 VP8/90000
a=rtpmap:97 rtx/90000
a=rtpmap:98 VP9/90000
a=rtpmap:99 rtx/90000""".replace("\n", "\r\n")

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        assertEquals("SDP should remain unchanged when H264 is not present", sdp, newSdp)
    }

    @Test
    fun preferH264Codec_noVideoMediaLine_returnsOriginalSdp() {
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=audio 9 UDP/TLS/RTP/SAVPF 111
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:111 opus/48000/2""".replace("\n", "\r\n")

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        assertEquals("SDP should remain unchanged when there is no video media line", sdp, newSdp)
    }

    @Test
    fun preferH264Codec_emptySdp_returnsOriginalSdp() {
        val sdp = ""

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        assertEquals("Empty SDP should remain empty", sdp, newSdp)
    }


    @Test
    fun preferH264Codec_caseInsensitive() {
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97 100
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:96 VP8/90000
a=rtpmap:97 rtx/90000
a=rtpmap:100 h264/90000""".replace("\n", "\r\n")

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        val expectedLine = "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97"
        assertTrue("SDP should handle case-insensitive H264 appropriately", newSdp.contains(expectedLine))
    }

    @Test
    fun preferH264Codec_ignoresMalformedRtpmap() {
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97 100
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:96 VP8/90000
a=rtpmap:97 rtx/90000
a=rtpmap:100 H264/90000""".replace("\n", "\r\n")

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        val expectedLine = "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97"
        assertTrue("SDP should contain the correctly reordered video line", newSdp.contains(expectedLine))
    }
}
