package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull

class UsbScreenStreamerTest {

    private fun setupMockContext(
        projectionManager: MediaProjectionManager,
    ): Context {
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        val displayMetrics = DisplayMetrics().apply { densityDpi = 160 }

        `when`(context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)).thenReturn(projectionManager)
        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(displayMetrics)

        return context
    }

    @Test
    fun start_throwsException_whenVirtualDisplayCannotBeCreated() {
        val projectionManager = mock(MediaProjectionManager::class.java)
        val projection = mock(MediaProjection::class.java)
        val context = setupMockContext(projectionManager)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(projection)

        // Return null for createVirtualDisplay to trigger the exception
        `when`(projection.createVirtualDisplay(
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()
        )).thenReturn(null)

        val streamer = UsbScreenStreamer(context) {}
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbStreamProfile(UsbStreamProfileTier.BALANCED, 1080, 1920, 30, 80)
        val imageReader = mock(ImageReader::class.java)

        mockStatic(ImageReader::class.java).use { mockedImageReader ->
            mockedImageReader.`when`<ImageReader> {
                ImageReader.newInstance(any(), any(), any(), any())
            }.thenReturn(imageReader)

            assertThrows(IllegalStateException::class.java) {
                streamer.start(
                    resultCode = 123,
                    resultData = intent,
                    profileProvider = { profile },
                    perfMonitor = perfMonitor,
                    onFrame = {}
                )
            }
        }
    }

    @Test
    fun start_throwsSecurityException_whenCreateVirtualDisplayThrowsSecurityException() {
        val projectionManager = mock(MediaProjectionManager::class.java)
        val projection = mock(MediaProjection::class.java)
        val context = setupMockContext(projectionManager)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(projection)

        `when`(projection.createVirtualDisplay(
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()
        )).thenThrow(SecurityException("MediaProjection permission revoked"))

        val streamer = UsbScreenStreamer(context) {}
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbStreamProfile(UsbStreamProfileTier.BALANCED, 1080, 1920, 30, 80)
        val imageReader = mock(ImageReader::class.java)

        mockStatic(ImageReader::class.java).use { mockedImageReader ->
            mockedImageReader.`when`<ImageReader> {
                ImageReader.newInstance(any(), any(), any(), any())
            }.thenReturn(imageReader)

            assertThrows(SecurityException::class.java) {
                streamer.start(
                    resultCode = 123,
                    resultData = intent,
                    profileProvider = { profile },
                    perfMonitor = perfMonitor,
                    onFrame = {}
                )
            }
        }
    }

    @Test
    fun start_throwsIllegalStateException_whenCreateVirtualDisplayThrowsIllegalStateException() {
        val projectionManager = mock(MediaProjectionManager::class.java)
        val projection = mock(MediaProjection::class.java)
        val context = setupMockContext(projectionManager)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(projection)

        `when`(projection.createVirtualDisplay(
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()
        )).thenThrow(IllegalStateException("MediaProjection is invalid"))

        val streamer = UsbScreenStreamer(context) {}
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbStreamProfile(UsbStreamProfileTier.BALANCED, 1080, 1920, 30, 80)
        val imageReader = mock(ImageReader::class.java)

        mockStatic(ImageReader::class.java).use { mockedImageReader ->
            mockedImageReader.`when`<ImageReader> {
                ImageReader.newInstance(any(), any(), any(), any())
            }.thenReturn(imageReader)

            assertThrows(IllegalStateException::class.java) {
                streamer.start(
                    resultCode = 123,
                    resultData = intent,
                    profileProvider = { profile },
                    perfMonitor = perfMonitor,
                    onFrame = {}
                )
            }
        }
    }

    @Test
    fun start_throwsSecurityException_whenGetMediaProjectionThrowsSecurityException() {
        val projectionManager = mock(MediaProjectionManager::class.java)
        val context = setupMockContext(projectionManager)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent))
            .thenThrow(SecurityException("User denied screen capture permission"))

        val streamer = UsbScreenStreamer(context) {}
        val perfMonitor = mock(UsbPerfMonitor::class.java)
        val profile = UsbStreamProfile(UsbStreamProfileTier.BALANCED, 1080, 1920, 30, 80)

        assertThrows(SecurityException::class.java) {
            streamer.start(
                resultCode = 123,
                resultData = intent,
                profileProvider = { profile },
                perfMonitor = perfMonitor,
                onFrame = {}
            )
        }
    }
}
