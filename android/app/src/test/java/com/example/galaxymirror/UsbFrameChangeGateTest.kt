package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class UsbFrameChangeGateTest {
    @Test
    fun emitsFirstFrame() {
        val gate = UsbFrameChangeGate(maxStillSkips = 4, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(
            UsbFrameChangeDecision.EMIT,
            gate.evaluate(signature = 10L, nowNanos = 0L),
        )
    }

    @Test
    fun skipsRepeatedStillFramesUntilBudgetIsExhausted() {
        val gate = UsbFrameChangeGate(maxStillSkips = 4, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 0L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 100_000_000L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 200_000_000L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 300_000_000L))
        assertEquals(UsbFrameChangeDecision.SKIP_STILL, gate.evaluate(10L, 400_000_000L))
        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 500_000_000L))
    }

    @Test
    fun emitsWhenSignatureChanges() {
        val gate = UsbFrameChangeGate(maxStillSkips = 4, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 0L))
        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(11L, 100_000_000L))
    }

    @Test
    fun emitsHeartbeatEvenWhenStill() {
        val gate = UsbFrameChangeGate(maxStillSkips = 100, heartbeatEveryNanos = 1_000_000_000L)

        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 0L))
        assertEquals(UsbFrameChangeDecision.EMIT, gate.evaluate(10L, 1_000_000_000L))
    }
}
