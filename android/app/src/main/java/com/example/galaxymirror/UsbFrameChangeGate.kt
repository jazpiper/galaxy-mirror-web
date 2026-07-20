package com.example.galaxymirror

enum class UsbFrameChangeDecision {
    EMIT,
    SKIP_STILL,
}

class UsbFrameChangeGate(
    private val maxStillSkips: Int = 4,
    private val heartbeatEveryNanos: Long = 1_000_000_000L,
) {
    private var lastSignature: Long? = null
    private var skippedStillFrames = 0
    private var lastEmitNanos: Long? = null

    fun evaluate(
        signature: Long,
        nowNanos: Long = System.nanoTime(),
    ): UsbFrameChangeDecision {
        val previousSignature = lastSignature
        val previousEmitNanos = lastEmitNanos
        val signatureChanged = previousSignature == null || previousSignature != signature
        val heartbeatDue =
            previousEmitNanos != null && nowNanos - previousEmitNanos >= heartbeatEveryNanos
        val skipBudgetExhausted = skippedStillFrames >= maxStillSkips

        return if (signatureChanged || heartbeatDue || skipBudgetExhausted) {
            lastSignature = signature
            skippedStillFrames = 0
            lastEmitNanos = nowNanos
            UsbFrameChangeDecision.EMIT
        } else {
            skippedStillFrames += 1
            UsbFrameChangeDecision.SKIP_STILL
        }
    }

    fun reset() {
        lastSignature = null
        skippedStillFrames = 0
        lastEmitNanos = null
    }
}
