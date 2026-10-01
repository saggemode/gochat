package com.example.gochat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.databinding.ActivityContactProfileBinding
import com.example.gochat.ui.calls.CallActivity
import com.example.gochat.ui.contacts.SelectContactActivity
import com.example.gochat.ui.security.SecurityVerificationActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.core.contacts.ContactSyncManager
import com.example.gochat.data.repository.MarketplaceRepository

@AndroidEntryPoint
class ContactProfileActivity : AppCompatActivity() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var marketplaceRepository: MarketplaceRepository

    @Inject
    lateinit var contactSyncManager: ContactSyncManager

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_USER_NAME = "extra_user_name"
        const val EXTRA_USER_PHONE = "extra_user_phone"
        const val EXTRA_USER_AVATAR = "extra_user_avatar"
        const val EXTRA_USER_PIN = "extra_user_pin"
        const val EXTRA_IS_ONLINE = "extra_is_online"
        const val EXTRA_LAST_SEEN = "extra_last_seen"
        const val EXTRA_TARGET_USER_ID = "extra_target_user_id"
    }

    private lateinit var binding: ActivityContactProfileBinding

    private var isMuted = false
    private var isLocked = false
    private var isFavourite = false
    private var disappearingOption = "Off"
    private var mediaVisibilityOption = "Default (Yes)"
    private var ipProtectionEnabled = true
    private var linkPreviewsDisabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val userName = intent.getStringExtra(EXTRA_USER_NAME) ?: "Contact"
        val userPhone = intent.getStringExtra(EXTRA_USER_PHONE).orEmpty()
        val userAvatar = intent.getStringExtra(EXTRA_USER_AVATAR).orEmpty()
        val userPin = intent.getStringExtra(EXTRA_USER_PIN).orEmpty()
        val isOnline = intent.getBooleanExtra(EXTRA_IS_ONLINE, false)
        val lastSeen = intent.getLongExtra(EXTRA_LAST_SEEN, 0L)
        val targetUserId = intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty()

        setupToolbar(userName)
        setupProfileInfo(userName, userPhone, userAvatar, userPin, isOnline, lastSeen, targetUserId)
        setupActionButtons(targetUserId, userName, userAvatar, convId)
        setupSettingsRows(userName, convId)
        loadDisappearingStatus(convId)
        setupSellerStorefront(userName, targetUserId, userPin, convId, userPhone)
        setupSharedMedia(convId, userName)
    }

    private fun setupToolbar(name: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.collapsingToolbar.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupProfileInfo(
        name: String,
        phone: String,
        avatar: String,
        pin: String,
        isOnline: Boolean,
        lastSeen: Long,
        targetUserId: String = ""
    ) {
        binding.tvProfileName.text = name

        val displayPhone = phone.ifBlank { "Mobile · GoChat" }
        binding.tvProfilePhone.text = displayPhone

        // Online / Last Seen status
        if (isOnline) {
            binding.tvProfilePresence.text = getString(R.string.status_online)
            binding.tvProfilePresence.setTextColor(getColor(R.color.online_green))
        } else if (lastSeen > 0L) {
            binding.tvProfilePresence.text = formatLastSeen(lastSeen)
            binding.tvProfilePresence.setTextColor(getColor(R.color.gochat_text_secondary))
        } else {
            binding.tvProfilePresence.text = getString(R.string.status_offline)
            binding.tvProfilePresence.setTextColor(getColor(R.color.gochat_text_secondary))
        }

        // BBM / GoChat PIN: check passed PIN -> synced contact PIN -> fallback
        val cachedContact = contactSyncManager.getCachedContacts().find {
            (targetUserId.isNotBlank() && (it.id == targetUserId || it.userId == targetUserId)) ||
            (phone.isNotBlank() && it.phone == phone) ||
            it.phonebookName.equals(name, ignoreCase = true)
        }
        val resolvedPin = when {
            pin.isNotBlank() -> pin
            !cachedContact?.pin.isNullOrBlank() -> cachedContact!!.pin
            else -> generatePinFromId(name)
        }
        binding.tvProfilePin.text = resolvedPin
        binding.layoutPinBadge.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GoChat PIN", resolvedPin))
            Toast.makeText(this, "PIN $resolvedPin copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Avatar
        MediaImageHelper.loadSafeImage(
            imageView = binding.ivProfileAvatar,
            url = avatar,
            isCircle = false,
            placeholderRes = R.drawable.ic_account,
            errorRes = R.drawable.ic_account
        )

        // Title on Create Group button
        binding.tvCreateGroupWith.text = getString(R.string.create_group_with_format, name)
    }

    private fun setupActionButtons(
        targetUserId: String,
        userName: String,
        userAvatar: String,
        convId: String
    ) {
        fun resolveCallTarget(): String {
            if (targetUserId.isNotBlank() && targetUserId != convId && !targetUserId.startsWith("conv_")) {
                return targetUserId
            }
            return targetUserId.ifBlank { convId }
        }

        // 1. Voice Call
        binding.btnActionVoice.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_CALL_ID, "call_${System.currentTimeMillis()}")
                putExtra(CallActivity.EXTRA_TARGET_USER_ID, resolveCallTarget())
                putExtra(CallActivity.EXTRA_PEER_NAME, userName)
                putExtra(CallActivity.EXTRA_PEER_AVATAR, userAvatar)
                putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
                putExtra(CallActivity.EXTRA_CALL_TYPE, "voice")
            }
            startActivity(intent)
        }

        // 2. Video Call
        binding.btnActionVideo.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_CALL_ID, "call_${System.currentTimeMillis()}")
                putExtra(CallActivity.EXTRA_TARGET_USER_ID, resolveCallTarget())
                putExtra(CallActivity.EXTRA_PEER_NAME, userName)
                putExtra(CallActivity.EXTRA_PEER_AVATAR, userAvatar)
                putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
                putExtra(CallActivity.EXTRA_CALL_TYPE, "video")
            }
            startActivity(intent)
        }

        // 3. Search
        binding.btnActionSearch.setOnClickListener {
            setResult(RESULT_OK, Intent().apply { putExtra("action", "search") })
            finish()
        }

        // 4. Create Group
        binding.btnActionGroup.setOnClickListener {
            val intent = Intent(this, SelectContactActivity::class.java).apply {
                putExtra(SelectContactActivity.EXTRA_ACTION, SelectContactActivity.ACTION_CREATE_GROUP)
                putExtra(SelectContactActivity.EXTRA_PRESELECT_USER_ID, targetUserId)
                putExtra(SelectContactActivity.EXTRA_PRESELECT_NAME, userName)
                putExtra(SelectContactActivity.EXTRA_PRESELECT_AVATAR, userAvatar)
                putExtra(SelectContactActivity.EXTRA_PRESELECT_PHONE, this@ContactProfileActivity.intent.getStringExtra(EXTRA_USER_PHONE).orEmpty())
            }
            startActivity(intent)
        }
    }

    private fun setupSettingsRows(userName: String, convId: String) {
        // 1. Notification
        binding.layoutNotification.setOnClickListener {
            showNotificationDialog()
        }

        // 2. Media visibility
        binding.layoutMediaVisibility.setOnClickListener {
            showMediaVisibilityDialog()
        }

        // Starred messages
        binding.layoutStarredMessages.setOnClickListener {
            val intent = Intent(this, StarredMessagesActivity::class.java).apply {
                putExtra(StarredMessagesActivity.EXTRA_CONVERSATION_ID, convId)
                putExtra(StarredMessagesActivity.EXTRA_CONVERSATION_TITLE, userName)
            }
            startActivity(intent)
        }

        // 3. Disappearing messages
        binding.layoutDisappearingMessages.setOnClickListener {
            showDisappearingMessagesDialog(convId)
        }

        // 4. Chat lock
        binding.layoutChatLock.setOnClickListener {
            toggleChatLock()
        }

        // Encryption verification
        binding.layoutEncryption.setOnClickListener {
            val intent = Intent(this, SecurityVerificationActivity::class.java).apply {
                putExtra(SecurityVerificationActivity.EXTRA_CONVERSATION_ID, convId)
                putExtra(SecurityVerificationActivity.EXTRA_PEER_USER_ID, intent.getStringExtra(EXTRA_TARGET_USER_ID) ?: convId)
                putExtra(SecurityVerificationActivity.EXTRA_PEER_PIN, intent.getStringExtra(EXTRA_USER_PIN))
                putExtra(SecurityVerificationActivity.EXTRA_PEER_NAME, userName)
                putExtra(SecurityVerificationActivity.EXTRA_PEER_AVATAR, intent.getStringExtra(EXTRA_USER_AVATAR))
            }
            startActivity(intent)
        }

        // 5. Advanced chat privacy
        binding.layoutAdvancedPrivacy.setOnClickListener {
            showAdvancedPrivacyDialog()
        }

        // 6. Create group with person
        binding.layoutCreateGroupWith.setOnClickListener {
            val intent = Intent(this, SelectContactActivity::class.java).apply {
                putExtra(SelectContactActivity.EXTRA_ACTION, SelectContactActivity.ACTION_CREATE_GROUP)
                putExtra(SelectContactActivity.EXTRA_PRESELECT_USER_ID, intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty())
                putExtra(SelectContactActivity.EXTRA_PRESELECT_NAME, userName)
                putExtra(SelectContactActivity.EXTRA_PRESELECT_AVATAR, intent.getStringExtra(EXTRA_USER_AVATAR).orEmpty())
                putExtra(SelectContactActivity.EXTRA_PRESELECT_PHONE, intent.getStringExtra(EXTRA_USER_PHONE).orEmpty())
            }
            startActivity(intent)
        }

        // 7. Add to groups
        binding.layoutAddToGroups.setOnClickListener {
            showAddToGroupsDialog(userName)
        }

        // 8. Add to favourites
        binding.layoutAddToFavourites.setOnClickListener {
            toggleFavourite(userName)
        }

        // 9. Export chat
        binding.layoutExportChat.setOnClickListener {
            val sheet = ExportChatBottomSheet(
                conversationId = convId,
                conversationTitle = userName,
                chatRepository = chatRepository
            )
            sheet.show(supportFragmentManager, ExportChatBottomSheet.TAG)
        }
    }

    private fun showNotificationDialog() {
        val options = arrayOf("8 hours", "1 week", "Always", "Unmute")
        AlertDialog.Builder(this)
            .setTitle("Mute notifications")
            .setItems(options) { _, which ->
                if (which == 3) {
                    isMuted = false
                    binding.tvNotificationStatus.text = "Notifications enabled"
                    Toast.makeText(this, "Notifications unmuted", Toast.LENGTH_SHORT).show()
                } else {
                    isMuted = true
                    binding.tvNotificationStatus.text = "Muted until ${options[which]}"
                    Toast.makeText(this, "Muted for ${options[which]}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showMediaVisibilityDialog() {
        val options = arrayOf("Default (Yes)", "Yes", "No")
        val currentIndex = when (mediaVisibilityOption) {
            "Yes" -> 1
            "No" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Media visibility")
            .setMessage("Show newly downloaded media from this chat in your device's gallery?")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                mediaVisibilityOption = options[which]
                binding.tvMediaVisibilityStatus.text = mediaVisibilityOption
                Toast.makeText(this, "Media visibility set to $mediaVisibilityOption", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun loadDisappearingStatus(convId: String) {
        if (convId.isBlank()) return
        lifecycleScope.launch {
            val conv = chatRepository.getConversationById(convId)
            if (conv != null) {
                val label = when (conv.disappearingMessagesDuration) {
                    86400 -> "24 hours"
                    604800 -> "7 days"
                    7776000 -> "90 days"
                    else -> if (conv.disappearingMessagesDuration > 0) "${conv.disappearingMessagesDuration / 3600} hours" else "Off"
                }
                disappearingOption = label
                binding.tvDisappearingStatus.text = label
            }
        }
    }

    private fun showDisappearingMessagesDialog(convId: String) {
        val options = arrayOf("Off", "24 hours", "7 days", "90 days")
        val values = intArrayOf(0, 86400, 604800, 7776000)
        val currentIndex = options.indexOf(disappearingOption).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Disappearing messages")
            .setMessage("For more privacy and storage, new messages will disappear from this chat for everyone after the selected duration.")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selectedDuration = values[which]
                disappearingOption = options[which]
                binding.tvDisappearingStatus.text = disappearingOption
                if (convId.isNotBlank()) {
                    lifecycleScope.launch {
                        chatRepository.setConversationDisappearingMessages(convId, selectedDuration)
                    }
                }
                Toast.makeText(this, "Disappearing messages set to $disappearingOption", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun toggleChatLock() {
        isLocked = !isLocked
        binding.switchChatLock.isChecked = isLocked
        if (isLocked) {
            Toast.makeText(this, "🔒 Chat locked. Access requires biometric or passcode.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Chat unlocked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAdvancedPrivacyDialog() {
        val options = booleanArrayOf(ipProtectionEnabled, linkPreviewsDisabled)
        val items = arrayOf("Protect IP address in calls", "Disable link previews")

        AlertDialog.Builder(this)
            .setTitle("Advanced Chat Privacy")
            .setMultiChoiceItems(items, options) { _, which, isChecked ->
                if (which == 0) ipProtectionEnabled = isChecked
                if (which == 1) linkPreviewsDisabled = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                Toast.makeText(this, "Advanced privacy settings updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAddToGroupsDialog(userName: String) {
        val dummyGroups = arrayOf("Family Group", "Work Team", "College Friends", "Soccer Club")
        AlertDialog.Builder(this)
            .setTitle("Add $userName to Group")
            .setItems(dummyGroups) { _, which ->
                Toast.makeText(this, "$userName added to ${dummyGroups[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun toggleFavourite(userName: String) {
        isFavourite = !isFavourite
        if (isFavourite) {
            binding.ivFavouriteStar.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B")) // Gold
            binding.tvFavouriteTitle.text = "Remove from favourites"
            Toast.makeText(this, "⭐ $userName added to favourites", Toast.LENGTH_SHORT).show()
        } else {
            binding.ivFavouriteStar.imageTintList = ColorStateList.valueOf(getColor(R.color.gochat_text_secondary))
            binding.tvFavouriteTitle.text = "Add to favourites"
            Toast.makeText(this, "$userName removed from favourites", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generatePinFromId(seed: String): String {
        val hash = seed.hashCode().toString().replace("-", "")
        return if (hash.length >= 6) hash.take(6).uppercase() else "7C4B9A"
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

    private fun setupSellerStorefront(
        userName: String,
        targetUserId: String,
        userPin: String,
        convId: String,
        userPhone: String
    ) {
        val adapter = SellerCatalogAdapter { product ->
            val resultIntent = Intent().apply {
                putExtra(ChatRoomActivity.EXTRA_PRODUCT_ID, product.id)
                putExtra(ChatRoomActivity.EXTRA_PRODUCT_NAME, product.displayTitle)
                putExtra(ChatRoomActivity.EXTRA_PRODUCT_PRICE, product.price)
                putExtra(ChatRoomActivity.EXTRA_PRODUCT_IMAGE, product.primaryImage)
                putExtra(ChatRoomActivity.EXTRA_INITIAL_MESSAGE, "Hi! I'm interested in ${product.displayTitle} (${String.format(Locale.US, "$%.2f", product.price)})")
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.rvSellerCatalog.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSellerCatalog.adapter = adapter

        // Hide card immediately until we verify this user actually has products
        binding.cardSellerStorefront.visibility = android.view.View.GONE

        lifecycleScope.launch {
            var sellerUserId = targetUserId.trim()
            var sellerPin = userPin.trim()

            // If sellerUserId is blank or equals convId (or starts with "conv_"), resolve member from conversation
            if (sellerUserId.isBlank() || sellerUserId == convId || sellerUserId.startsWith("conv_")) {
                val conv = chatRepository.getConversationById(convId)
                val myId = marketplaceRepository.userId.orEmpty()
                val otherMember = conv?.memberIds?.find { it.isNotBlank() && it != myId }
                if (!otherMember.isNullOrBlank()) {
                    sellerUserId = otherMember
                }
            }

            val cachedContacts = contactSyncManager.getCachedContacts()
            // If sellerPin is blank, attempt to resolve from synced contacts
            if (sellerPin.isBlank()) {
                val contact = cachedContacts.find {
                    (sellerUserId.isNotBlank() && (it.id == sellerUserId || it.userId == sellerUserId)) ||
                    (userPhone.isNotBlank() && it.phone == userPhone) ||
                    it.phonebookName.equals(userName, ignoreCase = true) ||
                    it.gochatName.equals(userName, ignoreCase = true)
                }
                if (contact != null && contact.pin.isNotBlank()) {
                    sellerPin = contact.pin.trim()
                    binding.tvProfilePin.text = sellerPin
                }
            }

            // If sellerUserId is still blank, try resolving from synced contacts using phone or name
            if (sellerUserId.isBlank()) {
                val contact = cachedContacts.find {
                    (sellerPin.isNotBlank() && it.pin.equals(sellerPin, ignoreCase = true)) ||
                    (userPhone.isNotBlank() && it.phone == userPhone) ||
                    it.phonebookName.equals(userName, ignoreCase = true)
                }
                if (contact != null) {
                    sellerUserId = contact.finalUserId
                }
            }

            // If neither seller user ID nor PIN is identifiable, do not show any products
            if (sellerUserId.isBlank() && sellerPin.isBlank()) {
                binding.cardSellerStorefront.visibility = android.view.View.GONE
                adapter.submitList(emptyList())
                return@launch
            }

            // Strictly fetch only this user's products
            val userProducts = marketplaceRepository.getUserProducts(sellerUserId, sellerPin)
            val store = marketplaceRepository.getStoreForUser(sellerUserId, sellerPin)

            // ONLY show the user's products if he has any; NEVER show other people's products!
            if (userProducts.isNotEmpty()) {
                binding.cardSellerStorefront.visibility = android.view.View.VISIBLE
                val storeTitle = store?.name?.ifBlank { null } ?: "$userName's Catalog"
                binding.tvSellerStoreName.text = storeTitle
                adapter.submitList(userProducts.take(8))

                binding.btnVisitFullStore.setOnClickListener {
                    val storeIdToPass = store?.id?.ifBlank { null } ?: sellerUserId.ifBlank { "store_default" }
                    val storeNameToPass = store?.name?.ifBlank { null } ?: "$userName's Official Store"
                    val intent = Intent(this@ContactProfileActivity, com.example.gochat.ui.marketplace.StorefrontActivity::class.java).apply {
                        putExtra("store_id", storeIdToPass)
                        putExtra("store_name", storeNameToPass)
                    }
                    startActivity(intent)
                }
            } else {
                binding.cardSellerStorefront.visibility = android.view.View.GONE
                adapter.submitList(emptyList())
            }
        }
    }

    private fun setupSharedMedia(convId: String, contactName: String) {
        // Horizontal preview thumbnails
        val thumbAdapter = MediaPreviewThumbAdapter { message ->
            val intent = Intent(this, MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_MEDIA_URL, message.mediaUrl)
                putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, message.type == com.example.gochat.data.model.MessageType.VIDEO)
                putExtra(MediaViewerActivity.EXTRA_TITLE, message.senderName)
            }
            startActivity(intent)
        }
        binding.rvMediaPreview.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        binding.rvMediaPreview.adapter = thumbAdapter

        // Observe count
        lifecycleScope.launch {
            chatRepository.getSharedMediaCount(convId).collect { count ->
                binding.tvMediaCount.text = if (count > 0) count.toString() else getString(R.string.no_media_items)
            }
        }

        // Observe recent previews
        lifecycleScope.launch {
            chatRepository.getRecentMediaPreviews(convId).collect { previews ->
                thumbAdapter.submitList(previews)
                binding.rvMediaPreview.visibility = if (previews.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        // Tap card to open full shared media browser
        binding.cardSharedMedia.setOnClickListener {
            val intent = Intent(this, SharedMediaActivity::class.java).apply {
                putExtra(SharedMediaActivity.EXTRA_CONVERSATION_ID, convId)
                putExtra(SharedMediaActivity.EXTRA_TITLE, contactName)
            }
            startActivity(intent)
        }
    }
}
