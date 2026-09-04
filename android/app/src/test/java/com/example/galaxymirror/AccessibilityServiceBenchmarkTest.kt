package com.example.galaxymirror

import android.content.res.Resources
import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.system.measureTimeMillis

class AccessibilityServiceBenchmarkTest {

    private class UncachedScreenDimensions(private val resources: Resources) {
        fun getScreenWidth(): Int = resources.displayMetrics.widthPixels
        fun getScreenHeight(): Int = resources.displayMetrics.heightPixels

        fun processTap(xRatio: Double, yRatio: Double): Pair<Float, Float> {
            val x = (xRatio * getScreenWidth()).toFloat()
            val y = (yRatio * getScreenHeight()).toFloat()
            return Pair(x, y)
        }

        fun processSwipe(x1Ratio: Double, y1Ratio: Double, x2Ratio: Double, y2Ratio: Double): Quad {
            val x1 = (x1Ratio * getScreenWidth()).toFloat()
            val y1 = (y1Ratio * getScreenHeight()).toFloat()
            val x2 = (x2Ratio * getScreenWidth()).toFloat()
            val y2 = (y2Ratio * getScreenHeight()).toFloat()
            return Quad(x1, y1, x2, y2)
        }
    }

    private class CachedScreenDimensions(private val resources: Resources) {
        @Volatile
        private var cachedWidth: Int = 0
        @Volatile
        private var cachedHeight: Int = 0

        init {
            updateDimensions()
        }

        fun updateDimensions() {
            val metrics = resources.displayMetrics
            cachedWidth = metrics.widthPixels
            cachedHeight = metrics.heightPixels
        }

        fun getScreenWidth(): Int {
            if (cachedWidth == 0) updateDimensions()
            return cachedWidth
        }

        fun getScreenHeight(): Int {
            if (cachedHeight == 0) updateDimensions()
            return cachedHeight
        }

        fun processTap(xRatio: Double, yRatio: Double): Pair<Float, Float> {
            val width = getScreenWidth()
            val height = getScreenHeight()
            val x = (xRatio * width).toFloat()
            val y = (yRatio * height).toFloat()
            return Pair(x, y)
        }

        fun processSwipe(x1Ratio: Double, y1Ratio: Double, x2Ratio: Double, y2Ratio: Double): Quad {
            val width = getScreenWidth()
            val height = getScreenHeight()
            val x1 = (x1Ratio * width).toFloat()
            val y1 = (y1Ratio * height).toFloat()
            val x2 = (x2Ratio * width).toFloat()
            val y2 = (y2Ratio * height).toFloat()
            return Quad(x1, y1, x2, y2)
        }
    }

    data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    @Test
    fun benchmarkScreenDimensionLookups() {
        val mockResources = mock(Resources::class.java)
        val displayMetrics = DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 2400
        }
        `when`(mockResources.displayMetrics).thenReturn(displayMetrics)

        val uncached = UncachedScreenDimensions(mockResources)
        val cached = CachedScreenDimensions(mockResources)

        // Sanity test to verify identical output
        val tapUncached = uncached.processTap(0.5, 0.25)
        val tapCached = cached.processTap(0.5, 0.25)
        assertEquals(tapUncached, tapCached)

        val swipeUncached = uncached.processSwipe(0.1, 0.2, 0.8, 0.9)
        val swipeCached = cached.processSwipe(0.1, 0.2, 0.8, 0.9)
        assertEquals(swipeUncached, swipeCached)

        val iterations = 50_000

        // Warmup
        repeat(5_000) {
            uncached.processTap(0.5, 0.25)
            uncached.processSwipe(0.1, 0.2, 0.8, 0.9)
            cached.processTap(0.5, 0.25)
            cached.processSwipe(0.1, 0.2, 0.8, 0.9)
        }

        val uncachedTime = measureTimeMillis {
            repeat(iterations) {
                uncached.processTap(0.5, 0.25)
                uncached.processSwipe(0.1, 0.2, 0.8, 0.9)
            }
        }

        val cachedTime = measureTimeMillis {
            repeat(iterations) {
                cached.processTap(0.5, 0.25)
                cached.processSwipe(0.1, 0.2, 0.8, 0.9)
            }
        }

        val speedup = if (uncachedTime > 0) {
            String.format("%.2f", ((uncachedTime - cachedTime).toDouble() / uncachedTime) * 100)
        } else "0.00"

        println("BENCHMARK Uncached time: ${uncachedTime}ms")
        println("BENCHMARK Cached time: ${cachedTime}ms (Improvement: ${speedup}%)")
    }
}
