package com.example.gochat.ui.chat

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.core.media.AudioPlayerManager
import com.example.gochat.core.media.AudioRecorderManager
import com.example.gochat.data.model.Message
import com.example.gochat.databinding.ActivityChatRoomBinding
import com.example.gochat.databinding.BottomSheetAttachmentPickerBinding
import com.example.gochat.databinding.DialogImagePreviewBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

class ChatRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
        const val EXTRA_CONVERSATION_AVATAR = "extra_conversation_avatar"
    }

    private lateinit var binding: ActivityChatRoomBinding
    private val viewModel: ChatRoomViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
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
            Toast.makeText(this, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture()
        } else {
            Toast.makeText(this, "Camera permission required to take photos", Toast.LENGTH_SHORT).show()
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
        binding = ActivityChatRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioRecorderManager = AudioRecorderManager(this)

        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: "Chat"
        val avatarUrl = intent.getStringExtra(EXTRA_CONVERSATION_AVATAR).orEmpty()

        setupToolbar(title, avatarUrl)
        setupMessagesRecyclerView()
        setupInputBar()
        setupReplyPreview()
        setupVoiceRecordingControls()
        observeState()

        if (convId.isNotEmpty()) {
            viewModel.initConversation(convId)
        }
    }

    private fun setupToolbar(title: String, avatarUrl: String) {
        with(binding) {
            tvChatTitle.text = title

            if (avatarUrl.isNotBlank()) {
                ivHeaderAvatar.load(avatarUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_account)
                    error(R.drawable.ic_account)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivHeaderAvatar.setImageResource(R.drawable.ic_account)
            }

            btnBack.setOnClickListener { finish() }

            btnPing.setOnClickListener {
                viewModel.sendPing()
            }

            btnCall.setOnClickListener {
                Toast.makeText(this@ChatRoomActivity, "Starting VoIP Call...", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "Voice note unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        )

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = messageAdapter
    }

    private fun setupInputBar() {
        with(binding) {
            etMessageInput.doAfterTextChanged { text ->
                val hasText = !text.isNullOrBlank()
                ivSendIcon.visibility = if (hasText) View.VISIBLE else View.GONE
                ivMicIcon.visibility = if (hasText) View.GONE else View.VISIBLE
                viewModel.sendTypingEvent(hasText)
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
            Toast.makeText(this, "Could not start audio recorder", Toast.LENGTH_SHORT).show()
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
                caption = "🎙️ Voice Note ($durationLabel)"
            )
        } else {
            Toast.makeText(this, "Recording failed or too short", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelVoiceRecording() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        audioRecorderManager.cancelRecording()

        binding.layoutVoiceRecording.visibility = View.GONE
        binding.layoutNormalInput.visibility = View.VISIBLE
        Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
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

        sheet.show()
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
            Toast.makeText(this, "Unable to initialize camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImagePreviewDialog(imageUri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.ivPreviewImage.load(imageUri) {
            crossfade(true)
        }

        dialogBinding.btnCloseImagePreview.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.fabSendImage.setOnClickListener {
            val caption = dialogBinding.etImageCaption.text?.toString()?.trim().orEmpty()
            dialogBinding.fabSendImage.isEnabled = false

            lifecycleScope.launch {
                val dataUri = withContext(Dispatchers.IO) {
                    convertImageUriToBase64(imageUri)
                }

                if (dataUri != null) {
                    viewModel.sendMediaMessage(
                        mediaUrl = dataUri,
                        type = 1, // Image
                        caption = caption.ifBlank { "📷 Photo" }
                    )
                    dialog.dismiss()
                } else {
                    dialogBinding.fabSendImage.isEnabled = true
                    Toast.makeText(this@ChatRoomActivity, "Failed to process photo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun convertImageUriToBase64(uri: Uri): String? {
        return try {
            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            // Compress to standard JPEG quality for efficient transfer
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (_: Exception) {
            null
        }
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
                    caption = "🎵 Audio File"
                )
            } else {
                Toast.makeText(this@ChatRoomActivity, "Failed to process audio file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                            binding.tvReplyPreviewSender.text = "Replying to ${replyMsg.senderName.ifBlank { "Message" }}"
                            binding.tvReplyPreviewText.text = replyMsg.content
                        } else {
                            binding.layoutReplyPreview.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isOtherUserTyping.collect { isTyping ->
                        binding.tvChatSubtitle.text = if (isTyping) "typing..." else "online"
                        binding.viewHeaderOnlineDot.visibility = View.VISIBLE
                    }
                }

                launch {
                    viewModel.screenShakeEvent.collect {
                        triggerScreenShake()
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
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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

        Toast.makeText(this, "💥 PING!!!", Toast.LENGTH_SHORT).show()
    }

    private fun showMessageOptionsDialog(message: Message) {
        val options = arrayOf("Reply", "Copy text", "Star message", "Delete message")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.setReplyingTo(message)
                    1 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("GoChat Message", message.content)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    2 -> Toast.makeText(this, "Message starred", Toast.LENGTH_SHORT).show()
                    3 -> viewModel.deleteMessage(message.id)
                }
            }
            .show()
    }

    private fun showMoreMenu() {
        val items = arrayOf("Wallpaper / Theme", "Mute notifications", "Clear chat", "Export chat")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                Toast.makeText(this, "Option selected", Toast.LENGTH_SHORT).show()
            }
            .show()
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
