package com.example.galaxymirror

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.system.measureTimeMillis

class CoroutineBenchmarkTest {

    @Test
    fun benchmarkContextSwitch() = runBlocking {
        val state = MirrorSessionState()

        // Warm up
        repeat(1000) {
            val a = withContext(Dispatchers.Default) {
                state.isActive(0, MirrorTransport.USB_JPEG)
            }
        }

        val time1 = measureTimeMillis {
            repeat(100_000) {
                val a = withContext(Dispatchers.Default) {
                    state.isActive(0, MirrorTransport.USB_JPEG)
                }
            }
        }

        val time2 = measureTimeMillis {
            repeat(100_000) {
                val a = state.isActive(0, MirrorTransport.USB_JPEG)
            }
        }

        println("===============================")
        println("Time withContext(Dispatchers.Default): $time1 ms")
        println("Time direct access: $time2 ms")
        println("===============================")
    }
}
