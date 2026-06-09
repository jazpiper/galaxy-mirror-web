package com.example.galaxymirror

class UsbFrameRateGate(fps: Int) {
    private val intervalNanos: Long = 1_000_000_000L / fps.coerceAtLeast(1)
    private var lastEmitNanos: Long? = null

    fun shouldEmit(nowNanos: Long = System.nanoTime()): Boolean {
        val last = lastEmitNanos
        if (last == null || nowNanos - last >= intervalNanos) {
            lastEmitNanos = nowNanos
            return true
        }
        return false
    }
}
