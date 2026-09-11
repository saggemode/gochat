package com.example.gochat.ui.calls

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.gochat.core.webrtc.WebRTCClient
import com.example.gochat.core.webrtc.WebRTCReconnectionHandler
import com.example.gochat.data.model.CallRecord

import com.example.gochat.data.repository.CallRepository
import com.example.gochat.databinding.ActivityCallBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_TARGET_USER_ID = "extra_target_user_id"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        const val EXTRA_CALL_TYPE = "extra_call_type" // "voice" or "video"
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var rtcClient: WebRTCClient
    private var reconnectionHandler: WebRTCReconnectionHandler? = null

    
    @Inject
    lateinit var callRepo: CallRepository
    
    private val callId: String by lazy { intent.getStringExtra(EXTRA_CALL_ID).orEmpty() }
    private val targetUserId: String by lazy { intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty() }
    private val isOutgoing: Boolean by lazy { intent.getBooleanExtra(EXTRA_IS_OUTGOING, true) }
    private val callType: String by lazy { intent.getStringExtra(EXTRA_CALL_TYPE) ?: "voice" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebRTC()
        setupUI()
        
        if (isOutgoing) {
            startOutgoingCall()
        }
    }

    private fun setupWebRTC() {
        rtcClient = WebRTCClient(this, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    CoroutineScope(Dispatchers.IO).launch {
                        callRepo.sendSignaling(callId, targetUserId, "ice-candidate", candidate = it.sdp)
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.getOrNull(0)?.addSink(binding.remoteVideoView)
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                reconnectionHandler?.handleConnectionChange(state)
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        rtcClient.createPeerConnection()
        
        reconnectionHandler = WebRTCReconnectionHandler(rtcClient) {
            if (isOutgoing) {
                startOutgoingCall(iceRestart = true)
            } else {
                // Incomer waits for new offer or sends a 'reconnect' signal
                CoroutineScope(Dispatchers.IO).launch {
                    callRepo.sendSignaling(callId, targetUserId, "request-reconnect")
                }
            }
        }

        val localStream = rtcClient.createLocalStream("local_stream")

        if (callType == "video") {
            // Setup local video capture
            // ... (simplified for now)
        }
        rtcClient.addStream(localStream)
        
        binding.remoteVideoView.init(rtcClient.getEglBaseContext(), null)
        binding.localVideoView.init(rtcClient.getEglBaseContext(), null)
    }

    private fun setupUI() {
        binding.btnEndCall.setOnClickListener {
            endCall()
        }
        
        if (callType == "voice") {
            binding.localVideoView.visibility = View.GONE
            binding.remoteVideoView.visibility = View.GONE
        }
    }

    private fun startOutgoingCall(iceRestart: Boolean = false) {
        rtcClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    CoroutineScope(Dispatchers.IO).launch {
                        callRepo.sendSignaling(callId, targetUserId, if (iceRestart) "ice-restart-offer" else "offer", sdp = it.description)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, iceRestart = iceRestart)
    }


    private fun endCall() {
        CoroutineScope(Dispatchers.IO).launch {
            callRepo.endCall(callId)
        }
        finish()
    }

    override fun onDestroy() {
        reconnectionHandler?.cancel()
        rtcClient.close()
        super.onDestroy()
    }

}
