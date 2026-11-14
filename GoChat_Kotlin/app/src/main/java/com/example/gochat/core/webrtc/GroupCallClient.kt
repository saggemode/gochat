package com.example.gochat.core.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * SFU client for multi-party (group) calls. Unlike the 1:1 [WebRTCClient] it
 * keeps two peer connections against the SFU:
 *  - publisher: sends our local audio up to the SFU
 *  - subscriber: receives every other participant's audio (played automatically)
 *
 * On each participant change the caller rebuilds the subscriber offer with the
 * right number of receive-only transceivers so the SFU's added tracks line up.
 * Audio-only for now; a video grid can layer on later.
 */
class GroupCallClient(
    context: Context,
    iceServers: List<PeerConnection.IceServer>?
) {
    private val eglBase = EglBase.create()
    private val rtcConfig = PeerConnection.RTCConfiguration(
        iceServers?.takeIf { it.isNotEmpty() } ?: listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    }

    private val factory: PeerConnectionFactory

    private var publisherPc: PeerConnection? = null
    private var subscriberPc: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    // The publisher's single video sender. Null until the first video track is added
    // (audio-only calls have none). Reused to swap camera↔screen via setTrack without
    // renegotiation; adding it the FIRST time (audio→video) needs a re-offer.
    private var videoSender: RtpSender? = null
    /** True while the current video track is a screen capture rather than the camera. */
    var isScreenSharing: Boolean = false
        private set

    /** Called on the signaling thread when a remote participant's video arrives. */
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

    /** Shared EGL context so the UI's SurfaceViewRenderers can render our tracks. */
    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        // Mirror the proven 1:1 config: use the device's hardware AEC when it has
        // one, and leave hardware NS off so WebRTC's software suppression applies.
        // Forcing hardware AEC on unsupported devices disables it entirely → echo.
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    private fun emptyObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(p0: IceCandidate?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    /**
     * Starts publishing local mic (and camera, when [video]) and returns the
     * publisher SDP offer. [onLocalVideo] provides the local camera track for a
     * self-preview tile.
     */
    fun createPublisherOffer(
        context: Context,
        video: Boolean,
        onLocalVideo: (VideoTrack) -> Unit,
        onOffer: (String) -> Unit
    ) {
        val pc = factory.createPeerConnection(rtcConfig, emptyObserver()) ?: return
        publisherPc = pc

        // Echo cancellation / AGC / noise suppression — without these the caller
        // hears their own voice looped back from the other side.
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("group_audio", audioSource).also {
            pc.addTrack(it, listOf("group_stream"))
        }

        if (video) {
            startCamera(context)?.let { track ->
                videoSender = pc.addTrack(track, listOf("group_stream"))
                onLocalVideo(track)
            }
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                onOffer(sdp.description)
            }
        }, MediaConstraints())
    }

    private fun startCamera(context: Context): VideoTrack? {
        return try {
            stopCurrentVideoCapture() // dispose any prior capture (e.g. a screen share)
            val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
            val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull() ?: return null
            val capturer = enumerator.createCapturer(device, null) ?: return null
            videoCapturer = capturer
            surfaceHelper = SurfaceTextureHelper.create("GroupCaptureThread", eglBase.eglBaseContext)
            val source = factory.createVideoSource(capturer.isScreencast)
            videoSource = source
            capturer.initialize(surfaceHelper, context, source.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            factory.createVideoTrack("group_video", source).also { localVideoTrack = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Stop + dispose whatever video capture (camera or screen) is currently running,
     *  so a new one can be started without leaking the previous capturer/source. */
    private fun stopCurrentVideoCapture() {
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        videoCapturer = null
        videoSource = null
        surfaceHelper = null
    }

    /** Build a screen-capture video track from the MediaProjection permission result. */
    private fun startScreenCapture(context: Context, permissionData: Intent): VideoTrack? {
        return try {
            stopCurrentVideoCapture()
            val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
                override fun onStop() {}
            })
            videoCapturer = capturer
            surfaceHelper = SurfaceTextureHelper.create("GroupScreenThread", eglBase.eglBaseContext)
            val source = factory.createVideoSource(true) // isScreencast = true
            videoSource = source
            capturer.initialize(surfaceHelper, context, source.capturerObserver)
            capturer.startCapture(1280, 720, 15)
            factory.createVideoTrack("group_video", source).also { localVideoTrack = it }
        } catch (e: Exception) {
            null
        }
    }

    /** (Re)create the publisher offer after a track change and hand it to [onOffer].
     *  The SFU replies with sfu-pub-answer, applied via [setPublisherAnswer]. */
    private fun renegotiatePublisher(onOffer: (String) -> Unit) {
        val pc = publisherPc ?: return
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                onOffer(sdp.description)
            }
        }, MediaConstraints())
    }

    /**
     * Switch an audio call to video (or re-enable the camera after a screen share).
     * Starts the camera and either adds the first video sender (→ renegotiate, via
     * [onOffer]) or swaps the existing sender's track (no renegotiation). [onLocalVideo]
     * provides the camera track for the self-preview tile.
     */
    fun enableCameraVideo(context: Context, onLocalVideo: (VideoTrack) -> Unit, onOffer: (String) -> Unit) {
        val pc = publisherPc ?: return
        val old = localVideoTrack
        val track = startCamera(context) ?: return
        isScreenSharing = false
        onLocalVideo(track)
        val sender = videoSender
        if (sender == null) {
            videoSender = pc.addTrack(track, listOf("group_stream")) // new m-line → renegotiate
            renegotiatePublisher(onOffer)
        } else {
            sender.setTrack(track, true) // reuse existing video m-line; no renegotiation
            try { old?.dispose() } catch (_: Exception) {}
        }
    }

    /** Switch video→audio: stop sending camera/screen video, keep the audio call alive.
     *  No renegotiation — the video m-line stays, it just carries no track. */
    fun disableCameraVideo() {
        stopCurrentVideoCapture()
        try { videoSender?.setTrack(null, false) } catch (_: Exception) {}
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        localVideoTrack = null
        isScreenSharing = false
    }

    /**
     * Share the screen instead of the camera. [permissionData] is the result Intent from
     * MediaProjectionManager.createScreenCaptureIntent() (a foreground service of type
     * mediaProjection must already be running on Android 10+). Adds the first video
     * sender (→ renegotiate) or swaps the existing one's track.
     */
    fun startScreenShare(context: Context, permissionData: Intent, onLocalVideo: (VideoTrack) -> Unit, onOffer: (String) -> Unit) {
        val pc = publisherPc ?: return
        val old = localVideoTrack
        val track = startScreenCapture(context, permissionData) ?: return
        isScreenSharing = true
        onLocalVideo(track)
        val sender = videoSender
        if (sender == null) {
            videoSender = pc.addTrack(track, listOf("group_stream"))
            renegotiatePublisher(onOffer)
        } else {
            sender.setTrack(track, true)
            try { old?.dispose() } catch (_: Exception) {}
        }
    }

    fun setPublisherAnswer(sdp: String) {
        publisherPc?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /**
     * (Re)builds the subscriber connection with [remoteAudioCount] receive-only
     * audio transceivers — one per other participant — and returns its offer.
     */
    fun createSubscriberOffer(remoteCount: Int, video: Boolean, onOffer: (String) -> Unit) {
        subscriberPc?.close()
        // Observer forwards each remote video track to the UI for a tile.
        val observer = object : PeerConnection.Observer by emptyObserver() {
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { onRemoteVideoTrack?.invoke(it) }
            }
        }
        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return
        subscriberPc = pc

        val n = remoteCount.coerceAtLeast(1)
        repeat(n) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            if (video) {
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            }
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                onOffer(sdp.description)
            }
        }, MediaConstraints())
    }

    fun setSubscriberAnswer(sdp: String) {
        subscriberPc?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun close() {
        try { publisherPc?.close() } catch (_: Exception) {}
        try { subscriberPc?.close() } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        publisherPc = null
        subscriberPc = null
        try { factory.dispose() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
