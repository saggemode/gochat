package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.databinding.ItemMediaPreviewThumbBinding
import com.example.gochat.databinding.ItemSharedDocBinding
import com.example.gochat.databinding.ItemSharedLinkBinding
import com.example.gochat.databinding.ItemSharedMediaGridBinding
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem == newItem
}

/**
 * 3-Column Grid Adapter for shared photos and videos.
 */
class SharedMediaGridAdapter(
    private val onMediaClick: (Message) -> Unit
) : ListAdapter<Message, SharedMediaGridAdapter.MediaViewHolder>(MessageDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemSharedMediaGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MediaViewHolder(private val binding: ItemSharedMediaGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val url = message.mediaThumbnail ?: message.mediaUrl.orEmpty()
            MediaImageHelper.loadSafeImage(
                imageView = binding.ivMediaThumbnail,
                url = url,
                isCircle = false,
                placeholderRes = R.color.gochat_card_lighter,
                errorRes = R.drawable.ic_gallery
            )

            if (message.type == MessageType.VIDEO) {
                binding.layoutVideoOverlay.visibility = View.VISIBLE
                val durationSec = message.mediaDuration ?: 0
                if (durationSec > 0) {
                    val minutes = durationSec / 60
                    val seconds = durationSec % 60
                    binding.tvVideoDuration.text = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
                } else {
                    binding.tvVideoDuration.text = "VIDEO"
                }
            } else {
                binding.layoutVideoOverlay.visibility = View.GONE
            }

            binding.root.setOnClickListener { onMediaClick(message) }
        }
    }
}

/**
 * List Adapter for shared document files (PDFs, ZIPs, APKs, DOCs).
 */
class SharedDocsAdapter(
    private val onDocClick: (Message) -> Unit
) : ListAdapter<Message, SharedDocsAdapter.DocViewHolder>(MessageDiffCallback) {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val binding = ItemSharedDocBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DocViewHolder(private val binding: ItemSharedDocBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            // Determine filename: preference content, then URL segment
            val rawName = if (message.content.isNotBlank() && !message.content.startsWith("http")) {
                message.content
            } else {
                message.mediaUrl?.substringAfterLast('/')?.substringBefore('?') ?: "Document"
            }
            binding.tvDocName.text = rawName

            val sizeStr = formatFileSize(message.mediaSize)
            val dateStr = dateFormat.format(Date(message.createdAt))
            binding.tvDocSubtitle.text = "$sizeStr · $dateStr"

            val ext = rawName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            when (ext) {
                "pdf" -> binding.ivDocIcon.setImageResource(R.drawable.ic_attach_file)
                "zip", "rar", "7z" -> binding.ivDocIcon.setImageResource(R.drawable.ic_attach_file)
                "apk" -> binding.ivDocIcon.setImageResource(R.drawable.ic_attach_file)
                else -> binding.ivDocIcon.setImageResource(R.drawable.ic_attach_file)
            }

            binding.root.setOnClickListener { onDocClick(message) }
            binding.btnDownloadDoc.setOnClickListener { onDocClick(message) }
        }

        private fun formatFileSize(bytes: Long?): String {
            if (bytes == null || bytes <= 0L) return "File"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}

/**
 * Data item representing an extracted URL in a message.
 */
data class SharedLinkItem(
    val messageId: String,
    val url: String,
    val domain: String,
    val timestamp: Long
)

object LinkDiffCallback : DiffUtil.ItemCallback<SharedLinkItem>() {
    override fun areItemsTheSame(oldItem: SharedLinkItem, newItem: SharedLinkItem): Boolean =
        oldItem.messageId == newItem.messageId && oldItem.url == newItem.url
    override fun areContentsTheSame(oldItem: SharedLinkItem, newItem: SharedLinkItem): Boolean = oldItem == newItem
}

/**
 * List Adapter for shared links and URLs.
 */
class SharedLinksAdapter(
    private val onLinkClick: (String) -> Unit,
    private val onCopyClick: (String) -> Unit
) : ListAdapter<SharedLinkItem, SharedLinksAdapter.LinkViewHolder>(LinkDiffCallback) {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkViewHolder {
        val binding = ItemSharedLinkBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LinkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LinkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LinkViewHolder(private val binding: ItemSharedLinkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SharedLinkItem) {
            binding.tvLinkDomain.text = item.domain.ifBlank { "Web Link" }
            binding.tvLinkUrl.text = item.url
            binding.tvLinkDate.text = dateFormat.format(Date(item.timestamp))

            binding.root.setOnClickListener { onLinkClick(item.url) }
            binding.btnCopyLink.setOnClickListener { onCopyClick(item.url) }
        }
    }
}

/**
 * Horizontal preview thumbnail adapter for contact/group profile header strip.
 */
class MediaPreviewThumbAdapter(
    private val onThumbClick: (Message) -> Unit
) : ListAdapter<Message, MediaPreviewThumbAdapter.ThumbViewHolder>(MessageDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
        val binding = ItemMediaPreviewThumbBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ThumbViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ThumbViewHolder(private val binding: ItemMediaPreviewThumbBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val url = message.mediaThumbnail ?: message.mediaUrl.orEmpty()
            MediaImageHelper.loadSafeImage(
                imageView = binding.ivThumb,
                url = url,
                isCircle = false,
                placeholderRes = R.color.gochat_card_lighter,
                errorRes = R.drawable.ic_gallery
            )

            binding.ivVideoIcon.visibility = if (message.type == MessageType.VIDEO) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onThumbClick(message) }
        }
    }
}
