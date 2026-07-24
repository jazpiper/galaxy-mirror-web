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
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull

class UsbScreenStreamerTest {

    private lateinit var context: Context
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var mediaProjection: MediaProjection
    private lateinit var resources: Resources
    private lateinit var displayMetrics: DisplayMetrics

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        projectionManager = mock(MediaProjectionManager::class.java)
        mediaProjection = mock(MediaProjection::class.java)
        resources = mock(Resources::class.java)
        displayMetrics = DisplayMetrics().apply { densityDpi = 160 }

        `when`(context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)).thenReturn(projectionManager)
        `when`(context.resources).thenReturn(resources)
        `when`(resources.displayMetrics).thenReturn(displayMetrics)
        `when`(projectionManager.getMediaProjection(any(), any())).thenReturn(mediaProjection)
    }

    @Test
    fun `start throws IllegalStateException when virtual display cannot be created`() {
        // Mock ImageReader statically since it's a final framework class created via newInstance
        var imageReaderMock: MockedStatic<ImageReader>? = null
        try {
            imageReaderMock = mockStatic(ImageReader::class.java)
            val mockImageReader = mock(ImageReader::class.java)
            imageReaderMock.`when`<ImageReader> { ImageReader.newInstance(any(), any(), any(), any()) }.thenReturn(mockImageReader)

            // Arrange
            `when`(
                mediaProjection.createVirtualDisplay(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull()
                )
            ).thenReturn(null)

            val streamer = UsbScreenStreamer(context) {}
            val perfMonitor = mock(UsbPerfMonitor::class.java)
            val changeGate = mock(UsbFrameChangeGate::class.java)

            // Act & Assert
            val exception = assertThrows(IllegalStateException::class.java) {
                streamer.start(
                    resultCode = -1,
                    resultData = Intent(),
                    profileProvider = { UsbStreamProfilePolicy.resolveTier(UsbStreamProfileTier.CLEAR) },
                    perfMonitor = perfMonitor,
                    changeGate = changeGate,
                    onFrame = {}
                )
            }

            org.junit.Assert.assertEquals("USB virtual display could not be created.", exception.message)
        } finally {
            imageReaderMock?.close()
        }
    }
}
