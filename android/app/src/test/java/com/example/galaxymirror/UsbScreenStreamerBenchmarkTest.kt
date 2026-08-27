package com.example.galaxymirror

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

class UsbScreenStreamerBenchmarkTest {

    private fun originalComputeFrameSignature(
        buffer: ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        width: Int,
        height: Int,
    ): Long {
        val dupBuffer = buffer.duplicate()
        val pStride = pixelStride.coerceAtLeast(1)
        val sampleRows = 8.coerceAtMost(height).coerceAtLeast(1)
        val sampleColumns = 8.coerceAtMost(width).coerceAtLeast(1)
        var hash = -3750763034362895579L

        for (rowIndex in 0 until sampleRows) {
            val y =
                if (sampleRows == 1) {
                    0
                } else {
                    rowIndex * (height - 1) / (sampleRows - 1)
                }
            for (columnIndex in 0 until sampleColumns) {
                val x =
                    if (sampleColumns == 1) {
                        0
                    } else {
                        columnIndex * (width - 1) / (sampleColumns - 1)
                    }
                val offset = y * rowStride + x * pStride
                if (offset in 0 until dupBuffer.limit()) {
                    hash = (hash xor dupBuffer.get(offset).toLong()) * 1099511628211L
                }
            }
        }
        return hash
    }

    private fun opt1_colOffsets_cmp(
        buffer: ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        width: Int,
        height: Int,
    ): Long {
        val limit = buffer.limit()
        val pStride = pixelStride.coerceAtLeast(1)
        val sampleRows = 8.coerceAtMost(height).coerceAtLeast(1)
        val sampleColumns = 8.coerceAtMost(width).coerceAtLeast(1)

        val colOffsets = IntArray(sampleColumns)
        val widthMinusOne = width - 1
        val maxColIndex = sampleColumns - 1
        for (columnIndex in 0 until sampleColumns) {
            val x = if (sampleColumns == 1) 0 else columnIndex * widthMinusOne / maxColIndex
            colOffsets[columnIndex] = x * pStride
        }

        var hash = -3750763034362895579L
        val heightMinusOne = height - 1
        val maxRowIndex = sampleRows - 1

        for (rowIndex in 0 until sampleRows) {
            val y = if (sampleRows == 1) 0 else rowIndex * heightMinusOne / maxRowIndex
            val rowOffset = y * rowStride
            for (columnIndex in 0 until sampleColumns) {
                val offset = rowOffset + colOffsets[columnIndex]
                if (offset >= 0 && offset < limit) {
                    hash = (hash xor buffer.get(offset).toLong()) * 1099511628211L
                }
            }
        }
        return hash
    }

    private fun opt1_colOffsets_range(
        buffer: ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        width: Int,
        height: Int,
    ): Long {
        val limit = buffer.limit()
        val pStride = pixelStride.coerceAtLeast(1)
        val sampleRows = 8.coerceAtMost(height).coerceAtLeast(1)
        val sampleColumns = 8.coerceAtMost(width).coerceAtLeast(1)

        val colOffsets = IntArray(sampleColumns)
        val widthMinusOne = width - 1
        val maxColIndex = sampleColumns - 1
        for (columnIndex in 0 until sampleColumns) {
            val x = if (sampleColumns == 1) 0 else columnIndex * widthMinusOne / maxColIndex
            colOffsets[columnIndex] = x * pStride
        }

        var hash = -3750763034362895579L
        val heightMinusOne = height - 1
        val maxRowIndex = sampleRows - 1

        for (rowIndex in 0 until sampleRows) {
            val y = if (sampleRows == 1) 0 else rowIndex * heightMinusOne / maxRowIndex
            val rowOffset = y * rowStride
            for (columnIndex in 0 until sampleColumns) {
                val offset = rowOffset + colOffsets[columnIndex]
                if (offset in 0 until limit) {
                    hash = (hash xor buffer.get(offset).toLong()) * 1099511628211L
                }
            }
        }
        return hash
    }

    @Test
    fun benchmarkComputeFrameSignature() {
        val width = 1080
        val height = 1920
        val pixelStride = 4
        val rowStride = 1080 * 4
        val size = rowStride * height
        val buffer = ByteBuffer.allocateDirect(size)
        for (i in 0 until size step 100) {
            buffer.put(i, (i % 256).toByte())
        }

        val testConfigs = listOf(
            Triple(1080, 1920, 4),
            Triple(720, 1280, 4),
            Triple(1, 1, 1),
            Triple(2, 2, 2),
            Triple(8, 8, 4),
            Triple(4, 100, 1)
        )

        for ((w, h, ps) in testConfigs) {
            val rs = w * ps + 16
            val bufSize = rs * h
            val testBuf = ByteBuffer.allocateDirect(bufSize)
            for (i in 0 until bufSize step 10) {
                testBuf.put(i, ((i * 31) % 256).toByte())
            }
            val origHash = originalComputeFrameSignature(testBuf, ps, rs, w, h)
            val optCmpHash = opt1_colOffsets_cmp(testBuf, ps, rs, w, h)
            val optRangeHash = opt1_colOffsets_range(testBuf, ps, rs, w, h)
            assertEquals("Hashes cmp match", origHash, optCmpHash)
            assertEquals("Hashes range match", origHash, optRangeHash)
        }

        val iterations = 1_000_000

        // Warmup
        for (i in 0 until 50_000) {
            originalComputeFrameSignature(buffer, pixelStride, rowStride, width, height)
            opt1_colOffsets_cmp(buffer, pixelStride, rowStride, width, height)
            opt1_colOffsets_range(buffer, pixelStride, rowStride, width, height)
        }

        val origTime = measureTimeMillis {
            for (i in 0 until iterations) {
                originalComputeFrameSignature(buffer, pixelStride, rowStride, width, height)
            }
        }

        val optCmpTime = measureTimeMillis {
            for (i in 0 until iterations) {
                opt1_colOffsets_cmp(buffer, pixelStride, rowStride, width, height)
            }
        }

        val optRangeTime = measureTimeMillis {
            for (i in 0 until iterations) {
                opt1_colOffsets_range(buffer, pixelStride, rowStride, width, height)
            }
        }

        val speedupCmp = String.format("%.2f", ((origTime - optCmpTime).toDouble() / origTime) * 100)
        val speedupRange = String.format("%.2f", ((origTime - optRangeTime).toDouble() / origTime) * 100)

        println("BENCHMARK Original time: ${origTime}ms")
        println("BENCHMARK Opt1 Cmp time: ${optCmpTime}ms (Speedup: ${speedupCmp}%)")
        println("BENCHMARK Opt1 Range time: ${optRangeTime}ms (Speedup: ${speedupRange}%)")
    }
}
