package com.example.galaxymirror

import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import android.content.Intent
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.webrtc.SessionDescription
import java.io.File


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
        // payload type가 비어 있는 rtpmap은 H264 후보로 잡히면 안 된다.
        val sdp = """v=0
o=- 123456 123456 IN IP4 127.0.0.1
s=-
t=0 0
m=video 9 UDP/TLS/RTP/SAVPF 96 97
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=rtpmap:96 VP8/90000
a=rtpmap:97 rtx/90000
a=rtpmap: H264/90000""".replace("\n", "\r\n")

        val mockService = mock(MediaProjectionService::class.java)
        val webRtcManager = WebRtcManager(mockService)

        val newSdp = webRtcManager.preferH264Codec(sdp)

        assertEquals("Malformed rtpmap should not be treated as an H264 payload", sdp, newSdp)
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun initializeWebRTC_handlesPeerConnectionInitializationErrorGracefully() {
        val mockService = mock(MediaProjectionService::class.java)
        val mockScreenCaptureManager = mock(ScreenCaptureManager::class.java)
        val mockIntent = mock(Intent::class.java)

        `when`(mockService.filesDir).thenReturn(tempFolder.root)
        `when`(mockService.isActiveSession(eq(1), any(MirrorTransport::class.java) ?: MirrorTransport.TAILSCALE_WEBRTC)).thenReturn(true)
        `when`(mockService.screenCaptureManager).thenReturn(mockScreenCaptureManager)
        `when`(mockScreenCaptureManager.mediaProjectionResultData).thenReturn(mockIntent)

        MediaProjectionService.isRunning = true
        try {
            val webRtcManager = object : WebRtcManager(mockService) {
                override fun initializePeerConnectionFactoryIfNeeded() {
                    throw RuntimeException("Simulated PeerConnectionFactory initialization error")
                }
            }

            val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
            var sendResponseCalled = false

            webRtcManager.initializeWebRTC(1, remoteSdp) {
                sendResponseCalled = true
            }

            CrashDiagnostics.flushExecutorForTesting()

            assertFalse("sendResponse should not be called when initialization throws", sendResponseCalled)
            assertNull("peerConnection should remain null after failure", webRtcManager.peerConnection)

            val caughtExceptionFile = File(tempFolder.root, "galaxy_mirror_last_caught_exception.txt")
            assertTrue("CrashDiagnostics caught exception file should exist", caughtExceptionFile.exists())
            val fileContent = caughtExceptionFile.readText()
            assertTrue("Report should mention WebRTC initialization", fileContent.contains("WebRTC initialization"))
            assertTrue("Report should include simulated error", fileContent.contains("Simulated PeerConnectionFactory initialization error"))
        } finally {
            MediaProjectionService.isRunning = false
        }
    }
}