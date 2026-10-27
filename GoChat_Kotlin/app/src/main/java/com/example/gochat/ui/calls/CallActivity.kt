package com.example.gochat.ui.calls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.core.haptic.HapticEngine
import com.example.gochat.core.webrtc.CallAudioController
import com.example.gochat.core.webrtc.WebRTCClient
import com.example.gochat.core.webrtc.WebRTCReconnectionHandler
import com.example.gochat.data.model.CallRecord
import com.example.gochat.data.model.CallStatus
import com.example.gochat.data.model.CallType
import com.example.gochat.data.repository.CallRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import com.example.gochat.databinding.ActivityCallBinding
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyNavigationBarInset
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarMargins
import com.example.gochat.ui.chat.ChatRoomActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

        private const val MENU_SEND_MESSAGE = 1
        private const val MENU_NOISE_CANCELLATION = 2
        private val COLOR_CONTROL_IDLE = Color.parseColor("#1F2C33")
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var rtcClient: WebRTCClient
    private var reconnectionHandler: WebRTCReconnectionHandler? = null
    private var localStream: MediaStream? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private lateinit var audioController: CallAudioController

    @Inject
    lateinit var callRepo: CallRepository

    @Inject
    lateinit var chatRepo: ChatRepository

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

    // Candidates that arrive before WebRTC is set up (incoming call still ringing).
    private val earlyRemoteCandidates = mutableListOf<String>()
    private var onPermissionsGranted: ((Map<String, Boolean>) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = onPermissionsGranted
        onPermissionsGranted = null
        action?.invoke(result)
    }

    private val isMediaReady: Boolean get() = ::rtcClient.isInitialized

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioController = CallAudioController(this)

        NotificationHelper.cancelCallNotification(this, callId)

        binding.layoutCallInfo.applyStatusBarInset()
        binding.layoutCallControls.applyNavigationBarInset()
        binding.localVideoView.applySystemBarMargins(top = true, right = true)

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
            withCallPermissions(callType == "video") {
                lifecycleScope.launch {
                    ensureIceServers()
                    setupWebRTC()
                    startOutgoingCall()
                    startCallTimeoutTimer()
                    playRingbackTone()
                }
            }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: a second incoming call while this one is active is answered as busy.
        val newCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val newIsOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, true)
        if (newCallId.isBlank() || newCallId == callId || newCallId == serverCallId) {
            if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) acceptIncomingCall()
            return
        }
        if (!newIsOutgoing) {
            lifecycleScope.launch(Dispatchers.IO) {
                callRepo.rejectCall(newCallId, isBusy = true)
            }
        }
    }

    /**
     * Calls cannot work without the microphone, and video needs the camera.
     * If only the camera is refused the call carries on as voice.
     */
    private fun withCallPermissions(needCamera: Boolean, onReady: () -> Unit) {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (needCamera) add(Manifest.permission.CAMERA)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onReady()
            return
        }
        onPermissionsGranted = { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            if (micGranted) {
                if (needCamera && result[Manifest.permission.CAMERA] == false) {
                    Toast.makeText(this, "Camera permission denied — continuing with audio only", Toast.LENGTH_SHORT).show()
                }
                onReady()
            } else {
                Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
                endCall()
            }
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Fetches STUN/TURN servers once; TURN is what lets calls connect across networks. */
    private suspend fun ensureIceServers() {
        if (iceServers.isNotEmpty()) return
        iceServers = callRepo.getIceServers().map { cfg ->
            val builder = PeerConnection.IceServer.builder(cfg.urls)
            if (!cfg.username.isNullOrBlank()) builder.setUsername(cfg.username)
            if (!cfg.credential.isNullOrBlank()) builder.setPassword(cfg.credential)
            builder.createIceServer()
        }
    }

    private fun setupWebRTC() {
        if (isMediaReady) return
        rtcClient = WebRTCClient(this, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    rtcClient.recordLocalCandidate(it)
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cid = serverCallId.ifBlank { callId }
                        callRepo.sendSignaling(cid, targetUserId, "ice-candidate", candidate = it.sdp)
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.getOrNull(0)?.let { showRemoteVideo(it) }
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
                    showRemoteVideo(track)
                }
            }
        }, iceServers)

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

        val wantsVideo = callType == "video" && hasCameraPermission()
        localStream = rtcClient.createLocalStream("local_stream", isVideo = wantsVideo)
        localStream?.let { rtcClient.addStream(it) }

        binding.remoteVideoView.init(rtcClient.getEglBaseContext(), null)
        binding.localVideoView.init(rtcClient.getEglBaseContext(), null)

        if (wantsVideo) {
            localStream?.videoTracks?.firstOrNull()?.addSink(binding.localVideoView)
            binding.localVideoView.visibility = View.VISIBLE
        }

        audioController.start(speakerOn = wantsVideo)
        renderControlStates()

        synchronized(earlyRemoteCandidates) {
            earlyRemoteCandidates.forEach { rtcClient.addIceCandidate(IceCandidate("0", 0, it)) }
            earlyRemoteCandidates.clear()
        }
    }

    private fun showRemoteVideo(track: VideoTrack) {
        runOnUiThread {
            track.addSink(binding.remoteVideoView)
            binding.remoteVideoView.visibility = View.VISIBLE
            binding.ivCallerAvatar.visibility = View.GONE
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

        binding.btnEndCall.setOnClickListener { endCall() }
        binding.btnAnswerCall.setOnClickListener { acceptIncomingCall() }

        binding.btnSpeaker.setOnClickListener {
            if (!isMediaReady) return@setOnClickListener
            audioController.setSpeaker(!audioController.isSpeakerOn)
            renderControlStates()
        }

        binding.btnMute.setOnClickListener {
            if (!isMediaReady) return@setOnClickListener
            isAudioMuted = !isAudioMuted
            rtcClient.setMicrophoneEnabled(!isAudioMuted)
            renderControlStates()
            Toast.makeText(this, if (isAudioMuted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleVideo.setOnClickListener { onVideoButtonClicked() }
        binding.btnMore.setOnClickListener { showMoreMenu() }

        // Voice calls start without video surfaces; they appear if either side turns video on.
        binding.localVideoView.visibility = View.GONE
        binding.remoteVideoView.visibility = View.GONE

        binding.tvCallStatus.text = when {
            isOutgoing -> "Calling..."
            autoAccept -> "Connecting..."
            else -> "Incoming Call..."
        }
        showInCallControls(isOutgoing || autoAccept)
    }

    /** Ringing incoming calls show only Decline / Accept; everything else shows the full row. */
    private fun showInCallControls(inCall: Boolean) {
        val inCallVisibility = if (inCall) View.VISIBLE else View.GONE
        binding.containerSpeaker.visibility = inCallVisibility
        binding.containerVideo.visibility = inCallVisibility
        binding.containerMute.visibility = inCallVisibility
        binding.containerMore.visibility = inCallVisibility
        binding.containerAnswer.visibility = if (inCall) View.GONE else View.VISIBLE
        binding.tvEndLabel.text = if (inCall) "End" else "Decline"
    }

    private fun renderControlStates() {
        setControlActive(binding.btnSpeaker, isMediaReady && audioController.isSpeakerOn)

        binding.btnMute.setImageResource(if (isAudioMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        setControlActive(binding.btnMute, isAudioMuted)

        val videoOn = isMediaReady && rtcClient.hasLocalVideo() && !isVideoMuted
        binding.btnToggleVideo.setImageResource(if (videoOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
        setControlActive(binding.btnToggleVideo, videoOn)
    }

    private fun setControlActive(button: FloatingActionButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (active) Color.WHITE else COLOR_CONTROL_IDLE)
        button.imageTintList = ColorStateList.valueOf(if (active) COLOR_CONTROL_IDLE else Color.WHITE)
    }

    private fun onVideoButtonClicked() {
        if (!isMediaReady) return
        if (rtcClient.hasLocalVideo()) {
            isVideoMuted = !isVideoMuted
            rtcClient.setVideoEnabled(!isVideoMuted)
            binding.localVideoView.visibility = if (isVideoMuted) View.GONE else View.VISIBLE
            renderControlStates()
            return
        }
        // Voice call → turn the camera on and renegotiate so the peer receives video.
        withCallPermissions(needCamera = true) {
            if (!hasCameraPermission()) return@withCallPermissions
            val track = rtcClient.startVideoMidCall()
            if (track == null) {
                Toast.makeText(this, "Unable to start camera", Toast.LENGTH_SHORT).show()
                return@withCallPermissions
            }
            track.addSink(binding.localVideoView)
            binding.localVideoView.visibility = View.VISIBLE
            isVideoMuted = false
            if (!audioController.isSpeakerOn) audioController.setSpeaker(true)
            renderControlStates()
            binding.tvCallStatus.text = "Switching to video…"
            // Distinct signal so the peer is prompted to accept the upgrade.
            startOutgoingCall(signalTypeOverride = "video-offer")
        }
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(this, binding.btnMore)
        popup.menu.add(0, MENU_SEND_MESSAGE, 0, "Send message")
        popup.menu.add(0, MENU_NOISE_CANCELLATION, 1, "Noise cancellation").apply {
            isCheckable = true
            isChecked = !isMediaReady || rtcClient.noiseSuppressionEnabled
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SEND_MESSAGE -> {
                    openChatWithPeer()
                    true
                }
                MENU_NOISE_CANCELLATION -> {
                    if (isMediaReady) {
                        val enable = !rtcClient.noiseSuppressionEnabled
                        rtcClient.setNoiseSuppression(enable)
                        Toast.makeText(
                            this,
                            if (enable) "Noise cancellation on" else "Noise cancellation off",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Opens the 1:1 chat with the peer while the call keeps running underneath. */
    private fun openChatWithPeer() {
        if (targetUserId.isBlank()) return
        lifecycleScope.launch {
            val conversation = withContext(Dispatchers.IO) {
                chatRepo.getAllConversationsList().firstOrNull {
                    !it.isGroup && it.memberIds.contains(targetUserId)
                } ?: chatRepo.createConversation(peerName, listOf(targetUserId)).getOrNull()
            }
            if (conversation == null) {
                Toast.makeText(this@CallActivity, "Unable to open chat", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent(this@CallActivity, ChatRoomActivity::class.java).apply {
                putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
                putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, conversation.title.ifBlank { peerName })
                putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, conversation.avatarUrl.ifBlank { peerAvatar })
                putExtra(ChatRoomActivity.EXTRA_IS_ONLINE, conversation.isOnline)
                putExtra(ChatRoomActivity.EXTRA_LAST_SEEN, conversation.lastSeen ?: 0L)
                putExtra(ChatRoomActivity.EXTRA_IS_GROUP, false)
                putExtra(ChatRoomActivity.EXTRA_PARTNER_ID, targetUserId)
            })
        }
    }

    private fun acceptIncomingCall() {
        if (isCallAccepted || isCallEnded) return
        isCallAccepted = true
        HapticEngine.stopIncomingCall(this)
        NotificationHelper.cancelCallNotification(this, callId)

        binding.tvCallStatus.text = "Connecting..."
        showInCallControls(true)

        val effectiveCallId = serverCallId.ifBlank { callId }
        lifecycleScope.launch(Dispatchers.IO) {
            callRepo.acceptCall(effectiveCallId)
        }

        withCallPermissions(callType == "video") {
            lifecycleScope.launch {
                ensureIceServers()
                setupWebRTC()
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
        }
    }

    /**
     * Prompts the user to accept an incoming voice→video upgrade. On accept we
     * turn on our own camera and answer with video (two-way); on decline we tell
     * the peer so they can revert to audio.
     */
    private fun promptVideoUpgrade(sdp: String) {
        if (isCallEnded) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Video call")
            .setMessage("$peerName wants to switch to a video call.")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ -> acceptVideoUpgrade(sdp) }
            .setNegativeButton("Decline") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val cid = serverCallId.ifBlank { callId }
                    callRepo.sendSignaling(cid, targetUserId, "video-declined")
                }
            }
            .show()
    }

    private fun acceptVideoUpgrade(sdp: String) {
        withCallPermissions(needCamera = true) {
            if (!hasCameraPermission()) {
                // No camera: still accept so we can see their video, but send none.
                handleRemoteOffer(sdp, enableLocalVideo = false)
                return@withCallPermissions
            }
            if (!audioController.isSpeakerOn) audioController.setSpeaker(true)
            handleRemoteOffer(sdp, enableLocalVideo = true)
        }
    }

    /** Peer declined our video upgrade: tear down our camera and revert to audio. */
    private fun onVideoUpgradeDeclined() {
        rtcClient.rollbackLocalDescription()
        rtcClient.stopVideo()
        isVideoMuted = false
        binding.localVideoView.visibility = View.GONE
        if (isCallConnected) binding.tvCallStatus.text = "Connected"
        renderControlStates()
        Toast.makeText(this, "$peerName declined video", Toast.LENGTH_SHORT).show()
    }

    private fun handleRemoteOffer(sdp: String, enableLocalVideo: Boolean = false) {
        rtcClient.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp), object : SdpObserver {
            override fun onSetSuccess() {
                // Turn on our camera (if accepting a video upgrade) BEFORE creating
                // the answer, so the answer advertises our outgoing video too. The
                // addTrack must happen synchronously here; only the UI touches the
                // main thread.
                if (enableLocalVideo && !rtcClient.hasLocalVideo()) {
                    val track = rtcClient.startVideoMidCall()
                    if (track != null) {
                        runOnUiThread {
                            track.addSink(binding.localVideoView)
                            binding.localVideoView.visibility = View.VISIBLE
                            isVideoMuted = false
                            renderControlStates()
                        }
                    }
                }
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
        if (!isCallConnected && isMediaReady) {
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

    private fun startOutgoingCall(iceRestart: Boolean = false, signalTypeOverride: String? = null) {
        if (!isMediaReady) return
        rtcClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cid = serverCallId.ifBlank { callId }
                        val sigType = signalTypeOverride ?: if (iceRestart) "ice-restart-offer" else "offer"
                        callRepo.sendSignaling(cid, targetUserId, sigType, sdp = it.description)
                        // Candidates sent before the peer was listening are lost, so resend
                        // everything gathered so far right behind each offer.
                        rtcClient.getLocalCandidates().forEach { candidate ->
                            callRepo.sendSignaling(cid, targetUserId, "ice-candidate", candidate = candidate.sdp)
                        }
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

                val eventCallId = event["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["id"]?.jsonPrimitive?.contentOrNull
                val actorId = event["actor_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["caller_id"]?.jsonPrimitive?.contentOrNull
                val eventTargetId = event["target_user_id"]?.jsonPrimitive?.contentOrNull
                    ?: event["receiver_id"]?.jsonPrimitive?.contentOrNull

                val isPeerMatch = (actorId != null && actorId == targetUserId) ||
                        (eventTargetId != null && eventTargetId == targetUserId)

                // The WebSocket replays recent call events, so an event carrying a call id must
                // belong to this call. Matching on the peer alone let a previous call's
                // "call_ended" kill a new call to the same person.
                val isThisCall = if (eventCallId.isNullOrBlank()) {
                    isPeerMatch
                } else {
                    eventCallId == callId || (serverCallId.isNotBlank() && eventCallId == serverCallId) ||
                            (callId.startsWith("call_") && serverCallId.isBlank() && isPeerMatch)
                }
                if (!isThisCall) return@collect

                // Learn the server call id only when this screen was opened with a local placeholder
                if (!eventCallId.isNullOrBlank() && serverCallId.isBlank() && callId.startsWith("call_")) {
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
                        } else if (sigType == "video-offer" && !sdp.isNullOrBlank()) {
                            // Peer wants to upgrade a voice call to video.
                            if (isCallAccepted && isMediaReady) {
                                runOnUiThread { promptVideoUpgrade(sdp) }
                            } else {
                                pendingOfferSdp = sdp
                            }
                        } else if (sigType == "video-declined") {
                            runOnUiThread { onVideoUpgradeDeclined() }
                        } else if ((sigType == "offer" || sigType == "ice-restart-offer") && !sdp.isNullOrBlank()) {
                            if (isCallAccepted && isMediaReady) {
                                handleRemoteOffer(sdp)
                            } else {
                                pendingOfferSdp = sdp
                            }
                        } else if ((sigType == "answer" || sigType == "sfu-pub-answer" || sigType == "sfu-sub-answer") && !sdp.isNullOrBlank()) {
                            if (isMediaReady) {
                                rtcClient.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp), object : SdpObserver {
                                    override fun onSetSuccess() {}
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {}
                                })
                            }
                        } else if (sigType == "ice-candidate" && !candidate.isNullOrBlank()) {
                            if (isMediaReady) {
                                rtcClient.addIceCandidate(IceCandidate("0", 0, candidate))
                            } else {
                                synchronized(earlyRemoteCandidates) { earlyRemoteCandidates.add(candidate) }
                            }
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
        audioController.stop()

        val finalStatus = if (callDurationSeconds > 0) {
            if (isOutgoing) CallStatus.OUTGOING else CallStatus.INCOMING
        } else {
            if (isOutgoing) CallStatus.OUTGOING else CallStatus.MISSED
        }

        val effectiveCallId = serverCallId.ifBlank { callId }
        val wasAccepted = isCallAccepted
        // Not tied to lifecycleScope: the activity finishes right away and the
        // server must still hear about the hang-up.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                callRepo.updateCallStatus(callId, finalStatus, callDurationSeconds)
                if (!isOutgoing && !wasAccepted) {
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
        audioController.stop()
        if (::rtcClient.isInitialized) {
            rtcClient.close()
        }
        timerJob?.cancel()
        super.onDestroy()
    }
}
