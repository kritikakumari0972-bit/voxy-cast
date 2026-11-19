package com.voxycast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit

class MirrorService : Service() {
    companion object {
        const val ACTION_STATUS = "com.voxycast.app.MIRROR_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CODE = "code"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "voxy_cast_mirroring"
        private const val NOTIFICATION_ID = 4101
    }

    private var httpClient: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
        if (resultCode == 0 || projectionData == null || serverUrl.isBlank()) {
            publishStatus("Missing screen-capture permission or server URL.")
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = buildNotification("Preparing screen capture…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        try {
            prepareCapture(projectionData)
            connectSignaling(serverUrl)
        } catch (error: Exception) {
            publishStatus("Could not start mirroring: " + (error.message ?: "unknown error"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun prepareCapture(projectionData: Intent) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers)
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory!!.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                publishStatus("WebRTC connection: " + state.name.lowercase())
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                sendJson(
                    JSONObject().put("type", "ice-candidate")
                        .put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                )
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                mediaStreams: Array<out org.webrtc.MediaStream>
            ) = Unit
        }) ?: throw IllegalStateException("Unable to create WebRTC peer connection")

        videoSource = factory!!.createVideoSource(true)
        surfaceTextureHelper = SurfaceTextureHelper.create("VoxyCastCapture", eglContext)
        screenCapturer = ScreenCapturerAndroid(
            projectionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    publishStatus("Screen capture permission ended.")
                    stopSelf()
                }
            }
        )
        screenCapturer!!.initialize(surfaceTextureHelper!!, this, videoSource!!.capturerObserver)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val longestSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val scale = minOf(1f, 1280f / longestSide.toFloat())
        val captureWidth = ((metrics.widthPixels * scale).toInt().coerceAtLeast(2)) and -2
        val captureHeight = ((metrics.heightPixels * scale).toInt().coerceAtLeast(2)) and -2
        screenCapturer!!.startCapture(captureWidth, captureHeight, 30)

        videoTrack = factory!!.createVideoTrack("voxy-screen-track", videoSource!!)
        peerConnection!!.addTrack(videoTrack!!, listOf("voxy-screen-stream"))
        publishStatus("Screen capture active. Creating a pairing code…")
    }

    private fun connectSignaling(baseUrl: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            normalized.startsWith("https://") -> "wss://" + normalized.removePrefix("https://")
            normalized.startsWith("http://") -> "ws://" + normalized.removePrefix("http://")
            normalized.startsWith("wss://") || normalized.startsWith("ws://") -> normalized
            else -> throw IllegalArgumentException("Server URL must start with http:// or https://")
        }
        httpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(wsBase + "/signal").build()
        socket = httpClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "create").toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handleSignal(text) }
            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                publishStatus("Signaling error: " + (throwable.message ?: "connection failed"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                publishStatus("Signaling connection closed.")
            }
        })
    }

    private fun handleSignal(raw: String) {
        try {
            val message = JSONObject(raw)
            when (message.optString("type")) {
                "room-created" -> {
                    val code = message.optString("code")
                    publishStatus("Ready. Open the browser receiver and enter this code.", code)
                    updateNotification("Waiting for receiver · code " + code)
                }
                "peer-joined" -> createOffer()
                "answer" -> {
                    val description = SessionDescription(SessionDescription.Type.ANSWER, message.getString("sdp"))
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription?) = Unit
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            pendingIce.forEach { peerConnection?.addIceCandidate(it) }
                            pendingIce.clear()
                            publishStatus("Receiver connected. Mirroring is live.")
                            updateNotification("Mirroring screen")
                        }
                        override fun onCreateFailure(error: String?) = Unit
                        override fun onSetFailure(error: String?) {
                            publishStatus("Could not apply receiver answer: " + (error ?: "unknown error"))
                        }
                    }, description)
                }
                "ice-candidate" -> {
                    val candidate = IceCandidate(
                        message.optString("sdpMid").takeIf { it.isNotEmpty() },
                        message.optInt("sdpMLineIndex"),
                        message.getString("candidate")
                    )
                    if (remoteDescriptionSet) peerConnection?.addIceCandidate(candidate)
                    else pendingIce.add(candidate)
                }
                "peer-left" -> publishStatus("Receiver disconnected. Waiting for another connection.")
                "room-closed" -> publishStatus("Pairing room closed.")
                "error" -> publishStatus("Signaling error: " + message.optString("code"))
            }
        } catch (error: Exception) {
            publishStatus("Invalid signaling response: " + (error.message ?: "unknown error"))
        }
    }

    private fun createOffer() {
        val connection = peerConnection ?: return
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) {
                    publishStatus("WebRTC did not create an offer.")
                    return
                }
                connection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        sendJson(JSONObject().put("type", "offer").put("sdp", description.description))
                        publishStatus("Pairing receiver and negotiating video…")
                    }
                    override fun onCreateFailure(error: String?) = Unit
                    override fun onSetFailure(error: String?) {
                        publishStatus("Could not set local offer: " + (error ?: "unknown error"))
                    }
                }, description)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                publishStatus("Could not create WebRTC offer: " + (error ?: "unknown error"))
            }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
    }

    private fun sendJson(message: JSONObject) { socket?.send(message.toString()) }

    private fun publishStatus(message: String, code: String? = null) {
        val intent = Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message)
        if (code != null) intent.putExtra(EXTRA_CODE, code)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Voxy Cast screen sharing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Voxy Cast is active")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        try { screenCapturer?.stopCapture() } catch (_: Exception) { }
        try { screenCapturer?.dispose() } catch (_: Exception) { }
        try { peerConnection?.close() } catch (_: Exception) { }
        try { peerConnection?.dispose() } catch (_: Exception) { }
        try { videoTrack?.dispose() } catch (_: Exception) { }
        try { videoSource?.dispose() } catch (_: Exception) { }
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) { }
        try { factory?.dispose() } catch (_: Exception) { }
        try { eglBase?.release() } catch (_: Exception) { }
        try { socket?.close(1000, "Sender stopped") } catch (_: Exception) { }
        httpClient?.dispatcher?.executorService?.shutdown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}