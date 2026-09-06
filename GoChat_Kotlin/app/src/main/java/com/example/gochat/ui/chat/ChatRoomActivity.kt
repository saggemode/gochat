package com.example.gochat.ui.chat

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.view.View
import android.view.Window
import android.view.animation.CycleInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.gochat.R
import com.example.gochat.core.media.AudioPlayerManager
import com.example.gochat.core.media.AudioRecorderManager
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.core.wallpaper.ChatTheme
import com.example.gochat.core.wallpaper.ChatThemeManager
import com.example.gochat.core.wallpaper.WallpaperType
import com.example.gochat.data.model.Message
import com.example.gochat.databinding.ActivityChatRoomBinding
import com.example.gochat.databinding.BottomSheetAttachmentPickerBinding
import com.example.gochat.databinding.DialogImagePreviewBinding
import com.example.gochat.ui.calls.CallActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ChatRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
        const val EXTRA_CONVERSATION_AVATAR = "extra_conversation_avatar"
        const val EXTRA_INITIAL_MESSAGE = "extra_initial_message"
    }

    private lateinit var binding: ActivityChatRoomBinding
    private val viewModel: ChatRoomViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var mentionAdapter: GroupMemberAdapter
    private lateinit var audioRecorderManager: AudioRecorderManager

    private var recordingDurationSeconds = 0
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (audioRecorderManager.isRecording) {
                recordingDurationSeconds++
                val minutes = recordingDurationSeconds / 60
                val seconds = recordingDurationSeconds % 60
                binding.tvRecordingTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                recordingHandler.postDelayed(this, 1000)
            }
        }
    }

    private var cameraTempPhotoUri: Uri? = null

    // Permission launchers
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceRecording()
        } else {
            Toast.makeText(this, getString(R.string.error_mic_permission), Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture()
        } else {
            Toast.makeText(this, getString(R.string.error_camera_permission), Toast.LENGTH_SHORT).show()
        }
    }

    // Media capture / pick launchers
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraTempPhotoUri?.let { showImagePreviewDialog(it) }
        }
    }

    private val pickGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { showImagePreviewDialog(it) }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedAudioFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityChatRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioRecorderManager = AudioRecorderManager(this)

        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: "Chat"
        val avatarUrl = intent.getStringExtra(EXTRA_CONVERSATION_AVATAR).orEmpty()

        setupToolbar(title, avatarUrl)
        setupTheme(convId)
        setupMessagesRecyclerView()
        setupInputBar()
        setupReplyPreview()
        setupVoiceRecordingControls()
        setupWindowInsets()
        observeState()

        val initialMessage = intent.getStringExtra(EXTRA_INITIAL_MESSAGE)
        if (!initialMessage.isNullOrBlank()) {
            binding.etMessageInput.setText(initialMessage)
            binding.etMessageInput.setSelection(initialMessage.length)
        }

        if (convId.isNotEmpty()) {
            viewModel.initConversation(convId)
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatRoot) { _, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Top inset ensures toolbar content doesn't collide with status bar / camera notch
            binding.toolbarChatRoom.updatePadding(
                top = systemBarsInsets.top
            )

            // Bottom inset: lift the chat input directly above the software keyboard when open,
            // or above the system navigation bar when keyboard is dismissed.
            val bottomInset = if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            binding.chatContentContainer.updatePadding(
                bottom = bottomInset,
                left = systemBarsInsets.left,
                right = systemBarsInsets.right
            )

            // Auto-scroll messages to keep latest message visible when keyboard pops up
            if (imeInsets.bottom > 0 && messageAdapter.itemCount > 0) {
                binding.rvMessages.post {
                    binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }

            windowInsets
        }

        // Smoothly animate the chat input bar as the keyboard slides up/down
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.chatContentContainer,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val bottomInset = if (imeInsets.bottom > 0) imeInsets.bottom else systemBars.bottom

                    binding.chatContentContainer.updatePadding(bottom = bottomInset)
                    return insets
                }
            }
        )
    }

    private fun setupToolbar(title: String, avatarUrl: String) {
        with(binding) {
            tvChatTitle.text = title

            MediaImageHelper.loadSafeImage(
                imageView = ivHeaderAvatar,
                url = avatarUrl,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )

            layoutHeaderInfo.setOnClickListener {
                val intent = Intent(this@ChatRoomActivity, GroupInfoActivity::class.java).apply {
                    putExtra(GroupInfoActivity.EXTRA_CONVERSATION_ID, viewModel.conversationId.value)
                    putExtra(GroupInfoActivity.EXTRA_GROUP_NAME, title)
                    putExtra(GroupInfoActivity.EXTRA_GROUP_AVATAR, avatarUrl)
                    
                    val memberIds = viewModel.mentionSuggestions.value.map { it.id }
                    if (memberIds.isNotEmpty()) {
                        putStringArrayListExtra(GroupInfoActivity.EXTRA_MEMBER_IDS, ArrayList(memberIds))
                    }
                }
                startActivity(intent)
            }

            btnBack.setOnClickListener { finish() }

            btnPing.setOnClickListener {
                viewModel.sendPing()
            }

            btnCall.setOnClickListener {
                val intent = Intent(this@ChatRoomActivity, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_CALL_ID, "call_${System.currentTimeMillis()}")
                    putExtra(CallActivity.EXTRA_TARGET_USER_ID, viewModel.conversationId.value)
                    putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
                    putExtra(CallActivity.EXTRA_CALL_TYPE, "voice")
                }
                startActivity(intent)
            }

            btnMoreChatOptions.setOnClickListener {
                showMoreMenu()
            }
        }
    }

    private fun setupMessagesRecyclerView() {
        messageAdapter = MessageAdapter(
            onReplyClicked = { message ->
                viewModel.setReplyingTo(message)
            },
            onMessageLongClicked = { message ->
                showMessageOptionsDialog(message)
            },
            onPlayVoiceClicked = { message ->
                val audioUrl = message.mediaUrl.orEmpty()
                if (audioUrl.isNotBlank()) {
                    AudioPlayerManager.playOrPause(this, message.id, audioUrl)
                } else {
                    Toast.makeText(this, getString(R.string.error_voice_note_unavailable), Toast.LENGTH_SHORT).show()
                }
            }
        ).apply {
            onImageClicked = { imageUrl ->
                showFullScreenImage(imageUrl)
            }
        }

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = messageAdapter

        // Mention suggestions
        mentionAdapter = GroupMemberAdapter(
            onItemClicked = { user ->
                insertMention(user.displayName)
            }
        )
        binding.rvMentionSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvMentionSuggestions.adapter = mentionAdapter
    }

    private fun insertMention(name: String) {
        val text = binding.etMessageInput.text.toString()
        val pos = binding.etMessageInput.selectionStart
        val beforeCursor = text.take(pos)
        val lastAt = beforeCursor.lastIndexOf('@')
        if (lastAt != -1) {
            val newText = text.substring(0, lastAt) + "@$name " + text.substring(pos)
            binding.etMessageInput.setText(newText)
            binding.etMessageInput.setSelection(lastAt + name.length + 2)
        }
        viewModel.onInputTextChanged(binding.etMessageInput.text.toString(), binding.etMessageInput.selectionStart)
    }

    private fun setupInputBar() {
        with(binding) {
            etMessageInput.doAfterTextChanged { text ->
                val hasText = !text.isNullOrBlank()
                ivSendIcon.visibility = if (hasText) View.VISIBLE else View.GONE
                ivMicIcon.visibility = if (hasText) View.GONE else View.VISIBLE
                viewModel.sendTypingEvent(hasText)
                
                viewModel.onInputTextChanged(text?.toString().orEmpty(), etMessageInput.selectionStart)
            }

            etMessageInput.setOnClickListener {
                if (messageAdapter.itemCount > 0) {
                    rvMessages.postDelayed({
                        rvMessages.smoothScrollToPosition(messageAdapter.itemCount - 1)
                    }, 150)
                }
            }

            etMessageInput.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && messageAdapter.itemCount > 0) {
                    rvMessages.postDelayed({
                        rvMessages.smoothScrollToPosition(messageAdapter.itemCount - 1)
                    }, 150)
                }
            }

            btnSendOrVoice.setOnClickListener {
                val text = etMessageInput.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    viewModel.sendTextMessage(text)
                    etMessageInput.setText("")
                } else {
                    checkAudioPermissionAndStartRecording()
                }
            }

            btnAttachment.setOnClickListener {
                showAttachmentPickerBottomSheet()
            }
        }
    }

    private fun setupReplyPreview() {
        binding.btnCloseReplyPreview.setOnClickListener {
            viewModel.clearReply()
        }
        binding.btnCloseEditPreview.setOnClickListener {
            viewModel.clearEditing()
            binding.etMessageInput.setText("")
        }
    }

    private fun setupVoiceRecordingControls() {
        binding.btnCancelVoiceRecording.setOnClickListener {
            cancelVoiceRecording()
        }

        binding.btnSendVoiceRecording.setOnClickListener {
            stopAndSendVoiceRecording()
        }
    }

    private fun checkAudioPermissionAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        val started = audioRecorderManager.startRecording()
        if (started) {
            binding.layoutNormalInput.visibility = View.GONE
            binding.layoutVoiceRecording.visibility = View.VISIBLE

            recordingDurationSeconds = 0
            binding.tvRecordingTimer.text = "00:00"
            recordingHandler.postDelayed(recordingTimerRunnable, 1000)

            // Pulsing dot animation
            val pulse = ObjectAnimator.ofFloat(binding.viewRecordingDot, "alpha", 1f, 0.2f, 1f)
            pulse.duration = 1000
            pulse.repeatCount = ObjectAnimator.INFINITE
            pulse.start()
        } else {
            Toast.makeText(this, getString(R.string.error_audio_recorder_start), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndSendVoiceRecording() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        val result = audioRecorderManager.stopRecording()

        binding.layoutVoiceRecording.visibility = View.GONE
        binding.layoutNormalInput.visibility = View.VISIBLE

        if (result != null) {
            val minutes = result.durationSeconds / 60
            val seconds = result.durationSeconds % 60
            val durationLabel = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
            viewModel.sendMediaMessage(
                mediaUrl = result.base64DataUri,
                type = 5, // Voice note
                caption = getString(R.string.caption_voice_note, durationLabel)
            )
        } else {
            Toast.makeText(this, getString(R.string.error_recording_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelVoiceRecording() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        audioRecorderManager.cancelRecording()

        binding.layoutVoiceRecording.visibility = View.GONE
        binding.layoutNormalInput.visibility = View.VISIBLE
        Toast.makeText(this, getString(R.string.toast_recording_cancelled), Toast.LENGTH_SHORT).show()
    }

    private fun showAttachmentPickerBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = BottomSheetAttachmentPickerBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.btnPickCamera.setOnClickListener {
            sheet.dismiss()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraCapture()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        sheetBinding.btnPickGallery.setOnClickListener {
            sheet.dismiss()
            pickGalleryLauncher.launch("image/*")
        }

        sheetBinding.btnPickAudio.setOnClickListener {
            sheet.dismiss()
            pickAudioLauncher.launch("audio/*")
        }

        sheetBinding.btnPickLocation.setOnClickListener {
            sheet.dismiss()
            startLocationPicker()
        }

        sheetBinding.btnPickGif.setOnClickListener {
            sheet.dismiss()
            showGifPicker()
        }

        sheet.show()
    }

    private fun startLocationPicker() {
        val intent = Intent(this, LocationPickerActivity::class.java)
        startActivityForResult(intent, 1002)
    }

    private fun showGifPicker() {
        val gifPicker = GifPickerBottomSheet { url ->
            viewModel.sendMediaMessage(url, 1, "GIF") // Using type IMAGE for GIFs
        }
        gifPicker.show(supportFragmentManager, "GifPicker")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && resultCode == RESULT_OK && data != null) {
            val lat = data.getDoubleExtra("lat", 0.0)
            val lng = data.getDoubleExtra("lng", 0.0)
            viewModel.sendTextMessage("📍 Location: https://maps.google.com/maps?q=$lat,$lng")
        }
    }

    private fun launchCameraCapture() {
        try {
            val imageDir = File(cacheDir, "images").apply {
                if (!exists()) mkdirs()
            }
            val photoFile = File(imageDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile
            )
            cameraTempPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_camera_init), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImagePreviewDialog(imageUri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        MediaImageHelper.loadSafeImage(
            imageView = dialogBinding.ivPreviewImage,
            url = imageUri.toString(),
            isCircle = false,
            placeholderRes = R.drawable.ic_gallery,
            errorRes = R.drawable.ic_gallery
        )

        dialogBinding.btnCloseImagePreview.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.fabSendImage.setOnClickListener {
            val caption = dialogBinding.etImageCaption.text?.toString()?.trim().orEmpty()
            dialogBinding.fabSendImage.isEnabled = false
            dialogBinding.etImageCaption.isEnabled = false

            lifecycleScope.launch {
                val compressed = withContext(Dispatchers.IO) {
                    ImageCompressor.compressImageUri(
                        context = applicationContext,
                        uri = imageUri,
                        maxDimension = 1280,
                        quality = 80
                    )
                }

                if (compressed != null) {
                    viewModel.sendImageMessage(
                        bytes = compressed.bytes,
                        dataUriFallback = compressed.dataUri,
                        caption = caption.ifBlank { getString(R.string.caption_photo) }
                    )
                    dialog.dismiss()
                } else {
                    dialogBinding.fabSendImage.isEnabled = true
                    dialogBinding.etImageCaption.isEnabled = true
                    Toast.makeText(this@ChatRoomActivity, getString(R.string.error_photo_process), Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun showFullScreenImage(mediaUrl: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.etImageCaption.visibility = View.GONE
        dialogBinding.fabSendImage.visibility = View.GONE

        MediaImageHelper.loadSafeImage(
            imageView = dialogBinding.ivPreviewImage,
            url = mediaUrl,
            isCircle = false,
            placeholderRes = R.drawable.ic_gallery,
            errorRes = R.drawable.ic_gallery
        )

        dialogBinding.btnCloseImagePreview.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun handleSelectedAudioFile(uri: Uri) {
        lifecycleScope.launch {
            val dataUri = withContext(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
                    val bytes = inputStream.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val mime = contentResolver.getType(uri) ?: "audio/m4a"
                    "data:$mime;base64,$base64"
                } catch (_: Exception) {
                    null
                }
            }

            if (dataUri != null) {
                viewModel.sendMediaMessage(
                    mediaUrl = dataUri,
                    type = 5,
                    caption = getString(R.string.caption_audio_file)
                )
            } else {
                Toast.makeText(this@ChatRoomActivity, getString(R.string.error_audio_process), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.botConfig.collect { config ->
                        binding.tvBotBanner.visibility = if (config?.isActive == true) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.messages.collect { messagesList ->
                        val wasAtBottom = isLastItemVisible()
                        messageAdapter.submitList(messagesList) {
                            if (wasAtBottom || messagesList.isNotEmpty()) {
                                binding.rvMessages.scrollToPosition(messagesList.size - 1)
                            }
                        }
                    }
                }

                launch {
                    viewModel.replyingTo.collect { replyMsg ->
                        if (replyMsg != null) {
                            binding.layoutReplyPreview.visibility = View.VISIBLE
                            binding.layoutEditPreview.visibility = View.GONE
                            binding.tvReplyPreviewSender.text = getString(R.string.replying_to_format, replyMsg.senderName.ifBlank { getString(R.string.message_hint) })
                            binding.tvReplyPreviewText.text = replyMsg.content
                        } else {
                            binding.layoutReplyPreview.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.editingMessage.collect { editMsg ->
                        if (editMsg != null) {
                            binding.layoutEditPreview.visibility = View.VISIBLE
                            binding.layoutReplyPreview.visibility = View.GONE
                            binding.tvEditPreviewText.text = editMsg.content
                            binding.etMessageInput.setText(editMsg.content)
                            binding.etMessageInput.requestFocus()
                            // Move cursor to end
                            binding.etMessageInput.setSelection(editMsg.content.length)
                        } else {
                            binding.layoutEditPreview.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isOtherUserTyping.collect { isTyping ->
                        binding.tvChatSubtitle.text = if (isTyping) getString(R.string.status_typing) else getString(R.string.status_online)
                        binding.viewHeaderOnlineDot.visibility = View.VISIBLE
                    }
                }

                launch {
                    viewModel.screenShakeEvent.collect {
                        triggerScreenShake()
                    }
                }

                launch {
                    viewModel.mentionSuggestions.collect { suggestions ->
                        if (suggestions.isNotEmpty()) {
                            binding.rvMentionSuggestions.visibility = View.VISIBLE
                            mentionAdapter.submitList(suggestions)
                        } else {
                            binding.rvMentionSuggestions.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun isLastItemVisible(): Boolean {
        val lm = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return true
        val lastPos = lm.findLastVisibleItemPosition()
        return lastPos >= messageAdapter.itemCount - 2
    }

    private fun triggerScreenShake() {
        // Haptic feedback
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(350)
            }
        } catch (_: Exception) {}

        // Visual screen shake animation
        val shakeAnimator = ObjectAnimator.ofFloat(binding.chatRoot, "translationX", 0f, 25f, -25f, 20f, -20f, 10f, -10f, 0f)
        shakeAnimator.duration = 500
        shakeAnimator.interpolator = CycleInterpolator(1f)
        shakeAnimator.start()

        Toast.makeText(this, getString(R.string.ping_message), Toast.LENGTH_SHORT).show()
    }

    private fun showMessageOptionsDialog(message: Message) {
        val sheet = MessageActionBottomSheet(message) { action ->
            when (action) {
                MessageActionBottomSheet.Action.REPLY -> viewModel.setReplyingTo(message)
                MessageActionBottomSheet.Action.FORWARD -> {
                    Toast.makeText(this, getString(R.string.prompt_forward_chat), Toast.LENGTH_SHORT).show()
                }
                MessageActionBottomSheet.Action.STAR -> {
                    viewModel.toggleStar(message.id, !message.isStarred)
                    val status = if (message.isStarred) "unstarred" else "starred"
                    Toast.makeText(this, getString(R.string.toast_message_status, status), Toast.LENGTH_SHORT).show()
                }
                MessageActionBottomSheet.Action.EDIT -> viewModel.setEditingMessage(message)
                MessageActionBottomSheet.Action.DELETE -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_delete_message_title))
                        .setMessage(getString(R.string.dialog_delete_message_desc))
                        .setPositiveButton(getString(R.string.action_delete)) { _, _ -> viewModel.deleteMessageForEveryone(message.id) }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
                MessageActionBottomSheet.Action.REACT_LIKE -> viewModel.addReaction(message.id, "👍")
                MessageActionBottomSheet.Action.REACT_HEART -> viewModel.addReaction(message.id, "❤️")
                MessageActionBottomSheet.Action.REACT_LAUGH -> viewModel.addReaction(message.id, "😂")
                MessageActionBottomSheet.Action.REACT_WOW -> viewModel.addReaction(message.id, "😮")
                MessageActionBottomSheet.Action.REACT_SAD -> viewModel.addReaction(message.id, "😢")
                MessageActionBottomSheet.Action.REACT_PRAY -> viewModel.addReaction(message.id, "🙏")
            }
        }
        sheet.show(supportFragmentManager, MessageActionBottomSheet.TAG)
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            getString(R.string.option_wallpaper_theme),
            getString(R.string.option_mute_notifications),
            getString(R.string.option_clear_chat),
            getString(R.string.option_export_chat)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val convId = viewModel.conversationId.value ?: intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: getString(R.string.message_hint)
                        val sheet = ChatWallpaperBottomSheet(
                            conversationId = convId,
                            conversationTitle = title,
                            onThemeChanged = { updatedTheme ->
                                applyTheme(updatedTheme)
                            }
                        )
                        sheet.show(supportFragmentManager, ChatWallpaperBottomSheet.TAG)
                    }
                    else -> Toast.makeText(this, getString(R.string.toast_option_selected), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun setupTheme(convId: String) {
        val themeManager = ChatThemeManager(this)
        val theme = themeManager.getTheme(convId)
        applyTheme(theme)
    }

    private fun applyTheme(theme: ChatTheme) {
        // Apply Wallpaper
        if (theme.type == WallpaperType.CUSTOM_IMAGE && !theme.imageUriOrPath.isNullOrBlank()) {
            binding.ivCustomWallpaper.visibility = View.VISIBLE
            binding.ivCustomWallpaper.load(File(theme.imageUriOrPath))
            binding.chatRoot.setBackgroundColor(Color.BLACK)
        } else {
            binding.ivCustomWallpaper.visibility = View.GONE
            if (theme.solidColor != null) {
                binding.chatRoot.setBackgroundColor(theme.solidColor)
            } else {
                val drawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    theme.bgGradientColors.toIntArray()
                )
                binding.chatRoot.background = drawable
            }
        }

        binding.chatDoodleView.visibility = if (theme.showDoodle) View.VISIBLE else View.GONE
        binding.chatDoodleView.setDoodleOpacity(theme.doodleOpacity)

        // Apply Accent Color
        val accentColor = theme.accentColor
        binding.tvChatTitle.setTextColor(accentColor)
        binding.ivSendIcon.imageTintList = ColorStateList.valueOf(accentColor)
        binding.btnPing.imageTintList = ColorStateList.valueOf(accentColor)
        binding.btnCall.imageTintList = ColorStateList.valueOf(accentColor)
        binding.btnMoreChatOptions.imageTintList = ColorStateList.valueOf(accentColor)
        binding.btnBack.imageTintList = ColorStateList.valueOf(accentColor)
        
        // Update adapter if it exists
        if (::messageAdapter.isInitialized) {
            messageAdapter.setAccentColor(accentColor)
        }
    }

    override fun onPause() {
        super.onPause()
        if (audioRecorderManager.isRecording) {
            cancelVoiceRecording()
        }
        AudioPlayerManager.stop()
    }

    override fun onDestroy() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        audioRecorderManager.cancelRecording()
        AudioPlayerManager.release()
        super.onDestroy()
    }
}
