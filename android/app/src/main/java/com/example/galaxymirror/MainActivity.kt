package com.example.galaxymirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import io.ktor.server.http.content.staticResources
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import org.webrtc.*

class MainActivity : ComponentActivity() {
  private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
  private var mediaProjectionManager: MediaProjectionManager? = null

  // WebRTC Components
  private var peerConnectionFactory: PeerConnectionFactory? = null
  private var peerConnection: PeerConnection? = null
  private var videoTrack: VideoTrack? = null
  private var surfaceTextureHelper: SurfaceTextureHelper? = null
  private var videoCapturer: VideoCapturer? = null
  private var controlChannel: DataChannel? = null

  // 화면 캡처 권한 요청 런처
  private val screenCaptureLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      startMediaProjectionService(result.resultCode, result.data!!)
    } else {
      Log.e("GalaxyMirror", "Screen capture permission denied by user.")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    enableEdgeToEdge()
    setContent {
      GalaxyMirrorTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }

    // 1. Ktor 임베디드 웹서버 실행
    startKtorServer()

    // 2. 안드로이드 화면 캡처(MediaProjection) 동의 요청 팝업 즉시 기동
    requestScreenCapturePermission()
  }

  private fun requestScreenCapturePermission() {
    mediaProjectionManager?.createScreenCaptureIntent()?.let { intent ->
      screenCaptureLauncher.launch(intent)
    }
  }

  private fun startMediaProjectionService(resultCode: Int, data: Intent) {
    val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
      putExtra("resultCode", resultCode)
      putExtra("resultData", data)
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }
    Log.d("GalaxyMirror", "Started MediaProjectionService.")
  }

  private fun startKtorServer() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        Log.d("KtorServer", "Starting Ktor Server on 0.0.0.0:8080...")
        server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
          install(WebSockets)
          routing {
            // 정적 리소스 서빙 (resources/files/index.html & viewer.js 서빙)
            staticResources("/", "files")
            
            get("/status") {
              call.respondText("🌌 Galaxy Mirror Web Server is active! Port: 8080")
            }
            
            // WebRTC 1:1 시그널링 WebSocket
            webSocket("/signaling") {
              Log.d("KtorServer", "New WebRTC signaling WebSocket connection established!")
              try {
                for (frame in incoming) {
                  if (frame is Frame.Text) {
                    val text = frame.readText()
                    Log.d("KtorServer", "Signaling packet received: $text")
                    // 브라우저 뷰어가 보낸 OFFER/ANSWER/CANDIDATE 패킷을 내부 WebRTC 로직으로 주입하거나 릴레이
                    handleSignalingMessage(text) { response ->
                      launch { send(Frame.Text(response)) }
                    }
                  }
                }
              } catch (e: ClosedReceiveChannelException) {
                Log.d("KtorServer", "Signaling connection closed by peer.")
              } catch (e: Throwable) {
                Log.e("KtorServer", "Error in signaling session: ${e.message}", e)
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

  // 1:1 시그널링 메시지 처리 및 WebRTC PeerConnection 제어
  private fun handleSignalingMessage(message: String, sendResponse: (String) -> Unit) {
    try {
      val json = org.json.JSONObject(message)
      val type = json.getString("type")
      
      when (type) {
        "OFFER" -> {
          Log.d("WebRTC", "Offer received. Creating Answer...")
          val sdpObj = json.getJSONObject("payload")
          val sdpDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
            sdpObj.getString("sdp")
          )
          
          initializeWebRTC(sdpDescription, sendResponse)
        }
        "ICE_CANDIDATE" -> {
          Log.d("WebRTC", "ICE Candidate received.")
          val candidateObj = json.getJSONObject("payload")
          val candidate = IceCandidate(
            candidateObj.getString("sdpMid"),
            candidateObj.getInt("sdpMLineIndex"),
            candidateObj.getString("candidate")
          )
          peerConnection?.addIceCandidate(candidate)
        }
      }
    } catch (e: Exception) {
      Log.e("WebRTC", "Error parsing signaling JSON: ${e.message}", e)
    }
  }

  // WebRTC PeerConnection 초기설정 및 SDP Answer 생성
  private fun initializeWebRTC(remoteSdp: SessionDescription, sendResponse: (String) -> Unit) {
    try {
      // 1. PeerConnectionFactory 초기화
      val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
        .createInitializationOptions()
      PeerConnectionFactory.initialize(initOptions)

      val factoryOptions = PeerConnectionFactory.Options()
      val encoderFactory = DefaultVideoEncoderFactory(null, true, true)
      val decoderFactory = DefaultVideoDecoderFactory(null)
      
      peerConnectionFactory = PeerConnectionFactory.builder()
        .setOptions(factoryOptions)
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(decoderFactory)
        .createPeerConnectionFactory()

      // 2. ICE Servers 설정 (1:1이므로 간단한 STUN 1개면 동작)
      val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
      )
      
      val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
      }

      // 3. PeerConnection 생성
      peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
          candidate?.let {
            val json = org.json.JSONObject().apply {
              put("type", "ICE_CANDIDATE")
              put("payload", org.json.JSONObject().apply {
                put("candidate", it.sdp)
                put("sdpMid", it.sdpMid)
                put("sdpMLineIndex", it.sdpMLineIndex)
              })
            }
            sendResponse(json.toString())
          }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {
          dataChannel?.let { dc ->
            controlChannel = dc
            Log.d("WebRTC", "DataChannel received: ${dc.label()}")
            dc.registerObserver(object : DataChannel.Observer {
              override fun onBufferedAmountChange(previousAmount: Long) {}
              override fun onStateChange() {
                Log.d("WebRTC", "DataChannel state: ${dc.state()}")
              }
              override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                  val bytes = ByteArray(buffer.data.remaining())
                  buffer.data.get(bytes)
                  val text = String(bytes, Charsets.UTF_8)
                  Log.d("WebRTC", "DataChannel message: $text")
                  val json = org.json.JSONObject(text)
                  GalaxyMirrorAccessibilityService.instance?.handleControlEvent(json)
                    ?: Log.w("WebRTC", "AccessibilityService not connected yet!")
                } catch (e: Exception) {
                  Log.e("WebRTC", "Error processing DataChannel message: ${e.message}", e)
                }
              }
            })
          }
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
      })

      // 4. 화면 미디어 캡처 소스 & 비디오 트랙 생성
      surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", null)
      val videoSource = peerConnectionFactory?.createVideoSource(true)
      videoTrack = peerConnectionFactory?.createVideoTrack("video_track_id", videoSource)

      // 미디어 프로젝션 시스템 연동
      videoCapturer = ScreenCapturerAndroid(null, object : MediaProjection.Callback() {
        override fun onStop() {
          Log.d("WebRTC", "MediaProjection stopped inside capturer.")
        }
      })
      
      videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
      
      // 가로 세로 해상도 스케일링 설정 (최대 1080p, 30fps)
      videoCapturer?.startCapture(1080, 2400, 30)

      // 5. PeerConnection에 비디오 트랙 추가
      peerConnection?.addTrack(videoTrack, listOf("video_stream_id"))

      // 6. Remote Description (Offer) 적용 및 Answer 생성
      peerConnection?.setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {
          Log.d("WebRTC", "SetRemoteDescription success. Creating Answer...")
          peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
              desc?.let {
                peerConnection?.setLocalDescription(object : SdpObserver {
                  override fun onCreateSuccess(desc: SessionDescription?) {}
                  override fun onSetSuccess() {
                    Log.d("WebRTC", "SetLocalDescription success. Sending Answer...")
                    val json = org.json.JSONObject().apply {
                      put("type", "ANSWER")
                      put("payload", org.json.JSONObject().apply {
                        put("type", "answer")
                        put("sdp", it.description)
                      })
                    }
                    sendResponse(json.toString())
                  }
                  override fun onCreateFailure(reason: String?) {
                    Log.e("WebRTC", "setLocalDescription onCreateFailure: $reason")
                  }
                  override fun onSetFailure(reason: String?) {
                    Log.e("WebRTC", "setLocalDescription onSetFailure: $reason")
                  }
                }, it)
              }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) {
              Log.e("WebRTC", "createAnswer onCreateFailure: $reason")
            }
            override fun onSetFailure(reason: String?) {
              Log.e("WebRTC", "createAnswer onSetFailure: $reason")
            }
          }, MediaConstraints())
        }
        override fun onCreateFailure(reason: String?) {
          Log.e("WebRTC", "setRemoteDescription onCreateFailure: $reason")
        }
        override fun onSetFailure(reason: String?) {
          Log.e("WebRTC", "setRemoteDescription onSetFailure: $reason")
        }
      }, remoteSdp)

    } catch (e: Exception) {
      Log.e("WebRTC", "Error during WebRTC negotiation initialize: ${e.message}", e)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    
    // Ktor Server stop
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        server?.stop(1000, 2000)
        Log.d("KtorServer", "Ktor Server stopped.")
      } catch (e: Exception) {
        Log.e("KtorServer", "Error stopping Ktor Server", e)
      }
    }

    // WebRTC cleanup
    try {
      videoCapturer?.stopCapture()
      videoCapturer?.dispose()
      surfaceTextureHelper?.dispose()
      peerConnection?.close()
      peerConnectionFactory?.dispose()
      Log.d("WebRTC", "WebRTC resources cleaned up successfully.")
    } catch (e: Exception) {
      Log.e("WebRTC", "Error cleaning up WebRTC resources: ${e.message}", e)
    }
  }
}
