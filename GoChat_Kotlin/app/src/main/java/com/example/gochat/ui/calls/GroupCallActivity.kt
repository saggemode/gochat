package com.example.gochat.ui.calls

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.webrtc.CallAudioController
import com.example.gochat.core.webrtc.GroupCallClient
import com.example.gochat.data.repository.CallRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import javax.inject.Inject

/**
 * Multi-party (group) call over the SFU. Publishes local audio (+ camera when
 * video) and subscribes to every other participant, re-subscribing whenever the
 * SFU reports a participant change. Video shows a tile grid; audio shows a
 * participant count.
 *
 * NOTE: real-time multi-party calling needs on-device testing with 3+ phones.
 */
@AndroidEntryPoint
class GroupCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_group_call_id"
        const val EXTRA_TITLE = "extra_group_call_title"
        const val EXTRA_MEMBER_IDS = "extra_group_call_member_ids"
        const val EXTRA_VIDEO = "extra_group_call_video"
    }

    @Inject lateinit var callRepo: CallRepository
    @Inject lateinit var webSocket: GoChatWebSocket

    private lateinit var audioController: CallAudioController
    private var client: GroupCallClient? = null

    private val callId: String by lazy { intent.getStringExtra(EXTRA_CALL_ID).orEmpty() }
    private val title: String by lazy { intent.getStringExtra(EXTRA_TITLE) ?: "Group call" }
    private val isVideo: Boolean by lazy { intent.getBooleanExtra(EXTRA_VIDEO, false) }

    private var isMuted = false
    private var isVideoOff = false

    private lateinit var tvTitle: TextView
    private lateinit var tvParticipants: TextView
    private lateinit var videoGrid: GridLayout

    private var localRenderer: SurfaceViewRenderer? = null
    private val remoteRenderers = mutableListOf<SurfaceViewRenderer>()

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[android.Manifest.permission.RECORD_AUDIO] == false) {
            android.widget.Toast.makeText(this, "Microphone permission is required", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        observeSignaling()
        join()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callId.isBlank()) { finish(); return }
        audioController = CallAudioController(this)
        setContentView(buildUi())

        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (isVideo) perms.add(android.Manifest.permission.CAMERA)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun buildUi(): LinearLayout {
        val pad = (16 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B141A"))
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )

            tvTitle = TextView(this@GroupCallActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(0, pad, 0, pad / 2)
            }
            addView(tvTitle)

            tvParticipants = TextView(this@GroupCallActivity).apply {
                text = "Connecting…"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
                gravity = Gravity.CENTER
            }
            addView(tvParticipants)

            videoGrid = GridLayout(this@GroupCallActivity).apply {
                columnCount = 2
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { topMargin = pad }
            }
            addView(videoGrid)

            val controls = LinearLayout(this@GroupCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, pad, 0, pad)
            }
            controls.addView(Button(this@GroupCallActivity).apply {
                text = "Mute"
                setOnClickListener {
                    isMuted = !isMuted
                    client?.setMicrophoneEnabled(!isMuted)
                    text = if (isMuted) "Unmute" else "Mute"
                }
            })
            if (isVideo) {
                controls.addView(Button(this@GroupCallActivity).apply {
                    text = "Camera"
                    setOnClickListener {
                        isVideoOff = !isVideoOff
                        client?.setVideoEnabled(!isVideoOff)
                        localRenderer?.visibility = if (isVideoOff) android.view.View.GONE else android.view.View.VISIBLE
                        text = if (isVideoOff) "Camera on" else "Camera"
                    }
                })
            }
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

    private fun newTile(): SurfaceViewRenderer {
        val c = client ?: return SurfaceViewRenderer(this)
        val side = (resources.displayMetrics.widthPixels / 2) - (24 * resources.displayMetrics.density).toInt()
        return SurfaceViewRenderer(this).apply {
            init(c.eglContext(), null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            layoutParams = GridLayout.LayoutParams().apply {
                width = side
                height = (side * 1.3f).toInt()
                setMargins(6, 6, 6, 6)
            }
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
            audioController.start(speakerOn = isVideo)
            val c = GroupCallClient(this@GroupCallActivity, iceServers)
            client = c
            c.onRemoteVideoTrack = { track -> runOnUiThread { addRemoteTile(track) } }

            c.createPublisherOffer(
                context = this@GroupCallActivity,
                video = isVideo,
                onLocalVideo = { track -> runOnUiThread { showLocalTile(track) } }
            ) { sdp ->
                lifecycleScope.launch { callRepo.sendSignaling(callId, "sfu", "sfu-pub-offer", sdp = sdp) }
            }
            tvParticipants.text = "In call"

            val memberIds = intent.getStringArrayListExtra(EXTRA_MEMBER_IDS)
            if (!memberIds.isNullOrEmpty()) {
                callRepo.ringGroup(callId, title, memberIds)
            }
        }
    }

    private fun showLocalTile(track: VideoTrack) {
        if (localRenderer != null) return
        val tile = newTile().apply { setMirror(true) }
        localRenderer = tile
        track.addSink(tile)
        videoGrid.addView(tile, 0)
    }

    private fun addRemoteTile(track: VideoTrack) {
        val tile = newTile()
        remoteRenderers.add(tile)
        track.addSink(tile)
        videoGrid.addView(tile)
    }

    private fun clearRemoteTiles() {
        remoteRenderers.forEach {
            try { videoGrid.removeView(it); it.release() } catch (_: Exception) {}
        }
        remoteRenderers.clear()
    }

    private fun resubscribe(remoteCount: Int) {
        val c = client ?: return
        runOnUiThread { clearRemoteTiles() }
        c.createSubscriberOffer(remoteCount, isVideo) { sdp ->
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
                        resubscribe(1)
                    }
                    "sfu-sub-answer" -> if (!sdp.isNullOrBlank()) client?.setSubscriberAnswer(sdp)
                    "sfu-participants" -> {
                        val ids = event["participants"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            .split(",").filter { it.isNotBlank() }
                        runOnUiThread { tvParticipants.text = "${ids.size} in call" }
                        resubscribe((ids.size - 1).coerceAtLeast(1))
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
        runOnUiThread {
            clearRemoteTiles()
            try { localRenderer?.release() } catch (_: Exception) {}
            localRenderer = null
        }
        try { client?.close() } catch (_: Exception) {}
        client = null
        try { audioController.stop() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
