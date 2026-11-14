package com.example.gochat.ui.calls

import com.example.gochat.R

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.gochat.core.ui.applySystemBarInsets
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.webrtc.CallAudioController
import com.example.gochat.core.webrtc.GroupCallClient
import com.example.gochat.core.webrtc.ScreenShareService
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
    private val title: String by lazy { intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.gcall_default_title) }
    // Mutable: the call can switch between audio and video mid-session.
    private var isVideo: Boolean = false

    private var isMuted = false
    private var isScreenSharing = false
    private var lastRemoteCount = 1

    private lateinit var tvTitle: TextView
    private lateinit var tvParticipants: TextView
    private lateinit var videoGrid: GridLayout
    private lateinit var btnVideo: Button
    private lateinit var btnScreen: Button

    private var localRenderer: SurfaceViewRenderer? = null
    private val remoteRenderers = mutableListOf<SurfaceViewRenderer>()

    // Screen-capture permission result (MediaProjection). On grant, start the foreground
    // service (required on Android 10+), then publish the screen as our video track.
    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            ScreenShareService.start(this)
            client?.startScreenShare(this, data, { t -> runOnUiThread { showLocalTile(t) } }) { sdp ->
                lifecycleScope.launch { callRepo.sendSignaling(callId, "sfu", "sfu-pub-offer", sdp = sdp) }
            }
            isScreenSharing = true
            isVideo = true
            runOnUiThread {
                btnScreen.text = getString(R.string.gcall_stop_sharing)
                btnVideo.text = getString(R.string.gcall_camera_on)
            }
            resubscribe(lastRemoteCount)
        } else {
            android.widget.Toast.makeText(this, getString(R.string.gcall_screen_denied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[android.Manifest.permission.RECORD_AUDIO] == false) {
            android.widget.Toast.makeText(this, getString(R.string.gcall_mic_required), android.widget.Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        observeSignaling()
        join()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callId.isBlank()) { finish(); return }
        isVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        audioController = CallAudioController(this)
        val rootUi = buildUi()
        setContentView(rootUi)
        rootUi.applySystemBarInsets() // keep title/controls clear of the status & nav bars

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
                text = getString(R.string.gcall_connecting)
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
                text = getString(R.string.gcall_mute)
                setOnClickListener {
                    isMuted = !isMuted
                    client?.setMicrophoneEnabled(!isMuted)
                    text = if (isMuted) getString(R.string.gcall_unmute) else getString(R.string.gcall_mute)
                }
            })
            // Video toggle: actually switches the call audio↔video (adds/removes the
            // camera track and renegotiates), so it works even if the call started as audio.
            btnVideo = Button(this@GroupCallActivity).apply {
                text = if (isVideo) getString(R.string.gcall_camera) else getString(R.string.gcall_camera_on)
                setOnClickListener { if (isVideo && !isScreenSharing) switchToAudio() else switchToVideo() }
            }
            controls.addView(btnVideo)
            // Screen share toggle.
            btnScreen = Button(this@GroupCallActivity).apply {
                text = getString(R.string.gcall_share_screen)
                setOnClickListener { if (isScreenSharing) stopScreenShare() else requestScreenShare() }
            }
            controls.addView(btnScreen)
            controls.addView(Button(this@GroupCallActivity).apply {
                text = getString(R.string.gcall_speaker)
                setOnClickListener { audioController.setSpeaker(!audioController.isSpeakerOn) }
            })
            controls.addView(Button(this@GroupCallActivity).apply {
                text = getString(R.string.gcall_end)
                setOnClickListener { endCall() }
            })
            addView(controls)
        }
    }

    private fun newTile(): SurfaceViewRenderer {
        val c = client ?: return SurfaceViewRenderer(this)
        return SurfaceViewRenderer(this).apply {
            init(c.eglContext(), null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            // Actual size is assigned by relayoutGrid() once the tile is attached, so
            // the grid reflows as participants join and leave.
            layoutParams = GridLayout.LayoutParams()
        }
    }

    /**
     * Adaptive grid: pick a column count from the number of tiles (1 full-width,
     * 2–4 → two columns, more → three) and resize every tile to fill the available
     * width evenly. Called whenever a tile is added or removed so the layout reflows
     * like a real group call instead of a fixed 2-column grid with static tiles.
     */
    private fun relayoutGrid() {
        val count = videoGrid.childCount
        if (count == 0) return
        val columns = when {
            count <= 1 -> 1
            count <= 4 -> 2
            else -> 3
        }
        videoGrid.columnCount = columns

        val density = resources.displayMetrics.density
        val marginPx = (6 * density).toInt()
        val gridWidth = if (videoGrid.width > 0) videoGrid.width else resources.displayMetrics.widthPixels
        val tileW = (gridWidth / columns) - marginPx * 2
        // One tile → tall portrait; a grid of several → roughly square tiles.
        val tileH = if (columns == 1) (tileW * 1.25f).toInt() else tileW

        for (i in 0 until videoGrid.childCount) {
            val v = videoGrid.getChildAt(i)
            val lp = (v.layoutParams as? GridLayout.LayoutParams) ?: GridLayout.LayoutParams()
            lp.width = tileW
            lp.height = tileH
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            v.layoutParams = lp
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
            tvParticipants.text = getString(R.string.gcall_in_call)

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
        relayoutGrid()
    }

    private fun addRemoteTile(track: VideoTrack) {
        val tile = newTile()
        remoteRenderers.add(tile)
        track.addSink(tile)
        videoGrid.addView(tile)
        relayoutGrid()
    }

    private fun clearRemoteTiles() {
        remoteRenderers.forEach {
            try { videoGrid.removeView(it); it.release() } catch (_: Exception) {}
        }
        remoteRenderers.clear()
        relayoutGrid()
    }

    private fun resubscribe(remoteCount: Int) {
        val c = client ?: return
        lastRemoteCount = remoteCount
        runOnUiThread { clearRemoteTiles() }
        c.createSubscriberOffer(remoteCount, isVideo) { sdp ->
            lifecycleScope.launch { callRepo.sendSignaling(callId, "sfu", "sfu-sub-offer", sdp = sdp) }
        }
    }

    // ── Switch audio ↔ video + screen share ─────────────────────────────────

    /** Turn the camera on (switches an audio call to video, or returns from screen share). */
    private fun switchToVideo() {
        val c = client ?: return
        c.enableCameraVideo(this, { t -> runOnUiThread { showLocalTile(t) } }) { sdp ->
            lifecycleScope.launch { callRepo.sendSignaling(callId, "sfu", "sfu-pub-offer", sdp = sdp) }
        }
        isVideo = true
        isScreenSharing = false
        btnVideo.text = getString(R.string.gcall_camera)
        btnScreen.text = getString(R.string.gcall_share_screen)
        resubscribe(lastRemoteCount) // re-subscribe with video so we also receive others' video
    }

    /** Turn the camera/screen off — stay in the call as audio-only. */
    private fun switchToAudio() {
        client?.disableCameraVideo()
        if (isScreenSharing) ScreenShareService.stop(this)
        isVideo = false
        isScreenSharing = false
        runOnUiThread {
            localRenderer?.let { try { videoGrid.removeView(it); it.release() } catch (_: Exception) {} }
            localRenderer = null
            relayoutGrid()
        }
        btnVideo.text = getString(R.string.gcall_camera_on)
        btnScreen.text = getString(R.string.gcall_share_screen)
    }

    /** Ask the system for screen-capture permission; the result launcher publishes it. */
    private fun requestScreenShare() {
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        if (mpm == null) {
            android.widget.Toast.makeText(this, getString(R.string.gcall_screen_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** Stop sharing the screen and fall back to the camera. */
    private fun stopScreenShare() {
        ScreenShareService.stop(this)
        switchToVideo() // swap the screen track back to the camera
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
                        runOnUiThread { tvParticipants.text = getString(R.string.gcall_participants, ids.size) }
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
        if (isScreenSharing) {
            try { ScreenShareService.stop(this) } catch (_: Exception) {}
            isScreenSharing = false
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
