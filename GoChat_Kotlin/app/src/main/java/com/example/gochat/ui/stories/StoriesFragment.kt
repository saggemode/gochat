package com.example.gochat.ui.stories

import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.databinding.DialogCreateMediaStatusBinding
import com.example.gochat.databinding.DialogCreateTextStatusBinding
import com.example.gochat.databinding.FragmentStoriesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

@AndroidEntryPoint
class StoriesFragment : Fragment() {

    private var _binding: FragmentStoriesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StoriesViewModel by viewModels()
    private lateinit var storyAdapter: StoryAdapter

    @javax.inject.Inject
    lateinit var tokenManager: com.example.gochat.data.api.TokenManager

    @javax.inject.Inject
    lateinit var chatRepository: com.example.gochat.data.repository.ChatRepository

    // Music picker (SAF) → the callback receives the uploaded URL + title.
    private var pendingMusicCallback: ((url: String, title: String, artist: String) -> Unit)? = null
    private val pickMusicLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val cb = pendingMusicCallback
        pendingMusicCallback = null
        if (uri == null || cb == null) return@registerForActivityResult
        applyPickedMusic(uri, cb)
    }

    private val bgColors = listOf(
        "#00A884", // WhatsApp Emerald
        "#128C7E", // Dark Teal
        "#075E54", // Deep Green
        "#2C3E50", // Midnight Blue
        "#8E44AD", // Wisteria Purple
        "#C0392B", // Crimson Red
        "#D35400", // Pumpkin Orange
        "#16A085"  // Sea Green
    )
    private var currentColorIndex = 0

    // Photo picker for camera/image status
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedImage(it) }
    }

    private var voiceRecorder: com.example.gochat.core.media.AudioRecorderManager? = null

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceStatusRecording()
        else Toast.makeText(requireContext(), getString(R.string.error_mic_permission), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeRefresh()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        storyAdapter = StoryAdapter { userStories ->
            val intent = StoryViewerActivity.createIntent(requireContext(), userStories)
            startActivity(intent)
        }

        binding.rvRecentStories.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = storyAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshStories.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.gochat_accent)
        )
        binding.swipeRefreshStories.setOnRefreshListener {
            viewModel.loadStories()
        }
    }

    private fun setupClickListeners() {
        binding.btnQuickTextStatus.setOnClickListener {
            showCreateTextStatusDialog()
        }

        binding.fabTextStatus.setOnClickListener {
            showCreateTextStatusDialog()
        }

        binding.btnQuickCameraStatus.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.fabCameraStatus.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.fabVoiceStatus.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) startVoiceStatusRecording()
            else requestRecordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        binding.layoutMyStatusTile.setOnClickListener {
            val myStories = viewModel.myStories.value
            if (myStories != null && myStories.stories.isNotEmpty()) {
                val intent = StoryViewerActivity.createIntent(requireContext(), myStories)
                startActivity(intent)
            } else {
                showCreateTextStatusDialog()
            }
        }
    }

    /** Records a short voice clip and posts it as an audio status. */
    private fun startVoiceStatusRecording() {
        val recorder = com.example.gochat.core.media.AudioRecorderManager(requireContext())
        if (!recorder.startRecording()) {
            Toast.makeText(requireContext(), getString(R.string.error_audio_recorder_start), Toast.LENGTH_SHORT).show()
            return
        }
        voiceRecorder = recorder
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.voice_status_recording_title))
            .setMessage(getString(R.string.voice_status_recording_msg))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ ->
                voiceRecorder?.cancelRecording()
                voiceRecorder = null
                d.dismiss()
            }
            .setPositiveButton(getString(R.string.voice_status_post)) { d, _ ->
                val result = voiceRecorder?.stopRecording()
                voiceRecorder = null
                d.dismiss()
                if (result == null || result.durationSeconds < 1) {
                    Toast.makeText(requireContext(), getString(R.string.error_recording_failed), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                postVoiceStatus(result.file)
            }
            .show()
    }

    private fun postVoiceStatus(file: java.io.File) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.error_audio_process), Toast.LENGTH_SHORT).show()
                return@launch
            }
            viewModel.postMediaStatus(
                mediaBytes = bytes,
                mimeType = "audio/mp4",
                localDataUri = "file://${file.absolutePath}",
                caption = "",
                mediaType = "audio",
                allowReshare = true
            ) { success, error ->
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.toast_status_updated) else (error ?: getString(R.string.error_audio_process)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.myStories.collectLatest { myStory ->
                if (myStory == null) return@collectLatest

                val hasStories = myStory.stories.isNotEmpty()
                val avatarToLoad = if (hasStories && myStory.stories.first().mediaUrl.isNotBlank()) {
                    myStory.stories.first().mediaUrl
                } else {
                    myStory.userAvatar
                }

                com.example.gochat.core.media.MediaImageHelper.loadSafeImage(
                    binding.ivMyStatusAvatar,
                    avatarToLoad,
                    isCircle = true,
                    placeholderRes = R.drawable.ic_account,
                    errorRes = R.drawable.ic_account
                )

                binding.viewMyStatusStoryRing.visibility = if (hasStories) View.VISIBLE else View.GONE
                binding.ivMyStatusAddBadge.visibility = if (hasStories) View.GONE else View.VISIBLE

                if (hasStories) {
                    val count = myStory.stories.size
                    val countStr = if (count == 1) getString(R.string.story_count_singular) else getString(R.string.story_count_plural, count)
                    val totalViews = myStory.totalViewCount
                    val viewsStr = if (totalViews > 0) " • 👁️ $totalViews ${if (totalViews == 1) "view" else "views"}" else ""
                    val latestTime = myStory.stories.firstOrNull()?.createdAt?.ifBlank { getString(R.string.time_recently) } ?: getString(R.string.time_recently)
                    binding.tvMyStatusSubtitle.text = "$countStr$viewsStr • $latestTime"
                } else {
                    binding.tvMyStatusSubtitle.text = "Tap to add status update"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentStories.collectLatest { stories ->
                storyAdapter.submitList(stories)
                if (stories.isEmpty()) {
                    binding.layoutNoStories.visibility = View.VISIBLE
                    binding.rvRecentStories.visibility = View.GONE
                } else {
                    binding.layoutNoStories.visibility = View.GONE
                    binding.rvRecentStories.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRefreshing.collectLatest { refreshing ->
                binding.swipeRefreshStories.isRefreshing = refreshing
            }
        }
    }

    // ── Status options: privacy (A), mentions (B), music (C) ────────────────────

    /** A fresh composer options seeded with the user's remembered status-privacy default. */
    private fun defaultComposerOptions() = com.example.gochat.data.model.StatusComposerOptions(
        audienceType = tokenManager.statusAudienceType,
        audienceContactIds = tokenManager.statusAudienceIds
    )

    private fun audienceLabel(o: com.example.gochat.data.model.StatusComposerOptions): String = when (o.audienceType) {
        "except" -> "My contacts except ${o.audienceContactIds.size}"
        "only" -> "Only share with ${o.audienceContactIds.size}"
        else -> "My contacts"
    }

    /** Pill summary: audience + music/mention indicators. */
    private fun statusOptionsSummary(o: com.example.gochat.data.model.StatusComposerOptions): String {
        val sb = StringBuilder(audienceLabel(o))
        if (o.musicUrl.isNotBlank()) sb.append(" · ♪")
        if (o.mentionedUserIds.isNotEmpty()) sb.append(" · @${o.mentionedUserIds.size}")
        return sb.toString()
    }

    private fun showStatusOptionsMenu(
        current: com.example.gochat.data.model.StatusComposerOptions,
        onUpdate: (com.example.gochat.data.model.StatusComposerOptions) -> Unit
    ) {
        val items = arrayOf(
            "Privacy: ${audienceLabel(current)}",
            if (current.mentionedUserIds.isEmpty()) "Mention contacts" else "Mentions (${current.mentionedUserIds.size})",
            if (current.musicUrl.isBlank()) "Add music" else "Music: ${current.musicTitle.ifBlank { "Attached" }} (tap to change)"
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Status options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAudiencePicker(current, onUpdate)
                    1 -> showContactMultiSelect("Mention contacts", current.mentionedUserIds) { ids ->
                        onUpdate(current.copy(mentionedUserIds = ids))
                    }
                    2 -> {
                        if (current.musicUrl.isNotBlank()) {
                            // Offer to remove or replace
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setMessage("Music: ${current.musicTitle.ifBlank { "Attached" }}")
                                .setPositiveButton("Replace") { _, _ -> pickMusic { u, t, a -> onUpdate(current.copy(musicUrl = u, musicTitle = t, musicArtist = a)) } }
                                .setNegativeButton("Remove") { _, _ -> onUpdate(current.copy(musicUrl = "", musicTitle = "", musicArtist = "")) }
                                .setNeutralButton(getString(R.string.btn_cancel), null)
                                .show()
                        } else {
                            pickMusic { u, t, a -> onUpdate(current.copy(musicUrl = u, musicTitle = t, musicArtist = a)) }
                        }
                    }
                }
            }
            .show()
    }

    private fun showAudiencePicker(
        current: com.example.gochat.data.model.StatusComposerOptions,
        onUpdate: (com.example.gochat.data.model.StatusComposerOptions) -> Unit
    ) {
        val types = arrayOf("My contacts", "My contacts except…", "Only share with…")
        val checkedIndex = when (current.audienceType) {
            "except" -> 1
            "only" -> 2
            else -> 0
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Status privacy")
            .setSingleChoiceItems(types, checkedIndex) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> {
                        tokenManager.statusAudienceType = "contacts"
                        tokenManager.statusAudienceIds = emptyList()
                        onUpdate(current.copy(audienceType = "contacts", audienceContactIds = emptyList()))
                    }
                    1 -> showContactMultiSelect("Hide status from", current.audienceContactIds) { ids ->
                        tokenManager.statusAudienceType = "except"
                        tokenManager.statusAudienceIds = ids
                        onUpdate(current.copy(audienceType = "except", audienceContactIds = ids))
                    }
                    2 -> showContactMultiSelect("Only share with", current.audienceContactIds) { ids ->
                        tokenManager.statusAudienceType = "only"
                        tokenManager.statusAudienceIds = ids
                        onUpdate(current.copy(audienceType = "only", audienceContactIds = ids))
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** Multi-select over the user's 1:1 chat partners. Returns the chosen user ids. */
    private fun showContactMultiSelect(title: String, preselected: List<String>, onDone: (List<String>) -> Unit) {
        lifecycleScope.launch {
            val pool = withContext(Dispatchers.IO) { loadContactPool() }
            if (pool.isEmpty()) {
                Toast.makeText(requireContext(), "No contacts available to choose from", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = pool.map { it.second }.toTypedArray()
            val checked = BooleanArray(pool.size) { preselected.contains(pool[it].first) }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMultiChoiceItems(names, checked) { _, i, isChecked -> checked[i] = isChecked }
                .setPositiveButton("Done") { _, _ ->
                    onDone(pool.filterIndexed { i, _ -> checked[i] }.map { it.first })
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    /** The audience/mention pool: distinct 1:1 chat partners (userId → display name). */
    private suspend fun loadContactPool(): List<Pair<String, String>> {
        val myId = tokenManager.userId.orEmpty()
        return try {
            chatRepository.getAllConversationsList()
                .filter { !it.isGroup }
                .mapNotNull { c ->
                    val pid = c.memberIds.firstOrNull { it != myId && it.isNotBlank() } ?: return@mapNotNull null
                    pid to c.title.ifBlank { "Contact" }
                }
                .distinctBy { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickMusic(onPicked: (url: String, title: String, artist: String) -> Unit) {
        pendingMusicCallback = onPicked
        try {
            pickMusicLauncher.launch(arrayOf("audio/*"))
        } catch (_: Exception) {
            pendingMusicCallback = null
            Toast.makeText(requireContext(), "No audio picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPickedMusic(uri: Uri, cb: (url: String, title: String, artist: String) -> Unit) {
        lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val displayName = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                        val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
                    }
                }.getOrNull() ?: "Audio"
            }
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(requireContext(), "Couldn't read that audio", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(requireContext(), "Uploading music…", Toast.LENGTH_SHORT).show()
            val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
            val url = viewModel.uploadStatusMusic(bytes, mime, displayName)
            if (url.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Music upload failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            cb(url, displayName.substringBeforeLast('.').take(60), "")
        }
    }

    private fun showCreateTextStatusDialog() {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogCreateTextStatusBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        currentColorIndex = 0
        dialogBinding.layoutTextStatusRoot.setBackgroundColor(Color.parseColor(bgColors[currentColorIndex]))

        // Inherit the global "Allow resharing of my status" privacy setting;
        // can still be overridden for this one status.
        var allowReshare = tokenManager.allowStoryReshare
        applyReshareToggleVisual(dialogBinding.ivReshareToggleIcon, dialogBinding.tvReshareToggleText, allowReshare)
        dialogBinding.layoutReshareToggle.setOnClickListener {
            allowReshare = !allowReshare
            applyReshareToggleVisual(dialogBinding.ivReshareToggleIcon, dialogBinding.tvReshareToggleText, allowReshare)
        }

        var statusOptions = defaultComposerOptions()
        dialogBinding.tvStatusOptionsText.text = statusOptionsSummary(statusOptions)
        dialogBinding.layoutStatusOptions.setOnClickListener {
            showStatusOptionsMenu(statusOptions) { updated ->
                statusOptions = updated
                dialogBinding.tvStatusOptionsText.text = statusOptionsSummary(statusOptions)
            }
        }

        dialogBinding.btnCloseTextStatus.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnChangeBgColor.setOnClickListener {
            currentColorIndex = (currentColorIndex + 1) % bgColors.size
            val color = Color.parseColor(bgColors[currentColorIndex])
            dialogBinding.layoutTextStatusRoot.setBackgroundColor(color)
        }

        dialogBinding.fabPostStatus.setOnClickListener {
            val text = dialogBinding.etStatusText.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.error_empty_status), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val chosenColor = bgColors[currentColorIndex]
            dialogBinding.fabPostStatus.isEnabled = false

            viewModel.postTextStatus(text, chosenColor, allowReshare, statusOptions) { success, error ->
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.toast_status_updated) else (error ?: getString(R.string.error_image_processing)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialog.show()
    }

    private fun handleSelectedImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val compressed = withContext(Dispatchers.IO) {
                    com.example.gochat.core.media.ImageCompressor.compressImageUri(
                        context = requireContext().applicationContext,
                        uri = uri,
                        maxDimension = 1280,
                        quality = 80
                    )
                }

                if (compressed != null) {
                    showCreateMediaStatusDialog(compressed)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.error_image_processing), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_image_loading), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Updates a reshare toggle's icon + label to reflect the allowed/off state. */
    private fun applyReshareToggleVisual(icon: android.widget.ImageView, label: android.widget.TextView, allowed: Boolean) {
        if (allowed) {
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.gochat_emerald_light))
            label.text = "Reshare: Allowed"
            label.setTextColor(Color.WHITE)
        } else {
            icon.setColorFilter(Color.parseColor("#99FFFFFF"))
            label.text = "Reshare: Off"
            label.setTextColor(Color.parseColor("#99FFFFFF"))
        }
    }

    private fun showCreateMediaStatusDialog(compressed: com.example.gochat.core.media.ImageCompressor.CompressedImage) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogCreateMediaStatusBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        com.example.gochat.core.media.MediaImageHelper.loadSafeImage(
            dialogBinding.ivMediaPreview,
            compressed.dataUri,
            isCircle = false
        )

        // Inherit the global "Allow resharing of my status" privacy setting.
        var allowReshare = tokenManager.allowStoryReshare
        applyReshareToggleVisual(dialogBinding.ivMediaReshareIcon, dialogBinding.tvMediaReshareText, allowReshare)
        dialogBinding.layoutMediaReshareToggle.setOnClickListener {
            allowReshare = !allowReshare
            applyReshareToggleVisual(dialogBinding.ivMediaReshareIcon, dialogBinding.tvMediaReshareText, allowReshare)
        }

        var statusOptions = defaultComposerOptions()
        dialogBinding.tvMediaStatusOptionsText.text = statusOptionsSummary(statusOptions)
        dialogBinding.layoutMediaStatusOptions.setOnClickListener {
            showStatusOptionsMenu(statusOptions) { updated ->
                statusOptions = updated
                dialogBinding.tvMediaStatusOptionsText.text = statusOptionsSummary(statusOptions)
            }
        }

        dialogBinding.btnCloseMediaStatus.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.fabPostMediaStatus.setOnClickListener {
            val caption = dialogBinding.etMediaCaption.text?.toString()?.trim().orEmpty()
            dialogBinding.fabPostMediaStatus.isEnabled = false

            viewModel.postMediaStatus(
                mediaBytes = compressed.bytes,
                mimeType = compressed.mimeType,
                localDataUri = compressed.dataUri,
                caption = caption,
                mediaType = "image",
                allowReshare = allowReshare,
                options = statusOptions
            ) { success, error ->
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.toast_status_updated) else (error ?: getString(R.string.error_image_processing)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialog.show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            viewModel.loadStories()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
