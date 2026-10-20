package com.example.gochat.core.webrtc

import android.content.Context
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.ArrayList

class WebRTCClient(
    private val context: Context,
    private val observer: PeerConnection.Observer,
    // STUN alone fails between two phones on carrier NAT; pass TURN servers (fetched from
    // the backend) so relayed connections work. Falls back to public STUN when empty.
    iceServers: List<PeerConnection.IceServer>? = null
) {
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory: PeerConnectionFactory by lazy { createPeerConnectionFactory() }
    private val iceServers: List<PeerConnection.IceServer> = iceServers?.takeIf { it.isNotEmpty() }
        ?: listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
    private var peerConnection: PeerConnection? = null

    // Remote ICE candidates that arrived before the remote description was applied.
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    // Every local candidate gathered so far, so they can be resent with a fresh offer.
    private val localCandidates = mutableListOf<IceCandidate>()

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var videoSender: RtpSender? = null
    private var localVideoTrack: VideoTrack? = null
    private var microphoneEnabled = true

    var noiseSuppressionEnabled = true
        private set

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
        // Hardware noise suppression is left off so the in-call "Noise cancellation"
        // switch (WebRTC software suppression) actually controls the result.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
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
        peerConnection?.setRemoteDescription(object : SdpObserver by sdpObserver {
            override fun onSetSuccess() {
                drainPendingRemoteCandidates()
                sdpObserver.onSetSuccess()
            }
        }, sdp)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        val pc = peerConnection ?: return
        synchronized(pendingRemoteCandidates) {
            if (pc.remoteDescription == null) {
                pendingRemoteCandidates.add(iceCandidate)
                return
            }
        }
        pc.addIceCandidate(iceCandidate)
    }

    private fun drainPendingRemoteCandidates() {
        val queued = synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        queued.forEach { peerConnection?.addIceCandidate(it) }
    }

    fun recordLocalCandidate(candidate: IceCandidate) {
        synchronized(localCandidates) { localCandidates.add(candidate) }
    }

    fun getLocalCandidates(): List<IceCandidate> = synchronized(localCandidates) { localCandidates.toList() }

    fun addStream(mediaStream: MediaStream) {
        // Under Unified Plan SdpSemantics, calling peerConnection.addStream() directly
        // triggers an RTC_CHECK(!IsUnifiedPlan()) abort. Tracks must be registered via addTrack().
        val streamIds = listOf(mediaStream.id.ifBlank { "local_stream" })
        mediaStream.audioTracks?.forEach { audioTrack ->
            audioSender = peerConnection?.addTrack(audioTrack, streamIds)
        }
        mediaStream.videoTracks?.forEach { videoTrack ->
            videoSender = peerConnection?.addTrack(videoTrack, streamIds)
        }
    }

    fun addTrack(track: MediaStreamTrack, streamIds: List<String> = listOf("local_stream")): RtpSender? {
        return peerConnection?.addTrack(track, streamIds)
    }

    fun createLocalStream(streamId: String, isVideo: Boolean = false): MediaStream {
        val stream = peerConnectionFactory.createLocalMediaStream(streamId)

        val source = peerConnectionFactory.createAudioSource(audioConstraints(noiseSuppressionEnabled))
        val audioTrack = peerConnectionFactory.createAudioTrack("${streamId}_audio", source)
        audioSource = source
        localAudioTrack = audioTrack
        stream.addTrack(audioTrack)

        if (isVideo) {
            startCamera("${streamId}_video")?.let { stream.addTrack(it) }
        }

        return stream
    }

    private fun audioConstraints(noiseSuppression: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", noiseSuppression.toString()))
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
    }

    /**
     * Swaps the outgoing audio track for one built with the new noise suppression setting.
     * The sender keeps its place in the SDP, so no renegotiation is needed.
     */
    fun setNoiseSuppression(enabled: Boolean) {
        if (enabled == noiseSuppressionEnabled) return
        noiseSuppressionEnabled = enabled
        val sender = audioSender ?: return

        val newSource = peerConnectionFactory.createAudioSource(audioConstraints(enabled))
        val newTrack = peerConnectionFactory.createAudioTrack("local_stream_audio_${System.currentTimeMillis()}", newSource)
        newTrack.setEnabled(microphoneEnabled)
        sender.setTrack(newTrack, false)

        // The old track still belongs to the local MediaStream, so silence it rather than dispose it.
        localAudioTrack?.setEnabled(false)
        localAudioTrack = newTrack
        audioSource = newSource
    }

    fun hasLocalVideo(): Boolean = localVideoTrack != null

    /**
     * Starts the camera mid-call (voice → video upgrade) and attaches it to the connection.
     * The caller must send a fresh offer afterwards so the peer learns about the new track.
     */
    fun startVideoMidCall(): VideoTrack? {
        localVideoTrack?.let { return it }
        val track = startCamera("local_stream_video") ?: return null
        videoSender = peerConnection?.addTrack(track, listOf("local_stream"))
        return track
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    private fun startCamera(trackId: String): VideoTrack? {
        return try {
            val capturer = createCameraCapturer() ?: return null
            videoCapturer = capturer
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            val vSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            videoSource = vSource
            capturer.initialize(surfaceTextureHelper, context, vSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            peerConnectionFactory.createVideoTrack(trackId, vSource).also { localVideoTrack = it }
        } catch (e: Exception) {
            android.util.Log.e("WebRTCClient", "Failed to start camera capturer for video call", e)
            null
        }
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

        audioSource = null
        localAudioTrack = null
        localVideoTrack = null
    }
}
