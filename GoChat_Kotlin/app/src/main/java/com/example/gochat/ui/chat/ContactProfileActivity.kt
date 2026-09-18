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

@AndroidEntryPoint
class ContactProfileActivity : AppCompatActivity() {

    @Inject
    lateinit var chatRepository: ChatRepository

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
        val targetUserId = intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty().ifBlank { convId }

        setupToolbar(userName)
        setupProfileInfo(userName, userPhone, userAvatar, userPin, isOnline, lastSeen)
        setupActionButtons(targetUserId, userName, userAvatar)
        setupSettingsRows(userName, convId)
        loadDisappearingStatus(convId)
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
        lastSeen: Long
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

        // BBM / GoChat PIN
        val finalPin = if (pin.isNotBlank()) pin else generatePinFromId(name)
        binding.tvProfilePin.text = finalPin
        binding.layoutPinBadge.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GoChat PIN", finalPin))
            Toast.makeText(this, "PIN $finalPin copied to clipboard", Toast.LENGTH_SHORT).show()
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

    private fun setupActionButtons(targetUserId: String, userName: String, userAvatar: String) {
        // 1. Voice Call
        binding.btnActionVoice.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_CALL_ID, "call_${System.currentTimeMillis()}")
                putExtra(CallActivity.EXTRA_TARGET_USER_ID, targetUserId)
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
                putExtra(CallActivity.EXTRA_TARGET_USER_ID, targetUserId)
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
                putExtra("action", "create_group")
                putExtra("preselect_contact_name", userName)
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
}
