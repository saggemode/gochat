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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.gochat.R
import com.example.gochat.ui.marketplace.OrdersActivity
import com.example.gochat.ui.marketplace.ProductDetailsActivity
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
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var mentionAdapter: GroupMemberAdapter
    private lateinit var audioRecorderManager: AudioRecorderManager

    private var unreadNewMessagesCount = 0
    private var isScrolledUp = false
    private var currentPartnerId: String? = null

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

    // Android 14+ Screenshot Detection
    private val screenCaptureCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ScreenCaptureCallback {
            viewModel.sendScreenshotNotification()
        }
    } else null

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
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
        observeState()

        val targetMsgId = intent.getStringExtra(EXTRA_TARGET_MESSAGE_ID)
        if (!targetMsgId.isNullOrEmpty()) {
            lifecycleScope.launch {
                viewModel.messages.collect { list ->
                    val idx = list.indexOfFirst { it.id == targetMsgId }
                    if (idx != -1) {
                        binding.rvMessages.post {
                            binding.rvMessages.scrollToPosition(idx)
                        }
                    }
                }
            }
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
            viewModel.initConversation(convId)
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
                }
            }
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
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val bottomInset = if (imeInsets.bottom > 0) imeInsets.bottom else systemBars.bottom

                    binding.chatContentContainer.updatePadding(bottom = bottomInset)
                    return insets
                }
            }
        )
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
                        
                        val memberIds = viewModel.mentionSuggestions.value.map { it.id }
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
                val resolvedPartnerId = currentPartnerId?.ifBlank { null }
                    ?: viewModel.partnerId?.ifBlank { null }
                    ?: viewModel.conversationId.value
                val intent = Intent(this@ChatRoomActivity, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_CALL_ID, "call_${System.currentTimeMillis()}")
                    putExtra(CallActivity.EXTRA_TARGET_USER_ID, resolvedPartnerId)
                    putExtra(CallActivity.EXTRA_PEER_NAME, title)
                    putExtra(CallActivity.EXTRA_PEER_AVATAR, avatarUrl)
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
            onBuyNowClicked = { prodId, name, price, image ->
                val intent = Intent(this@ChatRoomActivity, ProductDetailsActivity::class.java).apply {
                    putExtra("product_id", prodId)
                    putExtra("product_name", name)
                    putExtra("product_price", price)
                    putExtra("product_image", image)
                    putExtra("auto_buy", true)
                }
                startActivity(intent)
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
                    .setMessage("Have you received '${invoiceData.itemName}' in satisfactory condition?\n\nThis will release ${String.format(Locale.US, "$%.2f", invoiceData.amount)} from Escrow to the seller.")
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
        }

        val layoutManager = LinearLayoutManager(this).apply {
            reverseLayout = true
        }

        val themeManager = ChatThemeManager(this)
        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val currentTheme = themeManager.getTheme(convId)
        messageAdapter.setBubbleTheme(currentTheme.bubbleShape, currentTheme.accentColor)

        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = messageAdapter

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
                messageAdapter.notifyItemChanged(position)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)

        // Mention suggestions
        mentionAdapter = GroupMemberAdapter(
            onItemClicked = { user ->
                insertMention(user.displayName)
            }
        )
        binding.rvMentionSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvMentionSuggestions.adapter = mentionAdapter
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

        sheet.show()
    }

    private fun showCatalogPicker() {
        val picker = CatalogPickerBottomSheet { selectedProducts ->
            if (selectedProducts.isEmpty()) return@CatalogPickerBottomSheet
            viewModel.sendCatalogMessage(selectedProducts)
        }
        picker.show(supportFragmentManager, "CatalogPickerBottomSheet")
    }

    private fun showCreateInvoiceBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val invoiceBinding = com.example.gochat.databinding.BottomSheetCreateInvoiceBinding.inflate(layoutInflater)
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

    private fun showEscrowCheckoutBottomSheet(message: Message, invoiceData: com.example.gochat.data.model.InvoiceData) {
        val sheet = BottomSheetDialog(this)
        val checkoutBinding = com.example.gochat.databinding.BottomSheetEscrowCheckoutBinding.inflate(layoutInflater)
        sheet.setContentView(checkoutBinding.root)

        checkoutBinding.tvCheckoutItemName.text = invoiceData.itemName
        checkoutBinding.tvCheckoutItemNote.text = if (invoiceData.note.isNotBlank()) invoiceData.note else "Standard GoChat escrow protection applies"
        checkoutBinding.tvCheckoutTotalAmount.text = String.format(Locale.US, "$%.2f", invoiceData.amount)

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
            Toast.makeText(this, "✅ ${String.format(Locale.US, "$%.2f", invoiceData.amount)} paid into Escrow", Toast.LENGTH_LONG).show()
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

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.botConfig.collect { config ->
                        binding.tvBotBanner.visibility = if (config?.isActive == true) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.messagesPaged.collectLatest { pagingData ->
                        messageAdapter.submitData(pagingData)
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
                    viewModel.disappearingDuration.collect { duration ->
                        binding.ivHeaderDisappearingBadge.visibility = if (duration > 0) View.VISIBLE else View.GONE
                        binding.ivHeaderDisappearingBadge.setOnClickListener {
                            showDisappearingMessagesDialog()
                        }
                    }
                }

                launch {
                    viewModel.pinnedMessages.collect { pinnedList ->
                        updatePinnedMessageHeader(pinnedList)
                    }
                }
            }
        }
    }

    private fun updatePinnedMessageHeader(pinnedList: List<Message>) {
        if (pinnedList.isEmpty()) {
            if (binding.layoutPinnedMessage.visibility == View.VISIBLE) {
                TransitionManager.beginDelayedTransition(binding.chatContentContainer, AutoTransition().apply { duration = 180 })
                binding.layoutPinnedMessage.visibility = View.GONE
            }
            return
        }

        val pinnedMsg = pinnedList.first()
        if (binding.layoutPinnedMessage.visibility != View.VISIBLE) {
            TransitionManager.beginDelayedTransition(binding.chatContentContainer, AutoTransition().apply { duration = 180 })
            binding.layoutPinnedMessage.visibility = View.VISIBLE
        }

        val senderLabel = if (pinnedMsg.isMe) "You" else pinnedMsg.senderName.ifBlank { "Contact" }
        binding.tvPinnedTitle.text = "Pinned • $senderLabel"

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
            viewModel.togglePin(pinnedMsg.id, false)
            Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show()
        }

        binding.layoutPinnedMessage.setOnClickListener {
            val pos = (0 until messageAdapter.itemCount).indexOfFirst {
                messageAdapter.peek(it)?.id == pinnedMsg.id
            }
            if (pos != -1) {
                binding.rvMessages.smoothScrollToPosition(pos)
            } else {
                Toast.makeText(this, "Pinned message loaded in history", Toast.LENGTH_SHORT).show()
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
                MessageActionBottomSheet.Action.PIN -> {
                    viewModel.togglePin(message.id, !message.isPinned)
                    Toast.makeText(
                        this,
                        if (message.isPinned) "Message unpinned from top" else "📌 Message pinned to top",
                        Toast.LENGTH_SHORT
                    ).show()
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
        val convId = viewModel.conversationId.value.ifBlank { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
        val isLocked = com.example.gochat.core.security.ChatLockManager.isLocked(this, convId)
        val lockOption = if (isLocked) "Unlock Chat (Remove Lock)" else "Lock Chat (Require Biometric)"

        val items = arrayOf(
            "Search",
            "Media, links, and docs",
            lockOption,
            getString(R.string.option_wallpaper_theme),
            getString(R.string.option_disappearing_messages),
            if (viewModel.screenshotNotificationsEnabled.value) "Disable Screenshot Alerts" else "Enable Screenshot Alerts",
            getString(R.string.option_mute_notifications),
            getString(R.string.option_clear_chat),
            getString(R.string.option_export_chat)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openInChatSearch()
                    1 -> {
                        val title = intent.getStringExtra(EXTRA_CONVERSATION_TITLE) ?: getString(R.string.message_hint)
                        val intent = Intent(this, SharedMediaActivity::class.java).apply {
                            putExtra(SharedMediaActivity.EXTRA_CONVERSATION_ID, convId)
                            putExtra(SharedMediaActivity.EXTRA_CONVERSATION_TITLE, title)
                        }
                        startActivity(intent)
                    }
                    2 -> {
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
                    3 -> {
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
                    4 -> {
                        showDisappearingMessagesDialog()
                    }
                    5 -> {
                        val current = viewModel.screenshotNotificationsEnabled.value
                        viewModel.toggleScreenshotNotifications(!current)
                        val status = if (!current) "enabled" else "disabled"
                        Toast.makeText(this, "Screenshot alerts $status", Toast.LENGTH_SHORT).show()
                    }
                    else -> Toast.makeText(this, getString(R.string.toast_option_selected), Toast.LENGTH_SHORT).show()
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
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onScreenPaused()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let {
                unregisterScreenCaptureCallback(it)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.layoutInChatSearch.visibility == View.VISIBLE) {
            closeInChatSearch()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        viewModel.onScreenPaused()
        recordingHandler.removeCallbacks(recordingTimerRunnable)
        stopTypingHandler.removeCallbacks(stopTypingRunnable)
        stopTypingAnimation()
        audioRecorderManager.cancelRecording()
        AudioPlayerManager.release()
        super.onDestroy()
    }
}
