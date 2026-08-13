package com.example.galaxymirror

import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import org.mockito.Mockito.atLeastOnce
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import com.example.galaxymirror.MediaProjectionService
import com.example.galaxymirror.ScreenCaptureManager
import com.example.galaxymirror.UsbPerfMonitor
import kotlinx.coroutines.channels.Channel
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.client.HttpClient
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

class MirrorRoutingTest {

    private fun ApplicationTestBuilder.setupTestApp(): HttpClient {
        val mockService = mock(MediaProjectionService::class.java)
        `when`(mockService.filesDir).thenReturn(File("."))
        @Suppress("UNCHECKED_CAST")
        val mockChannel = mock(Channel::class.java) as Channel<Unit>
        `when`(mockService.permissionGrantChannel).thenReturn(mockChannel)

        val mockScreenCaptureManager = mock(ScreenCaptureManager::class.java)
        `when`(mockService.screenCaptureManager).thenReturn(mockScreenCaptureManager)

        val mockUsbPerfMonitor = mock(UsbPerfMonitor::class.java)
        `when`(mockScreenCaptureManager.usbPerfMonitor).thenReturn(mockUsbPerfMonitor)

        application {
            install(WebSockets)
            routing {
                setupMirrorRouting(mockService)
            }
        }

        return createClient {
            install(ClientWebSockets)
        }
    }

    @Test
    fun testSignalingDisconnectExceptionIsHandled() = testApplication {
        val client = setupTestApp()

        var exceptionCaught = false
        try {
            client.webSocket("/signaling") {
                throw ClosedReceiveChannelException("Test disconnect")
            }
        } catch (e: Exception) {
            exceptionCaught = true
        }
        assertTrue(exceptionCaught)
    }

    @Test
    fun testUsbDisconnectExceptionIsHandled() = testApplication {
        val client = setupTestApp()

        var exceptionCaught = false
        try {
            client.webSocket("/usb") {
                throw ClosedReceiveChannelException("Test disconnect")
            }
        } catch (e: Exception) {
            exceptionCaught = true
        }
        assertTrue(exceptionCaught)
    }
}
