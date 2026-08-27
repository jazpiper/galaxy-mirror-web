package com.example.galaxymirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.system.measureTimeMillis

class UsbScreenStreamerBenchmarkTest {

    @Test
    fun benchmarkCanvasAndRectReuse() {
        val width = 1080
        val height = 1920
        val targetWidth = 720
        val targetHeight = 1280
        val iterations = 100_000

        // Simulate un-cached approach (allocating Canvas and Rect objects every frame)
        val jBitmap = mock(Bitmap::class.java)

        // Warmup
        repeat(5_000) {
            val c = mock(Canvas::class.java)
            val r1 = Rect(0, 0, width, height)
            val r2 = Rect(0, 0, targetWidth, targetHeight)
        }

        var cachedCanvas: Canvas? = null
        var cachedSrcRect: Rect? = null
        var cachedDestRect: Rect? = null

        // Warmup cached
        repeat(5_000) {
            val c = cachedCanvas ?: mock(Canvas::class.java).also { cachedCanvas = it }
            val s = cachedSrcRect ?: Rect(0, 0, width, height).also { cachedSrcRect = it }
            s.set(0, 0, width, height)
            val d = cachedDestRect ?: Rect(0, 0, targetWidth, targetHeight).also { cachedDestRect = it }
            d.set(0, 0, targetWidth, targetHeight)
        }

        val unoptimizedTime = measureTimeMillis {
            repeat(iterations) {
                val canvas = Canvas(jBitmap)
                val srcRect = Rect(0, 0, width, height)
                val destRect = Rect(0, 0, targetWidth, targetHeight)
            }
        }

        val optimizedTime = measureTimeMillis {
            repeat(iterations) {
                var c = cachedCanvas
                if (c == null) {
                    c = Canvas(jBitmap)
                    cachedCanvas = c
                }
                var s = cachedSrcRect
                if (s == null) {
                    s = Rect(0, 0, width, height)
                    cachedSrcRect = s
                } else {
                    s.set(0, 0, width, height)
                }
                var d = cachedDestRect
                if (d == null) {
                    d = Rect(0, 0, targetWidth, targetHeight)
                    cachedDestRect = d
                } else {
                    d.set(0, 0, targetWidth, targetHeight)
                }
            }
        }

        println("==================================================")
        println("Canvas & Rect Allocation Benchmark ($iterations iterations):")
        println("  Unoptimized (per-frame allocation): ${unoptimizedTime} ms")
        println("  Optimized (cached reuse): ${optimizedTime} ms")
        println("==================================================")

        assertNotNull(cachedCanvas)
        assertNotNull(cachedSrcRect)
        assertNotNull(cachedDestRect)
    }
}
