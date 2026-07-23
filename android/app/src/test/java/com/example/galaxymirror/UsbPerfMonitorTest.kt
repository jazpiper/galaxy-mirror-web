package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class UsbPerfMonitorTest {
    @Test
    fun recordsFrameCountersAndEncodeTiming() {
        var now = 1_000L
        val monitor = UsbPerfMonitor(clockMillis = { now })

        monitor.recordFrameAcquired()
        monitor.recordFrameDroppedByFps()
        monitor.recordFrameSkippedByStillness()
        monitor.recordFrameEncoded(bytes = 100_000, encodeMillis = 20L)
        monitor.recordEncodeFailure()
        now = 2_000L

        val snapshot =
            monitor.snapshot(
                profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO),
                thermalStatus = UsbThermalStatus.NORMAL,
                thermalHeadroom = 0.8f,
                batteryTemperatureC = 32.5f,
            )

        assertEquals(1L, snapshot.framesAcquired)
        assertEquals(1L, snapshot.framesDroppedByFps)
        assertEquals(1L, snapshot.framesSkippedByStillness)
        assertEquals(1L, snapshot.framesEmitted)
        assertEquals(1L, snapshot.encodeFailures)
        assertEquals(20L, snapshot.lastEncodeMillis)
        assertEquals(20.0, snapshot.averageEncodeMillis, 0.01)
        assertEquals(100_000L, snapshot.bytesEmitted)
        assertEquals(100_000L, snapshot.bytesPerSecond)
        assertEquals(UsbStreamProfileTier.BALANCED, snapshot.profile.tier)
        assertEquals(UsbThermalStatus.NORMAL, snapshot.thermalStatus)
        assertEquals(0.8f, snapshot.thermalHeadroom)
        assertEquals(32.5f, snapshot.batteryTemperatureC)
    }

    @Test
    fun snapshotJsonContainsStableKeysForDebugEndpoint() {
        var now = 1_000L
        val monitor = UsbPerfMonitor(clockMillis = { now })
        monitor.recordFrameEncoded(bytes = 50_000, encodeMillis = 10L)
        now = 2_000L

        val json =
            monitor
                .snapshot(
                    profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.DATA_SAVER),
                    thermalStatus = UsbThermalStatus.LIGHT,
                    thermalHeadroom = null,
                    batteryTemperatureC = null,
                ).toJson()

        assertEquals("COOL", json.getJSONObject("profile").getString("tier"))
        assertEquals(360, json.getJSONObject("profile").getInt("width"))
        assertEquals("LIGHT", json.getString("thermalStatus"))
        assertTrue(json.has("bytesPerSecond"))
        assertTrue(json.has("framesAcquired"))
        assertTrue(json.has("framesEmitted"))
    }

    @Test
    fun snapshotJsonCanReportH264ProfileForDebugEndpoint() {
        var now = 1_000L
        val monitor = UsbPerfMonitor(clockMillis = { now })
        monitor.recordFrameEncoded(bytes = 75_000, encodeMillis = 4L)
        now = 2_000L

        val h264Profile = UsbH264StreamProfilePolicy.resolve(StreamQualityMode.AUTO)
        val json =
            monitor
                .snapshot(
                    profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO),
                    thermalStatus = UsbThermalStatus.NORMAL,
                    thermalHeadroom = null,
                    batteryTemperatureC = null,
                    codec = UsbVideoCodec.H264,
                    h264Profile = h264Profile,
                ).toJson()

        assertEquals("h264", json.getString("codec"))
        assertEquals(4_500_000, json.getInt("bitrateBps"))
        assertEquals(720, json.getJSONObject("profile").getInt("width"))
        assertEquals(1600, json.getJSONObject("profile").getInt("height"))
        assertEquals(30, json.getJSONObject("profile").getInt("fps"))
        assertEquals(4_500_000, json.getJSONObject("profile").getInt("bitrateBps"))
    }

    @Test
    fun snapshotJsonConvertsNonFiniteThermalNumbersToNull() {
        val monitor = UsbPerfMonitor(clockMillis = { 1_000L })

        val json =
            monitor
                .snapshot(
                    profile = UsbStreamProfilePolicy.resolve(StreamQualityMode.AUTO),
                    thermalStatus = UsbThermalStatus.NORMAL,
                    thermalHeadroom = Float.NaN,
                    batteryTemperatureC = Float.POSITIVE_INFINITY,
                ).toJson()

        assertTrue(json.isNull("thermalHeadroom"))
        assertTrue(json.isNull("batteryTemperatureC"))
    }
}
