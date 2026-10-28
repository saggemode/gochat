package com.example.gochat.ui.calls

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.webrtc.CallAudioController
import com.example.gochat.core.webrtc.GroupCallClient
import com.example.gochat.data.websocket.GoChatWebSocket
import com.example.gochat.data.repository.CallRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.PeerConnection
import javax.inject.Inject

/**
 * Multi-party (group) voice call over the SFU. Publishes local audio and
 * subscribes to every other participant; re-subscribes whenever the SFU reports
 * a participant change. Audio-only for now (remote audio plays automatically);
 * a video grid is a later layer.
 *
 * NOTE: real-time multi-party calling needs on-device testing with 3+ phones.
 */
@AndroidEntryPoint
class GroupCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_group_call_id"
        const val EXTRA_TITLE = "extra_group_call_title"
    }

    @Inject lateinit var callRepo: CallRepository
    @Inject lateinit var webSocket: GoChatWebSocket

    private lateinit var audioController: CallAudioController
    private var client: GroupCallClient? = null

    private val callId: String by lazy { intent.getStringExtra(EXTRA_CALL_ID).orEmpty() }
    private val title: String by lazy { intent.getStringExtra(EXTRA_TITLE) ?: "Group call" }

    private var isMuted = false
    private var joined = false

    private lateinit var tvTitle: TextView
    private lateinit var tvParticipants: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callId.isBlank()) { finish(); return }
        audioController = CallAudioController(this)

        setContentView(buildUi())

        observeSignaling()
        join()
    }

    private fun buildUi(): LinearLayout {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B141A"))
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )

            tvTitle = TextView(this@GroupCallActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, pad * 2, 0, pad / 2)
            }
            addView(tvTitle)

            tvStatus = TextView(this@GroupCallActivity).apply {
                text = "Connecting…"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 14f
                gravity = Gravity.CENTER
            }
            addView(tvStatus)

            tvParticipants = TextView(this@GroupCallActivity).apply {
                text = "You"
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, pad, 0, pad)
            }
            addView(tvParticipants)

            // Spacer
            addView(LinearLayout(this@GroupCallActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            val controls = LinearLayout(this@GroupCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            controls.addView(Button(this@GroupCallActivity).apply {
                text = "Mute"
                setOnClickListener {
                    isMuted = !isMuted
                    client?.setMicrophoneEnabled(!isMuted)
                    text = if (isMuted) "Unmute" else "Mute"
                }
            })
            controls.addView(Button(this@GroupCallActivity).apply {
                text = "Speaker"
                setOnClickListener { audioController.setSpeaker(!audioController.isSpeakerOn) }
            })
            controls.addView(Button(this@GroupCallActivity).apply {
                text = "End"
                setOnClickListener { endCall() }
            })
            addView(controls)
        }
    }

    private fun join() {
        lifecycleScope.launch {
            val iceServers = callRepo.getIceServers().map { cfg ->
                val b = PeerConnection.IceServer.builder(cfg.urls)
                cfg.username?.let { b.setUsername(it) }
                cfg.credential?.let { b.setPassword(it) }
                b.createIceServer()
            }
            audioController.start(speakerOn = false)
            val c = GroupCallClient(this@GroupCallActivity, iceServers)
            client = c
            c.createPublisherOffer { sdp ->
                lifecycleScope.launch { callRepo.sendSignaling(callId, "sfu", "sfu-pub-offer", sdp = sdp) }
            }
            joined = true
            tvStatus.text = "In call"
        }
    }

    private fun resubscribe(remoteCount: Int) {
        val c = client ?: return
        c.createSubscriberOffer(remoteCount) { sdp ->
            lifecycleScope.launch { callRepo.sendSignaling(callId, "sfu", "sfu-sub-offer", sdp = sdp) }
        }
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            webSocket.callEvents.collect { event ->
                val eventCallId = event["call_id"]?.jsonPrimitive?.contentOrNull
                if (eventCallId != null && eventCallId != callId) return@collect
                val sigType = event["signal_type"]?.jsonPrimitive?.contentOrNull
                    ?: event["signaling_type"]?.jsonPrimitive?.contentOrNull
                val sdp = event["sdp"]?.jsonPrimitive?.contentOrNull

                when (sigType) {
                    "sfu-pub-answer" -> if (!sdp.isNullOrBlank()) {
                        client?.setPublisherAnswer(sdp)
                        resubscribe(1) // subscribe to whoever's already here
                    }
                    "sfu-sub-answer" -> if (!sdp.isNullOrBlank()) client?.setSubscriberAnswer(sdp)
                    "sfu-participants" -> {
                        val list = event["participants"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val ids = list.split(",").filter { it.isNotBlank() }
                        runOnUiThread {
                            tvParticipants.text = "${ids.size} in call"
                        }
                        // Re-subscribe for everyone except ourselves.
                        val others = (ids.size - 1).coerceAtLeast(1)
                        resubscribe(others)
                    }
                }
            }
        }
    }

    private fun endCall() {
        lifecycleScope.launch {
            try { callRepo.sendSignaling(callId, "sfu", "sfu-leave") } catch (_: Exception) {}
            cleanup()
            finish()
        }
    }

    private fun cleanup() {
        try { client?.close() } catch (_: Exception) {}
        client = null
        try { audioController.stop() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
