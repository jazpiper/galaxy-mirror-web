package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class UsbH264ScreenStreamer(
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
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun start(
        resultCode: Int,
        resultData: Intent,
        profileProvider: () -> UsbH264StreamProfile,
        perfMonitor: UsbPerfMonitor,
        onVideoConfig: (String) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        stop()

        val startupProfile = profileProvider()
        var startupProjection: MediaProjection? = null
        var startupThread: HandlerThread? = null
        var startupEncoder: MediaCodec? = null
        var startupSurface: Surface? = null
        var startupCallback: MediaProjection.Callback? = null
        var startupDisplay: VirtualDisplay? = null
        var ownedByFields = false

        try {
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection grant could not be created for USB H.264 stream.")
            startupProjection = projection

            val streamThread = HandlerThread("UsbH264ScreenStreamer").apply { start() }
            startupThread = streamThread
            val streamHandler = Handler(streamThread.looper)

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            startupEncoder = codec
            val format = buildEncoderFormat(startupProfile)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            startupSurface = surface

            val callback =
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        handleProjectionStopped()
                    }
                }
            startupCallback = callback
            projection.registerCallback(callback, streamHandler)

            val display =
                projection.createVirtualDisplay(
                    "GalaxyMirrorUsbH264ScreenStreamer",
                    startupProfile.width,
                    startupProfile.height,
                    context.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    streamHandler,
                )
                    ?: throw IllegalStateException("USB H.264 virtual display could not be created.")
            startupDisplay = display

            synchronized(stateLock) {
                handlerThread = streamThread
                handler = streamHandler
                mediaProjection = projection
                projectionCallback = callback
                virtualDisplay = display
                encoder = codec
                inputSurface = surface
                running = true
                stopping = false
                ownedByFields = true
            }

            onVideoConfig(buildVideoConfig(startupProfile))
            codec.start()
            streamHandler.post {
                drainEncoder(
                    profileProvider = profileProvider,
                    perfMonitor = perfMonitor,
                    onChunk = onChunk,
                )
            }
        } catch (e: Exception) {
            if (ownedByFields) {
                releaseResources(stopProjection = true)
            } else {
                releaseStartupResources(
                    virtualDisplay = startupDisplay,
                    inputSurface = startupSurface,
                    encoder = startupEncoder,
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

    private fun buildEncoderFormat(profile: UsbH264StreamProfile): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, profile.width, profile.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, profile.keyFrameIntervalSeconds)
            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to set USB H.264 bitrate mode.", e)
            }
        }

    private fun drainEncoder(
        profileProvider: () -> UsbH264StreamProfile,
        perfMonitor: UsbPerfMonitor,
        onChunk: (ByteArray) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val codec = encoder ?: break
            val outputIndex =
                try {
                    codec.dequeueOutputBuffer(bufferInfo, ENCODER_DEQUEUE_TIMEOUT_US)
                } catch (e: IllegalStateException) {
                    if (running) {
                        perfMonitor.recordEncodeFailure()
                        Log.e(TAG, "USB H.264 encoder dequeue failed.", e)
                    }
                    break
                }

            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        emitEncodedBuffer(
                            codec = codec,
                            outputIndex = outputIndex,
                            outputBuffer = outputBuffer,
                            bufferInfo = bufferInfo,
                            profile = profileProvider(),
                            perfMonitor = perfMonitor,
                            onChunk = onChunk,
                        )
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Chrome is configured from the initial profile; Android may still report codec csd here.
                }
            }
        }
    }

    private fun emitEncodedBuffer(
        codec: MediaCodec,
        outputIndex: Int,
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        profile: UsbH264StreamProfile,
        perfMonitor: UsbPerfMonitor,
        onChunk: (ByteArray) -> Unit,
    ) {
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val encoded = ensureAnnexB(copyEncodedBytes(outputBuffer, bufferInfo))
            val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val packet =
                UsbH264Packet.encode(
                    presentationTimeUs = bufferInfo.presentationTimeUs,
                    isKeyFrame = isKeyFrame || isCodecConfig,
                    isCodecConfig = isCodecConfig,
                    payload = encoded,
                )
            onChunk(packet)
            perfMonitor.recordFrameEncoded(
                bytes = packet.size,
                encodeMillis = SystemClock.elapsedRealtime() - startedAt,
            )
            maybeRecordProfile(profile)
        } catch (e: Exception) {
            perfMonitor.recordEncodeFailure()
            Log.e(TAG, "Unable to emit USB H.264 frame.", e)
        } finally {
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun copyEncodedBytes(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ): ByteArray {
        val duplicate = outputBuffer.duplicate()
        duplicate.position(bufferInfo.offset)
        duplicate.limit(bufferInfo.offset + bufferInfo.size)
        val encoded = ByteArray(bufferInfo.size)
        duplicate.get(encoded)
        return encoded
    }

    private fun ensureAnnexB(encoded: ByteArray): ByteArray {
        if (encoded.hasAnnexBStartCode()) return encoded
        if (encoded.size < 4) return encoded

        // Build directly into one stream instead of a list + O(n^2) fold: avoids per-NAL
        // intermediate arrays and the quadratic accumulator on the per-frame encode path.
        val out = ByteArrayOutputStream(encoded.size + ANNEX_B_START_CODE.size)
        var offset = 0
        var nalCount = 0
        while (offset + 4 <= encoded.size) {
            val length =
                ((encoded[offset].toInt() and 0xff) shl 24) or
                    ((encoded[offset + 1].toInt() and 0xff) shl 16) or
                    ((encoded[offset + 2].toInt() and 0xff) shl 8) or
                    (encoded[offset + 3].toInt() and 0xff)
            offset += 4
            if (length <= 0 || offset + length > encoded.size) return encoded
            out.write(ANNEX_B_START_CODE)
            out.write(encoded, offset, length)
            offset += length
            nalCount++
        }
        if (offset != encoded.size || nalCount == 0) return encoded
        return out.toByteArray()
    }

    private fun ByteArray.hasAnnexBStartCode(): Boolean =
        size >= 4 &&
            this[0] == 0.toByte() &&
            this[1] == 0.toByte() &&
            (
                this[2] == 1.toByte() ||
                    (this[2] == 0.toByte() && this[3] == 1.toByte())
            )

    private fun buildVideoConfig(profile: UsbH264StreamProfile): String =
        JSONObject()
            .put("type", "USB_VIDEO_CONFIG")
            .put(
                "payload",
                JSONObject()
                    .put("codec", UsbVideoCodec.H264.wireValue)
                    .put("mime", profile.mime)
                    .put("chunkFormat", "annexb")
                    .put("codecString", DEFAULT_AVC_CODEC_STRING)
                    .put("width", profile.width)
                    .put("height", profile.height)
                    .put("fps", profile.fps)
                    .put("bitrateBps", profile.bitrateBps)
                    .put("keyFrameIntervalSeconds", profile.keyFrameIntervalSeconds),
            )
            .toString()

    private fun maybeRecordProfile(profile: UsbH264StreamProfile) {
        // The service owns status state; this hook keeps the streamer free of service references.
        lastObservedProfile = profile
    }

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

    private fun releaseResources(stopProjection: Boolean) {
        val resources =
            synchronized(stateLock) {
                running = false
                val snapshot =
                    ResourcesSnapshot(
                        handlerThread = handlerThread,
                        mediaProjection = mediaProjection,
                        projectionCallback = projectionCallback,
                        virtualDisplay = virtualDisplay,
                        encoder = encoder,
                        inputSurface = inputSurface,
                    )
                handlerThread = null
                handler = null
                mediaProjection = null
                projectionCallback = null
                virtualDisplay = null
                encoder = null
                inputSurface = null
                snapshot
            }

        releaseStartupResources(
            virtualDisplay = resources.virtualDisplay,
            inputSurface = resources.inputSurface,
            encoder = resources.encoder,
            mediaProjection = resources.mediaProjection,
            projectionCallback = resources.projectionCallback,
            handlerThread = resources.handlerThread,
            stopProjection = stopProjection,
        )
    }

    private fun releaseStartupResources(
        virtualDisplay: VirtualDisplay?,
        inputSurface: Surface?,
        encoder: MediaCodec?,
        mediaProjection: MediaProjection?,
        projectionCallback: MediaProjection.Callback?,
        handlerThread: HandlerThread?,
        stopProjection: Boolean = true,
    ) {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release USB H.264 virtual display.", e)
        }
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop USB H.264 encoder.", e)
        }
        try {
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release USB H.264 encoder.", e)
        }
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release USB H.264 input surface.", e)
        }
        try {
            if (mediaProjection != null && projectionCallback != null) {
                mediaProjection.unregisterCallback(projectionCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to unregister USB H.264 MediaProjection callback.", e)
        }
        try {
            if (stopProjection) {
                mediaProjection?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop USB H.264 MediaProjection.", e)
        }
        handlerThread?.quitSafely()
    }

    private data class ResourcesSnapshot(
        val handlerThread: HandlerThread?,
        val mediaProjection: MediaProjection?,
        val projectionCallback: MediaProjection.Callback?,
        val virtualDisplay: VirtualDisplay?,
        val encoder: MediaCodec?,
        val inputSurface: Surface?,
    )

    companion object {
        private const val TAG = "UsbH264ScreenStreamer"
        private const val ENCODER_DEQUEUE_TIMEOUT_US = 10_000L
        private const val DEFAULT_AVC_CODEC_STRING = "avc1.42E01F"
        private val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)

        @Volatile
        var lastObservedProfile: UsbH264StreamProfile? = null
            private set
    }
}
