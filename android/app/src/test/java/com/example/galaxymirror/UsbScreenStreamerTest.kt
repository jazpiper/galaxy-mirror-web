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

    @Test
    fun start_throwsException_whenVirtualDisplayCannotBeCreated() {
        val context = mock(Context::class.java)
        val projectionManager = mock(MediaProjectionManager::class.java)
        val projection = mock(MediaProjection::class.java)
        val resources = mock(Resources::class.java)
        val displayMetrics = DisplayMetrics().apply { densityDpi = 160 }

        `when`(context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)).thenReturn(projectionManager)
        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(displayMetrics)

        val intent = mock(Intent::class.java)
        `when`(projectionManager.getMediaProjection(123, intent)).thenReturn(projection)

        // Return null for createVirtualDisplay to trigger the exception
        `when`(projection.createVirtualDisplay(
            any(), any(), any(), any(), any(), any(), anyOrNull(), any()
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
}
