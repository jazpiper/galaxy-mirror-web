package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

class UsbScreenStreamer(
    private val context: Context,
    private val onProjectionStopped: () -> Unit,
) {
    private val stateLock = Any()
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun start(
        resultCode: Int,
        resultData: Intent,
        profile: UsbStreamProfile,
        onFrame: (ByteArray) -> Unit,
    ) {
        stop()

        var startupProjection: MediaProjection? = null
        var startupThread: HandlerThread? = null
        var startupReader: ImageReader? = null
        var startupCallback: MediaProjection.Callback? = null
        var startupDisplay: VirtualDisplay? = null
        var ownedByFields = false
        try {
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection grant could not be created for USB stream.")
            startupProjection = projection

            val streamThread = HandlerThread("UsbScreenStreamer").apply { start() }
            startupThread = streamThread
            val streamHandler = Handler(streamThread.looper)

            val reader = ImageReader.newInstance(profile.width, profile.height, PixelFormat.RGBA_8888, 2)
            startupReader = reader

            val frameRateGate = UsbFrameRateGate(profile.fps)
            val callback =
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        handleProjectionStopped()
                    }
                }
            startupCallback = callback

            reader.setOnImageAvailableListener(
                { availableReader ->
                    handleImageAvailable(
                        imageReader = availableReader,
                        frameRateGate = frameRateGate,
                        profile = profile,
                        onFrame = onFrame,
                    )
                },
                streamHandler,
            )
            projection.registerCallback(callback, streamHandler)

            val display =
                projection.createVirtualDisplay(
                    "GalaxyMirrorUsbScreenStreamer",
                    profile.width,
                    profile.height,
                    context.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    streamHandler,
                )
            if (display == null) {
                throw IllegalStateException("USB virtual display could not be created.")
            }
            startupDisplay = display

            synchronized(stateLock) {
                handlerThread = streamThread
                handler = streamHandler
                imageReader = reader
                mediaProjection = projection
                projectionCallback = callback
                virtualDisplay = display
                running = true
                stopping = false
                ownedByFields = true
            }
        } catch (e: Exception) {
            if (ownedByFields) {
                releaseResources(stopProjection = true)
            } else {
                releaseStartupResources(
                    virtualDisplay = startupDisplay,
                    imageReader = startupReader,
                    mediaProjection = startupProjection,
                    projectionCallback = startupCallback,
                    handlerThread = startupThread,
                )
            }
            throw e
        }
    }

    fun stop() {
        releaseResources(stopProjection = true)
    }

    fun isRunning(): Boolean = running

    private fun handleProjectionStopped() {
        val shouldNotify =
            synchronized(stateLock) {
                if (stopping) {
                    false
                } else {
                    stopping = true
                    running
                }
            }

        releaseResources(stopProjection = false)

        if (shouldNotify) {
            onProjectionStopped()
        }
    }

    private fun handleImageAvailable(
        imageReader: ImageReader,
        frameRateGate: UsbFrameRateGate,
        profile: UsbStreamProfile,
        onFrame: (ByteArray) -> Unit,
    ) {
        val image =
            try {
                imageReader.acquireLatestImage()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Unable to acquire USB stream image.", e)
                null
            } ?: return

        try {
            if (!running || !frameRateGate.shouldEmit()) return
            onFrame(encodeJpeg(image, profile))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to encode USB stream frame.", e)
        } finally {
            image.close()
        }
    }

    private fun encodeJpeg(image: Image, profile: UsbStreamProfile): ByteArray {
        val plane = image.planes.first()
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * profile.width
        val bitmapWidth = profile.width + rowPadding / pixelStride
        val sourceBitmap = Bitmap.createBitmap(bitmapWidth, profile.height, Bitmap.Config.ARGB_8888)
        sourceBitmap.copyPixelsFromBuffer(buffer)

        val jpegBitmap =
            if (bitmapWidth == profile.width) {
                sourceBitmap
            } else {
                Bitmap.createBitmap(sourceBitmap, 0, 0, profile.width, profile.height)
            }

        return try {
            ByteArrayOutputStream().use { output ->
                jpegBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    profile.jpegQuality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY),
                    output,
                )
                output.toByteArray()
            }
        } finally {
            if (jpegBitmap !== sourceBitmap) {
                jpegBitmap.recycle()
            }
            sourceBitmap.recycle()
        }
    }

    private fun releaseResources(stopProjection: Boolean) {
        val resources =
            synchronized(stateLock) {
                if (!running && !hasResourcesLocked()) return
                stopping = true
                running = false
                Resources(
                    virtualDisplay = virtualDisplay,
                    imageReader = imageReader,
                    mediaProjection = mediaProjection,
                    projectionCallback = projectionCallback,
                    handlerThread = handlerThread,
                ).also {
                    virtualDisplay = null
                    imageReader = null
                    mediaProjection = null
                    projectionCallback = null
                    handlerThread = null
                    handler = null
                }
            }

        releaseResourceBundle(resources, stopProjection)

        synchronized(stateLock) {
            stopping = false
        }
    }

    private fun releaseStartupResources(
        virtualDisplay: VirtualDisplay?,
        imageReader: ImageReader?,
        mediaProjection: MediaProjection?,
        projectionCallback: MediaProjection.Callback?,
        handlerThread: HandlerThread?,
    ) {
        releaseResourceBundle(
            resources =
                Resources(
                    virtualDisplay = virtualDisplay,
                    imageReader = imageReader,
                    mediaProjection = mediaProjection,
                    projectionCallback = projectionCallback,
                    handlerThread = handlerThread,
                ),
            stopProjection = true,
        )
    }

    private fun releaseResourceBundle(resources: Resources, stopProjection: Boolean) {
        try {
            resources.virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to release USB stream virtual display.", e)
        }

        try {
            resources.imageReader?.setOnImageAvailableListener(null, null)
            resources.imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to close USB stream image reader.", e)
        }

        val projection = resources.mediaProjection
        val callback = resources.projectionCallback
        unregisterMediaProjectionCallback(projection, callback)
        if (stopProjection) {
            stopMediaProjection(projection)
        }

        resources.handlerThread?.quitSafely()
    }

    private fun unregisterMediaProjectionCallback(
        projection: MediaProjection?,
        callback: MediaProjection.Callback?,
    ) {
        if (projection == null || callback == null) return
        try {
            projection.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to unregister USB stream MediaProjection callback.", e)
        }
    }

    private fun stopMediaProjection(projection: MediaProjection?) {
        if (projection == null) return
        try {
            projection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to stop USB stream MediaProjection.", e)
        }
    }

    private fun hasResourcesLocked(): Boolean =
        virtualDisplay != null ||
            imageReader != null ||
            mediaProjection != null ||
            projectionCallback != null ||
            handlerThread != null

    private data class Resources(
        val virtualDisplay: VirtualDisplay?,
        val imageReader: ImageReader?,
        val mediaProjection: MediaProjection?,
        val projectionCallback: MediaProjection.Callback?,
        val handlerThread: HandlerThread?,
    )

    private companion object {
        private const val TAG = "UsbScreenStreamer"
        private const val MIN_JPEG_QUALITY = 1
        private const val MAX_JPEG_QUALITY = 100
    }
}
