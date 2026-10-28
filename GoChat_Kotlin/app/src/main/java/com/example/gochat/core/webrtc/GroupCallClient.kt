package com.example.gochat.core.webrtc

import android.content.Context
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

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
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

    /** Starts publishing local microphone audio and returns the publisher SDP offer. */
    fun createPublisherOffer(onOffer: (String) -> Unit) {
        val pc = factory.createPeerConnection(rtcConfig, emptyObserver()) ?: return
        publisherPc = pc

        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("group_audio", audioSource).also {
            pc.addTrack(it, listOf("group_stream"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                onOffer(sdp.description)
            }
        }, MediaConstraints())
    }

    fun setPublisherAnswer(sdp: String) {
        publisherPc?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /**
     * (Re)builds the subscriber connection with [remoteAudioCount] receive-only
     * audio transceivers — one per other participant — and returns its offer.
     */
    fun createSubscriberOffer(remoteAudioCount: Int, onOffer: (String) -> Unit) {
        subscriberPc?.close()
        val pc = factory.createPeerConnection(rtcConfig, emptyObserver()) ?: return
        subscriberPc = pc

        repeat(remoteAudioCount.coerceAtLeast(1)) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
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

    fun close() {
        try { publisherPc?.close() } catch (_: Exception) {}
        try { subscriberPc?.close() } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
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
