package com.example.galaxymirror

class UsbFrameRateGate(fps: Int) {
    private var intervalNanos: Long = intervalFor(fps)
    private var lastEmitNanos: Long? = null

    fun updateFps(fps: Int) {
        intervalNanos = intervalFor(fps)
    }

    fun shouldEmit(nowNanos: Long = System.nanoTime()): Boolean {
        val last = lastEmitNanos
        if (last == null || nowNanos - last >= intervalNanos) {
            lastEmitNanos = nowNanos
            return true
        }
        return false
    }

    private fun intervalFor(fps: Int): Long = 1_000_000_000L / fps.coerceAtLeast(1)
}
