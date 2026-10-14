package com.example.gochat.core.webrtc

import android.content.Context
import org.webrtc.*
import java.util.ArrayList

class WebRTCClient(
    private val context: Context,
    private val observer: PeerConnection.Observer
) {
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory: PeerConnectionFactory by lazy { createPeerConnectionFactory() }
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        // Add TURN servers for real-world network traversal
        PeerConnection.IceServer.builder("turn:turn.gochat.example.com")
            .setUsername("user")
            .setPassword("pass")
            .createIceServer()
    )
    private var peerConnection: PeerConnection? = null

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Enable ICE Restart
            iceConnectionReceivingTimeout = 5000
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
    }

    fun createOffer(sdpObserver: SdpObserver, iceRestart: Boolean = false) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        peerConnection?.createOffer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        sdpObserver.onCreateSuccess(sdp)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
        }, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        sdpObserver.onCreateSuccess(sdp)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, sdpObserver: SdpObserver) {
        peerConnection?.setRemoteDescription(sdpObserver, sdp)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun addStream(mediaStream: MediaStream) {
        // Under Unified Plan SdpSemantics, calling peerConnection.addStream() directly
        // triggers an RTC_CHECK(!IsUnifiedPlan()) abort. Tracks must be registered via addTrack().
        val streamIds = listOf(mediaStream.id.ifBlank { "local_stream" })
        mediaStream.audioTracks?.forEach { audioTrack ->
            peerConnection?.addTrack(audioTrack, streamIds)
        }
        mediaStream.videoTracks?.forEach { videoTrack ->
            peerConnection?.addTrack(videoTrack, streamIds)
        }
    }

    fun addTrack(track: MediaStreamTrack, streamIds: List<String> = listOf("local_stream")): RtpSender? {
        return peerConnection?.addTrack(track, streamIds)
    }

    fun createLocalStream(streamId: String, isVideo: Boolean = false): MediaStream {
        val stream = peerConnectionFactory.createLocalMediaStream(streamId)
        
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        val audioTrack = peerConnectionFactory.createAudioTrack("${streamId}_audio", audioSource)
        stream.addTrack(audioTrack)

        if (isVideo) {
            try {
                val capturer = createCameraCapturer()
                if (capturer != null) {
                    videoCapturer = capturer
                    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
                    val vSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                    videoSource = vSource
                    capturer.initialize(surfaceTextureHelper, context, vSource.capturerObserver)
                    capturer.startCapture(1280, 720, 30)
                    val videoTrack = peerConnectionFactory.createVideoTrack("${streamId}_video", vSource)
                    stream.addTrack(videoTrack)
                }
            } catch (e: Exception) {
                android.util.Log.e("WebRTCClient", "Failed to start camera capturer for video call", e)
            }
        }

        return stream
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        val deviceNames = enumerator.deviceNames
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val targetDevice = frontCamera ?: deviceNames.firstOrNull()
        return targetDevice?.let { enumerator.createCapturer(it, null) }
    }

    fun createVideoTrack(videoSource: VideoSource): VideoTrack {
        return peerConnectionFactory.createVideoTrack("video_track", videoSource)
    }

    fun createVideoSource(isScreenshot: Boolean): VideoSource {
        return peerConnectionFactory.createVideoSource(isScreenshot)
    }

    fun getEglBaseContext(): EglBase.Context = eglBaseContext

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {}
        try {
            videoCapturer?.dispose()
        } catch (_: Exception) {}
        videoCapturer = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnection?.close()
        peerConnection = null
    }
}
