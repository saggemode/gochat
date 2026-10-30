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
import com.example.gochat.core.ui.EdgeToEdgeHelper.applyEdgeToEdge
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import android.view.MotionEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.gochat.R
import com.example.gochat.ui.marketplace.OrdersActivity
import com.example.gochat.ui.marketplace.ProductDetailsActivity
import com.example.gochat.ui.marketplace.CartActivity
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.data.model.Product
import com.google.android.material.snackbar.Snackbar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import com.example.gochat.ui.contacts.SelectContactActivity
import com.example.gochat.core.currency.CurrencyManager
import com.example.gochat.core.media.AudioPlayerManager
import com.example.gochat.core.media.AudioRecorderManager
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.core.wallpaper.ChatTheme
import com.example.gochat.core.wallpaper.ChatThemeManager
import com.example.gochat.core.wallpaper.WallpaperType
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.core.haptic.HapticEngine
import androidx.transition.TransitionManager
import androidx.transition.AutoTransition
import com.example.gochat.data.model.InvoiceData
import com.example.gochat.databinding.ActivityChatRoomBinding
import com.example.gochat.databinding.BottomSheetAttachmentPickerBinding
import com.example.gochat.databinding.DialogImagePreviewBinding
import com.example.gochat.ui.calls.CallActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.CallRepository
import com.example.gochat.databinding.BottomSheetCustomOfferBinding

@AndroidEntryPoint
class ChatRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
        const val EXTRA_CONVERSATION_AVATAR = "extra_conversation_avatar"
        const val EXTRA_INITIAL_MESSAGE = "extra_initial_message"
        const val EXTRA_IS_ONLINE = "extra_is_online"
        const val EXTRA_LAST_SEEN = "extra_last_seen"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_PARTNER_ID = "extra_partner_id"
        const val EXTRA_PRODUCT_ID = "extra_product_id"
        const val EXTRA_PRODUCT_NAME = "extra_product_name"
        const val EXTRA_PRODUCT_PRICE = "extra_product_price"
        const val EXTRA_PRODUCT_IMAGE = "extra_product_image"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_ORDER_NUMBER = "extra_order_number"
        const val EXTRA_ORDER_TOTAL = "extra_order_total"
        const val EXTRA_TARGET_MESSAGE_ID = "extra_target_message_id"
    }

    private lateinit var binding: ActivityChatRoomBinding
    private val viewModel: ChatRoomViewModel by viewModels()
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var tokenManager: com.example.gochat.data.api.TokenManager
    @Inject lateinit var marketplaceRepository: MarketplaceRepository
    @Inject lateinit var callRepository: CallRepository
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var mentionAdapter: GroupMemberAdapter
    private lateinit var commandAdapter: BotCommandAdapter
    private lateinit var audioRecorderManager: AudioRecorderManager

    private var unreadNewMessagesCount = 0
    private var isScrolledUp = false
    private var currentPartnerId: String? = null
    private var isPartnerBlocked: Boolean = false

    private var recordingDurationSeconds = 0
    private var recordingDotPulse: ObjectAnimator? = null
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val recordingTimerRunnable = object : Runnable {
        private var tickCount = 0
        override fun run() {
            if (audioRecorderManager.isRecording) {
                if (!audioRecorderManager.isPaused) {
                    tickCount++
                    if (tickCount >= 10) {
                        recordingDurationSeconds++
                        val minutes = recordingDurationSeconds / 60
                        val seconds = recordingDurationSeconds % 60
                        binding.tvRecordingTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                        tickCount = 0
                    }
                    
                    // Update Waveform
                    val amplitude = audioRecorderManager.getMaxAmplitude()
                    binding.waveformRecording.addBar(amplitude.toFloat())
                }
                
                recordingHandler.postDelayed(this, 100)
            } else {
                tickCount = 0
            }
        }
    }

    private var cameraTempPhotoUri: Uri? = null

    // Typing animation & outgoing typing debounce
    private var isCurrentlyTypingSent = false
    private val stopTypingHandler = Handler(Looper.getMainLooper())
    private val stopTypingRunnable = Runnable {
        if (isCurrentlyTypingSent) {
            isCurrentlyTypingSent = false
            viewModel.sendTypingEvent(false)
        }
    }

    private val typingAnimHandler = Handler(Looper.getMainLooper())
    private var typingDotCount = 0
    private var isTypingAnimationRunning = false
    private val typingAnimationRunnable = object : Runnable {
        override fun run() {
            if (!isTypingAnimationRunning) return
            val dots = ".".repeat((typingDotCount % 3) + 1)
            binding.tvChatSubtitle.text = "typing$dots"
            typingDotCount++
            typingAnimHandler.postDelayed(this, 400)
        }
    }

    private fun startTypingAnimation() {
        if (!isTypingAnimationRunning) {
            isTypingAnimationRunning = true
            typingDotCount = 0
            binding.tvChatSubtitle.text = "typing."
            typingAnimHandler.removeCallbacks(typingAnimationRunnable)
            typingAnimHandler.postDelayed(typingAnimationRunnable, 400)
        }
    }

    private fun stopTypingAnimation() {
        if (isTypingAnimationRunning) {
            isTypingAnimationRunning = false
            typingAnimHandler.removeCallbacks(typingAnimationRunnable)
        }
    }

    /** Shows/hides the in-chat typing bubble; renders the sender's avatar in group chats. */
    private fun renderTypingBubble(participant: TypingParticipant?) {
        if (participant == null) {
            binding.layoutTypingIndicator.visibility = View.GONE
            binding.typingDots.stop()
            return
        }
        val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        if (isGroup) {
            binding.ivTypingAvatar.visibility = View.VISIBLE
            MediaImageHelper.loadSafeImage(
                imageView = binding.ivTypingAvatar,
                url = participant.avatarUrl,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )
        } else {
            binding.ivTypingAvatar.visibility = View.GONE
        }
        binding.layoutTypingIndicator.visibility = View.VISIBLE
        binding.typingDots.start()
    }

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

    private var cameraTempVideoUri: Uri? = null

    // ── Video note (hold-to-record) state ─────────────────────────
    private var videoNoteProvider: ProcessCameraProvider? = null
    private var videoNoteCapture: VideoCapture<Recorder>? = null
    private var videoNoteRecording: Recording? = null
    private var videoNoteFile: File? = null
    private var isVideoNoteRecording = false
    private var isVideoNoteLocked = false
    private var isVideoNoteCancelled = false
    private var videoNoteDownX = 0f
    private var videoNoteDownY = 0f
    private var videoNoteStartMs = 0L
    // Video notes aren't re-compressed, so cap the recording itself at 15 MB (Telegram's serve-back limit).
    private val VIDEO_NOTE_MAX_BYTES = 15L * 1024 * 1024
    private val videoNoteHandler = Handler(Looper.getMainLooper())
    private val videoNoteStartRunnable = Runnable { startVideoNote() }
    private val videoNoteTimerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - videoNoteStartMs) / 1000
            binding.tvVideoNoteTimer.text = String.format(Locale.US, "● %02d:%02d", elapsed / 60, elapsed % 60)
            videoNoteHandler.postDelayed(this, 500)
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

    // Long-press the camera → record a video, WhatsApp-style.
    private val requestCameraForVideoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchVideoCapture()
        } else {
            Toast.makeText(this, getString(R.string.error_camera_permission), Toast.LENGTH_SHORT).show()
        }
    }

    private val captureVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success) {
            cameraTempVideoUri?.let { sendVideoCompressed(it) }
        }
    }

    // Location picker result (replaces the deprecated startActivityForResult/onActivityResult path)
    private val locationPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                val lat = data.getDoubleExtra("lat", 0.0)
                val lng = data.getDoubleExtra("lng", 0.0)
                viewModel.sendTextMessage("📍 Location: https://maps.google.com/maps?q=$lat,$lng")
            }
        }
    }

    private val requestVideoNotePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true) {
            Toast.makeText(this, "Hold the camera to record a video note", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    // WhatsApp-style in-app camera: tap = photo, hold = video. Returns the captured media.
    private val inAppCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getStringExtra(CameraCaptureActivity.EXTRA_RESULT_URI)
            val type = result.data?.getStringExtra(CameraCaptureActivity.EXTRA_RESULT_TYPE)
            if (!uri.isNullOrBlank()) {
                when (type) {
                    "photo" -> viewModel.sendMediaMessage(uri, 1, "")
                    "video" -> sendVideoCompressed(Uri.parse(uri))
                }
            }
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

    // Gallery picker that accepts BOTH photos and videos, routing by MIME type.
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { routePickedMedia(it) }
    }

    // Document picker — any file type (PDF, docs, zips, etc.).
    private val pickDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { sendPickedDocument(it) }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedAudioFile(it) }
    }

    // Android 14+ Screenshot Detection
    private val screenCaptureCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ScreenCaptureCallback {
            viewModel.sendScreenshotNotification()
        }
    } else null

    // Android < 14 Screenshot Detection fallback using MediaStore ContentObserver
    private var screenshotObserver: android.database.ContentObserver? = null

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            android.util.Log.w("ChatRoomActivity", "POST_NOTIFICATIONS permission not granted by user")
        }
    }

    private val contactProfileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val prodId = result.data?.getStringExtra(EXTRA_PRODUCT_ID)
            if (!prodId.isNullOrBlank()) {
                val prodName = result.data?.getStringExtra(EXTRA_PRODUCT_NAME).orEmpty()
                val prodPrice = result.data?.getDoubleExtra(EXTRA_PRODUCT_PRICE, 0.0) ?: 0.0
                val prodImage = result.data?.getStringExtra(EXTRA_PRODUCT_IMAGE).orEmpty()
                val inquiry = result.data?.getStringExtra(EXTRA_INITIAL_MESSAGE).orEmpty()

                viewModel.sendProductMessage(
                    productId = prodId,
                    name = prodName,
                    price = prodPrice,
                    image = prodImage,
                    inquiry = inquiry
                )
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        binding = ActivityChatRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioRecorderManager = AudioRecorderManager(this)

        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: "Chat"
        val avatarUrl = intent.getStringExtra(EXTRA_CONVERSATION_AVATAR).orEmpty()
        val isOnline = intent.getBooleanExtra(EXTRA_IS_ONLINE, false)
        val lastSeen = intent.getLongExtra(EXTRA_LAST_SEEN, 0L)
        val partnerId = intent.getStringExtra(EXTRA_PARTNER_ID)
        currentPartnerId = partnerId
        viewModel.setInitialPresence(isOnline, if (lastSeen > 0L) lastSeen else null, partnerId)

        setupToolbar(title, avatarUrl)
        setupTheme(convId)
        setupMessagesRecyclerView()
        setupInputBar()
        setupReplyPreview()
        setupVoiceRecordingControls()
        setupWindowInsets()
        setupInChatSearch()
        setupSelectionBar()
        setupScreenshotDetection()
        observeState()

        val targetMsgId = intent.getStringExtra(EXTRA_TARGET_MESSAGE_ID)
        if (!targetMsgId.isNullOrEmpty()) {
            jumpToAndHighlightMessage(targetMsgId)
        }

        if (convId.isNotEmpty()) {
            val isChatLocked = com.example.gochat.core.security.ChatLockManager.isLocked(this, convId)
            val isSessionUnlocked = com.example.gochat.core.security.ChatLockManager.isSessionUnlocked(convId)
            if (isChatLocked && !isSessionUnlocked) {
                com.example.gochat.core.security.ChatLockManager.authenticate(
                    activity = this,
                    title = "Unlock $title",
                    subtitle = "Confirm authentication to access chat",
                    onSuccess = {
                        com.example.gochat.core.security.ChatLockManager.unlockForSession(convId)
                    },
                    onError = {
                        Toast.makeText(this, "Authentication required", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
            val isGroupChat = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
            if (!isGroupChat) {
                binding.tvBotBanner.visibility = View.GONE
            }
            viewModel.initConversation(convId, isGroupChat)
            if (!isGroupChat) {
                viewModel.resolvePartnerCountry()
                viewModel.checkSafetyNumberChange(convId, isGroupChat)
            }
        }

        setupLinkedOrderBanner()

        if (savedInstanceState == null) {
            val prodId = intent.getStringExtra(EXTRA_PRODUCT_ID)
            if (!prodId.isNullOrBlank()) {
                val prodName = intent.getStringExtra(EXTRA_PRODUCT_NAME).orEmpty()
                val prodPrice = intent.getDoubleExtra(EXTRA_PRODUCT_PRICE, 0.0)
                val prodImage = intent.getStringExtra(EXTRA_PRODUCT_IMAGE).orEmpty()
                val inquiry = intent.getStringExtra(EXTRA_INITIAL_MESSAGE).orEmpty()
                
                viewModel.sendProductMessage(prodId, prodName, prodPrice, prodImage, inquiry, convId)
            } else {
                val initialMessage = intent.getStringExtra(EXTRA_INITIAL_MESSAGE)
                if (!initialMessage.isNullOrBlank()) {
                    binding.etMessageInput.setText(initialMessage)
                    binding.etMessageInput.setSelection(initialMessage.length)
                } else {
                    // Restore saved draft for this conversation
                    lifecycleScope.launch {
                        val draft = viewModel.loadDraft(convId)
                        if (!draft.isNullOrBlank() && binding.etMessageInput.text.isNullOrBlank()) {
                            binding.etMessageInput.setText(draft)
                            binding.etMessageInput.setSelection(draft.length)
                        }
                    }
                }
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatRoot) { _, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

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
                    binding.rvMessages.scrollToPosition(0)
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
                    val systemBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                    )
                    val bottomInset = if (imeInsets.bottom > 0) imeInsets.bottom else systemBars.bottom

                    binding.chatContentContainer.updatePadding(bottom = bottomInset)
                    return insets
                }
            }
        )
        ViewCompat.requestApplyInsets(binding.chatRoot)
    }

    private fun setupToolbar(title: String, avatarUrl: String) {
        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        with(binding) {
            tvChatTitle.text = title

            val isStoreConv = com.example.gochat.core.marketplace.SellerVerificationHelper.isStoreConversation(convId)
            val verificationLevel = com.example.gochat.core.marketplace.SellerVerificationHelper.getVerificationLevel(
                conversationId = convId,
                title = title
            )
            if (isStoreConv || verificationLevel != com.example.gochat.core.marketplace.SellerVerificationLevel.NONE) {
                ivToolbarVerified.visibility = View.VISIBLE
                ivToolbarVerified.imageTintList = ColorStateList.valueOf(verificationLevel.badgeColorInt)
                ivToolbarVerified.setOnClickListener {
                    Toast.makeText(this@ChatRoomActivity, com.example.gochat.core.marketplace.SellerVerificationHelper.getTrustExplanation(verificationLevel, title), Toast.LENGTH_LONG).show()
                }
            } else {
                ivToolbarVerified.visibility = View.GONE
                // Asynchronously verify against business profile
                val partner = currentPartnerId?.ifBlank { null } ?: viewModel.partnerId
                if (!partner.isNullOrBlank()) {
                    lifecycleScope.launch {
                        val store = marketplaceRepository.getStoreForUser(partner, "")
                        if (store?.isVerified == true) {
                            ivToolbarVerified.visibility = View.VISIBLE
                            ivToolbarVerified.imageTintList = ColorStateList.valueOf(Color.parseColor("#00A884"))
                            ivToolbarVerified.setOnClickListener {
                                val explanation = com.example.gochat.core.marketplace.SellerVerificationHelper.getTrustExplanation(
                                    com.example.gochat.core.marketplace.SellerVerificationLevel.VERIFIED_MERCHANT,
                                    store.name.ifBlank { title }
                                )
                                Toast.makeText(this@ChatRoomActivity, explanation, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }

            MediaImageHelper.loadSafeImage(
                imageView = ivHeaderAvatar,
                url = avatarUrl,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )

            layoutHeaderInfo.setOnClickListener {
                val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
                if (isGroup) {
                    val intent = Intent(this@ChatRoomActivity, GroupInfoActivity::class.java).apply {
                        putExtra(GroupInfoActivity.EXTRA_CONVERSATION_ID, viewModel.conversationId.value)
                        putExtra(GroupInfoActivity.EXTRA_GROUP_NAME, title)
                        putExtra(GroupInfoActivity.EXTRA_GROUP_AVATAR, avatarUrl)
                        
                        val memberIds = viewModel.members.value.map { it.id }
                        if (memberIds.isNotEmpty()) {
                            putStringArrayListExtra(GroupInfoActivity.EXTRA_MEMBER_IDS, ArrayList(memberIds))
                        }
                    }
                    startActivity(intent)
                } else {
                    val resolvedPartnerId = currentPartnerId?.ifBlank { null }
                        ?: viewModel.partnerId?.ifBlank { null }
                        ?: ""
                    val intent = Intent(this@ChatRoomActivity, ContactProfileActivity::class.java).apply {
                        putExtra(ContactProfileActivity.EXTRA_CONVERSATION_ID, viewModel.conversationId.value)
                        putExtra(ContactProfileActivity.EXTRA_USER_NAME, title)
                        putExtra(ContactProfileActivity.EXTRA_USER_AVATAR, avatarUrl)
                        putExtra(ContactProfileActivity.EXTRA_IS_ONLINE, viewModel.isPartnerOnline.value)
                        putExtra(ContactProfileActivity.EXTRA_LAST_SEEN, viewModel.partnerLastSeen.value ?: 0L)
                        putExtra(ContactProfileActivity.EXTRA_TARGET_USER_ID, resolvedPartnerId)
                    }
                    contactProfileLauncher.launch(intent)
                }
            }

            btnBack.setOnClickListener { finish() }

            btnPing.setOnClickListener {
                viewModel.sendPing()
            }

            btnCall.setOnClickListener {
                lifecycleScope.launch {
                    var targetId = currentPartnerId?.ifBlank { null }
                    if (targetId.isNullOrBlank()) {
                        val conv = chatRepository.getConversationById(viewModel.conversationId.value)
                        val myId = tokenManager.userId.orEmpty()
                        targetId = conv?.memberIds?.find { it != myId }
                    }
                    if (targetId.isNullOrBlank()) {
                        Toast.makeText(this@ChatRoomActivity, "Cannot place call: recipient user not found", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val result = callRepository.startCall(targetId, "voice")
                    if (result.isFailure) {
                        val error = result.exceptionOrNull()?.message ?: "Unable to place call"
                        Toast.makeText(this@ChatRoomActivity, error, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val serverCallId = result.getOrNull()?.id
                    val effectiveCallId = if (!serverCallId.isNullOrBlank()) serverCallId else "call_${System.currentTimeMillis()}"
                    val intent = Intent(this@ChatRoomActivity, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_CALL_ID, effectiveCallId)
                        putExtra(CallActivity.EXTRA_TARGET_USER_ID, targetId)
                        putExtra(CallActivity.EXTRA_PEER_NAME, title)
                        putExtra(CallActivity.EXTRA_PEER_AVATAR, avatarUrl)
                        putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
                        putExtra(CallActivity.EXTRA_CALL_TYPE, "voice")
                    }
                    startActivity(intent)
                }
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
            onQuotedMessageClicked = { targetId ->
                jumpToAndHighlightMessage(targetId)
            }
            onEditHistoryClicked = { msg ->
                showEditHistoryDialog(msg)
            }
            onRetryClicked = { msg ->
                viewModel.retryFailedMessage(msg.id)
                Toast.makeText(this@ChatRoomActivity, "Retrying…", Toast.LENGTH_SHORT).show()
            }
            onMediaClick = { message ->
                message.mediaUrl?.let { url ->
                    showFullScreenImage(
                        mediaUrl = url,
                        isViewOnce = message.isViewOnce,
                        isVideo = message.type == com.example.gochat.data.model.MessageType.VIDEO
                    )
                    if (message.isViewOnce && !message.isMe && !message.isViewed) {
                        viewModel.markMessageAsViewed(message.id)
                    }
                }
            }
            onImageClicked = { imageUrl ->
                showFullScreenImage(imageUrl)
            }
            onAddToCartClicked = { productId, productJson ->
                showQuickShoppingSheet(productId, productJson)
            }
            onBuyNowClicked = { prodId, name, price, image ->
                showQuickShoppingSheet(prodId, "")
            }
            onPayInvoiceClicked = { message, invoiceData ->
                showEscrowCheckoutBottomSheet(message, invoiceData)
            }
            onMarkShippedClicked = { message, invoiceData ->
                AlertDialog.Builder(this@ChatRoomActivity)
                    .setTitle("Mark as Shipped")
                    .setMessage("Confirm that you have dispatched '${invoiceData.itemName}' to the buyer?")
                    .setPositiveButton("Confirm Shipped") { _, _ ->
                        viewModel.updatePaymentRequestStatus(
                            messageId = message.id,
                            currentContent = message.content,
                            newStatus = "shipped"
                        )
                        Toast.makeText(this@ChatRoomActivity, "🚚 Item marked as shipped", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            onConfirmReceiptClicked = { message, invoiceData ->
                AlertDialog.Builder(this@ChatRoomActivity)
                    .setTitle("Confirm Receipt & Release Funds")
                    .setMessage("Have you received '${invoiceData.itemName}' in satisfactory condition?\n\nThis will release ${CurrencyManager.formatPrice(invoiceData.amount)} from Escrow to the seller.")
                    .setPositiveButton("Release Funds") { _, _ ->
                        viewModel.updatePaymentRequestStatus(
                            messageId = message.id,
                            currentContent = message.content,
                            newStatus = "completed"
                        )
                        HapticEngine.playPaymentConfirmed(this@ChatRoomActivity)
                        Toast.makeText(this@ChatRoomActivity, "🎉 Order completed! Funds released to seller.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Not Yet", null)
                    .show()
            }
            onAcceptOfferClicked = { message, offer ->
                AlertDialog.Builder(this@ChatRoomActivity)
                    .setTitle("Accept Custom Offer")
                    .setMessage("Accept offer of ${CurrencyManager.formatPrice(offer.offeredPrice)} for '${offer.itemName}'?")
                    .setPositiveButton("Accept & Pay") { _, _ ->
                        viewModel.updateCustomOfferStatus(
                            messageId = message.id,
                            currentContent = message.content,
                            newStatus = "accepted"
                        )
                        val invoiceData = InvoiceData(
                            id = offer.id,
                            itemName = offer.itemName,
                            amount = offer.offeredPrice,
                            note = offer.note,
                            status = "pending"
                        )
                        showEscrowCheckoutBottomSheet(message, invoiceData)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            onCounterOfferClicked = { _, offer ->
                showCreateCustomOfferBottomSheet(
                    prefillItem = offer.itemName,
                    prefillOriginal = offer.offeredPrice,
                    prefillOffered = offer.offeredPrice * 0.9
                )
            }
            onVotePollClicked = { message, optionId ->
                viewModel.votePoll(message, optionId)
            }
            onViewPollResultsClicked = { pollData ->
                val currentUid = tokenManager.userId ?: ""
                val sheet = PollVotersBottomSheet(pollData, currentUid)
                sheet.show(supportFragmentManager, PollVotersBottomSheet.TAG)
            }
        }
        messageAdapter.currentUserId = tokenManager.userId ?: ""

        val layoutManager = LinearLayoutManager(this).apply {
            reverseLayout = true
        }

        val themeManager = ChatThemeManager(this)
        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val currentTheme = themeManager.getTheme(convId)
        messageAdapter.setBubbleTheme(currentTheme.bubbleShape, currentTheme.accentColor)

        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = messageAdapter
        // Smoothness: don't run a "change" animation every time a bubble rebinds for a
        // status update (SENDING→SENT→DELIVERED→READ) — that flicker reads as jank.
        (binding.rvMessages.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        binding.rvMessages.setItemViewCacheSize(20)

        // Floating FAB & Unread Messages Pill clicks
        binding.layoutScrollToBottomContainer.setOnClickListener {
            unreadNewMessagesCount = 0
            binding.rvMessages.smoothScrollToPosition(0)
            updateScrollToBottomVisibility()
        }
        binding.btnScrollToBottomFab.setOnClickListener {
            unreadNewMessagesCount = 0
            binding.rvMessages.smoothScrollToPosition(0)
            updateScrollToBottomVisibility()
        }

        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateScrollToBottomVisibility()
            }
        })

        // Auto-scroll to bottom on new messages if at bottom; otherwise increment unread badge!
        messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    if (!isScrolledUp) {
                        binding.rvMessages.scrollToPosition(0)
                    } else {
                        unreadNewMessagesCount += itemCount
                        updateScrollToBottomVisibility()
                    }
                }
            }
        })

        // Swipe to reply
        val swipeCallback = SwipeToReplyCallback(this) { position ->
            val message = messageAdapter.peek(position)
            if (message != null) {
                viewModel.setReplyingTo(message)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)

        // Mention suggestions
        mentionAdapter = GroupMemberAdapter(
            onItemClicked = { user ->
                val targetName = user.displayName.ifBlank { "User_${user.id.takeLast(4)}" }
                insertMention(targetName)
            }
        )
        binding.rvMentionSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvMentionSuggestions.adapter = mentionAdapter

        // Bot command suggestions
        commandAdapter = BotCommandAdapter { command ->
            binding.etMessageInput.setText(command.template)
            binding.etMessageInput.setSelection(command.template.length)
            viewModel.onInputTextChanged(command.template, command.template.length)
        }
        binding.rvCommandSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvCommandSuggestions.adapter = commandAdapter
    }

    private fun updateScrollToBottomVisibility() {
        val layoutManager = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        isScrolledUp = firstVisible > 1

        if (!isScrolledUp) {
            unreadNewMessagesCount = 0
            binding.layoutScrollToBottomContainer.visibility = View.GONE
            binding.layoutUnreadBadge.visibility = View.GONE
        } else {
            binding.layoutScrollToBottomContainer.visibility = View.VISIBLE
            if (unreadNewMessagesCount > 0) {
                binding.layoutUnreadBadge.visibility = View.VISIBLE
                val msgLabel = if (unreadNewMessagesCount == 1) "1 new message" else "$unreadNewMessagesCount new messages"
                binding.tvUnreadMessagesText.text = "↓ $msgLabel"
            } else {
                binding.layoutUnreadBadge.visibility = View.GONE
            }
        }
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
                btnToneAssistant.visibility = if (hasText) View.VISIBLE else View.GONE
                
                if (hasText) {
                    if (!isCurrentlyTypingSent) {
                        isCurrentlyTypingSent = true
                        viewModel.sendTypingEvent(true)
                    }
                    stopTypingHandler.removeCallbacks(stopTypingRunnable)
                    stopTypingHandler.postDelayed(stopTypingRunnable, 2500)
                } else {
                    if (isCurrentlyTypingSent) {
                        isCurrentlyTypingSent = false
                        stopTypingHandler.removeCallbacks(stopTypingRunnable)
                        viewModel.sendTypingEvent(false)
                    }
                }
                
                viewModel.onInputTextChanged(text?.toString().orEmpty(), etMessageInput.selectionStart)
            }

            btnToneAssistant.setOnClickListener {
                val currentText = etMessageInput.text?.toString().orEmpty().trim()
                if (currentText.isNotBlank()) {
                    val sheet = ToneAssistantBottomSheet(currentText) { polishedText ->
                        etMessageInput.setText(polishedText)
                        etMessageInput.setSelection(polishedText.length)
                    }
                    sheet.show(supportFragmentManager, ToneAssistantBottomSheet.TAG)
                }
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
                    if (isCurrentlyTypingSent) {
                        isCurrentlyTypingSent = false
                        stopTypingHandler.removeCallbacks(stopTypingRunnable)
                        viewModel.sendTypingEvent(false)
                    }
                    viewModel.sendTextMessage(text)
                    HapticEngine.playMessageSent(this@ChatRoomActivity)
                    etMessageInput.setText("")
                    // Clear saved draft after sending
                    val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                    if (convId.isNotEmpty()) viewModel.clearDraft(convId)
                } else {
                    checkAudioPermissionAndStartRecording()
                }
            }

            btnAttachment.setOnClickListener {
                showAttachmentPickerBottomSheet()
            }

            // Tap the camera = open the full camera. Press-and-hold = instant video note
            // (circular viewfinder; slide up to lock, release to send, slide left to cancel).
            setupVideoNoteTouch(btnCameraInput)
        }
    }

    private fun setupReplyPreview() {
        binding.btnCloseReplyPreview.setOnClickListener {
            viewModel.clearReply()
        }
        binding.layoutReplyPreview.setOnClickListener {
            viewModel.replyingTo.value?.id?.let { targetId ->
                jumpToAndHighlightMessage(targetId)
            }
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

        binding.btnPauseRecording.setOnClickListener {
            pauseVoiceRecording()
        }

        binding.btnResumeRecording.setOnClickListener {
            resumeVoiceRecording()
        }

        binding.btnSendVoiceRecording.setOnClickListener {
            stopAndSendVoiceRecording()
        }
    }

    private fun pauseVoiceRecording() {
        if (!audioRecorderManager.isRecording || audioRecorderManager.isPaused) return
        audioRecorderManager.pauseRecording()
        binding.btnPauseRecording.visibility = View.GONE
        binding.btnResumeRecording.visibility = View.VISIBLE
        recordingDotPulse?.pause()
        binding.viewRecordingDot.alpha = 0.4f
    }

    private fun resumeVoiceRecording() {
        if (!audioRecorderManager.isRecording || !audioRecorderManager.isPaused) return
        val resumed = audioRecorderManager.resumeRecording()
        if (resumed) {
            binding.btnResumeRecording.visibility = View.GONE
            binding.btnPauseRecording.visibility = View.VISIBLE
            recordingDotPulse?.resume()
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
            binding.btnCancelVoiceRecording.visibility = View.VISIBLE
            binding.btnPauseRecording.visibility = View.VISIBLE
            binding.btnResumeRecording.visibility = View.GONE
            binding.btnSendVoiceRecording.visibility = View.VISIBLE

            recordingDurationSeconds = 0
            binding.tvRecordingTimer.text = "00:00"
            recordingHandler.removeCallbacks(recordingTimerRunnable)
            recordingHandler.postDelayed(recordingTimerRunnable, 100)

            // Pulsing dot animation
            recordingDotPulse?.cancel()
            recordingDotPulse = ObjectAnimator.ofFloat(binding.viewRecordingDot, "alpha", 1f, 0.2f, 1f).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            Toast.makeText(this, getString(R.string.error_audio_recorder_start), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndSendVoiceRecording() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        recordingDotPulse?.cancel()
        recordingDotPulse = null
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
            HapticEngine.playMessageSent(this)
        } else {
            Toast.makeText(this, getString(R.string.error_recording_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelVoiceRecording() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        recordingDotPulse?.cancel()
        recordingDotPulse = null
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

        // Hold the camera to record a video (tap = photo), like WhatsApp.
        sheetBinding.btnPickCamera.setOnLongClickListener {
            sheet.dismiss()
            Toast.makeText(this, "Recording video…", Toast.LENGTH_SHORT).show()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchVideoCapture()
            } else {
                requestCameraForVideoPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            true
        }

        sheetBinding.btnPickGallery.setOnClickListener {
            sheet.dismiss()
            // Photos AND videos, like WhatsApp's gallery.
            pickMediaLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                )
            )
        }

        sheetBinding.btnPickDocument.setOnClickListener {
            sheet.dismiss()
            pickDocumentLauncher.launch(arrayOf("*/*"))
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

        sheetBinding.btnPickProduct.setOnClickListener {
            sheet.dismiss()
            showProductPicker()
        }

        // Catalog sharing (Lightweight Catalog V2)
        sheetBinding.btnPickCatalog.visibility = View.VISIBLE
        sheetBinding.btnPickCatalog.setOnClickListener {
            sheet.dismiss()
            showCatalogPicker()
        }

        sheetBinding.btnPickPayment.setOnClickListener {
            sheet.dismiss()
            showCreateInvoiceBottomSheet()
        }

        sheetBinding.btnPickQuickReply.setOnClickListener {
            sheet.dismiss()
            showQuickRepliesBottomSheet()
        }

        sheetBinding.btnPickPoll.setOnClickListener {
            sheet.dismiss()
            showCreatePollBottomSheet()
        }

        sheet.show()
    }

    private fun showCatalogPicker() {
        val picker = CatalogPickerBottomSheet { selectedProducts ->
            if (selectedProducts.isEmpty()) return@CatalogPickerBottomSheet
            viewModel.sendCatalogMessage(selectedProducts)
        }
        picker.show(supportFragmentManager, "CatalogPickerBottomSheet")
    }

    private fun showCreatePollBottomSheet() {
        val currentUid = tokenManager.userId ?: ""
        val sheet = CreatePollBottomSheet(currentUid) { pollData ->
            viewModel.sendPollMessage(pollData)
        }
        sheet.show(supportFragmentManager, CreatePollBottomSheet.TAG)
    }

    private fun showQuickRepliesBottomSheet() {
        val sheet = QuickRepliesBottomSheet(marketplaceRepository) { replyText ->
            binding.etMessageInput.setText(replyText)
            binding.etMessageInput.setSelection(replyText.length)
            binding.etMessageInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        sheet.show(supportFragmentManager, QuickRepliesBottomSheet.TAG)
    }

    private fun showCreateInvoiceBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val invoiceBinding = com.example.gochat.databinding.BottomSheetCreateInvoiceBinding.inflate(layoutInflater)
        invoiceBinding.tvInvoiceCurrencySymbol.text = CurrencyManager.getUserCurrencySymbol()
        sheet.setContentView(invoiceBinding.root)

        invoiceBinding.btnCloseInvoiceSheet.setOnClickListener {
            sheet.dismiss()
        }

        invoiceBinding.btnSendInvoice.setOnClickListener {
            val itemName = invoiceBinding.etInvoiceItemName.text.toString().trim()
            val amountStr = invoiceBinding.etInvoiceAmount.text.toString().trim()
            val note = invoiceBinding.etInvoiceNote.text.toString().trim()

            if (itemName.isBlank()) {
                Toast.makeText(this, "Please enter an item or service name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amount = amountStr.toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.sendPaymentRequestMessage(
                itemName = itemName,
                amount = amount,
                note = note
            )
            HapticEngine.playMessageSent(this)
            sheet.dismiss()
            Toast.makeText(this, "💳 Payment request sent", Toast.LENGTH_SHORT).show()
        }

        sheet.show()
    }

    private fun showCreateCustomOfferBottomSheet(
        prefillItem: String = "",
        prefillOriginal: Double = 0.0,
        prefillOffered: Double = 0.0
    ) {
        val sheet = BottomSheetDialog(this)
        val offerBinding = BottomSheetCustomOfferBinding.inflate(layoutInflater)
        sheet.setContentView(offerBinding.root)

        if (prefillItem.isNotBlank()) {
            offerBinding.etOfferItemName.setText(prefillItem)
        }
        if (prefillOriginal > 0) {
            offerBinding.etOfferOriginalPrice.setText(prefillOriginal.toString())
        }
        if (prefillOffered > 0) {
            offerBinding.etOfferPrice.setText(prefillOffered.toString())
        }

        offerBinding.btnCloseOfferSheet.setOnClickListener {
            sheet.dismiss()
        }

        offerBinding.btnSendOffer.setOnClickListener {
            val itemName = offerBinding.etOfferItemName.text.toString().trim()
            val origStr = offerBinding.etOfferOriginalPrice.text.toString().trim()
            val offStr = offerBinding.etOfferPrice.text.toString().trim()
            val note = offerBinding.etOfferNote.text.toString().trim()

            if (itemName.isBlank()) {
                Toast.makeText(this, "Please enter an item name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val origPrice = origStr.toDoubleOrNull() ?: 0.0
            val offPrice = offStr.toDoubleOrNull()

            if (offPrice == null || offPrice <= 0.0) {
                Toast.makeText(this, "Please enter a valid offered price", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.sendCustomOfferMessage(
                itemName = itemName,
                originalPrice = origPrice,
                offeredPrice = offPrice,
                note = note
            )
            HapticEngine.playMessageSent(this)
            sheet.dismiss()
            Toast.makeText(this, "🔥 Custom offer sent", Toast.LENGTH_SHORT).show()
        }

        sheet.show()
    }

    private fun showEscrowCheckoutBottomSheet(message: Message, invoiceData: com.example.gochat.data.model.InvoiceData) {
        val sheet = BottomSheetDialog(this)
        val checkoutBinding = com.example.gochat.databinding.BottomSheetEscrowCheckoutBinding.inflate(layoutInflater)
        sheet.setContentView(checkoutBinding.root)

        checkoutBinding.tvCheckoutItemName.text = invoiceData.itemName
        checkoutBinding.tvCheckoutItemNote.text = if (invoiceData.note.isNotBlank()) invoiceData.note else "Standard GoChat escrow protection applies"
        checkoutBinding.tvCheckoutTotalAmount.text = CurrencyManager.formatPrice(invoiceData.amount)

        checkoutBinding.btnCloseCheckoutSheet.setOnClickListener {
            sheet.dismiss()
        }

        // Toggle payment methods
        checkoutBinding.layoutMethodWallet.setOnClickListener {
            checkoutBinding.rbWallet.isChecked = true
            checkoutBinding.rbCard.isChecked = false
        }
        checkoutBinding.layoutMethodCard.setOnClickListener {
            checkoutBinding.rbWallet.isChecked = false
            checkoutBinding.rbCard.isChecked = true
        }

        checkoutBinding.btnAuthorizeEscrowPay.setOnClickListener {
            sheet.dismiss()
            viewModel.updatePaymentRequestStatus(
                messageId = message.id,
                currentContent = message.content,
                newStatus = "paid_escrow"
            )
            HapticEngine.playPaymentConfirmed(this@ChatRoomActivity)
            Toast.makeText(this, "✅ ${CurrencyManager.formatPrice(invoiceData.amount)} paid into Escrow", Toast.LENGTH_LONG).show()
        }

        sheet.show()
    }

    private fun showProductPicker() {
        val picker = ProductPickerBottomSheet { product ->
            viewModel.sendProductMessage(
                productId = product.id,
                name = product.displayTitle,
                price = product.price,
                image = product.primaryImage,
                inquiry = ""
            )
        }
        picker.show(supportFragmentManager, "ProductPicker")
    }

    private fun showQuickShoppingSheet(productId: String, productJson: String) {
        lifecycleScope.launch {
            val fetchedProduct = marketplaceRepository.getProductById(productId).getOrNull()
            val product = fetchedProduct ?: run {
                try {
                    val root = Json.decodeFromString<JsonObject>(productJson)
                    val prodData = root["product"]?.jsonObject ?: root
                    val prodName = prodData["name"]?.jsonPrimitive?.contentOrNull
                        ?: prodData["title"]?.jsonPrimitive?.contentOrNull ?: "Product"
                    val prodPrice = prodData["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val prodImage = prodData["image"]?.jsonPrimitive?.contentOrNull
                        ?: prodData["imageUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    Product(
                        id = productId,
                        sellerId = "",
                        name = prodName,
                        description = "",
                        price = prodPrice,
                        imageUrl = prodImage,
                        imageUrls = if (prodImage.isNotEmpty()) listOf(prodImage) else emptyList(),
                        storeId = "",
                        storeName = "Store",
                        stock = 10
                    )
                } catch (_: Exception) {
                    null
                }
            }

            if (product == null) {
                Toast.makeText(this@ChatRoomActivity, "Unable to load product details", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val sheet = QuickAddBottomSheet(
                product = product,
                onAddToCart = { prod, qty, _ ->
                    lifecycleScope.launch {
                        val res = marketplaceRepository.addToCart(prod.id, qty, prod)
                        if (res.isSuccess) {
                            HapticEngine.playMessageSent(this@ChatRoomActivity)
                            Snackbar.make(binding.root, "🛒 Added ${prod.displayTitle} x$qty to Cart!", Snackbar.LENGTH_LONG)
                                .setAction("View Cart") {
                                    startActivity(Intent(this@ChatRoomActivity, CartActivity::class.java))
                                }
                                .show()
                        } else {
                            Toast.makeText(this@ChatRoomActivity, "Failed to add to cart", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDirectCheckout = { prod, _, _ ->
                    val intent = Intent(this@ChatRoomActivity, ProductDetailsActivity::class.java).apply {
                        putExtra("product_id", prod.id)
                        putExtra("product_name", prod.displayTitle)
                        putExtra("product_price", prod.price)
                        putExtra("product_image", prod.primaryImage)
                        putExtra("auto_buy", true)
                    }
                    startActivity(intent)
                }
            )
            sheet.show(supportFragmentManager, "QuickAddBottomSheet")
        }
    }

    private fun setupLinkedOrderBanner() {
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID)
        val orderNumber = intent.getStringExtra(EXTRA_ORDER_NUMBER)
        val orderTotal = intent.getDoubleExtra(EXTRA_ORDER_TOTAL, 0.0)

        if (!orderId.isNullOrBlank()) {
            binding.layoutLinkedOrder.visibility = View.VISIBLE
            val displayNum = if (!orderNumber.isNullOrBlank()) orderNumber else "ORD-${orderId.takeLast(6)}"
            val totalStr = if (orderTotal > 0) String.format(Locale.US, " • $%.2f", orderTotal) else ""
            binding.tvLinkedOrderText.text = "Linked Order: #$displayNum$totalStr"

            binding.btnViewLinkedOrder.setOnClickListener {
                val intent = Intent(this, OrdersActivity::class.java).apply {
                    putExtra("highlight_order_id", orderId)
                }
                startActivity(intent)
            }

            binding.btnCloseLinkedOrder.setOnClickListener {
                binding.layoutLinkedOrder.visibility = View.GONE
            }
        } else {
            binding.layoutLinkedOrder.visibility = View.GONE
        }
    }

    private fun startLocationPicker() {
        locationPickerLauncher.launch(Intent(this, LocationPickerActivity::class.java))
    }

    private fun showGifPicker() {
        val gifPicker = GifPickerBottomSheet { url ->
            viewModel.sendMediaMessage(url, 1, "GIF") // Using type IMAGE for GIFs
        }
        gifPicker.show(supportFragmentManager, "GifPicker")
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

    private fun launchVideoCapture() {
        try {
            val videoDir = File(cacheDir, "videos").apply { if (!exists()) mkdirs() }
            val videoFile = File(videoDir, "camera_${System.currentTimeMillis()}.mp4")
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                videoFile
            )
            cameraTempVideoUri = uri
            captureVideoLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_camera_init), Toast.LENGTH_SHORT).show()
        }
    }

    /** Routes a picked gallery item to the image or video send path based on its MIME type. */
    private fun routePickedMedia(uri: Uri) {
        val mime = contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("video")) {
            sendVideoCompressed(uri)
        } else {
            showImagePreviewDialog(uri)
        }
    }

    /**
     * Compresses a video down to the ~15 MB send limit (so it can be served back from the
     * Telegram-backed CDN) and then sends it. Shows a progress dialog while transcoding.
     * Falls back to sending the original only if it is already under the limit.
     */
    private fun sendVideoCompressed(uri: Uri) {
        val progress = buildVideoProgressDialog()
        progress.show()
        lifecycleScope.launch {
            val result = com.example.gochat.core.media.VideoCompressor.compress(this@ChatRoomActivity, uri)
            progress.dismiss()
            when (result) {
                is com.example.gochat.core.media.VideoCompressor.Result.Success -> {
                    viewModel.sendMediaMessage(Uri.fromFile(result.file).toString(), 2, "🎥 Video")
                    HapticEngine.playMessageSent(this@ChatRoomActivity)
                }
                is com.example.gochat.core.media.VideoCompressor.Result.TooLarge -> {
                    Toast.makeText(
                        this@ChatRoomActivity,
                        "Video is too long to send — keep it under 15 MB.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is com.example.gochat.core.media.VideoCompressor.Result.Failed -> {
                    // Transcode failed; send the original only if it already fits.
                    val size = originalSize(uri)
                    if (size in 1..com.example.gochat.core.media.VideoCompressor.MAX_BYTES) {
                        viewModel.sendMediaMessage(uri.toString(), 2, "🎥 Video")
                        HapticEngine.playMessageSent(this@ChatRoomActivity)
                    } else {
                        Toast.makeText(
                            this@ChatRoomActivity,
                            "Couldn't prepare this video for sending.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun buildVideoProgressDialog(): AlertDialog {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ProgressBar(this@ChatRoomActivity))
            addView(android.widget.TextView(this@ChatRoomActivity).apply {
                text = getString(R.string.compressing_video)
                setPadding(padding, 0, 0, 0)
            })
        }
        return AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()
    }

    private fun originalSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun sendPickedDocument(uri: Uri) {
        val name = queryDisplayName(uri) ?: "Document"
        viewModel.sendDocumentMessage(uri.toString(), name)
        HapticEngine.playMessageSent(this)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Video note: hold the camera icon to record instantly
    // ══════════════════════════════════════════════════════════════

    private fun hasVideoNotePermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return cam && mic
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupVideoNoteTouch(view: View) {
        val lockDistancePx = 120f * resources.displayMetrics.density
        val cancelDistancePx = 120f * resources.displayMetrics.density

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    videoNoteDownX = event.rawX
                    videoNoteDownY = event.rawY
                    if (!hasVideoNotePermissions()) {
                        // Can't start recording mid-gesture; ask once, then user holds again.
                        requestVideoNotePermissions.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                        return@setOnTouchListener true
                    }
                    v.isPressed = true
                    // Hold past the threshold → start the video note. A quick release = open full camera.
                    videoNoteHandler.postDelayed(videoNoteStartRunnable, 250L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isVideoNoteRecording && !isVideoNoteLocked) {
                        val dy = videoNoteDownY - event.rawY  // up is positive
                        val dx = videoNoteDownX - event.rawX  // left is positive
                        if (dy > lockDistancePx) {
                            lockVideoNote()
                        } else if (dx > cancelDistancePx) {
                            binding.tvVideoNoteCancelHint.text = "◀  Release to cancel"
                            binding.tvVideoNoteCancelHint.setTextColor(0xFFFF5252.toInt())
                        } else {
                            binding.tvVideoNoteCancelHint.text = "◀  Slide to cancel"
                            binding.tvVideoNoteCancelHint.setTextColor(0xDDFFFFFF.toInt())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    videoNoteHandler.removeCallbacks(videoNoteStartRunnable)
                    if (isVideoNoteRecording) {
                        if (isVideoNoteLocked) {
                            // Stay recording; user ends with the on-screen buttons.
                        } else {
                            val dx = videoNoteDownX - event.rawX
                            if (dx > cancelDistancePx) cancelVideoNote() else stopVideoNoteAndSend()
                        }
                    } else {
                        // Quick tap → open the full camera screen.
                        inAppCameraLauncher.launch(Intent(this@ChatRoomActivity, CameraCaptureActivity::class.java))
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startVideoNote() {
        if (isVideoNoteRecording) return
        isVideoNoteCancelled = false
        isVideoNoteLocked = false
        binding.progressVideoNote.progress = 0
        binding.layoutVideoNoteOverlay.visibility = View.VISIBLE
        binding.layoutVideoNoteLocked.visibility = View.GONE
        binding.layoutVideoNoteLockHint.visibility = View.VISIBLE
        binding.tvVideoNoteCancelHint.visibility = View.VISIBLE
        binding.tvVideoNoteCancelHint.text = "◀  Slide to cancel"
        binding.tvVideoNoteCancelHint.setTextColor(0xDDFFFFFF.toInt())
        HapticEngine.playMessageSent(this)

        binding.btnCancelVideoNote.setOnClickListener { cancelVideoNote() }
        binding.btnSendVideoNote.setOnClickListener { stopVideoNoteAndSend() }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                videoNoteProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.videoNotePreview.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoNoteCapture = capture
                // Front camera, like a WhatsApp video note.
                val selector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(this, selector, preview, capture)
                } catch (e: Exception) {
                    // Fallback to back camera if no front camera.
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
                beginVideoNoteRecording(capture)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to start camera", Toast.LENGTH_SHORT).show()
                hideVideoNoteOverlay()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun beginVideoNoteRecording(capture: VideoCapture<Recorder>) {
        val dir = File(cacheDir, "videos").apply { if (!exists()) mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.mp4")
        videoNoteFile = file
        val outputOptions = FileOutputOptions.Builder(file).build()
        videoNoteRecording = capture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isVideoNoteRecording = true
                        videoNoteStartMs = System.currentTimeMillis()
                        videoNoteHandler.post(videoNoteTimerRunnable)
                    }
                    is VideoRecordEvent.Status -> {
                        // Drive the ring from bytes recorded and auto-send at the 15 MB cap so the
                        // note stays small enough to be served back from the Telegram-backed CDN.
                        val bytes = event.recordingStats.numBytesRecorded
                        val pct = ((bytes.toDouble() / VIDEO_NOTE_MAX_BYTES) * 100).toInt().coerceIn(0, 100)
                        binding.progressVideoNote.progress = pct
                        if (bytes >= VIDEO_NOTE_MAX_BYTES && videoNoteRecording != null) {
                            Toast.makeText(this, "Reached 15 MB — sending", Toast.LENGTH_SHORT).show()
                            stopVideoNoteAndSend()
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        isVideoNoteRecording = false
                        videoNoteHandler.removeCallbacks(videoNoteTimerRunnable)
                        videoNoteProvider?.unbindAll()
                        hideVideoNoteOverlay()
                        val f = videoNoteFile
                        if (isVideoNoteCancelled || event.hasError() || f == null || !f.exists() || f.length() == 0L) {
                            f?.delete()
                        } else {
                            // Marker tells the adapter to render this as a round video note.
                            viewModel.sendMediaMessage(Uri.fromFile(f).toString(), 2, com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER)
                        }
                    }
                    else -> {}
                }
            }
    }

    private fun lockVideoNote() {
        if (isVideoNoteLocked) return
        isVideoNoteLocked = true
        binding.layoutVideoNoteLockHint.visibility = View.GONE
        binding.tvVideoNoteCancelHint.visibility = View.GONE
        binding.layoutVideoNoteLocked.visibility = View.VISIBLE
        Toast.makeText(this, "Recording locked — hands-free", Toast.LENGTH_SHORT).show()
    }

    private fun stopVideoNoteAndSend() {
        if (!isVideoNoteRecording) return
        isVideoNoteCancelled = false
        videoNoteRecording?.stop()
        videoNoteRecording = null
    }

    private fun cancelVideoNote() {
        isVideoNoteCancelled = true
        videoNoteRecording?.stop()
        videoNoteRecording = null
        if (!isVideoNoteRecording) hideVideoNoteOverlay()
    }

    private fun hideVideoNoteOverlay() {
        binding.layoutVideoNoteOverlay.visibility = View.GONE
        binding.progressVideoNote.progress = 0
        isVideoNoteLocked = false
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

        var isViewOnce = false
        dialogBinding.btnViewOnceToggle.setOnClickListener {
            isViewOnce = !isViewOnce
            if (isViewOnce) {
                dialogBinding.btnViewOnceToggle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.gochat_emerald))
                Toast.makeText(this, "Photo set to view once", Toast.LENGTH_SHORT).show()
            } else {
                dialogBinding.btnViewOnceToggle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.gochat_text_secondary))
                Toast.makeText(this, "View once disabled", Toast.LENGTH_SHORT).show()
            }
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
                        caption = caption.ifBlank { if (isViewOnce) "① Photo" else getString(R.string.caption_photo) },
                        isViewOnce = isViewOnce
                    )
                    HapticEngine.playMessageSent(this@ChatRoomActivity)
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

    private fun showFullScreenImage(mediaUrl: String, isViewOnce: Boolean = false, isVideo: Boolean = false) {
        val intent = Intent(this, MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_MEDIA_URL, mediaUrl)
            putExtra(MediaViewerActivity.EXTRA_TITLE, binding.tvChatTitle.text.toString())
            putExtra(MediaViewerActivity.EXTRA_IS_VIEW_ONCE, isViewOnce)
            putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, isVideo)
        }
        startActivity(intent)
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
                HapticEngine.playMessageSent(this@ChatRoomActivity)
            } else {
                Toast.makeText(this@ChatRoomActivity, getString(R.string.error_audio_process), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Shows a trust-signal banner when a contact's E2EE safety number has changed.
     * Offers to open the verification screen. Non-blocking (a long Snackbar).
     */
    private fun showSafetyNumberChangedBanner() {
        val name = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: getString(R.string.safety_number_contact_fallback)
        Snackbar.make(binding.root, getString(R.string.safety_number_changed, name), Snackbar.LENGTH_INDEFINITE)
            .setAction(getString(R.string.safety_number_verify)) {
                val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                val peerId = intent.getStringExtra(EXTRA_PARTNER_ID).orEmpty()
                val intent = Intent(this, com.example.gochat.ui.security.SecurityVerificationActivity::class.java).apply {
                    putExtra(com.example.gochat.ui.security.SecurityVerificationActivity.EXTRA_CONVERSATION_ID, convId)
                    putExtra(com.example.gochat.ui.security.SecurityVerificationActivity.EXTRA_PEER_USER_ID, peerId)
                    putExtra(com.example.gochat.ui.security.SecurityVerificationActivity.EXTRA_PEER_PIN, viewModel.partnerPin)
                    putExtra(com.example.gochat.ui.security.SecurityVerificationActivity.EXTRA_PEER_NAME, name)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val isGroupIntent = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
                    combine(viewModel.botConfig, viewModel.isGroupAdmin, viewModel.isGroup) { config, isAdmin, isGroupVm ->
                        val isGroup = isGroupIntent || isGroupVm
                        isGroup && config?.isActive == true && isAdmin
                    }.collect { showBanner ->
                        binding.tvBotBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.messagesPaged.collectLatest { pagingData ->
                        messageAdapter.submitData(pagingData)
                    }
                }

                launch {
                    viewModel.safetyNumberChanged.collect { changed ->
                        if (changed) {
                            showSafetyNumberChangedBanner()
                            viewModel.acknowledgeSafetyNumberChange()
                        }
                    }
                }

                launch {
                    combine(viewModel.searchResults, viewModel.currentSearchIndex) { results, index ->
                        Pair(results, index)
                    }.collect { (results, index) ->
                        if (results.isEmpty()) {
                            binding.tvSearchMatchCount.text = if (viewModel.searchQuery.value.isBlank()) "" else "0 of 0"
                        } else {
                            val currentNum = (index + 1).coerceAtLeast(1)
                            binding.tvSearchMatchCount.text = "$currentNum of ${results.size}"
                            val targetMsg = results.getOrNull(index)
                            if (targetMsg != null) {
                                val pos = (0 until messageAdapter.itemCount).indexOfFirst {
                                    messageAdapter.peek(it)?.id == targetMsg.id
                                }
                                if (pos != -1) {
                                    binding.rvMessages.scrollToPosition(pos)
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.replyingTo.collect { replyMsg ->
                        if (replyMsg != null) {
                            binding.layoutReplyPreview.visibility = View.VISIBLE
                            binding.layoutEditPreview.visibility = View.GONE

                            val senderName = if (replyMsg.isMe) {
                                getString(R.string.replying_to_yourself)
                            } else {
                                getString(R.string.replying_to_format, replyMsg.senderName.ifBlank { getString(R.string.message_hint) })
                            }
                            binding.tvReplyPreviewSender.text = senderName

                            val previewText = when (replyMsg.type) {
                                MessageType.IMAGE -> if (replyMsg.content.isNotBlank() && replyMsg.content != "Photo" && replyMsg.content != "① Photo") "📷 ${replyMsg.content.take(150)}" else getString(R.string.reply_photo)
                                MessageType.VIDEO -> if (replyMsg.content.isNotBlank() && replyMsg.content != "Video") "🎥 ${replyMsg.content.take(150)}" else getString(R.string.reply_video)
                                MessageType.VOICE, MessageType.AUDIO -> getString(R.string.reply_voice_note)
                                MessageType.FILE -> if (replyMsg.content.isNotBlank()) "📄 ${replyMsg.content.take(150)}" else getString(R.string.reply_document)
                                MessageType.LOCATION -> "📍 Location"
                                MessageType.CONTACT -> "👤 Contact"
                                MessageType.PRODUCT -> if (replyMsg.content.isNotBlank()) "🛒 ${replyMsg.content.take(100)}" else "🛒 Product"
                                MessageType.POLL -> "📊 Poll: ${replyMsg.content.take(100)}"
                                else -> replyMsg.content.ifBlank { "Message" }
                            }
                            binding.tvReplyPreviewText.text = previewText

                            val mediaUrl = replyMsg.mediaThumbnail ?: replyMsg.mediaUrl
                            val isVisual = replyMsg.type == MessageType.IMAGE ||
                                    replyMsg.type == MessageType.VIDEO ||
                                    replyMsg.type == MessageType.PRODUCT

                            if (isVisual && !mediaUrl.isNullOrBlank()) {
                                binding.ivReplyPreviewThumbnail.visibility = View.VISIBLE
                                binding.ivReplyPreviewThumbnail.imageTintList = null
                                if (replyMsg.type == MessageType.VIDEO) {
                                    // Video/video note: extract a real first frame.
                                    MediaImageHelper.loadVideoThumbnail(
                                        imageView = binding.ivReplyPreviewThumbnail,
                                        url = mediaUrl,
                                        placeholderRes = R.drawable.ic_gallery
                                    )
                                } else {
                                    MediaImageHelper.loadSafeImage(
                                        imageView = binding.ivReplyPreviewThumbnail,
                                        url = mediaUrl,
                                        cornerRadiusDp = 6f,
                                        placeholderRes = R.drawable.ic_gallery,
                                        errorRes = R.drawable.ic_gallery
                                    )
                                }
                            } else if (replyMsg.type == MessageType.VOICE || replyMsg.type == MessageType.AUDIO) {
                                binding.ivReplyPreviewThumbnail.visibility = View.VISIBLE
                                binding.ivReplyPreviewThumbnail.setImageResource(R.drawable.ic_mic)
                                binding.ivReplyPreviewThumbnail.imageTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(this@ChatRoomActivity, R.color.gochat_emerald_light)
                                )
                            } else if (replyMsg.type == MessageType.FILE) {
                                binding.ivReplyPreviewThumbnail.visibility = View.VISIBLE
                                binding.ivReplyPreviewThumbnail.setImageResource(R.drawable.ic_document)
                                binding.ivReplyPreviewThumbnail.imageTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(this@ChatRoomActivity, R.color.gochat_text_secondary)
                                )
                            } else {
                                binding.ivReplyPreviewThumbnail.visibility = View.GONE
                            }

                            // Highlight the replied-to message in the chat
                            messageAdapter.highlightMessage(replyMsg.id)

                            // Focus input and open soft keyboard
                            binding.etMessageInput.requestFocus()
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                            imm?.showSoftInput(binding.etMessageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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
                    combine(
                        viewModel.isOtherUserTyping,
                        viewModel.isPartnerOnline,
                        viewModel.partnerLastSeen,
                        viewModel.isDeviceOnline
                    ) { isTyping, isOnline, lastSeen, isDeviceOnline ->
                        renderPresence(isTyping, isOnline, lastSeen, isDeviceOnline)
                    }.collect {}
                }

                launch {
                    viewModel.typingParticipant.collect { participant ->
                        renderTypingBubble(participant)
                    }
                }

                launch {
                    viewModel.partnerCountry.collect { country ->
                        if (country != null) {
                            binding.tvCountryBanner.text = "${country.flag}  This person may be messaging from ${country.name}"
                            binding.tvCountryBanner.visibility = View.VISIBLE
                        } else {
                            binding.tvCountryBanner.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.screenShakeEvent.collect {
                        triggerScreenShake()
                    }
                }

                launch {
                    viewModel.toastEvent.collect { message ->
                        Toast.makeText(this@ChatRoomActivity, message, Toast.LENGTH_LONG).show()
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

                launch {
                    viewModel.commandSuggestions.collect { suggestions ->
                        if (suggestions.isNotEmpty()) {
                            binding.rvCommandSuggestions.visibility = View.VISIBLE
                            commandAdapter.submitList(suggestions)
                        } else {
                            binding.rvCommandSuggestions.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.disappearingDuration.collect { duration ->
                        binding.ivHeaderDisappearingBadge.visibility = if (duration > 0) View.VISIBLE else View.GONE
                        binding.ivHeaderDisappearingBadge.setOnClickListener {
                            showDisappearingMessagesDialog()
                        }
                    }
                }

                launch {
                    viewModel.screenshotNotificationsEnabled.collect { enabled ->
                        binding.ivHeaderScreenshotBadge.visibility = if (enabled) View.VISIBLE else View.GONE
                        binding.ivHeaderScreenshotBadge.setOnClickListener {
                            Toast.makeText(this@ChatRoomActivity, "📸 Screenshot alerts are active in this chat", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                launch {
                    viewModel.pinnedMessages.collect { pinnedList ->
                        updatePinnedMessageHeader(pinnedList)
                    }
                }

                // WhatsApp Behavior: Live observe if 1-on-1 contact is blocked
                val isGroupChat = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
                val roomConvId = viewModel.conversationId.value.ifBlank { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
                val contactTitle = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: getString(R.string.message_hint)

                if (!isGroupChat) {
                    launch {
                        var targetId = currentPartnerId?.ifBlank { null } ?: viewModel.partnerId?.ifBlank { null }
                        if (targetId.isNullOrBlank() && roomConvId.isNotEmpty()) {
                            val conv = chatRepository.getConversationById(roomConvId)
                            val myId = tokenManager.userId.orEmpty()
                            targetId = conv?.memberIds?.find { it.isNotBlank() && it != myId }
                            if (!targetId.isNullOrBlank()) {
                                currentPartnerId = targetId
                            }
                        }
                        val finalPartnerId = targetId.orEmpty().ifBlank { roomConvId }
                        if (finalPartnerId.isNotEmpty()) {
                            chatRepository.observeIsUserBlocked(finalPartnerId).collect { isBlocked ->
                                isPartnerBlocked = isBlocked
                                if (isBlocked) {
                                    binding.layoutBlockedBanner.visibility = View.VISIBLE
                                    binding.layoutNormalInput.visibility = View.GONE
                                    binding.layoutVoiceRecording.visibility = View.GONE
                                    binding.tvBlockedBannerMessage.text = getString(R.string.blocked_banner_text)
                                    binding.layoutBlockedBanner.setOnClickListener {
                                        BlockContactHelper.showUnblockConfirmationDialog(
                                            context = this@ChatRoomActivity,
                                            coroutineScope = lifecycleScope,
                                            chatRepository = chatRepository,
                                            userId = finalPartnerId,
                                            userName = contactTitle
                                        )
                                    }
                                } else {
                                    binding.layoutBlockedBanner.visibility = View.GONE
                                    binding.layoutNormalInput.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private var currentPinnedIndex: Int = 0

    private fun updatePinnedMessageHeader(pinnedList: List<Message>) {
        if (pinnedList.isEmpty()) {
            currentPinnedIndex = 0
            if (binding.layoutPinnedMessage.visibility == View.VISIBLE) {
                TransitionManager.beginDelayedTransition(binding.chatContentContainer, AutoTransition().apply { duration = 180 })
                binding.layoutPinnedMessage.visibility = View.GONE
            }
            return
        }

        if (currentPinnedIndex !in pinnedList.indices) {
            currentPinnedIndex = 0
        }
        val pinnedMsg = pinnedList[currentPinnedIndex]

        if (binding.layoutPinnedMessage.visibility != View.VISIBLE) {
            TransitionManager.beginDelayedTransition(binding.chatContentContainer, AutoTransition().apply { duration = 180 })
            binding.layoutPinnedMessage.visibility = View.VISIBLE
        }

        val senderLabel = if (pinnedMsg.isMe) "You" else pinnedMsg.senderName.ifBlank { "Contact" }
        binding.tvPinnedTitle.text = "📌 Pinned • $senderLabel (${currentPinnedIndex + 1} of ${pinnedList.size})"

        val snippet = when (pinnedMsg.type) {
            MessageType.TEXT -> pinnedMsg.content
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO, MessageType.VOICE -> "🎵 Audio message"
            MessageType.FILE -> "📄 Document"
            MessageType.LOCATION -> "📍 Location"
            MessageType.CONTACT -> "👤 Contact card"
            MessageType.POLL -> "📊 Poll: ${pinnedMsg.content}"
            MessageType.PRODUCT -> "🛍️ Product inquiry"
            MessageType.ORDER -> "📦 Order update"
            MessageType.PAYMENT_REQUEST -> "💳 Payment Request"
            else -> pinnedMsg.content
        }
        binding.tvPinnedSnippet.text = snippet

        binding.btnUnpinMessage.setOnClickListener {
            val msgToUnpin = pinnedMsg
            viewModel.togglePin(msgToUnpin.id, false)
            Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show()
            if (currentPinnedIndex >= pinnedList.size - 1) {
                currentPinnedIndex = (pinnedList.size - 2).coerceAtLeast(0)
            }
        }

        binding.layoutPinnedMessage.setOnClickListener {
            val pos = (0 until messageAdapter.itemCount).indexOfFirst {
                messageAdapter.peek(it)?.id == pinnedMsg.id
            }
            if (pos != -1) {
                binding.rvMessages.smoothScrollToPosition(pos)
                messageAdapter.highlightMessage(pinnedMsg.id)
            } else {
                Toast.makeText(this, "Pinned message loaded in history", Toast.LENGTH_SHORT).show()
            }

            // Cycle to next pinned message on subsequent tap if multiple
            if (pinnedList.size > 1) {
                currentPinnedIndex = (currentPinnedIndex + 1) % pinnedList.size
                updatePinnedMessageHeader(pinnedList)
            }
        }

        binding.layoutPinnedMessage.setOnLongClickListener {
            showPinnedMessagesListDialog(pinnedList)
            true
        }
    }

    private fun showPinnedMessagesListDialog(pinnedList: List<Message>) {
        val items = pinnedList.mapIndexed { idx, msg ->
            val author = if (msg.isMe) "You" else msg.senderName.ifBlank { "Contact" }
            val preview = when (msg.type) {
                MessageType.TEXT -> msg.content
                MessageType.IMAGE -> "📷 Photo"
                MessageType.VIDEO -> "🎥 Video"
                MessageType.AUDIO, MessageType.VOICE -> "🎵 Audio message"
                MessageType.FILE -> "📄 Document"
                MessageType.LOCATION -> "📍 Location"
                MessageType.POLL -> "📊 Poll: ${msg.content}"
                else -> msg.content
            }.replace('\n', ' ').take(35)
            "${idx + 1}. $author: $preview"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Pinned Messages (${pinnedList.size})")
            .setItems(items) { _, which ->
                currentPinnedIndex = which
                val selected = pinnedList[which]
                updatePinnedMessageHeader(pinnedList)
                val pos = (0 until messageAdapter.itemCount).indexOfFirst {
                    messageAdapter.peek(it)?.id == selected.id
                }
                if (pos != -1) {
                    binding.rvMessages.smoothScrollToPosition(pos)
                    messageAdapter.highlightMessage(selected.id)
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
                    val forwardSheet = ForwardMessageBottomSheet(message)
                    forwardSheet.show(supportFragmentManager, "forward_message")
                }
                MessageActionBottomSheet.Action.STAR -> {
                    viewModel.toggleStar(message.id, !message.isStarred)
                    val status = if (message.isStarred) "unstarred" else "starred"
                    Toast.makeText(this, getString(R.string.toast_message_status, status), Toast.LENGTH_SHORT).show()
                }
                MessageActionBottomSheet.Action.TRANSLATE -> {
                    showTranslationDialog(message)
                }
                MessageActionBottomSheet.Action.PIN -> {
                    if (message.isPinned) {
                        viewModel.togglePin(message.id, false)
                        Toast.makeText(this, "Message unpinned from top", Toast.LENGTH_SHORT).show()
                    } else {
                        val currentPinned = viewModel.pinnedMessages.value
                        if (currentPinned.size >= 3) {
                            AlertDialog.Builder(this)
                                .setTitle("Pinned Messages Limit (3/3)")
                                .setMessage("You can only pin up to 3 messages per chat. Would you like to replace the oldest pinned message?")
                                .setPositiveButton("Replace Oldest") { _, _ ->
                                    val oldest = currentPinned.minByOrNull { it.createdAt } ?: currentPinned.lastOrNull()
                                    if (oldest != null) {
                                        viewModel.togglePin(oldest.id, false)
                                    }
                                    viewModel.togglePin(message.id, true)
                                    Toast.makeText(this, "📌 Message pinned to top", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            viewModel.togglePin(message.id, true)
                            Toast.makeText(this, "📌 Message pinned to top", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                MessageActionBottomSheet.Action.EDIT -> viewModel.setEditingMessage(message)
                MessageActionBottomSheet.Action.VIEW_EDIT_HISTORY -> showEditHistoryDialog(message)
                MessageActionBottomSheet.Action.EXPORT -> {
                    showExportChatBottomSheet(targetMessages = listOf(message))
                }
                MessageActionBottomSheet.Action.REPORT -> {
                    com.example.gochat.ui.common.ReportContentDialog.show(
                        context = this,
                        title = "Report this message?",
                    ) { reason ->
                        lifecycleScope.launch {
                            chatRepository.reportContent("message", message.id, reason)
                            Toast.makeText(
                                this@ChatRoomActivity,
                                getString(R.string.report_submitted_toast),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                MessageActionBottomSheet.Action.DELETE -> {
                    val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
                    val canDeleteForEveryone = message.isMe || (isGroup && viewModel.isGroupAdmin.value)
                    if (canDeleteForEveryone) {
                        val deleteOptions = arrayOf(
                            getString(R.string.action_delete_for_everyone),
                            getString(R.string.action_delete_for_me)
                        )
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dialog_delete_message_title))
                            .setItems(deleteOptions) { _, which ->
                                when (which) {
                                    0 -> {
                                        viewModel.deleteMessageForEveryone(message.id)
                                        Toast.makeText(this, "Message deleted for everyone", Toast.LENGTH_SHORT).show()
                                    }
                                    1 -> {
                                        viewModel.deleteMessageForMe(message.id)
                                        Toast.makeText(this, "Message deleted for me", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dialog_delete_message_title))
                            .setMessage("Delete this message for yourself?")
                            .setPositiveButton(getString(R.string.action_delete_for_me)) { _, _ ->
                                viewModel.deleteMessageForMe(message.id)
                                Toast.makeText(this, "Message deleted for me", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    }
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

    private fun showTranslationDialog(message: Message) {
        val languages = arrayOf("English", "Spanish", "French", "German", "Arabic", "Portuguese", "Russian", "Japanese", "Chinese", "Hindi")
        AlertDialog.Builder(this)
            .setTitle("Translate Message to")
            .setItems(languages) { _, which ->
                val targetLang = languages[which]
                performTranslation(message, targetLang)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showEditHistoryDialog(message: Message) {
        val loadingToast = Toast.makeText(this, "Loading edit history...", Toast.LENGTH_SHORT)
        loadingToast.show()
        viewModel.getEditHistory(message.id) { entries ->
            loadingToast.cancel()
            if (entries.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.title_edit_history)
                    .setMessage(R.string.edit_history_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@getEditHistory
            }

            val timeFormatter = java.text.SimpleDateFormat("h:mm a, d MMM yyyy", java.util.Locale.getDefault())
            val formattedItems = ArrayList<CharSequence>()
            entries.forEachIndexed { index, entry ->
                val label = if (index == 0) getString(R.string.label_original) else getString(R.string.label_edit_version, index)
                val timeStr = if (entry.editedAt > 0) {
                    val epochMs = if (entry.editedAt < 100_000_000_000L) entry.editedAt * 1000L else entry.editedAt
                    timeFormatter.format(java.util.Date(epochMs))
                } else ""
                val header = if (timeStr.isNotEmpty()) "$label ($timeStr)" else label

                val ssb = android.text.SpannableStringBuilder()
                val start = ssb.length
                ssb.append(header).append("\n")
                ssb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.gochat_emerald_light)), start, ssb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append(entry.content)
                formattedItems.add(ssb)
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.title_edit_history)
                .setItems(formattedItems.toTypedArray(), null)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun performTranslation(message: Message, targetLang: String) {
        Toast.makeText(this, "Translating to $targetLang...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = viewModel.translateMessage(message.content, targetLang)
            result.onSuccess { (translated, detected) ->
                messageAdapter.setTranslation(message.id, translated, detected, targetLang)
                Toast.makeText(this@ChatRoomActivity, "Translated from $detected", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@ChatRoomActivity, "Translation failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMoreMenu() {
        val convId = viewModel.conversationId.value.ifBlank { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
        val isLocked = com.example.gochat.core.security.ChatLockManager.isLocked(this, convId)
        val lockOption = if (isLocked) "Unlock Chat (Remove Lock)" else "Lock Chat (Require Biometric)"
        val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: getString(R.string.message_hint)
        val avatarUrl = intent.getStringExtra(EXTRA_CONVERSATION_AVATAR).orEmpty()
        val resolvedPartnerId = currentPartnerId?.ifBlank { null }
            ?: viewModel.partnerId?.ifBlank { null }
            ?: ""

        val menuItems = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!isGroup) {
            menuItems.add(getString(R.string.option_view_contact))
            actions.add {
                val intent = Intent(this@ChatRoomActivity, ContactProfileActivity::class.java).apply {
                    putExtra(ContactProfileActivity.EXTRA_CONVERSATION_ID, viewModel.conversationId.value)
                    putExtra(ContactProfileActivity.EXTRA_USER_NAME, title)
                    putExtra(ContactProfileActivity.EXTRA_USER_AVATAR, avatarUrl)
                    putExtra(ContactProfileActivity.EXTRA_IS_ONLINE, viewModel.isPartnerOnline.value)
                    putExtra(ContactProfileActivity.EXTRA_LAST_SEEN, viewModel.partnerLastSeen.value ?: 0L)
                    putExtra(ContactProfileActivity.EXTRA_TARGET_USER_ID, resolvedPartnerId)
                }
                contactProfileLauncher.launch(intent)
            }

            menuItems.add(getString(R.string.create_group_with_format, title))
            actions.add {
                val intent = Intent(this@ChatRoomActivity, SelectContactActivity::class.java).apply {
                    putExtra(SelectContactActivity.EXTRA_ACTION, SelectContactActivity.ACTION_CREATE_GROUP)
                    putExtra(SelectContactActivity.EXTRA_PRESELECT_USER_ID, resolvedPartnerId)
                    putExtra(SelectContactActivity.EXTRA_PRESELECT_NAME, title)
                    putExtra(SelectContactActivity.EXTRA_PRESELECT_AVATAR, avatarUrl)
                }
                startActivity(intent)
            }

            // End-to-end encryption toggle (direct chats only; opt-in, off by default).
            val e2eeOn = com.example.gochat.core.crypto.E2EESettings.isEnabled(this, convId)
            menuItems.add(if (e2eeOn) "Turn off encryption" else "Turn on encryption (end-to-end)")
            actions.add {
                val newState = !e2eeOn
                com.example.gochat.core.crypto.E2EESettings.setEnabled(this, convId, newState)
                Toast.makeText(
                    this,
                    if (newState) "End-to-end encryption ON for this chat. The other person needs a recent app version."
                    else "End-to-end encryption OFF for this chat.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            menuItems.add("Group info")
            actions.add {
                val intent = Intent(this@ChatRoomActivity, GroupInfoActivity::class.java).apply {
                    putExtra(GroupInfoActivity.EXTRA_CONVERSATION_ID, viewModel.conversationId.value)
                    putExtra(GroupInfoActivity.EXTRA_GROUP_NAME, title)
                    putExtra(GroupInfoActivity.EXTRA_GROUP_AVATAR, avatarUrl)
                    val memberIds = viewModel.members.value.map { it.id }
                    if (memberIds.isNotEmpty()) {
                        putStringArrayListExtra(GroupInfoActivity.EXTRA_MEMBER_IDS, ArrayList(memberIds))
                    }
                }
                startActivity(intent)
            }
        }

        menuItems.add("Search")
        actions.add { openInChatSearch() }

        menuItems.add("Media, links, and docs")
        actions.add {
            val intent = Intent(this, SharedMediaActivity::class.java).apply {
                putExtra(SharedMediaActivity.EXTRA_CONVERSATION_ID, convId)
                putExtra(SharedMediaActivity.EXTRA_CONVERSATION_TITLE, title)
            }
            startActivity(intent)
        }

        menuItems.add("Starred Messages")
        actions.add {
            val intent = Intent(this, StarredMessagesActivity::class.java).apply {
                putExtra(StarredMessagesActivity.EXTRA_CONVERSATION_ID, convId)
                putExtra(StarredMessagesActivity.EXTRA_CONVERSATION_TITLE, title)
            }
            startActivity(intent)
        }

        menuItems.add(lockOption)
        actions.add {
            com.example.gochat.core.security.ChatLockManager.authenticate(
                activity = this,
                title = if (isLocked) "Unlock Chat" else "Lock Chat",
                subtitle = "Confirm biometric or device credential to modify chat lock",
                onSuccess = {
                    com.example.gochat.core.security.ChatLockManager.setLocked(this, convId, !isLocked)
                    val status = if (!isLocked) "locked" else "unlocked"
                    Toast.makeText(this, "Chat $status", Toast.LENGTH_SHORT).show()
                }
            )
        }

        menuItems.add(getString(R.string.option_wallpaper_theme))
        actions.add {
            val sheet = ChatWallpaperBottomSheet(
                conversationId = convId,
                conversationTitle = title,
                onThemeChanged = { updatedTheme ->
                    applyTheme(updatedTheme)
                }
            )
            sheet.show(supportFragmentManager, ChatWallpaperBottomSheet.TAG)
        }

        menuItems.add(getString(R.string.option_disappearing_messages))
        actions.add {
            showDisappearingMessagesDialog()
        }

        menuItems.add(if (viewModel.screenshotNotificationsEnabled.value) "Disable Screenshot Alerts" else "Enable Screenshot Alerts")
        actions.add {
            val current = viewModel.screenshotNotificationsEnabled.value
            viewModel.toggleScreenshotNotifications(!current)
            val status = if (!current) "enabled" else "disabled"
            Toast.makeText(this, "Screenshot alerts $status", Toast.LENGTH_SHORT).show()
        }

        menuItems.add(if (viewModel.isMuted.value) "Unmute notifications" else getString(R.string.option_mute_notifications))
        actions.add {
            val currentlyMuted = viewModel.isMuted.value
            if (currentlyMuted) {
                viewModel.setConversationMuted(false)
                Toast.makeText(this, "Notifications unmuted", Toast.LENGTH_SHORT).show()
            } else {
                val options = arrayOf("8 hours", "1 week", "Always")
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.option_mute_notifications))
                    .setItems(options) { _, _ ->
                        viewModel.setConversationMuted(true)
                        Toast.makeText(this, "Notifications muted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }

        menuItems.add(getString(R.string.option_clear_chat))
        actions.add {
            ClearChatHelper.showClearChatDialog(
                context = this,
                coroutineScope = lifecycleScope,
                chatRepository = chatRepository,
                conversationId = convId
            )
        }

        menuItems.add(getString(R.string.option_export_chat))
        actions.add {
            showExportChatBottomSheet()
        }

        if (!isGroup) {
            val targetId = currentPartnerId?.ifBlank { null }
                ?: viewModel.partnerId?.ifBlank { null }
                ?: convId

            if (isPartnerBlocked) {
                menuItems.add(getString(R.string.action_unblock_contact, title))
                actions.add {
                    BlockContactHelper.showUnblockConfirmationDialog(
                        context = this@ChatRoomActivity,
                        coroutineScope = lifecycleScope,
                        chatRepository = chatRepository,
                        userId = targetId,
                        userName = title
                    )
                }
            } else {
                menuItems.add(getString(R.string.action_block_contact, title))
                actions.add {
                    BlockContactHelper.showBlockConfirmationDialog(
                        context = this@ChatRoomActivity,
                        coroutineScope = lifecycleScope,
                        chatRepository = chatRepository,
                        userId = targetId,
                        userName = title
                    )
                }
            }

            menuItems.add(getString(R.string.action_report_contact, title))
            actions.add {
                BlockContactHelper.showReportBottomSheet(
                    fragmentManager = supportFragmentManager,
                    userId = targetId,
                    userName = title,
                    conversationId = convId,
                    chatRepository = chatRepository,
                    onReportCompleted = { _ ->
                        // Keep chat room open so previous chat history remains visible alongside blocked banner
                    }
                )
            }
        }

        AlertDialog.Builder(this)
            .setItems(menuItems.toTypedArray()) { _, which ->
                if (which in actions.indices) {
                    actions[which].invoke()
                }
            }
            .show()
    }

    private fun showDisappearingMessagesDialog() {
        val options = arrayOf("Off", "24 Hours", "7 Days", "90 Days")
        val values = intArrayOf(0, 86400, 604800, 7776000)
        var selectedIdx = 0
        val currentDuration = viewModel.disappearingDuration.value
        selectedIdx = values.indexOf(currentDuration).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.option_disappearing_messages))
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                viewModel.setDisappearingMessages(values[which])
                dialog.dismiss()
                val status = if (values[which] > 0) "enabled (${options[which]})" else "disabled"
                Toast.makeText(this, "Disappearing messages $status", Toast.LENGTH_SHORT).show()
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
            messageAdapter.setBubbleTheme(theme.bubbleShape, accentColor)
        }
    }

    private fun renderPresence(isTyping: Boolean, isOnline: Boolean, lastSeen: Long?, isDeviceOnline: Boolean) {
        val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        if (!isDeviceOnline) {
            binding.tvChatSubtitle.text = getString(R.string.waiting_for_network)
            binding.tvChatSubtitle.visibility = View.VISIBLE
            binding.viewHeaderOnlineDot.visibility = View.GONE
            return
        }
        if (isTyping) {
            startTypingAnimation()
            binding.tvChatSubtitle.visibility = View.VISIBLE
            binding.viewHeaderOnlineDot.visibility = View.VISIBLE
        } else {
            stopTypingAnimation()
            if (isGroup) {
                val memberCount = viewModel.members.value.size
                if (memberCount > 0) {
                    binding.tvChatSubtitle.text = "$memberCount members"
                    binding.tvChatSubtitle.visibility = View.VISIBLE
                } else {
                    binding.tvChatSubtitle.visibility = View.GONE
                }
                binding.viewHeaderOnlineDot.visibility = View.GONE
            } else if (isOnline) {
                binding.tvChatSubtitle.text = getString(R.string.status_online)
                binding.tvChatSubtitle.visibility = View.VISIBLE
                binding.viewHeaderOnlineDot.visibility = View.VISIBLE
            } else {
                binding.viewHeaderOnlineDot.visibility = View.GONE
                if (lastSeen != null && lastSeen > 0L) {
                    binding.tvChatSubtitle.text = formatLastSeen(lastSeen)
                    binding.tvChatSubtitle.visibility = View.VISIBLE
                } else {
                    binding.tvChatSubtitle.text = getString(R.string.status_offline)
                    binding.tvChatSubtitle.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun formatLastSeen(lastSeenMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - lastSeenMs
        if (diff < 60_000L) {
            return getString(R.string.status_last_seen_just_now)
        }

        val calNow = Calendar.getInstance()
        val calSeen = Calendar.getInstance().apply { timeInMillis = lastSeenMs }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val timeStr = timeFormat.format(Date(lastSeenMs))

        val isToday = calNow.get(Calendar.YEAR) == calSeen.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calSeen.get(Calendar.DAY_OF_YEAR)

        if (isToday) {
            return getString(R.string.status_last_seen_today, timeStr)
        }

        calNow.add(Calendar.DAY_OF_YEAR, -1)
        val isYesterday = calNow.get(Calendar.YEAR) == calSeen.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calSeen.get(Calendar.DAY_OF_YEAR)

        if (isYesterday) {
            return getString(R.string.status_last_seen_yesterday, timeStr)
        }

        if (diff < 6 * 24 * 60 * 60 * 1000L) {
            val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())
            return getString(R.string.status_last_seen_date, dayFormat.format(Date(lastSeenMs)), timeStr)
        }

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        return getString(R.string.status_last_seen_date, dateFormat.format(Date(lastSeenMs)), timeStr)
    }

    override fun onResume() {
        super.onResume()
        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        if (convId.isNotEmpty()) {
            viewModel.onScreenResumed(convId)
            com.example.gochat.core.notification.NotificationHelper.dismissNotification(this, convId)
        }
    }

    override fun onPause() {
        super.onPause()
        // Release any inline round video-note player.
        if (::messageAdapter.isInitialized) messageAdapter.stopInlineVideo()
        // Save draft text before leaving
        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        if (convId.isNotEmpty()) {
            val draftText = binding.etMessageInput.text?.toString()
            viewModel.saveDraft(convId, draftText)
        }
        viewModel.onScreenPaused()
        if (isCurrentlyTypingSent) {
            isCurrentlyTypingSent = false
            stopTypingHandler.removeCallbacks(stopTypingRunnable)
            viewModel.sendTypingEvent(false)
        }
        stopTypingAnimation()
        if (audioRecorderManager.isRecording) {
            cancelVoiceRecording()
        }
        AudioPlayerManager.stop()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let {
                registerScreenCaptureCallback(mainExecutor, it)
            }
        } else {
            screenshotObserver?.let {
                contentResolver.registerContentObserver(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    it
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onScreenPaused()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let {
                unregisterScreenCaptureCallback(it)
            }
        } else {
            screenshotObserver?.let {
                contentResolver.unregisterContentObserver(it)
            }
        }
    }

    private fun setupScreenshotDetection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenshotObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                private var lastCheckedId: Long = -1L

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (!viewModel.screenshotNotificationsEnabled.value) return

                    try {
                        val projection = arrayOf(
                            android.provider.MediaStore.Images.Media._ID,
                            android.provider.MediaStore.Images.Media.DATA,
                            android.provider.MediaStore.Images.Media.DATE_ADDED
                        )
                        val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                        contentResolver.query(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            null,
                            null,
                            sortOrder
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                                val data = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)).orEmpty().lowercase()
                                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED))

                                if (id != lastCheckedId) {
                                    lastCheckedId = id
                                    val nowSec = System.currentTimeMillis() / 1000L
                                    if (Math.abs(nowSec - dateAdded) <= 10 && (data.contains("screenshot") || data.contains("screen_shot") || data.contains("capture"))) {
                                        viewModel.sendScreenshotNotification()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun setupInChatSearch() {
        binding.btnCloseSearch.setOnClickListener {
            closeInChatSearch()
        }

        binding.etSearchQuery.doAfterTextChanged { text ->
            val q = text?.toString().orEmpty()
            viewModel.setSearchQuery(q)
            messageAdapter.searchQuery = q
        }

        binding.btnSearchNext.setOnClickListener {
            viewModel.nextSearchResult()
        }

        binding.btnSearchPrev.setOnClickListener {
            viewModel.prevSearchResult()
        }
    }

    private fun openInChatSearch() {
        binding.layoutInChatSearch.visibility = View.VISIBLE
        binding.etSearchQuery.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(binding.etSearchQuery, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeInChatSearch() {
        binding.layoutInChatSearch.visibility = View.GONE
        binding.etSearchQuery.setText("")
        viewModel.clearSearch()
        messageAdapter.searchQuery = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetMsgId = intent.getStringExtra(EXTRA_TARGET_MESSAGE_ID)
        if (!targetMsgId.isNullOrEmpty()) {
            jumpToAndHighlightMessage(targetMsgId)
        }
    }

    private var targetMessageJumpJob: kotlinx.coroutines.Job? = null

    private fun jumpToAndHighlightMessage(targetMsgId: String) {
        targetMessageJumpJob?.cancel()
        targetMessageJumpJob = lifecycleScope.launch {
            // First check if already present in adapter's loaded items
            val adapterPos = (0 until messageAdapter.itemCount).indexOfFirst {
                messageAdapter.peek(it)?.id == targetMsgId
            }
            if (adapterPos != -1) {
                val layoutMgr = binding.rvMessages.layoutManager as? LinearLayoutManager
                val currentPos = layoutMgr?.findFirstVisibleItemPosition() ?: 0
                if (Math.abs(currentPos - adapterPos) > 25) {
                    val jumpNearby = if (adapterPos > currentPos) adapterPos - 10 else adapterPos + 10
                    binding.rvMessages.scrollToPosition(jumpNearby.coerceIn(0, messageAdapter.itemCount - 1))
                }
                binding.rvMessages.post {
                    binding.rvMessages.smoothScrollToPosition(adapterPos)
                    messageAdapter.highlightMessage(targetMsgId)
                }
                return@launch
            }

            // Otherwise, observe viewModel.messages until found or timeout
            var found = false
            kotlinx.coroutines.withTimeoutOrNull(2500) {
                viewModel.messages.collect { list ->
                    val idx = list.indexOfFirst { it.id == targetMsgId }
                    if (idx != -1) {
                        found = true
                        val layoutMgr = binding.rvMessages.layoutManager as? LinearLayoutManager
                        val currentPos = layoutMgr?.findFirstVisibleItemPosition() ?: 0
                        if (Math.abs(currentPos - idx) > 25) {
                            val jumpNearby = if (idx > currentPos) idx - 10 else idx + 10
                            binding.rvMessages.scrollToPosition(jumpNearby.coerceIn(0, (messageAdapter.itemCount - 1).coerceAtLeast(0)))
                        }
                        binding.rvMessages.post {
                            binding.rvMessages.smoothScrollToPosition(idx)
                            messageAdapter.highlightMessage(targetMsgId)
                        }
                        targetMessageJumpJob?.cancel()
                    }
                }
            }
            if (!found) {
                Toast.makeText(this@ChatRoomActivity, getString(R.string.original_message_not_found), Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // calls super.onBackPressed(); OnBackPressedDispatcher migration tracked separately
    override fun onBackPressed() {
        if (binding.layoutVideoNoteOverlay.visibility == View.VISIBLE) {
            cancelVideoNote()
            return
        }
        if (messageAdapter.isSelectionMode) {
            exitSelectionMode()
            return
        }
        if (binding.layoutInChatSearch.visibility == View.VISIBLE) {
            closeInChatSearch()
            return
        }
        super.onBackPressed()
    }

    private fun enterSelectionMode(initialSelectedMessage: Message? = null) {
        messageAdapter.isSelectionMode = true
        if (initialSelectedMessage != null) {
            messageAdapter.toggleSelection(initialSelectedMessage.id)
        }
        binding.layoutSelectionBar.visibility = View.VISIBLE
        binding.toolbarChatRoom.visibility = View.GONE
        updateSelectionUI(messageAdapter.selectedMessageIds.size)
    }

    private fun exitSelectionMode() {
        messageAdapter.isSelectionMode = false
        messageAdapter.clearSelection()
        binding.layoutSelectionBar.visibility = View.GONE
        binding.toolbarChatRoom.visibility = View.VISIBLE
    }

    private fun updateSelectionUI(count: Int) {
        binding.tvSelectionCount.text = getString(R.string.export_selection_count, count)
        binding.btnExportSelectionAction.isEnabled = count > 0
        binding.btnStarSelectionAction.isEnabled = count > 0
        binding.btnDeleteSelectionAction.isEnabled = count > 0
    }

    private fun setupSelectionBar() {
        messageAdapter.onSelectionChanged = { count ->
            updateSelectionUI(count)
        }

        binding.btnCloseSelection.setOnClickListener {
            exitSelectionMode()
        }

        binding.btnSelectAll.setOnClickListener {
            messageAdapter.selectAll()
        }

        binding.btnStarSelectionAction.setOnClickListener {
            val selected = messageAdapter.getSelectedMessages()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least 1 message to star", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val shouldStar = selected.any { !it.isStarred }
            selected.forEach { msg ->
                viewModel.toggleStar(msg.id, shouldStar)
            }
            val actionText = if (shouldStar) "starred" else "unstarred"
            Toast.makeText(this, "${selected.size} message(s) $actionText", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }

        binding.btnExportSelectionAction.setOnClickListener {
            val selected = messageAdapter.getSelectedMessages()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least 1 message to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportChatBottomSheet(targetMessages = selected)
        }

        binding.btnDeleteSelectionAction.setOnClickListener {
            val selected = messageAdapter.getSelectedMessages()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least 1 message to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
            val canDeleteForEveryone = selected.all { it.isMe || (isGroup && viewModel.isGroupAdmin.value) }
            if (canDeleteForEveryone) {
                val deleteOptions = arrayOf(
                    getString(R.string.action_delete_for_everyone),
                    getString(R.string.action_delete_for_me)
                )
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_delete_multiple_messages_title, selected.size))
                    .setItems(deleteOptions) { _, which ->
                        val ids = selected.map { it.id }
                        when (which) {
                            0 -> {
                                viewModel.deleteMessagesForEveryone(ids)
                                Toast.makeText(this, "${selected.size} messages deleted for everyone", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                viewModel.deleteMessagesForMe(ids)
                                Toast.makeText(this, "${selected.size} messages deleted for me", Toast.LENGTH_SHORT).show()
                            }
                        }
                        exitSelectionMode()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_delete_multiple_messages_title, selected.size))
                    .setMessage(getString(R.string.dialog_delete_messages_desc))
                    .setPositiveButton(getString(R.string.action_delete_for_me)) { _, _ ->
                        viewModel.deleteMessagesForMe(selected.map { it.id })
                        Toast.makeText(this, "${selected.size} messages deleted for me", Toast.LENGTH_SHORT).show()
                        exitSelectionMode()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    private fun showExportChatBottomSheet(targetMessages: List<Message>? = null) {
        val convId = viewModel.conversationId.value.ifBlank { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: "Chat"

        val sheet = ExportChatBottomSheet(
            conversationId = convId,
            conversationTitle = title,
            chatRepository = chatRepository,
            targetMessages = targetMessages,
            onSelectMessagesRequested = {
                enterSelectionMode()
            }
        )
        sheet.show(supportFragmentManager, ExportChatBottomSheet.TAG)
    }

    override fun onDestroy() {
        viewModel.onScreenPaused()
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        stopTypingHandler.removeCallbacks(stopTypingRunnable)
        stopTypingAnimation()
        audioRecorderManager.cancelRecording()
        videoNoteHandler.removeCallbacks(videoNoteStartRunnable)
        videoNoteHandler.removeCallbacks(videoNoteTimerRunnable)
        try { videoNoteRecording?.stop() } catch (_: Exception) {}
        videoNoteProvider?.unbindAll()
        AudioPlayerManager.release()
        super.onDestroy()
    }
}
