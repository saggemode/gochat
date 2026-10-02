package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.databinding.ItemStarredMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StarredMessagesAdapter(
    private val onItemClick: (Message) -> Unit,
    private val onUnstarClick: (Message) -> Unit
) : ListAdapter<Message, StarredMessagesAdapter.ViewHolder>(DiffCallback) {

    var conversationTitles: Map<String, String> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStarredMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemStarredMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val author = if (message.isMe) "You" else message.senderName.ifBlank { "Contact" }
            val chatTitle = conversationTitles[message.conversationId]
            if (!chatTitle.isNullOrBlank() && !chatTitle.equals(author, ignoreCase = true)) {
                binding.tvSenderName.text = "$author • $chatTitle"
            } else {
                binding.tvSenderName.text = author
            }
            binding.tvMessageTime.text = formatTimestamp(message.createdAt)

            val snippet = if (message.isDeleted) {
                "🚫 This message was deleted"
            } else if (isBase64Ciphertext(message.content)) {
                "🔒 Encrypted message"
            } else {
                when (message.type) {
                    MessageType.TEXT -> message.content
                    MessageType.IMAGE -> if (message.content.isNotBlank() && !message.content.startsWith("http")) message.content else "📷 Photo"
                    MessageType.VIDEO -> if (message.content.isNotBlank() && !message.content.startsWith("http")) message.content else "🎥 Video"
                    MessageType.VOICE, MessageType.AUDIO -> "🎵 Voice note"
                    MessageType.FILE -> "📄 Document: ${message.content}"
                    MessageType.POLL -> "📊 Poll: ${message.content}"
                    MessageType.CATALOG -> "🛍️ Product Catalog"
                    MessageType.PRODUCT -> "🛒 Product inquiry"
                    MessageType.ORDER -> "📦 Order update"
                    MessageType.PAYMENT_REQUEST -> "💳 Payment Request"
                    MessageType.LOCATION -> "📍 Location"
                    MessageType.CONTACT -> "👤 Contact card"
                    else -> message.content.ifBlank { "Message" }
                }
            }
            binding.tvMessageSnippet.text = snippet

            // Media preview thumbnail
            if ((message.type == MessageType.IMAGE || message.type == MessageType.VIDEO) && !message.mediaUrl.isNullOrBlank()) {
                binding.ivMediaThumbnail.visibility = View.VISIBLE
                binding.ivMediaThumbnail.load(message.mediaUrl) {
                    crossfade(true)
                }
            } else {
                binding.ivMediaThumbnail.visibility = View.GONE
            }

            binding.btnUnstar.setOnClickListener {
                onUnstarClick(message)
            }

            binding.btnViewInChat.setOnClickListener {
                onItemClick(message)
            }

            binding.root.setOnClickListener {
                onItemClick(message)
            }
        }

        private fun formatTimestamp(timeMs: Long): String {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(timeMs))
        }

        private fun isBase64Ciphertext(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.length < 44 || trimmed.contains(" ") || trimmed.contains("\n")) return false
            if (!trimmed.matches(Regex("^[A-Za-z0-9+/=]+$"))) return false
            return try {
                android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean =
            oldItem == newItem
    }
}
