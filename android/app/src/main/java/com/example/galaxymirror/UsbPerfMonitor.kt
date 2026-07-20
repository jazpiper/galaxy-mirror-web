package com.example.galaxymirror

import org.json.JSONObject

class UsbPerfMonitor(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var startedAtMillis = clockMillis()
    private var framesAcquired = 0L
    private var framesDroppedByFps = 0L
    private var framesSkippedByStillness = 0L
    private var framesEmitted = 0L
    private var encodeFailures = 0L
    private var bytesEmitted = 0L
    private var totalEncodeMillis = 0L
    private var lastEncodeMillis = 0L

    fun recordFrameAcquired() =
        synchronized(lock) {
            framesAcquired += 1
        }

    fun recordFrameDroppedByFps() =
        synchronized(lock) {
            framesDroppedByFps += 1
        }

    fun recordFrameSkippedByStillness() =
        synchronized(lock) {
            framesSkippedByStillness += 1
        }

    fun recordFrameEncoded(
        bytes: Int,
        encodeMillis: Long,
    ) = synchronized(lock) {
        framesEmitted += 1
        bytesEmitted += bytes.coerceAtLeast(0)
        totalEncodeMillis += encodeMillis.coerceAtLeast(0)
        lastEncodeMillis = encodeMillis.coerceAtLeast(0)
    }

    fun recordEncodeFailure() =
        synchronized(lock) {
            encodeFailures += 1
        }

    fun reset() =
        synchronized(lock) {
            startedAtMillis = clockMillis()
            framesAcquired = 0L
            framesDroppedByFps = 0L
            framesSkippedByStillness = 0L
            framesEmitted = 0L
            encodeFailures = 0L
            bytesEmitted = 0L
            totalEncodeMillis = 0L
            lastEncodeMillis = 0L
        }

    fun snapshot(
        profile: UsbStreamProfile,
        thermalStatus: UsbThermalStatus,
        thermalHeadroom: Float?,
        batteryTemperatureC: Float?,
        codec: UsbVideoCodec = UsbVideoCodec.JPEG,
        h264Profile: UsbH264StreamProfile? = null,
    ): UsbPerfSnapshot =
        synchronized(lock) {
            val elapsedMillis = (clockMillis() - startedAtMillis).coerceAtLeast(1L)
            UsbPerfSnapshot(
                profile = profile,
                codec = codec,
                h264Profile = h264Profile,
                thermalStatus = thermalStatus,
                thermalHeadroom = thermalHeadroom,
                batteryTemperatureC = batteryTemperatureC,
                framesAcquired = framesAcquired,
                framesDroppedByFps = framesDroppedByFps,
                framesSkippedByStillness = framesSkippedByStillness,
                framesEmitted = framesEmitted,
                encodeFailures = encodeFailures,
                bytesEmitted = bytesEmitted,
                bytesPerSecond = bytesEmitted * 1_000L / elapsedMillis,
                lastEncodeMillis = lastEncodeMillis,
                averageEncodeMillis =
                    if (framesEmitted == 0L) {
                        0.0
                    } else {
                        totalEncodeMillis.toDouble() / framesEmitted.toDouble()
                    },
            )
        }
}

data class UsbPerfSnapshot(
    val profile: UsbStreamProfile,
    val codec: UsbVideoCodec,
    val h264Profile: UsbH264StreamProfile?,
    val thermalStatus: UsbThermalStatus,
    val thermalHeadroom: Float?,
    val batteryTemperatureC: Float?,
    val framesAcquired: Long,
    val framesDroppedByFps: Long,
    val framesSkippedByStillness: Long,
    val framesEmitted: Long,
    val encodeFailures: Long,
    val bytesEmitted: Long,
    val bytesPerSecond: Long,
    val lastEncodeMillis: Long,
    val averageEncodeMillis: Double,
) {
    fun toJson(): JSONObject {
        val profileJson =
            if (codec == UsbVideoCodec.H264 && h264Profile != null) {
                JSONObject()
                    .put("tier", h264Profile.tier.name)
                    .put("width", h264Profile.width)
                    .put("height", h264Profile.height)
                    .put("fps", h264Profile.fps)
                    .put("bitrateBps", h264Profile.bitrateBps)
                    .put("keyFrameIntervalSeconds", h264Profile.keyFrameIntervalSeconds)
                    .put("mime", h264Profile.mime)
                    .put("policy", h264Profile.policy)
            } else {
                JSONObject()
                    .put("tier", profile.tier.name)
                    .put("width", profile.width)
                    .put("height", profile.height)
                    .put("fps", profile.fps)
                    .put("jpegQuality", profile.jpegQuality)
                    .put("policy", profile.policy)
            }
        return JSONObject()
            .put("codec", codec.wireValue)
            .put("bitrateBps", h264Profile?.bitrateBps ?: JSONObject.NULL)
            .put("profile", profileJson)
            .put("thermalStatus", thermalStatus.name)
            .put("thermalHeadroom", thermalHeadroom.jsonValueOrNull())
            .put("batteryTemperatureC", batteryTemperatureC.jsonValueOrNull())
            .put("framesAcquired", framesAcquired)
            .put("framesDroppedByFps", framesDroppedByFps)
            .put("framesSkippedByStillness", framesSkippedByStillness)
            .put("framesEmitted", framesEmitted)
            .put("encodeFailures", encodeFailures)
            .put("bytesEmitted", bytesEmitted)
            .put("bytesPerSecond", bytesPerSecond)
            .put("lastEncodeMillis", lastEncodeMillis)
            .put(
                "averageEncodeMillis",
                if (averageEncodeMillis.isFinite()) averageEncodeMillis else JSONObject.NULL,
            )
    }
}

private fun Float?.jsonValueOrNull(): Any =
    if (this != null && isFinite()) this else JSONObject.NULL
