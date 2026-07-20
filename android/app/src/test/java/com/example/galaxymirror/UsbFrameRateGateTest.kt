package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class UsbFrameRateGateTest {
    @Test
    fun acceptsFirstFrameImmediately() {
        val gate = UsbFrameRateGate(fps = 10)

        assertTrue(gate.shouldEmit(nowNanos = 1_000L))
    }

    @Test
    fun rejectsFramesBeforeInterval() {
        val gate = UsbFrameRateGate(fps = 10)

        assertTrue(gate.shouldEmit(nowNanos = 0L))
        assertFalse(gate.shouldEmit(nowNanos = 50_000_000L))
        assertTrue(gate.shouldEmit(nowNanos = 100_000_000L))
    }

    @Test
    fun updateFpsChangesIntervalWithoutRecreatingGate() {
        val gate = UsbFrameRateGate(fps = 10)

        assertTrue(gate.shouldEmit(nowNanos = 0L))
        assertFalse(gate.shouldEmit(nowNanos = 60_000_000L))

        gate.updateFps(4)

        assertTrue(gate.shouldEmit(nowNanos = 250_000_000L))
    }
}
