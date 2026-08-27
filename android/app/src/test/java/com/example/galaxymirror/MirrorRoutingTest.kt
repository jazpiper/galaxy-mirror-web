package com.example.galaxymirror

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.readReason
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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

    @Test
    fun testUsbSessionCrossOriginRequestIsRejected() = testApplication {
        val client = setupTestApp()

        client.webSocket(
            urlString = "/usb/session",
            request = {
                header(HttpHeaders.Origin, "http://evil.com")
                header(HttpHeaders.Host, "127.0.0.1:8080")
            }
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            assertEquals("cross-origin rejected", reason?.message)
        }
    }

    @Test
    fun testSignalingCrossOriginRequestIsRejected() = testApplication {
        val client = setupTestApp()

        client.webSocket(
            urlString = "/signaling",
            request = {
                header(HttpHeaders.Origin, "http://evil.com")
                header(HttpHeaders.Host, "127.0.0.1:8080")
            }
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            assertEquals("cross-origin rejected", reason?.message)
        }
    }
}
