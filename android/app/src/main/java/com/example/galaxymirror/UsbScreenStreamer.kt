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
import android.os.SystemClock
import android.util.Log

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

    private var cachedSourceBitmap: Bitmap? = null
    private var cachedJpegBitmap: Bitmap? = null
    private var cachedOutputStream: java.io.ByteArrayOutputStream? = null

    fun start(
        resultCode: Int,
        resultData: Intent,
        profileProvider: () -> UsbStreamProfile,
        perfMonitor: UsbPerfMonitor,
        changeGate: UsbFrameChangeGate = UsbFrameChangeGate(),
        onFrame: (ByteArray) -> Unit,
    ) {
        stop()

        val captureProfile = UsbStreamProfilePolicy.resolveTier(UsbStreamProfileTier.CLEAR)
        changeGate.reset()
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

            val reader = ImageReader.newInstance(captureProfile.width, captureProfile.height, PixelFormat.RGBA_8888, 2)
            startupReader = reader

            val frameRateGate = UsbFrameRateGate(captureProfile.fps)
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
                        captureProfile = captureProfile,
                        profileProvider = profileProvider,
                        perfMonitor = perfMonitor,
                        changeGate = changeGate,
                        onFrame = onFrame,
                    )
                },
                streamHandler,
            )
            projection.registerCallback(callback, streamHandler)

            val display =
                projection.createVirtualDisplay(
                    "GalaxyMirrorUsbScreenStreamer",
                    captureProfile.width,
                    captureProfile.height,
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
        captureProfile: UsbStreamProfile,
        profileProvider: () -> UsbStreamProfile,
        perfMonitor: UsbPerfMonitor,
        changeGate: UsbFrameChangeGate,
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
            perfMonitor.recordFrameAcquired()
            val profile = profileProvider()
            frameRateGate.updateFps(profile.fps)
            if (!running || !frameRateGate.shouldEmit()) {
                perfMonitor.recordFrameDroppedByFps()
                return
            }
            val signature = computeFrameSignature(image, captureProfile)
            if (changeGate.evaluate(signature) == UsbFrameChangeDecision.SKIP_STILL) {
                perfMonitor.recordFrameSkippedByStillness()
                return
            }
            val startedAt = SystemClock.elapsedRealtime()
            val frame = encodeJpeg(image, profile, captureProfile)
            onFrame(frame)
            perfMonitor.recordFrameEncoded(
                bytes = frame.size,
                encodeMillis = SystemClock.elapsedRealtime() - startedAt,
            )
        } catch (e: Exception) {
            perfMonitor.recordEncodeFailure()
            Log.e(TAG, "Unable to encode USB stream frame.", e)
        } finally {
            image.close()
        }
    }

    private fun computeFrameSignature(image: Image, profile: UsbStreamProfile): Long {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val sampleRows = FRAME_SIGNATURE_SAMPLE_ROWS.coerceAtMost(profile.height).coerceAtLeast(1)
        val sampleColumns = FRAME_SIGNATURE_SAMPLE_COLUMNS.coerceAtMost(profile.width).coerceAtLeast(1)
        var hash = FRAME_SIGNATURE_HASH_SEED

        for (rowIndex in 0 until sampleRows) {
            val y =
                if (sampleRows == 1) {
                    0
                } else {
                    rowIndex * (profile.height - 1) / (sampleRows - 1)
                }
            for (columnIndex in 0 until sampleColumns) {
                val x =
                    if (sampleColumns == 1) {
                        0
                    } else {
                        columnIndex * (profile.width - 1) / (sampleColumns - 1)
                    }
                val offset = y * rowStride + x * pixelStride
                if (offset in 0 until buffer.limit()) {
                    hash = (hash xor buffer.get(offset).toLong()) * FRAME_SIGNATURE_HASH_PRIME
                }
            }
        }
        return hash
    }

    private fun encodeJpeg(
        image: Image,
        profile: UsbStreamProfile,
        captureProfile: UsbStreamProfile,
    ): ByteArray {
        val plane = image.planes.first()
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureProfile.width
        val bitmapWidth = captureProfile.width + rowPadding / pixelStride

        val sBitmap: Bitmap
        val jBitmap: Bitmap

        synchronized(stateLock) {
            val currentSrc = cachedSourceBitmap
            if (currentSrc == null || currentSrc.width != bitmapWidth || currentSrc.height != captureProfile.height) {
                currentSrc?.recycle()
                cachedSourceBitmap = Bitmap.createBitmap(bitmapWidth, captureProfile.height, Bitmap.Config.ARGB_8888)
            }
            sBitmap = cachedSourceBitmap!!

            if (bitmapWidth == profile.width && captureProfile.height == profile.height) {
                cachedJpegBitmap?.recycle()
                cachedJpegBitmap = null
                jBitmap = sBitmap
            } else {
                val currentJpeg = cachedJpegBitmap
                if (currentJpeg == null || currentJpeg.width != profile.width || currentJpeg.height != profile.height) {
                    currentJpeg?.recycle()
                    cachedJpegBitmap = Bitmap.createBitmap(profile.width, profile.height, Bitmap.Config.ARGB_8888)
                }
                jBitmap = cachedJpegBitmap!!
            }
        }

        sBitmap.copyPixelsFromBuffer(buffer)

        if (jBitmap !== sBitmap) {
            val canvas = android.graphics.Canvas(jBitmap)
            val srcRect = android.graphics.Rect(0, 0, captureProfile.width, captureProfile.height)
            val destRect = android.graphics.Rect(0, 0, profile.width, profile.height)
            canvas.drawBitmap(sBitmap, srcRect, destRect, null)
        }

        val output = synchronized(stateLock) {
            var out = cachedOutputStream
            if (out == null) {
                out = java.io.ByteArrayOutputStream(1024 * 1024)
                cachedOutputStream = out
            } else {
                out.reset()
            }
            out
        }

        jBitmap.compress(
            Bitmap.CompressFormat.JPEG,
            profile.jpegQuality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY),
            output
        )
        return output.toByteArray()
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

        synchronized(stateLock) {
            cachedSourceBitmap?.recycle()
            cachedSourceBitmap = null
            cachedJpegBitmap?.recycle()
            cachedJpegBitmap = null
            cachedOutputStream = null
        }
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
        private const val FRAME_SIGNATURE_SAMPLE_ROWS = 8
        private const val FRAME_SIGNATURE_SAMPLE_COLUMNS = 8
        private const val FRAME_SIGNATURE_HASH_SEED = -3750763034362895579L
        private const val FRAME_SIGNATURE_HASH_PRIME = 1099511628211L
    }
}
