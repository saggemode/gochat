package com.example.gochat.ui.chat

import android.content.Intent
import android.content.res.ColorStateList
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
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.example.gochat.R
import com.example.gochat.core.currency.CurrencyManager
import com.example.gochat.core.media.AudioPlayerManager
import com.example.gochat.core.wallpaper.BubbleShape
import com.example.gochat.core.wallpaper.ChatBubbleHelper
import com.example.gochat.core.wallpaper.ChatTheme
import com.example.gochat.data.model.CatalogData
import com.example.gochat.data.model.CatalogItemData
import com.example.gochat.data.model.CustomOfferData
import com.example.gochat.data.model.InvoiceData
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageStatus
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.model.PollData
import com.example.gochat.data.model.PollOption
import com.example.gochat.databinding.ItemMessageMeBinding
import com.example.gochat.databinding.ItemMessageOtherBinding
import com.example.gochat.databinding.ItemMessageSystemBinding
import com.example.gochat.databinding.LayoutPollBubbleBinding
import com.example.gochat.databinding.ItemPollOptionViewBinding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
    var onMediaClick: ((Message) -> Unit)? = null

    // ── Inline round video-note playback ──────────────────────────
    private var activePlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var activePlayerView: androidx.media3.ui.PlayerView? = null
    private var activeMessageId: String? = null

    private fun toggleVideoNoteInline(
        message: Message,
        card: View,
        playerView: androidx.media3.ui.PlayerView,
        playIcon: View,
        thumb: View
    ) {
        val url = message.mediaUrl ?: return
        // Tapping the one that's playing stops it.
        if (activeMessageId == message.id && activePlayer != null) {
            stopInlineVideo()
            card.visibility = View.GONE
            playIcon.visibility = View.VISIBLE
            thumb.visibility = View.VISIBLE
            return
        }
        stopInlineVideo()
        // Use the app's authenticated OkHttp client so remote media (which needs the bearer
        // token) streams; DefaultDataSource still handles local file:// and data: URIs.
        val ctx = playerView.context
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.example.gochat.data.api.NetworkModule.getOkHttpClient(ctx)
        )
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(ctx, httpFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        playerView.player = player
        // Fail gracefully: a 404 (e.g. an older video too large for the Telegram CDN to serve
        // back) or any network error restores the thumbnail instead of leaving a blank card.
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val is404 = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 404
                val msg = if (is404) "This video is no longer available" else "Couldn't play this video"
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                stopInlineVideo()
                card.visibility = View.GONE
                playIcon.visibility = View.VISIBLE
                thumb.visibility = View.VISIBLE
            }
        })
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)))
        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true
        activePlayer = player
        activePlayerView = playerView
        activeMessageId = message.id
        card.visibility = View.VISIBLE
        playIcon.visibility = View.GONE
        thumb.visibility = View.GONE
    }

    /** Releases the inline player (call from the activity's onPause/onDestroy). */
    fun stopInlineVideo() {
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayerView?.player = null
        activePlayer = null
        activePlayerView = null
        activeMessageId = null
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val pv = (holder as? MessageMeViewHolder)?.inlinePlayerView()
            ?: (holder as? MessageOtherViewHolder)?.inlinePlayerView()
        if (pv != null && pv === activePlayerView) stopInlineVideo()
    }
    var onBuyNowClicked: ((productId: String, productName: String, price: Double, image: String) -> Unit)? = null
    var onAddToCartClicked: ((productId: String, productJson: String) -> Unit)? = null
    var onPayInvoiceClicked: ((message: Message, invoiceData: InvoiceData) -> Unit)? = null
    var onMarkShippedClicked: ((message: Message, invoiceData: InvoiceData) -> Unit)? = null
    var onConfirmReceiptClicked: ((message: Message, invoiceData: InvoiceData) -> Unit)? = null
    var onAcceptOfferClicked: ((message: Message, offer: CustomOfferData) -> Unit)? = null
    var onCounterOfferClicked: ((message: Message, offer: CustomOfferData) -> Unit)? = null
    var onVotePollClicked: ((message: Message, optionId: String) -> Unit)? = null
    var onViewPollResultsClicked: ((pollData: PollData) -> Unit)? = null
    var currentUserId: String = ""
    var onQuotedMessageClicked: ((replyToId: String) -> Unit)? = null
    var onEditHistoryClicked: ((message: Message) -> Unit)? = null
    /** Invoked when the user taps the failed-status icon on an outgoing message to retry sending. */
    var onRetryClicked: ((message: Message) -> Unit)? = null

    private val quotedMessageCache = LruCache<String, Message>(150)
    private val pendingQuotedFetches = mutableSetOf<String>()

    private fun bindQuotedReply(
        message: Message,
        layoutQuotedReply: View,
        tvQuotedSender: TextView,
        tvQuotedText: TextView,
        ivQuotedThumbnail: ImageView
    ) {
        val replyToId = message.replyToId
        if (replyToId.isNullOrBlank() || message.isDeleted) {
            layoutQuotedReply.visibility = View.GONE
            return
        }

        layoutQuotedReply.visibility = View.VISIBLE

        val senderName = message.replyToSenderName?.ifBlank { null }
            ?: layoutQuotedReply.context.getString(R.string.original_message_label)
        tvQuotedSender.text = senderName

        var parent = quotedMessageCache.get(replyToId)
        if (parent == null) {
            parent = snapshot().items.find { it.id == replyToId }
            if (parent != null) {
                quotedMessageCache.put(replyToId, parent)
            } else if (!pendingQuotedFetches.contains(replyToId)) {
                pendingQuotedFetches.add(replyToId)
                adapterScope.launch {
                    val loaded = withContext(Dispatchers.IO) {
                        try {
                            com.example.gochat.data.db.AppDatabase.getInstance(layoutQuotedReply.context).chatDao().getMessageById(replyToId)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (loaded != null) {
                        quotedMessageCache.put(replyToId, loaded)
                        val pos = (0 until itemCount).indexOfFirst { peek(it)?.id == message.id }
                        if (pos != -1) {
                            notifyItemChanged(pos)
                        }
                    }
                }
            }
        }

        val displayText = when {
            !message.replyToText.isNullOrBlank() -> message.replyToText
            parent != null -> formatQuotedMessageText(parent)
            else -> layoutQuotedReply.context.getString(R.string.original_message_label)
        }
        tvQuotedText.text = displayText

        // Resolve thumbnail and icon
        val targetMediaUrl = message.replyToMediaUrl ?: parent?.mediaThumbnail ?: parent?.mediaUrl
        val parentType = parent?.type ?: when (message.replyToMediaType) {
            "IMAGE" -> MessageType.IMAGE
            "VIDEO" -> MessageType.VIDEO
            "VOICE" -> MessageType.VOICE
            "AUDIO" -> MessageType.AUDIO
            "FILE" -> MessageType.FILE
            "PRODUCT" -> MessageType.PRODUCT
            else -> null
        }

        val isVisualMedia = parentType == MessageType.IMAGE ||
                parentType == MessageType.VIDEO ||
                parentType == MessageType.PRODUCT ||
                (!targetMediaUrl.isNullOrBlank() && (displayText?.contains("📷") == true || displayText?.contains("🎥") == true || displayText?.contains("🛒") == true))

        if (isVisualMedia && !targetMediaUrl.isNullOrBlank()) {
            ivQuotedThumbnail.visibility = View.VISIBLE
            ivQuotedThumbnail.imageTintList = null
            MediaImageHelper.loadSafeImage(
                imageView = ivQuotedThumbnail,
                url = targetMediaUrl,
                cornerRadiusDp = 4f,
                placeholderRes = R.drawable.ic_gallery,
                errorRes = R.drawable.ic_gallery
            )
        } else if (parentType == MessageType.VOICE || parentType == MessageType.AUDIO ||
            displayText?.contains("Voice note") == true || displayText?.contains("🎵") == true || displayText?.contains("🎙️") == true) {
            ivQuotedThumbnail.visibility = View.VISIBLE
            ivQuotedThumbnail.setImageResource(R.drawable.ic_mic)
            ivQuotedThumbnail.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(layoutQuotedReply.context, R.color.gochat_emerald_light)
            )
        } else if (parentType == MessageType.FILE || displayText?.contains("📄") == true || displayText?.contains("Document") == true) {
            ivQuotedThumbnail.visibility = View.VISIBLE
            ivQuotedThumbnail.setImageResource(R.drawable.ic_document)
            ivQuotedThumbnail.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(layoutQuotedReply.context, R.color.gochat_text_secondary)
            )
        } else {
            ivQuotedThumbnail.visibility = View.GONE
        }

        layoutQuotedReply.setOnClickListener {
            if (!isSelectionMode) {
                onQuotedMessageClicked?.invoke(replyToId)
            } else {
                toggleSelection(message.id)
            }
        }
    }

    private fun formatQuotedMessageText(msg: Message): String {
        return when (msg.type) {
            MessageType.TEXT -> msg.content.take(200)
            MessageType.IMAGE -> if (msg.content.isNotBlank() && msg.content != "Photo" && msg.content != "① Photo") "📷 ${msg.content.take(150)}" else "📷 Photo"
            MessageType.VIDEO -> if (msg.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER) "🎥 Video note" else if (msg.content.isNotBlank() && msg.content != "Video") "🎥 ${msg.content.take(150)}" else "🎥 Video"
            MessageType.VOICE, MessageType.AUDIO -> "🎵 Voice note"
            MessageType.FILE -> if (msg.content.isNotBlank()) "📄 ${msg.content.take(150)}" else "📄 Document"
            MessageType.POLL -> "📊 Poll: ${msg.content.take(100)}"
            MessageType.PING -> "💥 PING"
            MessageType.CATALOG -> "🛍️ Product Catalog"
            MessageType.PRODUCT -> if (msg.content.isNotBlank()) "🛒 ${msg.content.take(100)}" else "🛒 Product"
            MessageType.PAYMENT_REQUEST -> "💳 Payment Request"
            MessageType.LOCATION -> "📍 Location"
            MessageType.CONTACT -> "👤 Contact"
            MessageType.STICKER -> "🏷️ Sticker"
            MessageType.ORDER -> "📦 Order"
            MessageType.SYSTEM -> "System message"
        }
    }

    private var accentColor: Int = 0xFF00A884.toInt() // Default emerald
    private var bubbleShape: BubbleShape = BubbleShape.CLASSIC

    // Reused across binds to avoid per-row allocations that cause jank on open/scroll.
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    private var cachedMeBubble: android.graphics.drawable.Drawable.ConstantState? = null
    private var cachedOtherBubble: android.graphics.drawable.Drawable.ConstantState? = null

    /** Bubble background for the current theme; built once per (isMe) and cheaply copied after. */
    private fun bubbleBackground(context: android.content.Context, isMe: Boolean): android.graphics.drawable.Drawable {
        val cached = if (isMe) cachedMeBubble else cachedOtherBubble
        if (cached != null) return cached.newDrawable().mutate()
        val d = ChatBubbleHelper.getBubbleDrawable(context, isMe, bubbleShape, accentColor)
        if (isMe) cachedMeBubble = d.constantState else cachedOtherBubble = d.constantState
        return d
    }

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
            // Theme changed → drop cached bubble drawables so they rebuild with the new look.
            cachedMeBubble = null
            cachedOtherBubble = null
            notifyDataSetChanged()
        }
    }

    data class TranslationState(
        val translatedText: String,
        val detectedLanguage: String,
        val targetLanguage: String
    )

    private val translationsMap = mutableMapOf<String, TranslationState>()

    fun setTranslation(messageId: String, translatedText: String, detectedLang: String, targetLang: String) {
        translationsMap[messageId] = TranslationState(translatedText, detectedLang, targetLang)
        val pos = (0 until itemCount).indexOfFirst { peek(it)?.id == messageId }
        if (pos != -1) {
            notifyItemChanged(pos)
        }
    }

    fun clearTranslation(messageId: String) {
        translationsMap.remove(messageId)
        val pos = (0 until itemCount).indexOfFirst { peek(it)?.id == messageId }
        if (pos != -1) {
            notifyItemChanged(pos)
        }
    }

    // ── Multi-Message Selection Support ────────────────────────
    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedMessageIds.clear()
            }
            notifyDataSetChanged()
        }

    val selectedMessageIds = mutableSetOf<String>()
    var onSelectionChanged: ((count: Int) -> Unit)? = null

    fun toggleSelection(messageId: String) {
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId)
        } else {
            selectedMessageIds.add(messageId)
        }
        val pos = (0 until itemCount).indexOfFirst { peek(it)?.id == messageId }
        if (pos != -1) {
            notifyItemChanged(pos)
        }
        onSelectionChanged?.invoke(selectedMessageIds.size)
    }

    fun selectAll() {
        for (i in 0 until itemCount) {
            peek(i)?.let { selectedMessageIds.add(it.id) }
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedMessageIds.size)
    }

    fun clearSelection() {
        selectedMessageIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedMessages(): List<Message> {
        val list = mutableListOf<Message>()
        for (i in 0 until itemCount) {
            val msg = peek(i)
            if (msg != null && selectedMessageIds.contains(msg.id)) {
                list.add(msg)
            }
        }
        return list.sortedBy { it.createdAt }
    }

    private fun bindDisappearingBadge(
        message: Message,
        layoutBadge: View,
        tvCountdown: TextView
    ) {
        val remainingMs = if (message.expiresAt != null && message.expiresAt > 0) {
            message.expiresAt - System.currentTimeMillis()
        } else if (message.disappearingDurationSeconds != null && message.disappearingDurationSeconds > 0) {
            (message.createdAt + (message.disappearingDurationSeconds * 1000L)) - System.currentTimeMillis()
        } else null

        if (remainingMs != null && !message.isDeleted) {
            layoutBadge.visibility = View.VISIBLE
            tvCountdown.text = formatRemainingTime(remainingMs)
            layoutBadge.setOnClickListener {
                val detail = formatRemainingTimeDetail(remainingMs)
                android.widget.Toast.makeText(layoutBadge.context, "⏱️ Message disappears in $detail", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            layoutBadge.visibility = View.GONE
            layoutBadge.setOnClickListener(null)
        }
    }

    private fun bindCustomOffer(
        message: Message,
        isMe: Boolean,
        layoutCustomOfferCard: View,
        tvOfferCardTitle: TextView,
        tvOfferDiscountBadge: TextView,
        tvOfferItemTitle: TextView,
        tvOfferOriginalPrice: TextView,
        tvOfferPrice: TextView,
        tvOfferNote: TextView,
        btnAcceptOffer: TextView,
        btnCounterOffer: TextView,
        tvMessageContent: TextView
    ) {
        val isOffer = (message.content.contains("\"custom_offer\"") ||
                message.content.contains("\"offered_price\"")) && !message.isDeleted

        if (isOffer) {
            layoutCustomOfferCard.visibility = View.VISIBLE
            try {
                val json = lenientJson
                val rootObj = json.decodeFromString<JsonObject>(message.content)
                val offerObj = rootObj["custom_offer"]?.let { if (it is JsonObject) it else null } ?: rootObj

                val offerId = offerObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val prodId = offerObj["product_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val itemName = offerObj["item_name"]?.jsonPrimitive?.contentOrNull ?: "Custom Offer"
                val origPrice = offerObj["original_price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val offPrice = offerObj["offered_price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val noteStr = offerObj["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val statusStr = offerObj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"

                val offerData = CustomOfferData(
                    id = offerId,
                    productId = prodId,
                    itemName = itemName,
                    originalPrice = origPrice,
                    offeredPrice = offPrice,
                    note = noteStr,
                    status = statusStr
                )

                val offerCurrency = offerObj["currency"]?.jsonPrimitive?.contentOrNull
                    ?: CurrencyManager.getUserCurrency()
                tvOfferItemTitle.text = itemName
                tvOfferPrice.text = CurrencyManager.formatPrice(offPrice, offerCurrency)

                if (origPrice > offPrice && origPrice > 0) {
                    tvOfferOriginalPrice.visibility = View.VISIBLE
                    tvOfferOriginalPrice.text = CurrencyManager.formatPrice(origPrice, offerCurrency)
                    tvOfferOriginalPrice.paintFlags = tvOfferOriginalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

                    val discountPercent = (((origPrice - offPrice) / origPrice) * 100).toInt()
                    tvOfferDiscountBadge.visibility = View.VISIBLE
                    tvOfferDiscountBadge.text = "🔥 $discountPercent% OFF"
                } else {
                    tvOfferOriginalPrice.visibility = View.GONE
                    tvOfferDiscountBadge.visibility = View.GONE
                }

                if (noteStr.isNotBlank()) {
                    tvOfferNote.visibility = View.VISIBLE
                    tvOfferNote.text = noteStr
                } else {
                    tvOfferNote.visibility = View.GONE
                }

                when (statusStr.lowercase(Locale.ROOT)) {
                    "accepted" -> {
                        btnAcceptOffer.text = "✅ ACCEPTED"
                        btnAcceptOffer.isEnabled = false
                        btnCounterOffer.visibility = View.GONE
                    }
                    "declined" -> {
                        btnAcceptOffer.text = "❌ DECLINED"
                        btnAcceptOffer.isEnabled = false
                        btnCounterOffer.visibility = View.GONE
                    }
                    else -> {
                        btnAcceptOffer.text = if (isMe) "⚡ Pending" else "⚡ Accept"
                        btnAcceptOffer.isEnabled = !isMe
                        btnCounterOffer.visibility = View.VISIBLE

                        btnAcceptOffer.setOnClickListener {
                            onAcceptOfferClicked?.invoke(message, offerData)
                        }
                        btnCounterOffer.setOnClickListener {
                            onCounterOfferClicked?.invoke(message, offerData)
                        }
                    }
                }

                tvMessageContent.visibility = View.GONE
                tvMessageContent.text = ""
            } catch (e: Exception) {
                layoutCustomOfferCard.visibility = View.GONE
            }
        } else {
            layoutCustomOfferCard.visibility = View.GONE
        }
    }

    private fun bindPaymentCard(
        message: Message,
        isMe: Boolean,
        layoutPaymentCard: View,
        tvPaymentItemName: TextView,
        tvPaymentAmount: TextView,
        tvPaymentNote: TextView,
        tvPaymentStatusBadge: TextView,
        tvPaymentEscrowNote: TextView,
        btnPaymentAction: TextView,
        tvMessageContent: TextView
    ) {
        val isPayment = (message.type == MessageType.PAYMENT_REQUEST ||
                message.content.contains("\"payment_request\"") ||
                message.content.contains("\"invoice\"")) && !message.isDeleted

        if (isPayment) {
            layoutPaymentCard.visibility = View.VISIBLE
            try {
                val rootObj = Json.decodeFromString<JsonObject>(message.content)
                val invObj = rootObj["payment_request"]?.jsonObject ?: rootObj["invoice"]?.jsonObject ?: rootObj
                val invoiceId = invObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val itemName = invObj["item_name"]?.jsonPrimitive?.contentOrNull
                    ?: invObj["name"]?.jsonPrimitive?.contentOrNull ?: "Item / Service"
                val amount = invObj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val note = invObj["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val status = invObj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"

                val invoiceData = InvoiceData(
                    id = invoiceId,
                    itemName = itemName,
                    amount = amount,
                    note = note,
                    status = status
                )

                val payCurrency = invObj["currency"]?.jsonPrimitive?.contentOrNull
                    ?: CurrencyManager.getUserCurrency()
                tvPaymentItemName.text = itemName
                tvPaymentAmount.text = CurrencyManager.formatPrice(amount, payCurrency)
                if (note.isNotBlank()) {
                    tvPaymentNote.visibility = View.VISIBLE
                    tvPaymentNote.text = note
                } else {
                    tvPaymentNote.visibility = View.GONE
                }

                when (status.lowercase(Locale.ROOT)) {
                    "pending" -> {
                        tvPaymentStatusBadge.text = "PENDING"
                        tvPaymentStatusBadge.backgroundTintList = ColorStateList.valueOf(0xFF3498DB.toInt())
                        tvPaymentEscrowNote.text = "Secured by GoChat Escrow"

                        if (isMe) {
                            btnPaymentAction.visibility = View.VISIBLE
                            btnPaymentAction.text = "⏳ Awaiting Buyer Payment"
                            btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0x30FFFFFF)
                            btnPaymentAction.setTextColor(0xFFCCCCCC.toInt())
                            btnPaymentAction.isEnabled = false
                        } else {
                            btnPaymentAction.visibility = View.VISIBLE
                            btnPaymentAction.text = String.format(Locale.US, "💳 Pay Now ($%.2f)", amount)
                            btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                            btnPaymentAction.setTextColor(0xFF000000.toInt())
                            btnPaymentAction.isEnabled = true
                            btnPaymentAction.setOnClickListener {
                                onPayInvoiceClicked?.invoke(message, invoiceData)
                            }
                        }
                    }
                    "paid_escrow" -> {
                        tvPaymentStatusBadge.text = "✅ PAID (ESCROW)"
                        tvPaymentStatusBadge.backgroundTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                        tvPaymentEscrowNote.text = "Held in Escrow • Awaiting shipment"

                        if (isMe) {
                            btnPaymentAction.visibility = View.VISIBLE
                            btnPaymentAction.text = "📦 Mark as Shipped"
                            btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0xFF2980B9.toInt())
                            btnPaymentAction.setTextColor(0xFFFFFFFF.toInt())
                            btnPaymentAction.isEnabled = true
                            btnPaymentAction.setOnClickListener {
                                onMarkShippedClicked?.invoke(message, invoiceData)
                            }
                        } else {
                            btnPaymentAction.visibility = View.VISIBLE
                            btnPaymentAction.text = "🛡️ Paid • Held in Escrow"
                            btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0x3000A884.toInt())
                            btnPaymentAction.setTextColor(0xFF00A884.toInt())
                            btnPaymentAction.isEnabled = false
                        }
                    }
                    "shipped" -> {
                        tvPaymentStatusBadge.text = "🚚 SHIPPED"
                        tvPaymentStatusBadge.backgroundTintList = ColorStateList.valueOf(0xFFE67E22.toInt())
                        tvPaymentEscrowNote.text = "Dispatched • Deliver to buyer"

                        if (isMe) {
                            btnPaymentAction.visibility = View.VISIBLE
                            btnPaymentAction.text = "🚚 Shipped • In Transit"
                            btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0x30FFFFFF)
                            btnPaymentAction.setTextColor(0xFFCCCCCC.toInt())
                            btnPaymentAction.isEnabled = false
                        } else {
                            btnPaymentAction.visibility = View.VISIBLE
                            btnPaymentAction.text = "🎉 Confirm Receipt & Release"
                            btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                            btnPaymentAction.setTextColor(0xFF000000.toInt())
                            btnPaymentAction.isEnabled = true
                            btnPaymentAction.setOnClickListener {
                                onConfirmReceiptClicked?.invoke(message, invoiceData)
                            }
                        }
                    }
                    "completed" -> {
                        tvPaymentStatusBadge.text = "🎉 COMPLETED"
                        tvPaymentStatusBadge.backgroundTintList = ColorStateList.valueOf(0xFF27AE60.toInt())
                        tvPaymentEscrowNote.text = "Funds released to seller"

                        btnPaymentAction.visibility = View.VISIBLE
                        btnPaymentAction.text = "✅ Order Completed"
                        btnPaymentAction.backgroundTintList = ColorStateList.valueOf(0x3027AE60.toInt())
                        btnPaymentAction.setTextColor(0xFF27AE60.toInt())
                        btnPaymentAction.isEnabled = false
                    }
                    else -> {
                        tvPaymentStatusBadge.text = status.uppercase(Locale.ROOT)
                        btnPaymentAction.visibility = View.GONE
                    }
                }
                tvMessageContent.visibility = View.GONE
            } catch (e: Exception) {
                layoutPaymentCard.visibility = View.GONE
            }
        } else {
            layoutPaymentCard.visibility = View.GONE
        }
    }

    private fun bindCatalogCard(
        message: Message,
        layoutCatalogCard: View,
        tvCatalogCardTitle: TextView,
        tvCatalogCardSubtitle: TextView,
        tvCatalogCardCountBadge: TextView,
        layoutCatalogThumbs: View,
        ivCatalogThumb1: ImageView,
        ivCatalogThumb2: ImageView,
        ivCatalogThumb3: ImageView,
        tvCatalogMoreOverlay: TextView,
        btnViewCatalogStore: TextView,
        tvMessageContent: TextView
    ) {
        val isCatalog = (message.type == MessageType.CATALOG ||
                (message.content.contains("\"products\"") && (message.content.contains("\"catalog\"") || message.content.contains("\"store_name\""))) ||
                (message.content.startsWith("{") && (message.content.contains("\"type\":\"catalog\"") || message.content.contains("\"type\": \"catalog\"")))) && !message.isDeleted

        if (!isCatalog) {
            layoutCatalogCard.visibility = View.GONE
            return
        }

        // Prevent bulk raw JSON text from displaying or crashing the UI
        tvMessageContent.visibility = View.GONE
        tvMessageContent.text = ""

        try {
            val json = lenientJson
            val rootObj = json.decodeFromString<JsonObject>(message.content)
            val catalogObj = rootObj["catalog"]?.jsonObject ?: rootObj
            val storeTitle = catalogObj["title"]?.jsonPrimitive?.contentOrNull
                ?: catalogObj["store_name"]?.jsonPrimitive?.contentOrNull
                ?: "Store Catalog"
            val storeId = catalogObj["store_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val subtitle = catalogObj["subtitle"]?.jsonPrimitive?.contentOrNull
                ?: "Featured Selection"

            val previewImagesList = mutableListOf<String>()
            val previewArray = (rootObj["preview_images"] ?: catalogObj["preview_images"])?.jsonArray
            previewArray?.forEach { elem ->
                elem.jsonPrimitive.contentOrNull?.let { if (it.isNotBlank()) previewImagesList.add(it) }
            }

            // Fallback for V1 format: extract image from products array if preview_images is absent
            if (previewImagesList.isEmpty()) {
                val productsArray = (rootObj["products"] ?: catalogObj["products"])?.jsonArray
                productsArray?.forEach { elem ->
                    if (elem is JsonObject) {
                        val img = (elem["image"] ?: elem["imageUrl"] ?: elem["image_url"])?.jsonPrimitive?.contentOrNull
                        if (!img.isNullOrBlank()) previewImagesList.add(img)
                    }
                }
            }

            val itemCount = rootObj["item_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: catalogObj["item_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: (rootObj["products"] ?: catalogObj["products"])?.jsonArray?.size
                ?: previewImagesList.size

            layoutCatalogCard.visibility = View.VISIBLE
            tvCatalogCardTitle.text = storeTitle
            tvCatalogCardSubtitle.text = "$subtitle · $itemCount items"
            tvCatalogCardCountBadge.text = "$itemCount ITEMS"

            layoutCatalogThumbs.visibility = if (previewImagesList.isNotEmpty()) View.VISIBLE else View.GONE

            val thumbViews = listOf(ivCatalogThumb1, ivCatalogThumb2, ivCatalogThumb3)
            thumbViews.forEachIndexed { index, thumbView ->
                if (index < previewImagesList.size) {
                    thumbView.visibility = View.VISIBLE
                    MediaImageHelper.loadSafeImage(
                        imageView = thumbView,
                        url = previewImagesList[index],
                        cornerRadiusDp = 6f
                    )
                } else {
                    thumbView.visibility = View.INVISIBLE
                }
            }

            if (itemCount > 3) {
                tvCatalogMoreOverlay.visibility = View.VISIBLE
                tvCatalogMoreOverlay.text = "+${itemCount - 2}"
            } else {
                tvCatalogMoreOverlay.visibility = View.GONE
            }

            val openStorefront: (View) -> Unit = { view ->
                val intent = Intent(view.context, com.example.gochat.ui.marketplace.StorefrontActivity::class.java).apply {
                    if (storeId.isNotBlank()) {
                        putExtra("store_id", storeId)
                    }
                    putExtra("store_name", storeTitle)
                }
                view.context.startActivity(intent)
            }

            btnViewCatalogStore.setOnClickListener(openStorefront)
            layoutCatalogCard.setOnClickListener(openStorefront)
        } catch (e: Exception) {
            layoutCatalogCard.visibility = View.GONE
            tvMessageContent.visibility = View.VISIBLE
            tvMessageContent.text = "🛍️ Product Catalog"
        }
    }

    private fun bindPollBubble(
        message: Message,
        layoutPollCard: View,
        pollBinding: LayoutPollBubbleBinding,
        tvMessageContent: TextView
    ) {
        val pollData = message.getPollData()
        if (pollData == null) {
            layoutPollCard.visibility = View.GONE
            return
        }

        layoutPollCard.visibility = View.VISIBLE
        tvMessageContent.visibility = View.GONE
        tvMessageContent.text = ""

        val context = layoutPollCard.context
        val isExpired = pollData.isExpired
        val hasVoted = pollData.hasUserVoted(currentUserId)
        val userVotedOptionIds = pollData.userVotedOptionIds(currentUserId)

        // 1. Header Type Badge (Poll vs Quiz)
        if (pollData.isQuiz) {
            pollBinding.tvPollTypeBadge.text = "🧠 QUIZ"
            pollBinding.tvPollTypeBadge.backgroundTintList = ColorStateList.valueOf(0x339B59B6.toInt())
            pollBinding.tvPollTypeBadge.setTextColor(0xFFBB86FC.toInt())
        } else {
            pollBinding.tvPollTypeBadge.text = "📊 POLL"
            pollBinding.tvPollTypeBadge.backgroundTintList = ColorStateList.valueOf(0x2600A884.toInt())
            pollBinding.tvPollTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.gochat_emerald_light))
        }

        // Expiration Timer Tag
        if (isExpired) {
            pollBinding.tvPollExpireTimer.visibility = View.VISIBLE
            pollBinding.tvPollExpireTimer.text = "🔒 Closed"
            pollBinding.tvPollExpireTimer.backgroundTintList = ColorStateList.valueOf(0x28E74C3C.toInt())
            pollBinding.tvPollExpireTimer.setTextColor(0xFFE74C3C.toInt())
        } else if (pollData.expiresAt != null && pollData.expiresAt > 0L) {
            val remainingMs = pollData.expiresAt - System.currentTimeMillis()
            pollBinding.tvPollExpireTimer.visibility = View.VISIBLE
            pollBinding.tvPollExpireTimer.text = "⏳ " + formatRemainingTime(remainingMs) + " left"
            pollBinding.tvPollExpireTimer.backgroundTintList = ColorStateList.valueOf(0x28E67E22.toInt())
            pollBinding.tvPollExpireTimer.setTextColor(0xFFF39C12.toInt())
        } else {
            pollBinding.tvPollExpireTimer.visibility = View.GONE
        }

        // Mode Note
        pollBinding.tvPollModeNote.text = when {
            pollData.isQuiz -> "Single choice • Quiz"
            pollData.allowMultipleAnswers -> "Select one or more"
            else -> "Select one"
        }

        // Question
        pollBinding.tvPollQuestion.text = pollData.question

        // 2. Options rendering
        val container = pollBinding.layoutPollOptionsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val totalVotes = pollData.totalVotes

        val showResults = hasVoted || isExpired || message.isMe

        pollData.options.forEach { opt ->
            val optBinding = ItemPollOptionViewBinding.inflate(inflater, container, false)
            val isSelectedByMe = userVotedOptionIds.contains(opt.id)
            val percent = if (totalVotes > 0) ((opt.votes.toFloat() / totalVotes) * 100).toInt() else 0

            optBinding.tvOptionText.text = opt.text

            if (showResults) {
                // Show horizontal result bar chart fill
                optBinding.progressOptionBar.visibility = View.VISIBLE
                val weight = percent.coerceIn(0, 100).toFloat()
                val remainder = (100f - weight).coerceAtLeast(0f)

                optBinding.progressOptionBar.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    weight
                )
                optBinding.progressOptionBarRemainder.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    remainder
                )

                // Stats on the right
                optBinding.layoutOptionStats.visibility = View.VISIBLE
                optBinding.tvOptionPercent.text = "$percent%"
                optBinding.tvOptionVotes.text = "${opt.votes} vote" + if (opt.votes == 1) "" else "s"

                // Quiz Mode Results Visualization
                if (pollData.isQuiz) {
                    val isCorrectOption = opt.isCorrect || (pollData.correctOptionId != null && opt.id == pollData.correctOptionId)
                    if (isCorrectOption) {
                        // Correct Option: Highlight in Emerald Green
                        optBinding.layoutOptionRoot.setBackgroundResource(R.drawable.bg_card_dark)
                        optBinding.progressOptionBar.backgroundTintList = ColorStateList.valueOf(0x4000A884.toInt())
                        optBinding.ivOptionIndicator.setImageResource(R.drawable.ic_check)
                        optBinding.ivOptionIndicator.imageTintList = ColorStateList.valueOf(0xFF00A884.toInt())

                        optBinding.tvOptionBadge.visibility = View.VISIBLE
                        optBinding.tvOptionBadge.text = "✓ Correct Answer"
                        optBinding.tvOptionBadge.setTextColor(0xFF00A884.toInt())
                    } else if (isSelectedByMe) {
                        // User chose wrong answer: Highlight in Red
                        optBinding.layoutOptionRoot.setBackgroundResource(R.drawable.bg_card_dark)
                        optBinding.progressOptionBar.backgroundTintList = ColorStateList.valueOf(0x40E74C3C.toInt())
                        optBinding.ivOptionIndicator.setImageResource(R.drawable.ic_close)
                        optBinding.ivOptionIndicator.imageTintList = ColorStateList.valueOf(0xFFE74C3C.toInt())

                        optBinding.tvOptionBadge.visibility = View.VISIBLE
                        optBinding.tvOptionBadge.text = "✗ Your choice"
                        optBinding.tvOptionBadge.setTextColor(0xFFE74C3C.toInt())
                    } else {
                        // Other wrong options
                        optBinding.progressOptionBar.backgroundTintList = ColorStateList.valueOf(0x20FFFFFF)
                        optBinding.ivOptionIndicator.setImageResource(R.drawable.ic_radio_unchecked)
                        optBinding.ivOptionIndicator.imageTintList = ColorStateList.valueOf(0xFF666666.toInt())
                        optBinding.tvOptionBadge.visibility = View.GONE
                    }
                } else {
                    // Standard Poll Results Visualization
                    if (isSelectedByMe) {
                        optBinding.progressOptionBar.backgroundTintList = ColorStateList.valueOf(0x4000A884.toInt())
                        val indicatorRes = if (pollData.allowMultipleAnswers) R.drawable.ic_checkbox_checked else R.drawable.ic_radio_checked
                        optBinding.ivOptionIndicator.setImageResource(indicatorRes)
                        optBinding.ivOptionIndicator.imageTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                        optBinding.tvOptionBadge.visibility = View.GONE
                    } else {
                        optBinding.progressOptionBar.backgroundTintList = ColorStateList.valueOf(0x20FFFFFF)
                        val indicatorRes = if (pollData.allowMultipleAnswers) R.drawable.ic_checkbox_unchecked else R.drawable.ic_radio_unchecked
                        optBinding.ivOptionIndicator.setImageResource(indicatorRes)
                        optBinding.ivOptionIndicator.imageTintList = ColorStateList.valueOf(0xFF666666.toInt())
                        optBinding.tvOptionBadge.visibility = View.GONE
                    }
                }
            } else {
                // Not voted yet: show clean voting options
                optBinding.progressOptionBar.visibility = View.GONE
                optBinding.layoutOptionStats.visibility = View.GONE
                optBinding.tvOptionBadge.visibility = View.GONE

                val indicatorRes = if (pollData.allowMultipleAnswers) R.drawable.ic_checkbox_unchecked else R.drawable.ic_radio_unchecked
                optBinding.ivOptionIndicator.setImageResource(indicatorRes)
                optBinding.ivOptionIndicator.imageTintList = ColorStateList.valueOf(0xFF888888.toInt())
            }

            // Click handling
            if (!isExpired) {
                optBinding.layoutOptionRoot.setOnClickListener {
                    onVotePollClicked?.invoke(message, opt.id)
                }
            } else {
                optBinding.layoutOptionRoot.isClickable = false
            }

            container.addView(optBinding.root)
        }

        // 3. Quiz explanation (Revealed after voting or when expired)
        if (pollData.isQuiz && showResults && !pollData.explanation.isNullOrBlank()) {
            pollBinding.layoutQuizExplanation.visibility = View.VISIBLE
            pollBinding.tvQuizExplanationText.text = pollData.explanation
        } else {
            pollBinding.layoutQuizExplanation.visibility = View.GONE
        }

        // 4. Footer: Total votes & View Votes Sheet
        val votesWord = if (totalVotes == 1) "vote" else "votes"
        pollBinding.tvPollTotalVotes.text = "👥 $totalVotes $votesWord" + if (pollData.isAnonymous) " • Anonymous" else ""

        if (!pollData.isAnonymous && totalVotes > 0) {
            pollBinding.btnViewPollVotes.visibility = View.VISIBLE
            pollBinding.btnViewPollVotes.setOnClickListener {
                onViewPollResultsClicked?.invoke(pollData)
            }
        } else {
            pollBinding.btnViewPollVotes.visibility = View.GONE
        }
    }

    private fun formatRemainingTime(remainingMs: Long): String {
        return when {
            remainingMs <= 0 -> "<1m"
            remainingMs < 60_000L -> "<1m"
            remainingMs < 3_600_000L -> "${remainingMs / 60_000L}m"
            remainingMs < 86_400_000L -> "${remainingMs / 3_600_000L}h"
            else -> "${remainingMs / 86_400_000L}d"
        }
    }

    private fun formatRemainingTimeDetail(remainingMs: Long): String {
        return when {
            remainingMs <= 0 -> "less than a minute"
            remainingMs < 3_600_000L -> "${(remainingMs / 60_000L).coerceAtLeast(1)} minute(s)"
            remainingMs < 86_400_000L -> {
                val hours = remainingMs / 3_600_000L
                val mins = (remainingMs % 3_600_000L) / 60_000L
                if (mins > 0) "$hours hour(s) and $mins min" else "$hours hour(s)"
            }
            else -> {
                val days = remainingMs / 86_400_000L
                val hours = (remainingMs % 86_400_000L) / 3_600_000L
                if (hours > 0) "$days day(s) and $hours hr(s)" else "$days day(s)"
            }
        }
    }

    var highlightedMessageId: String? = null
        private set

    fun highlightMessage(messageId: String) {
        highlightedMessageId = messageId
        val pos = (0 until itemCount).indexOfFirst { peek(it)?.id == messageId }
        if (pos != -1) {
            notifyItemChanged(pos)
        }
    }

    var searchQuery: String = ""
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
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

    private val mentionPattern = Pattern.compile("@[\\w]+")

    private fun highlightQueryAndMentions(text: String, mentionColor: Int, query: String): CharSequence {
        val spannable = SpannableString(text)
        val matcher = mentionPattern.matcher(text)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(mentionColor),
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
        if (query.isNotBlank()) {
            var startIndex = text.indexOf(query, ignoreCase = true)
            while (startIndex >= 0) {
                val endIndex = startIndex + query.length
                spannable.setSpan(
                    android.text.style.BackgroundColorSpan(0x66FFEB3B.toInt()),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = text.indexOf(query, endIndex, ignoreCase = true)
            }
        }
        return spannable
    }

    private fun highlightMentions(text: String, color: Int): CharSequence {
        return highlightQueryAndMentions(text, color, searchQuery)
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
        private const val VIEW_TYPE_SYSTEM = 3
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        if (message?.type == MessageType.SYSTEM ||
            message?.content?.startsWith("📦 Order") == true ||
            message?.content?.startsWith("🛡️") == true ||
            message?.content?.startsWith("SYSTEM:") == true) {
            return VIEW_TYPE_SYSTEM
        }
        return if (message?.isMe == true) VIEW_TYPE_ME else VIEW_TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                val binding = ItemMessageSystemBinding.inflate(inflater, parent, false)
                MessageSystemViewHolder(binding)
            }
            VIEW_TYPE_ME -> {
                val binding = ItemMessageMeBinding.inflate(inflater, parent, false)
                MessageMeViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageOtherBinding.inflate(inflater, parent, false)
                MessageOtherViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position) ?: return
        when (holder) {
            is MessageSystemViewHolder -> holder.bind(message)
            is MessageMeViewHolder -> holder.bind(message)
            is MessageOtherViewHolder -> holder.bind(message)
        }
    }

    inner class MessageSystemViewHolder(private val binding: ItemMessageSystemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val rawContent = message.content
            val displayText = when {
                rawContent.startsWith("SYSTEM:", ignoreCase = true) -> rawContent.substring(7).trim()
                rawContent.startsWith("system:", ignoreCase = true) -> rawContent.substring(7).trim()
                else -> rawContent
            }
            binding.tvSystemMessageContent.text = displayText
            binding.tvSystemMessageTime.text = formatTime(message.createdAt)

            val contentLower = rawContent.lowercase(Locale.ROOT)
            when {
                contentLower.contains("screenshot") -> {
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_camera_status)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFFF59E0B.toInt()) // Amber/warning
                }
                contentLower.contains("invite link") -> {
                    // Joined via invite link
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_link)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFF00B4D8.toInt())
                }
                contentLower.contains("approved") && contentLower.contains("join") -> {
                    // Admin approved a join request
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_shield)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                }
                contentLower.contains("added") || contentLower.contains("created group") || contentLower.contains("group") -> {
                    // Admin added directly / other group event
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_group_add)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                }
                contentLower.contains("shipped") || contentLower.contains("transit") -> {
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_forward)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFF00B4D8.toInt())
                }
                contentLower.contains("delivered") || contentLower.contains("completed") || contentLower.contains("paid") -> {
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_verified_badge)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                }
                contentLower.contains("cancel") || contentLower.contains("refund") -> {
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_close)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFFE74C3C.toInt())
                }
                else -> {
                    binding.ivSystemMessageIcon.setImageResource(R.drawable.ic_cart)
                    binding.ivSystemMessageIcon.imageTintList = ColorStateList.valueOf(0xFF00A884.toInt())
                }
            }

            binding.layoutSystemPill.setOnClickListener {
                if (message.content.contains("Order #", ignoreCase = true)) {
                    val intent = Intent(binding.root.context, com.example.gochat.ui.marketplace.OrdersActivity::class.java)
                    binding.root.context.startActivity(intent)
                }
            }
        }
    }

    inner class MessageMeViewHolder(private val binding: ItemMessageMeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun inlinePlayerView(): androidx.media3.ui.PlayerView = binding.videoNotePlayer

        fun bind(message: Message) {
            with(binding) {
                // Apply dynamic bubble shape & background (cached per theme to avoid per-bind allocation)
                layoutBubbleContainer.background = bubbleBackground(root.context, isMe = true)

                val textColor = ChatBubbleHelper.getMessageTextColor(isMe = true, shape = bubbleShape)
                val timeColor = ChatBubbleHelper.getTimestampTextColor(isMe = true, shape = bubbleShape)

                // Highlight pulse animation if targeted
                if (message.id == highlightedMessageId) {
                    val highlightBg = 0x3300A884.toInt() // Soft emerald pulse
                    root.setBackgroundColor(highlightBg)
                    root.animate()
                        .alpha(1f)
                        .setDuration(1200)
                        .withEndAction {
                            root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            if (highlightedMessageId == message.id) {
                                highlightedMessageId = null
                            }
                        }
                        .start()
                } else {
                    root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }

                // Forwarded status
                if (message.isForwarded) {
                    layoutForwarded.visibility = View.VISIBLE
                    if (message.isViral) {
                        tvForwardedLabel.text = "Frequently forwarded"
                        ivForwardIcon.setImageResource(R.drawable.ic_repeat)
                    } else {
                        tvForwardedLabel.text = root.context.getString(R.string.label_forwarded)
                        ivForwardIcon.setImageResource(R.drawable.ic_forward)
                    }
                } else {
                    layoutForwarded.visibility = View.GONE
                }

                val isVideoNote = message.type == MessageType.VIDEO &&
                              message.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER &&
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()
                val isVideo = (message.type == MessageType.VIDEO ||
                              message.content.equals("Video", ignoreCase = true) ||
                              message.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER) &&
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()
                val isImage = ((message.type == MessageType.IMAGE || message.content.contains("Photo", ignoreCase = true)) &&
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()) || isVideo
                val isVoice = (message.type == MessageType.VOICE || message.type == MessageType.AUDIO || message.content.contains("Voice Note", ignoreCase = true)) && 
                              !message.isDeleted
                val isProduct = (message.type == MessageType.PRODUCT || (message.content.contains("\"product\"") && message.content.contains("\"price\""))) && !message.isDeleted
                val isOrder = (message.type == MessageType.ORDER || message.content.contains("\"order_number\"") || message.content.contains("\"type\":\"order\"")) && !message.isDeleted
                val isPayment = (message.type == MessageType.PAYMENT_REQUEST || message.content.contains("\"invoice\"") || message.content.contains("\"payment_request\"")) && !message.isDeleted

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
                } else if (isBase64Ciphertext(message.content)) {
                    tvMessageContent.text = "🔒 [Encrypted Message]"
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_text_secondary))
                    tvMessageContent.setTypeface(null, Typeface.ITALIC)
                    tvMessageContent.setOnClickListener(null)
                } else if (isOrder) {
                    tvMessageContent.text = ""
                    tvMessageContent.visibility = View.GONE
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

                        val prodCurrency = prodData["currency"]?.jsonPrimitive?.contentOrNull
                            ?: CurrencyManager.getUserCurrency()
                        tvProductCardTitle.text = prodName
                        tvProductCardPrice.text = CurrencyManager.formatPrice(prodPrice, prodCurrency)
                        
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

                        btnAddToCartProductCard.setOnClickListener {
                            prodId?.let { id ->
                                onAddToCartClicked?.invoke(id, message.content) ?: run {
                                    val intent = Intent(root.context, ProductDetailsActivity::class.java).apply {
                                        putExtra("product_id", id)
                                        putExtra("auto_add_cart", true)
                                    }
                                    root.context.startActivity(intent)
                                }
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
                        val json = lenientJson
                        val orderObj = json.decodeFromString<JsonObject>(message.content)
                        val orderData = orderObj["order"]?.let { if (it is JsonObject) it else null } ?: orderObj

                        val orderNum = orderData["order_number"]?.jsonPrimitive?.contentOrNull ?: "ORD"
                        val orderStatus = orderData["status"]?.jsonPrimitive?.contentOrNull ?: "PAID"
                        val storeName = orderData["store_name"]?.jsonPrimitive?.contentOrNull ?: "Official Store"
                        val summary = orderData["items_summary"]?.jsonPrimitive?.contentOrNull ?: "Marketplace Order"
                        val totalAmount = orderData["total_amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val orderId = orderData["id"]?.jsonPrimitive?.contentOrNull ?: ""

                        val orderCurrency = orderData["currency"]?.jsonPrimitive?.contentOrNull
                            ?: orderData["currency_code"]?.jsonPrimitive?.contentOrNull
                            ?: CurrencyManager.getUserCurrency()
                        tvOrderCardNumber.text = "#$orderNum"
                        tvOrderCardStatus.text = orderStatus.uppercase(Locale.ROOT)
                        tvOrderCardStore.text = storeName
                        tvOrderCardSummary.text = summary
                        tvOrderCardTotal.text = CurrencyManager.formatPrice(totalAmount, orderCurrency)

                        btnViewOrderCard.setOnClickListener {
                            val intent = Intent(root.context, com.example.gochat.ui.marketplace.OrdersActivity::class.java).apply {
                                if (orderId.isNotBlank()) putExtra("highlight_order_id", orderId)
                            }
                            root.context.startActivity(intent)
                        }
                    } catch (_: Exception) {
                        tvOrderCardNumber.text = "#ORD"
                        tvOrderCardStatus.text = "ORDER"
                        tvOrderCardStore.text = "Marketplace Store"
                        tvOrderCardSummary.text = "Marketplace Order"
                    }
                    tvMessageContent.visibility = View.GONE
                    tvMessageContent.text = ""
                } else {
                    layoutOrderCard.visibility = View.GONE
                }

                // Payment Request / Invoice Card Row
                bindPaymentCard(
                    message = message,
                    isMe = true,
                    layoutPaymentCard = layoutPaymentCard,
                    tvPaymentItemName = tvPaymentItemName,
                    tvPaymentAmount = tvPaymentAmount,
                    tvPaymentNote = tvPaymentNote,
                    tvPaymentStatusBadge = tvPaymentStatusBadge,
                    tvPaymentEscrowNote = tvPaymentEscrowNote,
                    btnPaymentAction = btnPaymentAction,
                    tvMessageContent = tvMessageContent
                )

                bindCustomOffer(
                    message = message,
                    isMe = true,
                    layoutCustomOfferCard = layoutCustomOfferCard,
                    tvOfferCardTitle = tvOfferCardTitle,
                    tvOfferDiscountBadge = tvOfferDiscountBadge,
                    tvOfferItemTitle = tvOfferItemTitle,
                    tvOfferOriginalPrice = tvOfferOriginalPrice,
                    tvOfferPrice = tvOfferPrice,
                    tvOfferNote = tvOfferNote,
                    btnAcceptOffer = btnAcceptOffer,
                    btnCounterOffer = btnCounterOffer,
                    tvMessageContent = tvMessageContent
                )

                // Product Catalog Card Row
                bindCatalogCard(
                    message = message,
                    layoutCatalogCard = layoutCatalogCard,
                    tvCatalogCardTitle = tvCatalogCardTitle,
                    tvCatalogCardSubtitle = tvCatalogCardSubtitle,
                    tvCatalogCardCountBadge = tvCatalogCardCountBadge,
                    layoutCatalogThumbs = layoutCatalogThumbs,
                    ivCatalogThumb1 = ivCatalogThumb1,
                    ivCatalogThumb2 = ivCatalogThumb2,
                    ivCatalogThumb3 = ivCatalogThumb3,
                    tvCatalogMoreOverlay = tvCatalogMoreOverlay,
                    btnViewCatalogStore = btnViewCatalogStore,
                    tvMessageContent = tvMessageContent
                )

                // Poll 2.0 Card Preview
                bindPollBubble(
                    message = message,
                    layoutPollCard = layoutPollCard,
                    pollBinding = includePoll,
                    tvMessageContent = tvMessageContent
                )

                // Hide the text label if it's purely a media label
                val isOnlyMediaLabel = message.content.isBlank() ||
                    message.content.contains("Photo", ignoreCase = true) ||
                    message.content.contains("Voice Note", ignoreCase = true) ||
                    message.content.equals("Video", ignoreCase = true) ||
                    message.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER

                val isCatalogMessage = (message.type == MessageType.CATALOG ||
                        (message.content.contains("\"products\"") && (message.content.contains("\"catalog\"") || message.content.contains("\"store_name\""))) ||
                        (message.content.startsWith("{") && (message.content.contains("\"type\":\"catalog\"") || message.content.contains("\"type\": \"catalog\"")))) && !message.isDeleted

                val isPollMessage = (message.type == MessageType.POLL || message.getPollData() != null) && !message.isDeleted

                if (isPollMessage || isCatalogMessage || isOrder || isPayment) {
                    tvMessageContent.visibility = View.GONE
                    tvMessageContent.text = ""
                } else if ((isImage || isVoice) && isOnlyMediaLabel) {
                    tvMessageContent.visibility = View.GONE
                } else {
                    tvMessageContent.visibility = if (message.content.isNotEmpty() || message.isPing || message.isDeleted) View.VISIBLE else View.GONE
                }

                // Edited status
                if (message.isEdited && !message.isDeleted) {
                    tvEdited.visibility = View.VISIBLE
                    tvEdited.setOnClickListener { onEditHistoryClicked?.invoke(message) }
                } else {
                    tvEdited.visibility = View.GONE
                    tvEdited.setOnClickListener(null)
                }

                // Starred status
                ivStarred.visibility = if (message.isStarred) View.VISIBLE else View.GONE

                // Inline Translation
                val translation = translationsMap[message.id]
                if (translation != null && !message.isDeleted) {
                    layoutTranslation.visibility = View.VISIBLE
                    val fromLang = translation.detectedLanguage.replaceFirstChar { it.uppercase() }
                    val toLang = translation.targetLanguage.replaceFirstChar { it.uppercase() }
                    tvTranslationHeader.text = "🌐 Translated ($fromLang → $toLang)"
                    tvTranslatedText.text = translation.translatedText
                    btnCloseTranslation.setOnClickListener {
                        clearTranslation(message.id)
                    }
                } else {
                    layoutTranslation.visibility = View.GONE
                }

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

                // Disappearing Countdown Badge
                bindDisappearingBadge(message, layoutDisappearingTimer, tvDisappearingCountdown)

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
                // Tap the failed indicator to retry sending (outbox).
                if (message.status == MessageStatus.FAILED) {
                    ivMessageStatus.setOnClickListener { onRetryClicked?.invoke(message) }
                } else {
                    ivMessageStatus.setOnClickListener(null)
                    ivMessageStatus.isClickable = false
                }

                // Quoted Reply container
                bindQuotedReply(
                    message = message,
                    layoutQuotedReply = layoutQuotedReply,
                    tvQuotedSender = tvQuotedSender,
                    tvQuotedText = tvQuotedText,
                    ivQuotedThumbnail = ivQuotedThumbnail
                )

                // Media Image Preview
                if (isImage) {
                    layoutImageContainer.visibility = View.VISIBLE
                    // Video notes render as a round bubble; normal image/video stays rectangular.
                    val density = itemView.resources.displayMetrics.density
                    val clp = layoutImageContainer.layoutParams
                    if (isVideoNote) {
                        val side = (220 * density).toInt()
                        clp.width = side
                        clp.height = side
                        ivMessageImage.layoutParams = ivMessageImage.layoutParams.apply { height = side }
                        ivMessageImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        ivMessageImage.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.5f)).build()
                        ivMessageImage.setBackgroundColor(0xFF0B141A.toInt())
                    } else {
                        clp.width = (260 * density).toInt()
                        clp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ivMessageImage.layoutParams = ivMessageImage.layoutParams.apply {
                            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        ivMessageImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        ivMessageImage.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(12 * density).build()
                    }
                    layoutImageContainer.layoutParams = clp
                    ivVideoPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE
                    // Reset any inline player on (re)bind so recycled views never show a stale one.
                    cardVideoNotePlayer.visibility = View.GONE
                    if (activeMessageId == message.id) stopInlineVideo()

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
                    } else if (isVideo) {
                        // Videos (incl. round notes): decode a real first-frame preview.
                        // Coil can't reliably decode a video from an extensionless
                        // download URL, so extract the frame explicitly.
                        ivMessageImage.alpha = 1.0f
                        MediaImageHelper.loadVideoThumbnail(
                            imageView = ivMessageImage,
                            url = message.mediaUrl,
                            placeholderRes = R.drawable.ic_gallery,
                            blurHash = message.blurHash
                        )
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
                    if (message.isViewOnce && message.isViewed) {
                        layoutImageContainer.setOnClickListener(null)
                        ivMessageImage.setOnClickListener(null)
                    } else {
                        if (isVideoNote) {
                            // Play the round note inline, in the bubble (not a separate player).
                            val playInline = View.OnClickListener {
                                toggleVideoNoteInline(message, cardVideoNotePlayer, videoNotePlayer, ivVideoPlayIcon, ivMessageImage)
                            }
                            layoutImageContainer.setOnClickListener(playInline)
                            ivMessageImage.setOnClickListener(playInline)
                        } else {
                            layoutImageContainer.setOnClickListener {
                                if (onMediaClick != null) onMediaClick?.invoke(message)
                                else message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                            }
                            ivMessageImage.setOnClickListener {
                                if (onMediaClick != null) onMediaClick?.invoke(message)
                                else message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                            }
                        }
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

                    waveformVoice.onSeekListener = { progress ->
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (AudioPlayerManager.currentPlayingMessageId != message.id) {
                            if (audioUrl.isNotBlank()) {
                                AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                                AudioPlayerManager.seekToFraction(progress)
                            }
                        } else {
                            AudioPlayerManager.seekToFraction(progress)
                        }
                    }

                    btnPlayPauseVoice.setOnClickListener {
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (audioUrl.isNotBlank()) {
                            AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                        } else {
                            onPlayVoiceClicked?.invoke(message)
                        }
                    }

                    btnVoiceSpeed.text = "${AudioPlayerManager.playbackSpeed}x"
                    btnVoiceSpeed.setOnClickListener {
                        val nextSpeed = when (AudioPlayerManager.playbackSpeed) {
                            1.0f -> 1.5f
                            1.5f -> 2.0f
                            else -> 1.0f
                        }
                        AudioPlayerManager.setSpeed(nextSpeed)
                        btnVoiceSpeed.text = "${nextSpeed}x"
                        notifyDataSetChanged()
                    }
                } else {
                    layoutVoiceNote.visibility = View.GONE
                }

                // Multi-Selection Mode support
                val isSelected = selectedMessageIds.contains(message.id)
                if (isSelectionMode) {
                    root.setOnClickListener {
                        toggleSelection(message.id)
                    }
                    root.setBackgroundColor(if (isSelected) 0x3300A884.toInt() else android.graphics.Color.TRANSPARENT)
                } else {
                    root.setOnClickListener(null)
                    root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }

                // Long Click / Swipe to reply
                root.setOnLongClickListener {
                    if (isSelectionMode) {
                        toggleSelection(message.id)
                    } else if (!message.isDeleted) {
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

        fun inlinePlayerView(): androidx.media3.ui.PlayerView = binding.videoNotePlayer

        fun bind(message: Message) {
            with(binding) {
                // Apply dynamic bubble shape & background (cached per theme to avoid per-bind allocation)
                layoutBubbleContainer.background = bubbleBackground(root.context, isMe = false)

                val textColor = ChatBubbleHelper.getMessageTextColor(isMe = false, shape = bubbleShape)
                val timeColor = ChatBubbleHelper.getTimestampTextColor(isMe = false, shape = bubbleShape)

                // Highlight pulse animation if targeted
                if (message.id == highlightedMessageId) {
                    val highlightBg = 0x3300A884.toInt() // Soft emerald pulse
                    root.setBackgroundColor(highlightBg)
                    root.animate()
                        .alpha(1f)
                        .setDuration(1200)
                        .withEndAction {
                            root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            if (highlightedMessageId == message.id) {
                                highlightedMessageId = null
                            }
                        }
                        .start()
                } else {
                    root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }

                // Forwarded status
                if (message.isForwarded) {
                    layoutForwarded.visibility = View.VISIBLE
                    if (message.isViral) {
                        tvForwardedLabel.text = "Frequently forwarded"
                        ivForwardIcon.setImageResource(R.drawable.ic_repeat)
                    } else {
                        tvForwardedLabel.text = root.context.getString(R.string.label_forwarded)
                        ivForwardIcon.setImageResource(R.drawable.ic_forward)
                    }
                } else {
                    layoutForwarded.visibility = View.GONE
                }

                val isVideoNote = message.type == MessageType.VIDEO &&
                              message.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER &&
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()
                val isVideo = (message.type == MessageType.VIDEO ||
                              message.content.equals("Video", ignoreCase = true) ||
                              message.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER) &&
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()
                val isImage = ((message.type == MessageType.IMAGE || message.content.contains("Photo", ignoreCase = true)) &&
                              !message.isDeleted && !message.mediaUrl.isNullOrBlank()) || isVideo
                val isVoice = (message.type == MessageType.VOICE || message.type == MessageType.AUDIO || message.content.contains("Voice Note", ignoreCase = true)) && 
                              !message.isDeleted
                val isProduct = (message.type == MessageType.PRODUCT || (message.content.contains("\"product\"") && message.content.contains("\"price\""))) && !message.isDeleted
                val isOrder = (message.type == MessageType.ORDER || message.content.contains("\"order_number\"") || message.content.contains("\"type\":\"order\"")) && !message.isDeleted
                val isPayment = (message.type == MessageType.PAYMENT_REQUEST || message.content.contains("\"invoice\"") || message.content.contains("\"payment_request\"")) && !message.isDeleted

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
                } else if (isBase64Ciphertext(message.content)) {
                    tvMessageContent.text = "🔒 [Encrypted Message]"
                    tvMessageContent.setTextColor(binding.root.context.getColor(R.color.gochat_text_secondary))
                    tvMessageContent.setTypeface(null, Typeface.ITALIC)
                    tvMessageContent.setOnClickListener(null)
                } else if (isOrder) {
                    tvMessageContent.text = ""
                    tvMessageContent.visibility = View.GONE
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

                        val prodCurrency = prodData["currency"]?.jsonPrimitive?.contentOrNull
                            ?: CurrencyManager.getUserCurrency()
                        tvProductCardTitle.text = prodName
                        tvProductCardPrice.text = CurrencyManager.formatPrice(prodPrice, prodCurrency)
                        
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

                        btnAddToCartProductCard.setOnClickListener {
                            prodId?.let { id ->
                                onAddToCartClicked?.invoke(id, message.content) ?: run {
                                    val intent = Intent(root.context, ProductDetailsActivity::class.java).apply {
                                        putExtra("product_id", id)
                                        putExtra("auto_add_cart", true)
                                    }
                                    root.context.startActivity(intent)
                                }
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
                        val json = lenientJson
                        val orderObj = json.decodeFromString<JsonObject>(message.content)
                        val orderData = orderObj["order"]?.let { if (it is JsonObject) it else null } ?: orderObj

                        val orderNum = orderData["order_number"]?.jsonPrimitive?.contentOrNull ?: "ORD"
                        val orderStatus = orderData["status"]?.jsonPrimitive?.contentOrNull ?: "PAID"
                        val storeName = orderData["store_name"]?.jsonPrimitive?.contentOrNull ?: "Official Store"
                        val summary = orderData["items_summary"]?.jsonPrimitive?.contentOrNull ?: "Marketplace Order"
                        val totalAmount = orderData["total_amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val orderId = orderData["id"]?.jsonPrimitive?.contentOrNull ?: ""

                        val orderCurrency = orderData["currency"]?.jsonPrimitive?.contentOrNull
                            ?: orderData["currency_code"]?.jsonPrimitive?.contentOrNull
                            ?: CurrencyManager.getUserCurrency()
                        tvOrderCardNumber.text = "#$orderNum"
                        tvOrderCardStatus.text = orderStatus.uppercase(Locale.ROOT)
                        tvOrderCardStore.text = storeName
                        tvOrderCardSummary.text = summary
                        tvOrderCardTotal.text = CurrencyManager.formatPrice(totalAmount, orderCurrency)

                        btnViewOrderCard.setOnClickListener {
                            val intent = Intent(root.context, com.example.gochat.ui.marketplace.OrdersActivity::class.java).apply {
                                if (orderId.isNotBlank()) putExtra("highlight_order_id", orderId)
                            }
                            root.context.startActivity(intent)
                        }
                    } catch (_: Exception) {
                        tvOrderCardNumber.text = "#ORD"
                        tvOrderCardStatus.text = "ORDER"
                        tvOrderCardStore.text = "Marketplace Store"
                        tvOrderCardSummary.text = "Marketplace Order"
                    }
                    tvMessageContent.visibility = View.GONE
                    tvMessageContent.text = ""
                } else {
                    layoutOrderCard.visibility = View.GONE
                }

                // Payment Request / Invoice Card Row
                bindPaymentCard(
                    message = message,
                    isMe = false,
                    layoutPaymentCard = layoutPaymentCard,
                    tvPaymentItemName = tvPaymentItemName,
                    tvPaymentAmount = tvPaymentAmount,
                    tvPaymentNote = tvPaymentNote,
                    tvPaymentStatusBadge = tvPaymentStatusBadge,
                    tvPaymentEscrowNote = tvPaymentEscrowNote,
                    btnPaymentAction = btnPaymentAction,
                    tvMessageContent = tvMessageContent
                )

                bindCustomOffer(
                    message = message,
                    isMe = false,
                    layoutCustomOfferCard = layoutCustomOfferCard,
                    tvOfferCardTitle = tvOfferCardTitle,
                    tvOfferDiscountBadge = tvOfferDiscountBadge,
                    tvOfferItemTitle = tvOfferItemTitle,
                    tvOfferOriginalPrice = tvOfferOriginalPrice,
                    tvOfferPrice = tvOfferPrice,
                    tvOfferNote = tvOfferNote,
                    btnAcceptOffer = btnAcceptOffer,
                    btnCounterOffer = btnCounterOffer,
                    tvMessageContent = tvMessageContent
                )

                // Product Catalog Card Row
                bindCatalogCard(
                    message = message,
                    layoutCatalogCard = layoutCatalogCard,
                    tvCatalogCardTitle = tvCatalogCardTitle,
                    tvCatalogCardSubtitle = tvCatalogCardSubtitle,
                    tvCatalogCardCountBadge = tvCatalogCardCountBadge,
                    layoutCatalogThumbs = layoutCatalogThumbs,
                    ivCatalogThumb1 = ivCatalogThumb1,
                    ivCatalogThumb2 = ivCatalogThumb2,
                    ivCatalogThumb3 = ivCatalogThumb3,
                    tvCatalogMoreOverlay = tvCatalogMoreOverlay,
                    btnViewCatalogStore = btnViewCatalogStore,
                    tvMessageContent = tvMessageContent
                )

                // Poll 2.0 Card Preview
                bindPollBubble(
                    message = message,
                    layoutPollCard = layoutPollCard,
                    pollBinding = includePoll,
                    tvMessageContent = tvMessageContent
                )

                val isOnlyMediaLabel = message.content.isBlank() ||
                    message.content.contains("Photo", ignoreCase = true) ||
                    message.content.contains("Voice Note", ignoreCase = true) ||
                    message.content.equals("Video", ignoreCase = true) ||
                    message.content == com.example.gochat.data.model.Message.VIDEO_NOTE_MARKER

                val isCatalogMessage = (message.type == MessageType.CATALOG ||
                        (message.content.contains("\"products\"") && (message.content.contains("\"catalog\"") || message.content.contains("\"store_name\""))) ||
                        (message.content.startsWith("{") && (message.content.contains("\"type\":\"catalog\"") || message.content.contains("\"type\": \"catalog\"")))) && !message.isDeleted

                val isPollMessage = (message.type == MessageType.POLL || message.getPollData() != null) && !message.isDeleted

                if (isPollMessage || isCatalogMessage || isOrder || isPayment) {
                    tvMessageContent.visibility = View.GONE
                    tvMessageContent.text = ""
                } else if ((isImage || isVoice) && isOnlyMediaLabel) {
                    tvMessageContent.visibility = View.GONE
                } else {
                    tvMessageContent.visibility = if (message.content.isNotEmpty() || message.isPing || message.isDeleted) View.VISIBLE else View.GONE
                }

                // Edited status
                if (message.isEdited && !message.isDeleted) {
                    tvEdited.visibility = View.VISIBLE
                    tvEdited.setOnClickListener { onEditHistoryClicked?.invoke(message) }
                } else {
                    tvEdited.visibility = View.GONE
                    tvEdited.setOnClickListener(null)
                }

                // Starred status
                ivStarred.visibility = if (message.isStarred) View.VISIBLE else View.GONE

                // Inline Translation
                val translation = translationsMap[message.id]
                if (translation != null && !message.isDeleted) {
                    layoutTranslation.visibility = View.VISIBLE
                    val fromLang = translation.detectedLanguage.replaceFirstChar { it.uppercase() }
                    val toLang = translation.targetLanguage.replaceFirstChar { it.uppercase() }
                    tvTranslationHeader.text = "🌐 Translated ($fromLang → $toLang)"
                    tvTranslatedText.text = translation.translatedText
                    btnCloseTranslation.setOnClickListener {
                        clearTranslation(message.id)
                    }
                } else {
                    layoutTranslation.visibility = View.GONE
                }

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

                // Disappearing Countdown Badge
                bindDisappearingBadge(message, layoutDisappearingTimer, tvDisappearingCountdown)

                // Timestamp
                tvMessageTime.text = formatTime(message.createdAt)
                tvMessageTime.setTextColor(timeColor)

                // Quoted Reply container
                bindQuotedReply(
                    message = message,
                    layoutQuotedReply = layoutQuotedReply,
                    tvQuotedSender = tvQuotedSender,
                    tvQuotedText = tvQuotedText,
                    ivQuotedThumbnail = ivQuotedThumbnail
                )

                // Media Image Preview
                if (isImage) {
                    layoutImageContainer.visibility = View.VISIBLE
                    // Video notes render as a round bubble; normal image/video stays rectangular.
                    val density = itemView.resources.displayMetrics.density
                    val clp = layoutImageContainer.layoutParams
                    if (isVideoNote) {
                        val side = (220 * density).toInt()
                        clp.width = side
                        clp.height = side
                        ivMessageImage.layoutParams = ivMessageImage.layoutParams.apply { height = side }
                        ivMessageImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        ivMessageImage.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.5f)).build()
                        ivMessageImage.setBackgroundColor(0xFF0B141A.toInt())
                    } else {
                        clp.width = (260 * density).toInt()
                        clp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ivMessageImage.layoutParams = ivMessageImage.layoutParams.apply {
                            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        ivMessageImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        ivMessageImage.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(12 * density).build()
                    }
                    layoutImageContainer.layoutParams = clp
                    ivVideoPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE
                    // Reset any inline player on (re)bind so recycled views never show a stale one.
                    cardVideoNotePlayer.visibility = View.GONE
                    if (activeMessageId == message.id) stopInlineVideo()

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
                    } else if (isVideo) {
                        // Videos (incl. round notes): decode a real first-frame preview.
                        // Coil can't reliably decode a video from an extensionless
                        // download URL, so extract the frame explicitly.
                        ivMessageImage.alpha = 1.0f
                        MediaImageHelper.loadVideoThumbnail(
                            imageView = ivMessageImage,
                            url = message.mediaUrl,
                            placeholderRes = R.drawable.ic_gallery,
                            blurHash = message.blurHash
                        )
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
                    if (message.isViewOnce && message.isViewed) {
                        layoutImageContainer.setOnClickListener(null)
                        ivMessageImage.setOnClickListener(null)
                    } else {
                        if (isVideoNote) {
                            // Play the round note inline, in the bubble (not a separate player).
                            val playInline = View.OnClickListener {
                                toggleVideoNoteInline(message, cardVideoNotePlayer, videoNotePlayer, ivVideoPlayIcon, ivMessageImage)
                            }
                            layoutImageContainer.setOnClickListener(playInline)
                            ivMessageImage.setOnClickListener(playInline)
                        } else {
                            layoutImageContainer.setOnClickListener {
                                if (onMediaClick != null) onMediaClick?.invoke(message)
                                else message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                            }
                            ivMessageImage.setOnClickListener {
                                if (onMediaClick != null) onMediaClick?.invoke(message)
                                else message.mediaUrl?.let { url -> onImageClicked?.invoke(url) }
                            }
                        }
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

                    waveformVoice.onSeekListener = { progress ->
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (AudioPlayerManager.currentPlayingMessageId != message.id) {
                            if (audioUrl.isNotBlank()) {
                                AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                                AudioPlayerManager.seekToFraction(progress)
                            }
                        } else {
                            AudioPlayerManager.seekToFraction(progress)
                        }
                    }

                    btnPlayPauseVoice.setOnClickListener {
                        val audioUrl = message.mediaUrl.orEmpty()
                        if (audioUrl.isNotBlank()) {
                            AudioPlayerManager.playOrPause(root.context, message.id, audioUrl)
                        } else {
                            onPlayVoiceClicked?.invoke(message)
                        }
                    }

                    btnVoiceSpeed.text = "${AudioPlayerManager.playbackSpeed}x"
                    btnVoiceSpeed.setOnClickListener {
                        val nextSpeed = when (AudioPlayerManager.playbackSpeed) {
                            1.0f -> 1.5f
                            1.5f -> 2.0f
                            else -> 1.0f
                        }
                        AudioPlayerManager.setSpeed(nextSpeed)
                        btnVoiceSpeed.text = "${nextSpeed}x"
                        notifyDataSetChanged()
                    }
                } else {
                    layoutVoiceNote.visibility = View.GONE
                }

                // Multi-Selection Mode support
                val isSelected = selectedMessageIds.contains(message.id)
                if (isSelectionMode) {
                    root.setOnClickListener {
                        toggleSelection(message.id)
                    }
                    root.setBackgroundColor(if (isSelected) 0x3300A884.toInt() else android.graphics.Color.TRANSPARENT)
                } else {
                    root.setOnClickListener(null)
                    root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }

                // Long Click / Swipe to reply
                root.setOnLongClickListener {
                    if (isSelectionMode) {
                        toggleSelection(message.id)
                    } else if (!message.isDeleted) {
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
        // Reuses one formatter (bind runs on the main thread, so no thread-safety concern).
        return timeFormat.format(Date(timeMillis))
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
