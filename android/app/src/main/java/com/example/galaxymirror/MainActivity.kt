package com.example.galaxymirror

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.galaxymirror.theme.GalaxyMirrorTheme
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private var server: io.ktor.server.engine.ApplicationEngine? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      GalaxyMirrorTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }

    startKtorServer()
  }

  private fun startKtorServer() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        Log.d("KtorServer", "Starting Ktor Server on 0.0.0.0:8080...")
        server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
          install(WebSockets)
          routing {
            get("/") {
              call.respondText("🌌 Galaxy Mirror Web Server is active! Port: 8080")
            }
            webSocket("/signaling") {
              Log.d("KtorServer", "New WebRTC signaling WebSocket connection established!")
              for (frame in incoming) {
                if (frame is Frame.Text) {
                  val text = frame.readText()
                  Log.d("KtorServer", "Received: $text")
                  send(Frame.Text("Echo from Galaxy: $text"))
                }
              }
            }
          }
        }.start(wait = false)
        Log.d("KtorServer", "Ktor Server successfully started.")
      } catch (e: Exception) {
        Log.e("KtorServer", "Error starting Ktor Server: ${e.message}", e)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        server?.stop(1000, 2000)
        Log.d("KtorServer", "Ktor Server stopped.")
      } catch (e: Exception) {
        Log.e("KtorServer", "Error stopping Ktor Server", e)
      }
    }
  }
}
