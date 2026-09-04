package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.gochat.R
import com.example.gochat.core.media.AudioPlayerManager
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageStatus
import com.example.gochat.data.model.MessageType
import com.example.gochat.databinding.ItemMessageMeBinding
import com.example.gochat.databinding.ItemMessageOtherBinding
import android.widget.SeekBar
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val onReplyClicked: (Message) -> Unit,
    private val onMessageLongClicked: ((Message) -> Unit)? = null,
    private val onPlayVoiceClicked: ((Message) -> Unit)? = null
) : ListAdapter<Message, RecyclerView.ViewHolder>(DiffCallback) {

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
        AudioPlayerManager.onPlaybackStateChanged = { msgId, _ ->
            val pos = currentList.indexOfFirst { it.id == msgId }
            if (pos != -1) notifyItemChanged(pos)
        }
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    companion object {
        private const val VIEW_TYPE_ME = 1
        private const val VIEW_TYPE_OTHER = 2
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message.isMe) VIEW_TYPE_ME else VIEW_TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ME) {
            val binding = ItemMessageMeBinding.inflate(inflater, parent, false)
            MessageMeViewHolder(binding)
        } else {
            val binding = ItemMessageOtherBinding.inflate(inflater, parent, false)
            MessageOtherViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        if (holder is MessageMeViewHolder) {
            holder.bind(message)
        } else if (holder is MessageOtherViewHolder) {
            holder.bind(message)
        }
    }

    inner class MessageMeViewHolder(private val binding: ItemMessageMeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            with(binding) {
                // Content text
                if (message.isPing) {
                    tvMessageContent.text = "💥 PING!!!"
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_emerald_light))
                } else {
                    tvMessageContent.text = message.content
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_text_primary))
                }
                tvMessageContent.visibility = if (message.content.isNotEmpty() || message.isPing) View.VISIBLE else View.GONE

                // Timestamp
                tvMessageTime.text = formatTime(message.createdAt)

                // Read Receipt status icon
                ivMessageStatus.visibility = View.VISIBLE
                when (message.status) {
                    MessageStatus.SENDING -> ivMessageStatus.setImageResource(R.drawable.ic_status_sent)
                    MessageStatus.SENT -> ivMessageStatus.setImageResource(R.drawable.ic_status_sent)
                    MessageStatus.DELIVERED -> ivMessageStatus.setImageResource(R.drawable.ic_status_delivered)
                    MessageStatus.READ -> ivMessageStatus.setImageResource(R.drawable.ic_status_read)
                    MessageStatus.FAILED -> ivMessageStatus.setImageResource(R.drawable.ic_error)
                }

                // Quoted Reply container
                if (!message.replyToId.isNullOrBlank()) {
                    layoutQuotedReply.visibility = View.VISIBLE
                    tvQuotedSender.text = message.replyToSenderName ?: "Original Message"
                    tvQuotedText.text = message.replyToText ?: ""
                } else {
                    layoutQuotedReply.visibility = View.GONE
                }

                // Media Image Preview
                if (message.type == MessageType.IMAGE && !message.mediaUrl.isNullOrBlank()) {
                    ivMessageImage.visibility = View.VISIBLE
                    ivMessageImage.load(message.mediaUrl) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(12f))
                    }
                } else {
                    ivMessageImage.visibility = View.GONE
                }

                // Voice Note Row
                if (message.type == MessageType.VOICE || message.type == MessageType.AUDIO) {
                    layoutVoiceNote.visibility = View.VISIBLE
                    val isPlayingThis = AudioPlayerManager.currentPlayingMessageId == message.id && AudioPlayerManager.isPlaying
                    btnPlayPauseVoice.setImageResource(if (isPlayingThis) R.drawable.ic_pause else R.drawable.ic_play)
                    tvVoiceDuration.text = message.mediaDuration?.let { formatDuration(it) } ?: "0:14"

                    btnPlayPauseVoice.setOnClickListener {
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (audioUrl.isNotBlank()) {
                            AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                        } else {
                            onPlayVoiceClicked?.invoke(message)
                        }
                    }

                    sbVoiceProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser && AudioPlayerManager.currentPlayingMessageId == message.id) {
                                val total = message.mediaDuration ?: 10
                                AudioPlayerManager.seekTo((progress / 100f * total * 1000).toInt())
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                } else {
                    layoutVoiceNote.visibility = View.GONE
                }

                // Long Click / Swipe to reply
                root.setOnLongClickListener {
                    onMessageLongClicked?.invoke(message)
                    true
                }
            }
        }
    }

    inner class MessageOtherViewHolder(private val binding: ItemMessageOtherBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            with(binding) {
                // Sender name
                if (message.senderName.isNotBlank()) {
                    tvSenderName.visibility = View.VISIBLE
                    tvSenderName.text = message.senderName
                } else {
                    tvSenderName.visibility = View.GONE
                }

                // Content text
                if (message.isPing) {
                    tvMessageContent.text = "💥 PING!!!"
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_emerald_light))
                } else {
                    tvMessageContent.text = message.content
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_text_primary))
                }
                tvMessageContent.visibility = if (message.content.isNotEmpty() || message.isPing) View.VISIBLE else View.GONE

                // Timestamp
                tvMessageTime.text = formatTime(message.createdAt)

                // Quoted Reply container
                if (!message.replyToId.isNullOrBlank()) {
                    layoutQuotedReply.visibility = View.VISIBLE
                    tvQuotedSender.text = message.replyToSenderName ?: "Original Message"
                    tvQuotedText.text = message.replyToText ?: ""
                } else {
                    layoutQuotedReply.visibility = View.GONE
                }

                // Media Image Preview
                if (message.type == MessageType.IMAGE && !message.mediaUrl.isNullOrBlank()) {
                    ivMessageImage.visibility = View.VISIBLE
                    ivMessageImage.load(message.mediaUrl) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(12f))
                    }
                } else {
                    ivMessageImage.visibility = View.GONE
                }

                // Voice Note Row
                if (message.type == MessageType.VOICE || message.type == MessageType.AUDIO) {
                    layoutVoiceNote.visibility = View.VISIBLE
                    val isPlayingThis = AudioPlayerManager.currentPlayingMessageId == message.id && AudioPlayerManager.isPlaying
                    btnPlayPauseVoice.setImageResource(if (isPlayingThis) R.drawable.ic_pause else R.drawable.ic_play)
                    tvVoiceDuration.text = message.mediaDuration?.let { formatDuration(it) } ?: "0:14"

                    btnPlayPauseVoice.setOnClickListener {
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (audioUrl.isNotBlank()) {
                            AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                        } else {
                            onPlayVoiceClicked?.invoke(message)
                        }
                    }

                    sbVoiceProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser && AudioPlayerManager.currentPlayingMessageId == message.id) {
                                val total = message.mediaDuration ?: 10
                                AudioPlayerManager.seekTo((progress / 100f * total * 1000).toInt())
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                } else {
                    layoutVoiceNote.visibility = View.GONE
                }

                // Long Click / Swipe to reply
                root.setOnLongClickListener {
                    onMessageLongClicked?.invoke(message)
                    true
                }
            }
        }
    }

    private fun formatTime(timeMillis: Long): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun formatDuration(durationSeconds: Int): String {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    object DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
}
