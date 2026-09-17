package com.example.gochat.ui.chat

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.ConversationType
import com.example.gochat.data.model.InvitationStatus
import com.example.gochat.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.*

class ConversationAdapter(
    private val onConversationClicked: (Conversation) -> Unit,
    private val onConversationLongClicked: ((Conversation) -> Unit)? = null
) : PagingDataAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(DiffCallback) {

    class ConversationViewHolder(val binding: ItemConversationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val conversation = getItem(position) ?: return
        with(holder.binding) {
            // Title / Name
            tvConversationTitle.text = conversation.title.ifBlank { "GoChat Contact" }

            val isLocked = com.example.gochat.core.security.ChatLockManager.isLocked(root.context, conversation.id)
            ivLockIndicator.visibility = if (isLocked) View.VISIBLE else View.GONE
            ivDisappearingIndicator.visibility = if (conversation.disappearingMessagesDuration > 0) View.VISIBLE else View.GONE

            // Last message preview & invitation status
            val preview = if (isLocked && !com.example.gochat.core.security.ChatLockManager.isSessionUnlocked(conversation.id)) {
                "🔒 Locked chat · Tap to unlock"
            } else {
                when (conversation.invitationStatus) {
                    InvitationStatus.PENDING_INCOMING -> "🤝 Incoming invitation · Tap to accept"
                    InvitationStatus.PENDING_OUTGOING -> "⏳ Invitation sent · Waiting for acceptance"
                    else -> conversation.lastMessageText?.ifBlank { "No messages yet" } ?: "No messages yet"
                }
            }
            tvLastMessage.text = preview

            // Formatted timestamp
            val timeMillis = conversation.lastMessageTime ?: conversation.updatedAt
            tvTimestamp.text = formatTimestamp(timeMillis)

            // Unread Count Badge
            if (conversation.unreadCount > 0) {
                tvUnreadBadge.visibility = View.VISIBLE
                tvUnreadBadge.text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
            } else {
                tvUnreadBadge.visibility = View.GONE
            }

            // Online indicator dot
            viewOnlineDot.visibility = if (conversation.isOnline && conversation.isDirect) {
                View.VISIBLE
            } else {
                View.GONE
            }

            // Pinned & Muted indicators
            ivPinned.visibility = if (conversation.isPinned) View.VISIBLE else View.GONE
            ivMuted.visibility = if (conversation.isMuted) View.VISIBLE else View.GONE

            // Avatar loading with Coil
            MediaImageHelper.loadSafeImage(
                imageView = ivAvatar,
                url = conversation.avatarUrl,
                isCircle = true,
                placeholderRes = R.drawable.ic_account,
                errorRes = R.drawable.ic_account
            )

            // Click Handlers
            root.setOnClickListener {
                onConversationClicked(conversation)
            }

            root.setOnLongClickListener {
                onConversationLongClicked?.invoke(conversation)
                true
            }
        }
    }

    private fun formatTimestamp(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val nowCalendar = Calendar.getInstance().apply { timeInMillis = now }

        return when {
            // Today -> "hh:mm a" (e.g. 03:45 PM)
            calendar.get(Calendar.YEAR) == nowCalendar.get(Calendar.YEAR) &&
            calendar.get(Calendar.DAY_OF_YEAR) == nowCalendar.get(Calendar.DAY_OF_YEAR) -> {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timeMillis))
            }
            // Yesterday -> "Yesterday"
            now - timeMillis < 48 * 60 * 60 * 1000L &&
            calendar.get(Calendar.DAY_OF_YEAR) == nowCalendar.get(Calendar.DAY_OF_YEAR) - 1 -> {
                "Yesterday"
            }
            // Older -> "MMM d" (e.g. Sep 4)
            else -> {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timeMillis))
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem == newItem
        }
    }
}
