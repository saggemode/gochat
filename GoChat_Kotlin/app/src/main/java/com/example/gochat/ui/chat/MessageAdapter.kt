package com.example.gochat.ui.chat

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.example.gochat.R
import com.example.gochat.core.media.AudioPlayerManager
import com.example.gochat.core.wallpaper.BubbleShape
import com.example.gochat.core.wallpaper.ChatBubbleHelper
import com.example.gochat.core.wallpaper.ChatTheme
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageStatus
import com.example.gochat.data.model.MessageType
import com.example.gochat.databinding.ItemMessageMeBinding
import com.example.gochat.databinding.ItemMessageOtherBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

import kotlinx.serialization.json.*


import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.core.utils.LinkPreview
import com.example.gochat.core.utils.LinkPreviewManager
import com.example.gochat.ui.marketplace.ProductDetailsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessageAdapter(
    private val onReplyClicked: (Message) -> Unit,
    private val onMessageLongClicked: ((Message) -> Unit)? = null,
    private val onPlayVoiceClicked: ((Message) -> Unit)? = null
) : PagingDataAdapter<Message, RecyclerView.ViewHolder>(DiffCallback) {

    private val adapterScope = CoroutineScope(Dispatchers.Main)
    private val linkCache = LruCache<String, LinkPreview>(50)
    private val linkFetching = mutableSetOf<String>()

    var onImageClicked: ((String) -> Unit)? = null
    var onBuyNowClicked: ((productId: String, productName: String, price: Double, image: String) -> Unit)? = null
    private var accentColor: Int = 0xFF00A884.toInt() // Default emerald
    private var bubbleShape: BubbleShape = BubbleShape.CLASSIC

    fun setBubbleTheme(shape: BubbleShape, accent: Int) {
        var changed = false
        if (this.bubbleShape != shape) {
            this.bubbleShape = shape
            changed = true
        }
        if (this.accentColor != accent) {
            this.accentColor = accent
            changed = true
        }
        if (changed) {
            notifyDataSetChanged()
        }
    }

    private val mentionPattern = Pattern.compile("@[\\w]+")

    private fun highlightMentions(text: String, color: Int): CharSequence {
        val spannable = SpannableString(text)
        val matcher = mentionPattern.matcher(text)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
        
        AudioPlayerManager.onPlaybackStateChanged = { msgId, _ ->
            val pos = (0 until itemCount).indexOfFirst { getItem(it)?.id == msgId }
            if (pos != -1) notifyItemChanged(pos)
        }

        AudioPlayerManager.onProgressUpdate = { msgId, currentPos, total ->
            val pos = (0 until itemCount).indexOfFirst { getItem(it)?.id == msgId }
            if (pos != -1) {
                val holder = recyclerView?.findViewHolderForAdapterPosition(pos)
                val progress = currentPos.toFloat() / total.coerceAtLeast(1)
                if (holder is MessageMeViewHolder) {
                    holder.updateWaveform(progress)
                } else if (holder is MessageOtherViewHolder) {
                    holder.updateWaveform(progress)
                }
            }
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
        return if (message?.isMe == true) VIEW_TYPE_ME else VIEW_TYPE_OTHER
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
        val message = getItem(position) ?: return
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
                // Apply dynamic bubble shape & background
                layoutBubbleContainer.background = ChatBubbleHelper.getBubbleDrawable(
                    context = root.context,
                    isMe = true,
                    shape = bubbleShape,
                    accentColor = accentColor
                )

                val textColor = ChatBubbleHelper.getMessageTextColor(isMe = true, shape = bubbleShape)
                val timeColor = ChatBubbleHelper.getTimestampTextColor(isMe = true, shape = bubbleShape)

                // Forwarded status
                layoutForwarded.visibility = if (message.isForwarded) View.VISIBLE else View.GONE

                val isImage = (message.type == MessageType.IMAGE || message.content.contains("Photo", ignoreCase = true)) && 
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()
                val isVoice = (message.type == MessageType.VOICE || message.type == MessageType.AUDIO || message.content.contains("Voice Note", ignoreCase = true)) && 
                              !message.isDeleted
                val isProduct = (message.type == MessageType.PRODUCT || (message.content.contains("\"product\"") && message.content.contains("\"price\""))) && !message.isDeleted
                val isOrder = (message.type == MessageType.ORDER || message.content.contains("\"order_number\"") || message.content.contains("\"type\":\"order\"")) && !message.isDeleted

                // Content text
                if (message.isDeleted) {
                    tvMessageContent.text = binding.root.context.getString(R.string.message_deleted_notice)
                    tvMessageContent.setTypeface(null, Typeface.ITALIC)
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_text_secondary))
                } else if (message.isPing) {
                    tvMessageContent.text = binding.root.context.getString(R.string.ping_message)
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_emerald_light))
                    tvMessageContent.setTypeface(null, Typeface.NORMAL)
                } else if (message.content.startsWith("📍 Location:")) {
                    tvMessageContent.text = highlightMentions(message.content, accentColor)
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_emerald_light))
                    tvMessageContent.setOnClickListener {
                        val uri = Uri.parse(message.content.substringAfter("Location: ").trim())
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        binding.root.context.startActivity(intent)
                    }
                } else {
                    tvMessageContent.text = highlightMentions(message.content, accentColor)
                    tvMessageContent.setTextColor(textColor)
                    tvMessageContent.setTypeface(null, Typeface.NORMAL)
                    tvMessageContent.setOnClickListener(null)
                    
                    // Link Preview logic (disabled for media, products, and orders)
                    if (!isVoice && !isImage && !isProduct && !isOrder) {
                        handleLinkPreview(message.content, binding)
                    } else {
                        updateLinkPreviewVisibility(binding, null)
                    }
                }

                // Product Card Row
                if (isProduct) {
                    layoutProductCard.visibility = View.VISIBLE
                    try {
                        val productObj = Json.decodeFromString<JsonObject>(message.content)
                        val prodData = productObj["product"]?.jsonObject ?: productObj
                        val inquiry = productObj["inquiry"]?.jsonPrimitive?.contentOrNull ?: ""
                        
                        val prodName = prodData["name"]?.jsonPrimitive?.contentOrNull
                            ?: prodData["title"]?.jsonPrimitive?.contentOrNull ?: "Product"
                        val prodPrice = prodData["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val prodImage = prodData["image"]?.jsonPrimitive?.contentOrNull
                            ?: prodData["imageUrl"]?.jsonPrimitive?.contentOrNull
                        val prodId = prodData["id"]?.jsonPrimitive?.contentOrNull

                        tvProductCardTitle.text = prodName
                        tvProductCardPrice.text = String.format(Locale.US, "$%.2f", prodPrice)
                        
                        MediaImageHelper.loadSafeImage(
                            imageView = ivProductCardImage,
                            url = prodImage,
                            cornerRadiusDp = 8f
                        )

                        tvMessageContent.text = inquiry
                        tvMessageContent.visibility = if (inquiry.isNotEmpty()) View.VISIBLE else View.GONE
                        
                        btnViewProductCard.setOnClickListener {
                            prodId?.let { id ->
                                val intent = Intent(root.context, ProductDetailsActivity::class.java).apply {
                                    putExtra("product_id", id)
                                }
                                root.context.startActivity(intent)
                            }
                        }

                        btnBuyNowProductCard.setOnClickListener {
                            prodId?.let { id ->
                                onBuyNowClicked?.invoke(id, prodName, prodPrice, prodImage.orEmpty()) ?: run {
                                    val intent = Intent(root.context, ProductDetailsActivity::class.java).apply {
                                        putExtra("product_id", id)
                                        putExtra("auto_buy", true)
                                    }
                                    root.context.startActivity(intent)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        layoutProductCard.visibility = View.GONE
                    }
                } else {
                    layoutProductCard.visibility = View.GONE
                }

                // Order Card Row
                if (isOrder) {
                    layoutOrderCard.visibility = View.VISIBLE
                    try {
                        val orderObj = Json.decodeFromString<JsonObject>(message.content)
                        val orderData = orderObj["order"]?.jsonObject ?: orderObj

                        val orderNum = orderData["order_number"]?.jsonPrimitive?.contentOrNull ?: "ORD"
                        val orderStatus = orderData["status"]?.jsonPrimitive?.contentOrNull ?: "PAID"
                        val storeName = orderData["store_name"]?.jsonPrimitive?.contentOrNull ?: "Official Store"
                        val summary = orderData["items_summary"]?.jsonPrimitive?.contentOrNull ?: "Marketplace Order"
                        val totalAmount = orderData["total_amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val orderId = orderData["id"]?.jsonPrimitive?.contentOrNull ?: ""

                        tvOrderCardNumber.text = "#$orderNum"
                        tvOrderCardStatus.text = orderStatus.uppercase(Locale.ROOT)
                        tvOrderCardStore.text = storeName
                        tvOrderCardSummary.text = summary
                        tvOrderCardTotal.text = String.format(Locale.US, "$%.2f", totalAmount)

                        btnViewOrderCard.setOnClickListener {
                            val intent = Intent(root.context, com.example.gochat.ui.marketplace.OrdersActivity::class.java).apply {
                                putExtra("highlight_order_id", orderId)
                            }
                            root.context.startActivity(intent)
                        }

                        tvMessageContent.visibility = View.GONE
                    } catch (e: Exception) {
                        layoutOrderCard.visibility = View.GONE
                    }
                } else {
                    layoutOrderCard.visibility = View.GONE
                }

                // Hide the text label if it's purely a media label
                val isOnlyMediaLabel = message.content.isBlank() || 
                    message.content.contains("Photo", ignoreCase = true) || 
                    message.content.contains("Voice Note", ignoreCase = true)

                if ((isImage || isVoice) && isOnlyMediaLabel) {
                    tvMessageContent.visibility = View.GONE
                } else {
                    tvMessageContent.visibility = if (message.content.isNotEmpty() || message.isPing || message.isDeleted) View.VISIBLE else View.GONE
                }

                // Edited status
                tvEdited.visibility = if (message.isEdited && !message.isDeleted) View.VISIBLE else View.GONE

                // Starred status
                ivStarred.visibility = if (message.isStarred) View.VISIBLE else View.GONE

                // Reactions
                if (message.reactions.isNotEmpty() && !message.isDeleted) {
                    layoutReactions.visibility = View.VISIBLE
                    val reactionText = message.reactions.groupBy { it.emoji }
                        .map { "${it.key} ${it.value.size}" }
                        .joinToString("  ")
                    tvReactions.text = reactionText
                } else {
                    layoutReactions.visibility = View.GONE
                }

                // Timestamp
                tvMessageTime.text = formatTime(message.createdAt)
                tvMessageTime.setTextColor(timeColor)

                // Read Receipt status icon
                ivMessageStatus.visibility = if (message.isDeleted) View.GONE else View.VISIBLE
                when (message.status) {
                    MessageStatus.SENDING -> ivMessageStatus.setImageResource(R.drawable.ic_status_pending)
                    MessageStatus.SENT -> ivMessageStatus.setImageResource(R.drawable.ic_status_sent)
                    MessageStatus.DELIVERED -> ivMessageStatus.setImageResource(R.drawable.ic_status_delivered)
                    MessageStatus.READ -> ivMessageStatus.setImageResource(R.drawable.ic_status_read)
                    MessageStatus.FAILED -> ivMessageStatus.setImageResource(R.drawable.ic_error)
                }

                // Quoted Reply container
                if (!message.replyToId.isNullOrBlank() && !message.isDeleted) {
                    layoutQuotedReply.visibility = View.VISIBLE
                    tvQuotedSender.text = message.replyToSenderName ?: binding.root.context.getString(R.string.original_message_label)
                    tvQuotedText.text = message.replyToText ?: ""
                } else {
                    layoutQuotedReply.visibility = View.GONE
                }

                // Media Image Preview
                if (isImage) {
                    layoutImageContainer.visibility = View.VISIBLE
                    val isSending = message.status == MessageStatus.SENDING
                    pbImageLoadingContainer.visibility = if (isSending) View.VISIBLE else View.GONE

                    if (isSending && !message.blurHash.isNullOrBlank()) {
                        // WhatsApp-style: show blurry preview while uploading
                        val blurBitmap = com.example.gochat.core.utils.BlurHashUtil.decode(message.blurHash, 32, 32)
                        if (blurBitmap != null) {
                            ivMessageImage.setImageDrawable(
                                android.graphics.drawable.BitmapDrawable(itemView.context.resources, blurBitmap)
                            )
                        } else {
                            ivMessageImage.setImageResource(R.drawable.ic_gallery)
                        }
                        ivMessageImage.alpha = 1.0f
                    } else {
                        // Load real image (with blur as placeholder for crossfade)
                        ivMessageImage.alpha = if (isSending) 0.6f else 1.0f
                        MediaImageHelper.loadSafeImage(
                            imageView = ivMessageImage,
                            url = message.mediaUrl,
                            isCircle = false,
                            cornerRadiusDp = 12f,
                            placeholderRes = R.drawable.ic_gallery,
                            errorRes = R.drawable.ic_gallery,
                            blurHash = message.blurHash
                        )
                    }
                    
                    // Interaction
                    layoutImageContainer.setOnClickListener {
                        message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                    }
                    ivMessageImage.setOnClickListener {
                        message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                    }
                } else {
                    layoutImageContainer.visibility = View.GONE
                    layoutImageContainer.setOnClickListener(null)
                    ivMessageImage.setOnClickListener(null)
                }

                // Voice Note Row
                if (isVoice) {
                    layoutVoiceNote.visibility = View.VISIBLE
                    val isPlayingThis = AudioPlayerManager.currentPlayingMessageId == message.id && AudioPlayerManager.isPlaying
                    btnPlayPauseVoice.setImageResource(if (isPlayingThis) R.drawable.ic_pause else R.drawable.ic_play)
                    tvVoiceDuration.text = message.mediaDuration?.let { formatDuration(it) } ?: "0:00"

                    // Mock waveform
                    val mockWaveform = List(30) { (0.2f + (0.8f * Math.random().toFloat())) }
                    waveformVoice.setWaveform(mockWaveform)
                    
                    if (!isPlayingThis) {
                        waveformVoice.setProgress(0f)
                    }

                    btnPlayPauseVoice.setOnClickListener {
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (audioUrl.isNotBlank()) {
                            AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                        } else {
                            onPlayVoiceClicked?.invoke(message)
                        }
                    }

                    btnVoiceSpeed.text = if (isPlayingThis) "${AudioPlayerManager.playbackSpeed}x" else "1.0x"
                    btnVoiceSpeed.setOnClickListener {
                        if (isPlayingThis) {
                            val nextSpeed = when (AudioPlayerManager.playbackSpeed) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            AudioPlayerManager.setSpeed(nextSpeed)
                            btnVoiceSpeed.text = "${nextSpeed}x"
                        }
                    }
                } else {
                    layoutVoiceNote.visibility = View.GONE
                }

                // Long Click / Swipe to reply
                root.setOnLongClickListener {
                    if (!message.isDeleted) {
                        onMessageLongClicked?.invoke(message)
                    }
                    true
                }
            }
        }
        fun updateWaveform(progress: Float) {
            binding.waveformVoice.setProgress(progress)
        }
    }

    inner class MessageOtherViewHolder(private val binding: ItemMessageOtherBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            with(binding) {
                // Apply dynamic bubble shape & background
                layoutBubbleContainer.background = ChatBubbleHelper.getBubbleDrawable(
                    context = root.context,
                    isMe = false,
                    shape = bubbleShape,
                    accentColor = accentColor
                )

                val textColor = ChatBubbleHelper.getMessageTextColor(isMe = false, shape = bubbleShape)
                val timeColor = ChatBubbleHelper.getTimestampTextColor(isMe = false, shape = bubbleShape)

                // Forwarded status
                layoutForwarded.visibility = if (message.isForwarded) View.VISIBLE else View.GONE

                val isImage = (message.type == MessageType.IMAGE || message.content.contains("Photo", ignoreCase = true)) && 
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()
                val isVoice = (message.type == MessageType.VOICE || message.type == MessageType.AUDIO || message.content.contains("Voice Note", ignoreCase = true)) && 
                              !message.isDeleted
                val isProduct = (message.type == MessageType.PRODUCT || (message.content.contains("\"product\"") && message.content.contains("\"price\""))) && !message.isDeleted
                val isOrder = (message.type == MessageType.ORDER || message.content.contains("\"order_number\"") || message.content.contains("\"type\":\"order\"")) && !message.isDeleted

                // Sender name
                if (message.senderName.isNotBlank()) {
                    tvSenderName.visibility = View.VISIBLE
                    tvSenderName.text = message.senderName
                } else {
                    tvSenderName.visibility = View.GONE
                }

                // Content text
                if (message.isDeleted) {
                    tvMessageContent.text = binding.root.context.getString(R.string.message_deleted_notice)
                    tvMessageContent.setTypeface(null, Typeface.ITALIC)
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_text_secondary))
                } else if (message.isPing) {
                    tvMessageContent.text = binding.root.context.getString(R.string.ping_message)
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_emerald_light))
                    tvMessageContent.setTypeface(null, Typeface.NORMAL)
                } else if (message.content.startsWith("📍 Location:")) {
                    tvMessageContent.text = highlightMentions(message.content, accentColor)
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_emerald_light))
                    tvMessageContent.setOnClickListener {
                        val uri = Uri.parse(message.content.substringAfter("Location: ").trim())
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        binding.root.context.startActivity(intent)
                    }
                } else {
                    tvMessageContent.text = highlightMentions(message.content, accentColor)
                    tvMessageContent.setTextColor(textColor)
                    tvMessageContent.setTypeface(null, Typeface.NORMAL)
                    tvMessageContent.setOnClickListener(null)
                    
                    // Link Preview logic
                    if (!isVoice && !isImage && !isProduct && !isOrder) {
                        handleLinkPreview(message.content, binding)
                    } else {
                        updateLinkPreviewVisibility(binding, null)
                    }
                }

                // Product Card Row
                if (isProduct) {
                    layoutProductCard.visibility = View.VISIBLE
                    try {
                        val productObj = Json.decodeFromString<JsonObject>(message.content)
                        val prodData = productObj["product"]?.jsonObject ?: productObj
                        val inquiry = productObj["inquiry"]?.jsonPrimitive?.contentOrNull ?: ""
                        
                        val prodName = prodData["name"]?.jsonPrimitive?.contentOrNull
                            ?: prodData["title"]?.jsonPrimitive?.contentOrNull ?: "Product"
                        val prodPrice = prodData["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val prodImage = prodData["image"]?.jsonPrimitive?.contentOrNull
                            ?: prodData["imageUrl"]?.jsonPrimitive?.contentOrNull
                        val prodId = prodData["id"]?.jsonPrimitive?.contentOrNull

                        tvProductCardTitle.text = prodName
                        tvProductCardPrice.text = String.format(Locale.US, "$%.2f", prodPrice)
                        
                        MediaImageHelper.loadSafeImage(
                            imageView = ivProductCardImage,
                            url = prodImage,
                            cornerRadiusDp = 8f
                        )

                        tvMessageContent.text = inquiry
                        tvMessageContent.visibility = if (inquiry.isNotEmpty()) View.VISIBLE else View.GONE
                        
                        btnViewProductCard.setOnClickListener {
                            prodId?.let { id ->
                                val intent = Intent(root.context, ProductDetailsActivity::class.java).apply {
                                    putExtra("product_id", id)
                                }
                                root.context.startActivity(intent)
                            }
                        }

                        btnBuyNowProductCard.setOnClickListener {
                            prodId?.let { id ->
                                onBuyNowClicked?.invoke(id, prodName, prodPrice, prodImage.orEmpty()) ?: run {
                                    val intent = Intent(root.context, ProductDetailsActivity::class.java).apply {
                                        putExtra("product_id", id)
                                        putExtra("auto_buy", true)
                                    }
                                    root.context.startActivity(intent)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        layoutProductCard.visibility = View.GONE
                    }
                } else {
                    layoutProductCard.visibility = View.GONE
                }

                // Order Card Row
                if (isOrder) {
                    layoutOrderCard.visibility = View.VISIBLE
                    try {
                        val orderObj = Json.decodeFromString<JsonObject>(message.content)
                        val orderData = orderObj["order"]?.jsonObject ?: orderObj

                        val orderNum = orderData["order_number"]?.jsonPrimitive?.contentOrNull ?: "ORD"
                        val orderStatus = orderData["status"]?.jsonPrimitive?.contentOrNull ?: "PAID"
                        val storeName = orderData["store_name"]?.jsonPrimitive?.contentOrNull ?: "Official Store"
                        val summary = orderData["items_summary"]?.jsonPrimitive?.contentOrNull ?: "Marketplace Order"
                        val totalAmount = orderData["total_amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val orderId = orderData["id"]?.jsonPrimitive?.contentOrNull ?: ""

                        tvOrderCardNumber.text = "#$orderNum"
                        tvOrderCardStatus.text = orderStatus.uppercase(Locale.ROOT)
                        tvOrderCardStore.text = storeName
                        tvOrderCardSummary.text = summary
                        tvOrderCardTotal.text = String.format(Locale.US, "$%.2f", totalAmount)

                        btnViewOrderCard.setOnClickListener {
                            val intent = Intent(root.context, com.example.gochat.ui.marketplace.OrdersActivity::class.java).apply {
                                putExtra("highlight_order_id", orderId)
                            }
                            root.context.startActivity(intent)
                        }

                        tvMessageContent.visibility = View.GONE
                    } catch (e: Exception) {
                        layoutOrderCard.visibility = View.GONE
                    }
                } else {
                    layoutOrderCard.visibility = View.GONE
                }

                val isOnlyMediaLabel = message.content.isBlank() || 
                    message.content.contains("Photo", ignoreCase = true) || 
                    message.content.contains("Voice Note", ignoreCase = true)

                if ((isImage || isVoice) && isOnlyMediaLabel) {
                    tvMessageContent.visibility = View.GONE
                } else {
                    tvMessageContent.visibility = if (message.content.isNotEmpty() || message.isPing || message.isDeleted) View.VISIBLE else View.GONE
                }

                // Edited status
                tvEdited.visibility = if (message.isEdited && !message.isDeleted) View.VISIBLE else View.GONE

                // Starred status
                ivStarred.visibility = if (message.isStarred) View.VISIBLE else View.GONE

                // Reactions
                if (message.reactions.isNotEmpty() && !message.isDeleted) {
                    layoutReactions.visibility = View.VISIBLE
                    val reactionText = message.reactions.groupBy { it.emoji }
                        .map { "${it.key} ${it.value.size}" }
                        .joinToString("  ")
                    tvReactions.text = reactionText
                } else {
                    layoutReactions.visibility = View.GONE
                }

                // Timestamp
                tvMessageTime.text = formatTime(message.createdAt)
                tvMessageTime.setTextColor(timeColor)

                // Quoted Reply container
                if (!message.replyToId.isNullOrBlank() && !message.isDeleted) {
                    layoutQuotedReply.visibility = View.VISIBLE
                    tvQuotedSender.text = message.replyToSenderName ?: binding.root.context.getString(R.string.original_message_label)
                    tvQuotedText.text = message.replyToText ?: ""
                } else {
                    layoutQuotedReply.visibility = View.GONE
                }

                // Media Image Preview
                if (isImage) {
                    layoutImageContainer.visibility = View.VISIBLE
                    val isSending = message.status == MessageStatus.SENDING
                    pbImageLoadingContainer.visibility = if (isSending) View.VISIBLE else View.GONE

                    if (isSending && !message.blurHash.isNullOrBlank()) {
                        // WhatsApp-style: show blurry preview while uploading
                        val blurBitmap = com.example.gochat.core.utils.BlurHashUtil.decode(message.blurHash, 32, 32)
                        if (blurBitmap != null) {
                            ivMessageImage.setImageDrawable(
                                android.graphics.drawable.BitmapDrawable(itemView.context.resources, blurBitmap)
                            )
                        } else {
                            ivMessageImage.setImageResource(R.drawable.ic_gallery)
                        }
                        ivMessageImage.alpha = 1.0f
                    } else {
                        // Load real image (with blur as placeholder for crossfade)
                        ivMessageImage.alpha = if (isSending) 0.6f else 1.0f
                        MediaImageHelper.loadSafeImage(
                            imageView = ivMessageImage,
                            url = message.mediaUrl,
                            isCircle = false,
                            cornerRadiusDp = 12f,
                            placeholderRes = R.drawable.ic_gallery,
                            errorRes = R.drawable.ic_gallery,
                            blurHash = message.blurHash
                        )
                    }
                    
                    // Interaction
                    layoutImageContainer.setOnClickListener {
                        message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                    }
                    ivMessageImage.setOnClickListener {
                        message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                    }
                } else {
                    layoutImageContainer.visibility = View.GONE
                    layoutImageContainer.setOnClickListener(null)
                    ivMessageImage.setOnClickListener(null)
                }

                // Voice Note Row
                if (isVoice) {
                    layoutVoiceNote.visibility = View.VISIBLE
                    val isPlayingThis = AudioPlayerManager.currentPlayingMessageId == message.id && AudioPlayerManager.isPlaying
                    btnPlayPauseVoice.setImageResource(if (isPlayingThis) R.drawable.ic_pause else R.drawable.ic_play)
                    tvVoiceDuration.text = message.mediaDuration?.let { formatDuration(it) } ?: "0:00"

                    // Mock waveform
                    val mockWaveform = List(30) { (0.2f + (0.8f * Math.random().toFloat())) }
                    waveformVoice.setWaveform(mockWaveform)
                    
                    if (!isPlayingThis) {
                        waveformVoice.setProgress(0f)
                    }

                    btnPlayPauseVoice.setOnClickListener {
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (audioUrl.isNotBlank()) {
                            AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                        } else {
                            onPlayVoiceClicked?.invoke(message)
                        }
                    }

                    btnVoiceSpeed.text = if (isPlayingThis) "${AudioPlayerManager.playbackSpeed}x" else "1.0x"
                    btnVoiceSpeed.setOnClickListener {
                        if (isPlayingThis) {
                            val nextSpeed = when (AudioPlayerManager.playbackSpeed) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            AudioPlayerManager.setSpeed(nextSpeed)
                            btnVoiceSpeed.text = "${nextSpeed}x"
                        }
                    }
                } else {
                    layoutVoiceNote.visibility = View.GONE
                }

                // Long Click / Swipe to reply
                root.setOnLongClickListener {
                    if (!message.isDeleted) {
                        onMessageLongClicked?.invoke(message)
                    }
                    true
                }
            }
        }

        fun updateWaveform(progress: Float) {
            binding.waveformVoice.setProgress(progress)
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

    private fun handleLinkPreview(content: String, binding: Any) {
        val url = LinkPreviewManager.extractUrl(content) ?: run {
            updateLinkPreviewVisibility(binding, null)
            return
        }

        val cached = linkCache.get(url)
        if (cached != null) {
            updateLinkPreviewVisibility(binding, cached)
        } else {
            updateLinkPreviewVisibility(binding, null)
            if (!linkFetching.contains(url)) {
                linkFetching.add(url)
                adapterScope.launch {
                    val preview = LinkPreviewManager.getPreview(url)
                    if (preview != null) {
                        linkCache.put(url, preview)
                        updateLinkPreviewVisibility(binding, preview)
                    }
                    linkFetching.remove(url)
                }
            }
        }
    }

    private fun updateLinkPreviewVisibility(binding: Any, preview: LinkPreview?) {
        val previewBinding = when (binding) {
            is ItemMessageMeBinding -> binding.root.findViewById<View>(R.id.layoutLinkPreview)
            is ItemMessageOtherBinding -> binding.root.findViewById<View>(R.id.layoutLinkPreview)
            else -> null
        } ?: return

        if (preview == null) {
            previewBinding.visibility = View.GONE
        } else {
            previewBinding.visibility = View.VISIBLE
            val ivImage = previewBinding.findViewById<ImageView>(R.id.ivLinkImage)
            val tvTitle = previewBinding.findViewById<TextView>(R.id.tvLinkTitle)
            val tvDesc = previewBinding.findViewById<TextView>(R.id.tvLinkDescription)
            val tvDomain = previewBinding.findViewById<TextView>(R.id.tvLinkDomain)

            tvTitle.text = preview.title
            tvDesc.text = preview.description
            tvDomain.text = preview.domain

            if (!preview.imageUrl.isNullOrBlank()) {
                ivImage.visibility = View.VISIBLE
                ivImage.load(preview.imageUrl) {
                    crossfade(true)
                }
            } else {
                ivImage.visibility = View.GONE
            }

            previewBinding.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))
                previewBinding.context.startActivity(intent)
            }
        }
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
