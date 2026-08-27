package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify

class UsbH264ScreenStreamerTest {

    private fun createMockContext(): Context {
        val context = mock(Context::class.java)
        val projectionManager = mock(MediaProjectionManager::class.java)
        `when`(context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)).thenReturn(projectionManager)
        return context
    }

    @Test
    fun start_throwsException_whenMediaProjectionIsNull() {
        val context = createMockContext()
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(null)

        val streamer = UsbH264ScreenStreamer(context) {}
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbH264StreamProfilePolicy.resolveTier(UsbStreamProfileTier.BALANCED)

        assertThrows(IllegalStateException::class.java) {
            streamer.start(
                resultCode = 123,
                resultData = intent,
                profileProvider = { profile },
                perfMonitor = perfMonitor,
                onVideoConfig = {},
                onChunk = {}
            )
        }
        assertFalse(streamer.isRunning())
    }

    @Test
    fun start_throwsException_whenVirtualDisplayIsNull() {
        val context = createMockContext()
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mock(MediaProjection::class.java)
        val resources = mock(Resources::class.java)
        val displayMetrics = DisplayMetrics().apply { densityDpi = 160 }

        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(displayMetrics)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(projection)

        `when`(projection.createVirtualDisplay(
            any(), anyInt(), anyInt(), anyInt(), anyInt(), anyOrNull(), anyOrNull(), any()
        )).thenReturn(null)

        val streamer = UsbH264ScreenStreamer(context) {}
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbH264StreamProfilePolicy.resolveTier(UsbStreamProfileTier.BALANCED)

        val mockCodec = mock(MediaCodec::class.java)
        val mockSurface = mock(Surface::class.java)
        val mockMediaFormat = mock(MediaFormat::class.java)

        `when`(mockCodec.createInputSurface()).thenReturn(mockSurface)

        mockStatic(MediaCodec::class.java).use { mockedMediaCodec ->
            mockedMediaCodec.`when`<MediaCodec> {
                MediaCodec.createEncoderByType(anyString())
            }.thenReturn(mockCodec)

            mockStatic(MediaFormat::class.java).use { mockedMediaFormat ->
                mockedMediaFormat.`when`<MediaFormat> {
                    MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt())
                }.thenReturn(mockMediaFormat)

                assertThrows(IllegalStateException::class.java) {
                    streamer.start(
                        resultCode = 123,
                        resultData = intent,
                        profileProvider = { profile },
                        perfMonitor = perfMonitor,
                        onVideoConfig = {},
                        onChunk = {}
                    )
                }
            }
        }
        assertFalse(streamer.isRunning())
    }

    @Test
    fun changeResolution_resizesVirtualDisplay_whenRunning() {
        val context = createMockContext()
        val streamer = UsbH264ScreenStreamer(context) {}

        val stateLockField = UsbH264ScreenStreamer::class.java.getDeclaredField("stateLock").apply { isAccessible = true }
        val lock = stateLockField.get(streamer)!!

        val virtualDisplayMock = mock(VirtualDisplay::class.java)
        val encoderMock = mock(MediaCodec::class.java)

        synchronized(lock) {
            UsbH264ScreenStreamer::class.java.getDeclaredField("running").apply { isAccessible = true }.set(streamer, true)
            UsbH264ScreenStreamer::class.java.getDeclaredField("stopping").apply { isAccessible = true }.set(streamer, false)
            UsbH264ScreenStreamer::class.java.getDeclaredField("virtualDisplay").apply { isAccessible = true }.set(streamer, virtualDisplayMock)
            UsbH264ScreenStreamer::class.java.getDeclaredField("encoder").apply { isAccessible = true }.set(streamer, encoderMock)
        }

        streamer.changeResolution(1920, 1080, 320)
        verify(virtualDisplayMock).resize(1920, 1080, 160)

        streamer.changeResolution(3000, 200, 240)
        verify(virtualDisplayMock).resize(2560, 480, 240)
    }

    @Test
    fun changeResolution_doesNothing_whenNotRunning() {
        val context = createMockContext()
        val streamer = UsbH264ScreenStreamer(context) {}
        streamer.changeResolution(1920, 1080, 160)
        assertFalse(streamer.isRunning())
    }

    @Test
    fun stop_releasesResourcesAndResetsState() {
        val context = createMockContext()
        val streamer = UsbH264ScreenStreamer(context) {}

        val stateLockField = UsbH264ScreenStreamer::class.java.getDeclaredField("stateLock").apply { isAccessible = true }
        val lock = stateLockField.get(streamer)!!

        val virtualDisplayMock = mock(VirtualDisplay::class.java)
        val encoderMock = mock(MediaCodec::class.java)

        synchronized(lock) {
            UsbH264ScreenStreamer::class.java.getDeclaredField("running").apply { isAccessible = true }.set(streamer, true)
            UsbH264ScreenStreamer::class.java.getDeclaredField("virtualDisplay").apply { isAccessible = true }.set(streamer, virtualDisplayMock)
            UsbH264ScreenStreamer::class.java.getDeclaredField("encoder").apply { isAccessible = true }.set(streamer, encoderMock)
        }

        assertTrue(streamer.isRunning())
        streamer.stop()

        assertFalse(streamer.isRunning())
        verify(virtualDisplayMock).release()
        verify(encoderMock).stop()
        verify(encoderMock).release()
    }

    @Test
    fun projectionCallback_onStop_triggersCallbackAndReleases() {
        var projectionStoppedCalled = false
        val context = createMockContext()
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mock(MediaProjection::class.java)
        val resources = mock(Resources::class.java)
        val virtualDisplayMock = mock(VirtualDisplay::class.java)
        val displayMetrics = DisplayMetrics().apply { densityDpi = 160 }

        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(displayMetrics)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(projection)
        `when`(projection.createVirtualDisplay(
            any(), anyInt(), anyInt(), anyInt(), anyInt(), anyOrNull(), anyOrNull(), any()
        )).thenReturn(virtualDisplayMock)

        val streamer = UsbH264ScreenStreamer(context) {
            projectionStoppedCalled = true
        }
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbH264StreamProfilePolicy.resolveTier(UsbStreamProfileTier.BALANCED)

        val callbackCaptor = ArgumentCaptor.forClass(MediaProjection.Callback::class.java)
        val mockCodec = mock(MediaCodec::class.java)
        val mockSurface = mock(Surface::class.java)
        val mockMediaFormat = mock(MediaFormat::class.java)

        `when`(mockCodec.createInputSurface()).thenReturn(mockSurface)

        var videoConfigEmitted: String? = null

        mockStatic(MediaCodec::class.java).use { mockedMediaCodec ->
            mockedMediaCodec.`when`<MediaCodec> {
                MediaCodec.createEncoderByType(anyString())
            }.thenReturn(mockCodec)

            mockStatic(MediaFormat::class.java).use { mockedMediaFormat ->
                mockedMediaFormat.`when`<MediaFormat> {
                    MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt())
                }.thenReturn(mockMediaFormat)

                streamer.start(
                    resultCode = 123,
                    resultData = intent,
                    profileProvider = { profile },
                    perfMonitor = perfMonitor,
                    onVideoConfig = { videoConfigEmitted = it },
                    onChunk = {}
                )

                assertTrue(streamer.isRunning())
                assertTrue(videoConfigEmitted?.contains("USB_VIDEO_CONFIG") == true)
                verify(projection).registerCallback(callbackCaptor.capture(), any())

                // Trigger onStop callback
                callbackCaptor.value.onStop()

                assertFalse(streamer.isRunning())
                assertTrue(projectionStoppedCalled)
            }
        }
    }
}
