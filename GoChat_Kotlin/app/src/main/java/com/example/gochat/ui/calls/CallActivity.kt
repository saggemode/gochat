package com.example.gochat.ui.calls

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.core.haptic.HapticEngine
import com.example.gochat.core.webrtc.WebRTCClient
import com.example.gochat.core.webrtc.WebRTCReconnectionHandler
import com.example.gochat.data.model.CallRecord
import com.example.gochat.data.model.CallStatus
import com.example.gochat.data.model.CallType
import com.example.gochat.data.repository.CallRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import com.example.gochat.databinding.ActivityCallBinding
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyNavigationBarInset
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarMargins
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.*
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_TARGET_USER_ID = "extra_target_user_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_PEER_AVATAR = "extra_peer_avatar"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        const val EXTRA_CALL_TYPE = "extra_call_type" // "voice" or "video"
        const val EXTRA_AUTO_ACCEPT = "extra_auto_accept"
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var rtcClient: WebRTCClient
    private var reconnectionHandler: WebRTCReconnectionHandler? = null
    private var localStream: MediaStream? = null

    @Inject
    lateinit var callRepo: CallRepository

    @Inject
    lateinit var webSocket: GoChatWebSocket

    private val callId: String by lazy { intent.getStringExtra(EXTRA_CALL_ID).orEmpty() }
    private val targetUserId: String by lazy { intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty() }
    private val peerName: String by lazy { intent.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "GoChat Contact" } }
    private val peerAvatar: String by lazy { intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty() }
    private val isOutgoing: Boolean by lazy { intent.getBooleanExtra(EXTRA_IS_OUTGOING, true) }
    private val callType: String by lazy { intent.getStringExtra(EXTRA_CALL_TYPE) ?: "voice" }

    private val autoAccept: Boolean by lazy { intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false) }
    private var isAudioMuted = false
    private var isVideoMuted = false
    private var callStartTime = 0L
    private var callDurationSeconds = 0
    private var timerJob: Job? = null
    private var isCallEnded = false
    private var isCallAccepted = false
    private var isCallConnected = false
    private var isRinging = false
    private var callTimeoutJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    private var pendingOfferSdp: String? = null
    private var serverCallId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.cancelCallNotification(this, callId)

        binding.layoutCallInfo.applyStatusBarInset()
        binding.layoutCallControls.applyNavigationBarInset()
        binding.localVideoView.applySystemBarMargins(top = true, right = true)

        setupWebRTC()
        setupUI()
        observeSignaling()

        if (isOutgoing) {
            isCallAccepted = true
            lifecycleScope.launch(Dispatchers.IO) {
                callRepo.recordCall(
                    CallRecord(
                        id = callId,
                        peerId = targetUserId,
                        peerName = peerName,
                        peerAvatar = peerAvatar,
                        type = if (callType == "video") CallType.VIDEO else CallType.AUDIO,
                        status = CallStatus.OUTGOING,
                        durationSeconds = 0,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            startOutgoingCall()
            startCallTimeoutTimer()
            playRingbackTone()
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                callRepo.recordCall(
                    CallRecord(
                        id = callId,
                        peerId = targetUserId,
                        peerName = peerName,
                        peerAvatar = peerAvatar,
                        type = if (callType == "video") CallType.VIDEO else CallType.AUDIO,
                        status = CallStatus.INCOMING,
                        durationSeconds = 0,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            if (autoAccept) {
                acceptIncomingCall()
            } else {
                HapticEngine.startIncomingCall(this)
                // Notify caller immediately that recipient device is ringing
                lifecycleScope.launch(Dispatchers.IO) {
                    val cid = serverCallId.ifBlank { callId }
                    callRepo.sendSignaling(cid, targetUserId, "ringing")
                }
            }
        }
    }

    private fun setupWebRTC() {
        rtcClient = WebRTCClient(this, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cid = serverCallId.ifBlank { callId }
                        callRepo.sendSignaling(cid, targetUserId, "ice-candidate", candidate = it.sdp)
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                runOnUiThread {
                    stream?.videoTracks?.getOrNull(0)?.addSink(binding.remoteVideoView)
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                reconnectionHandler?.handleConnectionChange(state)

                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            isCallConnected = true
                            callTimeoutJob?.cancel()
                            stopRingbackTone()
                            HapticEngine.stopIncomingCall(this@CallActivity)
                            startCallTimer()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            binding.tvCallStatus.text = "Reconnecting..."
                        }
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            endCall()
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    runOnUiThread {
                        track.addSink(binding.remoteVideoView)
                    }
                }
            }
        })

        rtcClient.createPeerConnection()

        reconnectionHandler = WebRTCReconnectionHandler(rtcClient) {
            if (isOutgoing) {
                startOutgoingCall(iceRestart = true)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    callRepo.sendSignaling(callId, targetUserId, "request-reconnect")
                }
            }
        }

        localStream = rtcClient.createLocalStream("local_stream", isVideo = callType == "video")
        localStream?.let { rtcClient.addStream(it) }

        binding.remoteVideoView.init(rtcClient.getEglBaseContext(), null)
        binding.localVideoView.init(rtcClient.getEglBaseContext(), null)

        if (callType == "video") {
            localStream?.videoTracks?.firstOrNull()?.addSink(binding.localVideoView)
        }
    }

    private fun setupUI() {
        binding.tvCallerName.text = peerName

        if (peerAvatar.isNotBlank()) {
            binding.ivCallerAvatar.load(peerAvatar) {
                crossfade(true)
                placeholder(R.drawable.ic_account)
                error(R.drawable.ic_account)
                transformations(CircleCropTransformation())
            }
        } else {
            binding.ivCallerAvatar.setImageResource(R.drawable.ic_account)
        }

        binding.btnEndCall.setOnClickListener {
            endCall()
        }

        binding.btnAnswerCall.setOnClickListener {
            acceptIncomingCall()
        }

        binding.btnMute.setOnClickListener {
            isAudioMuted = !isAudioMuted
            localStream?.audioTracks?.forEach { it.setEnabled(!isAudioMuted) }
            binding.btnMute.backgroundTintList = ColorStateList.valueOf(
                if (isAudioMuted) ContextCompat.getColor(this, R.color.gochat_error)
                else Color.parseColor("#1F2C33")
            )
            Toast.makeText(this, if (isAudioMuted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleVideo.setOnClickListener {
            if (callType == "video") {
                isVideoMuted = !isVideoMuted
                localStream?.videoTracks?.forEach { it.setEnabled(!isVideoMuted) }
                binding.btnToggleVideo.backgroundTintList = ColorStateList.valueOf(
                    if (isVideoMuted) ContextCompat.getColor(this, R.color.gochat_error)
                    else Color.parseColor("#1F2C33")
                )
            } else {
                Toast.makeText(this, "Video is not available on voice calls", Toast.LENGTH_SHORT).show()
            }
        }

        if (isOutgoing || autoAccept) {
            binding.tvCallStatus.text = if (isOutgoing) "Calling..." else "Connecting..."
            binding.btnAnswerCall.visibility = View.GONE
            binding.btnMute.visibility = View.VISIBLE
            binding.btnToggleVideo.visibility = if (callType == "video") View.VISIBLE else View.GONE
        } else {
            binding.tvCallStatus.text = "Incoming Call..."
            binding.btnAnswerCall.visibility = View.VISIBLE
            binding.btnMute.visibility = View.GONE
            binding.btnToggleVideo.visibility = View.GONE
        }

        if (callType == "voice") {
            binding.localVideoView.visibility = View.GONE
            binding.remoteVideoView.visibility = View.GONE
            binding.btnToggleVideo.visibility = View.GONE
        }
    }

    private fun acceptIncomingCall() {
        if (isCallAccepted || isCallEnded) return
        isCallAccepted = true
        HapticEngine.stopIncomingCall(this)
        NotificationHelper.cancelCallNotification(this, callId)

        runOnUiThread {
            binding.tvCallStatus.text = "Connecting..."
            binding.btnAnswerCall.visibility = View.GONE
            binding.btnMute.visibility = View.VISIBLE
            if (callType == "video") {
                binding.btnToggleVideo.visibility = View.VISIBLE
            }
        }

        val effectiveCallId = serverCallId.ifBlank { callId }
        lifecycleScope.launch(Dispatchers.IO) {
            callRepo.acceptCall(effectiveCallId)
        }

        if (pendingOfferSdp != null) {
            val sdp = pendingOfferSdp!!
            pendingOfferSdp = null
            handleRemoteOffer(sdp)
        } else {
            // Offer has not arrived yet, explicitly ask caller to re-transmit offer
            lifecycleScope.launch(Dispatchers.IO) {
                val cid = serverCallId.ifBlank { callId }
                callRepo.sendSignaling(cid, targetUserId, "request-offer")
            }
        }
    }

    private fun handleRemoteOffer(sdp: String) {
        rtcClient.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp), object : SdpObserver {
            override fun onSetSuccess() {
                rtcClient.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        answerSdp?.let {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val cid = serverCallId.ifBlank { callId }
                                callRepo.sendSignaling(cid, targetUserId, "answer", sdp = it.description)
                            }
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                })
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        })
    }

    private fun playRingbackTone() {
        if (isCallConnected || isCallEnded) return
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        } catch (e: Exception) {
            Log.e("CallActivity", "Failed to start ringback tone: ${e.message}")
        }
    }

    private fun stopRingbackTone() {
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
    }

    private fun playBusyTone() {
        try {
            val busy = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
            busy.startTone(ToneGenerator.TONE_SUP_BUSY, 1500)
            lifecycleScope.launch {
                delay(1600)
                try { busy.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun handleRinging() {
        if (isCallConnected || isCallEnded || !isOutgoing) return
        isRinging = true
        runOnUiThread {
            if (!isCallConnected && !isCallEnded) {
                binding.tvCallStatus.text = "Ringing..."
            }
        }
        playRingbackTone()
        // Remote peer is confirmed ringing; ensure caller's WebRTC offer is sent
        if (!isCallConnected) {
            startOutgoingCall(iceRestart = false)
        }
    }

    private fun startCallTimeoutTimer() {
        callTimeoutJob?.cancel()
        callTimeoutJob = lifecycleScope.launch {
            // WhatsApp-standard 40 seconds timeout
            delay(40_000L)
            if (!isCallConnected && !isCallEnded) {
                runOnUiThread {
                    stopRingbackTone()
                    binding.tvCallStatus.text = if (isRinging) "No Answer" else "Unavailable"
                }
                playBusyTone()
                delay(2000L)
                endCall()
            }
        }
    }

    private fun startOutgoingCall(iceRestart: Boolean = false) {
        rtcClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cid = serverCallId.ifBlank { callId }
                        callRepo.sendSignaling(cid, targetUserId, if (iceRestart) "ice-restart-offer" else "offer", sdp = it.description)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, iceRestart = iceRestart)
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            webSocket.callEvents.collect { event ->
                // The gateway sends flattened call events with both "type" and "event" fields
                val type = event["type"]?.jsonPrimitive?.contentOrNull
                    ?: event["event"]?.jsonPrimitive?.contentOrNull

                // Match on call_id or peer targetUserId to ensure events are never dropped
                val eventCallId = event["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["id"]?.jsonPrimitive?.contentOrNull
                val actorId = event["actor_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["caller_id"]?.jsonPrimitive?.contentOrNull
                val eventTargetId = event["target_user_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["receiver_id"]?.jsonPrimitive?.contentOrNull

                val isPeerMatch = (actorId != null && actorId == targetUserId) ||
                        (eventTargetId != null && eventTargetId == targetUserId)

                val isCallIdMatch = (eventCallId == null || eventCallId.isBlank() ||
                        eventCallId == callId ||
                        (serverCallId.isNotBlank() && eventCallId == serverCallId))

                if (!isCallIdMatch && !isPeerMatch) {
                    return@collect
                }

                // If we receive the server call_id for the first time, remember it
                if (eventCallId != null && eventCallId.isNotBlank() && serverCallId.isBlank()) {
                    serverCallId = eventCallId
                }

                when (type) {
                    "call_ringing" -> {
                        handleRinging()
                    }
                    "call_signaling" -> {
                        // signal_type comes from the flattened call_payload "type" field
                        val sigType = event["signal_type"]?.jsonPrimitive?.contentOrNull
                            ?: event["signaling_type"]?.jsonPrimitive?.contentOrNull
                        val sdp = event["sdp"]?.jsonPrimitive?.contentOrNull
                        val candidate = event["candidate"]?.jsonPrimitive?.contentOrNull

                        if (sigType == "ringing") {
                            handleRinging()
                        } else if (sigType == "request-offer" && isOutgoing) {
                            startOutgoingCall(iceRestart = false)
                        } else if ((sigType == "offer" || sigType == "ice-restart-offer") && sdp != null) {
                            if (isCallAccepted) {
                                handleRemoteOffer(sdp)
                            } else {
                                pendingOfferSdp = sdp
                            }
                        } else if ((sigType == "answer" || sigType == "sfu-pub-answer" || sigType == "sfu-sub-answer") && sdp != null) {
                            rtcClient.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp), object : SdpObserver {
                                override fun onSetSuccess() {}
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            })
                        } else if (sigType == "ice-candidate" && candidate != null) {
                            rtcClient.addIceCandidate(IceCandidate("0", 0, candidate))
                        }
                    }
                    "call_accepted" -> {
                        isCallConnected = true
                        callTimeoutJob?.cancel()
                        stopRingbackTone()
                        runOnUiThread {
                            HapticEngine.stopIncomingCall(this@CallActivity)
                            binding.tvCallStatus.text = "Connected"
                            startCallTimer()
                        }
                    }
                    "call_rejected" -> {
                        callTimeoutJob?.cancel()
                        stopRingbackTone()
                        val isBusy = event["status"]?.jsonPrimitive?.contentOrNull == "busy"
                        runOnUiThread {
                            HapticEngine.stopIncomingCall(this@CallActivity)
                            binding.tvCallStatus.text = if (isBusy) "Busy" else "Call Declined"
                        }
                        playBusyTone()
                        delay(1500)
                        endCall()
                    }
                    "call_ended" -> {
                        callTimeoutJob?.cancel()
                        stopRingbackTone()
                        runOnUiThread {
                            binding.tvCallStatus.text = "Call Ended"
                        }
                        playBusyTone()
                        delay(1200)
                        endCall()
                    }
                }
            }
        }
    }

    private fun startCallTimer() {
        if (callStartTime != 0L) return
        callStartTime = System.currentTimeMillis()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                callDurationSeconds = elapsed.toInt()
                val mins = elapsed / 60
                val secs = elapsed % 60
                binding.tvCallStatus.text = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
                delay(1000)
            }
        }
    }

    private fun endCall() {
        HapticEngine.stopIncomingCall(this)
        NotificationHelper.cancelCallNotification(this, callId)
        if (isCallEnded) return
        isCallEnded = true

        stopRingbackTone()
        callTimeoutJob?.cancel()
        callTimeoutJob = null
        timerJob?.cancel()
        timerJob = null

        val finalStatus = if (callDurationSeconds > 0) {
            if (isOutgoing) CallStatus.OUTGOING else CallStatus.INCOMING
        } else {
            if (isOutgoing) CallStatus.OUTGOING else CallStatus.MISSED
        }

        val effectiveCallId = serverCallId.ifBlank { callId }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                callRepo.updateCallStatus(callId, finalStatus, callDurationSeconds)
                if (!isOutgoing && !isCallAccepted) {
                    callRepo.rejectCall(effectiveCallId)
                } else {
                    callRepo.endCall(effectiveCallId)
                }
            } catch (_: Exception) {}
        }

        finish()
    }

    override fun onDestroy() {
        stopRingbackTone()
        callTimeoutJob?.cancel()
        HapticEngine.stopIncomingCall(this)
        NotificationHelper.cancelCallNotification(this, callId)
        reconnectionHandler?.cancel()
        if (::rtcClient.isInitialized) {
            rtcClient.close()
        }
        timerJob?.cancel()
        super.onDestroy()
    }
}
