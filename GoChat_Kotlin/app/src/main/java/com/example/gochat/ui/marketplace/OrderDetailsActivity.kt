package com.example.gochat.ui.marketplace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.Order
import com.example.gochat.data.model.OrderStatus
import com.example.gochat.data.model.OrderStatusHistoryItem
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityOrderDetailsBinding
import com.example.gochat.databinding.ItemOrderStatusHistoryBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class OrderDetailsActivity : AppCompatActivity() {

    @Inject
    lateinit var marketplaceRepository: MarketplaceRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var tokenManager: TokenManager

    private lateinit var binding: ActivityOrderDetailsBinding
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

    private val colorCompleted = Color.parseColor("#00A884") // GoChat Emerald / Teal
    private val colorInactive = Color.parseColor("#374151")  // Muted gray
    private val colorIconInactive = Color.parseColor("#6B7280")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrderDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val orderJson = intent.getStringExtra("order_json") ?: return finish()
        var currentOrder = try {
            Json.decodeFromString<Order>(orderJson)
        } catch (e: Exception) {
            return finish()
        }

        setupToolbar()
        renderOrder(currentOrder)
        loadLiveUpdates(currentOrder.id)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun renderOrder(order: Order) {
        val orderNum = order.orderNumber.ifBlank { "ORD-${order.id.takeLast(6).uppercase()}" }
        binding.tvOrderNumber.text = "Order #$orderNum"
        binding.tvOrderDate.text = "Placed on ${dateFormat.format(Date(order.createdAt))}"
        binding.tvStoreName.text = order.storeName.ifBlank { "Official Store" }
        binding.tvTotalAmount.text = String.format(Locale.US, "Total: $%.2f", order.totalAmount)

        // Status Badge
        binding.tvOrderStatusBadge.text = when (order.status) {
            OrderStatus.PENDING -> "PENDING"
            OrderStatus.PAID -> "PAID"
            OrderStatus.PROCESSING -> "PROCESSING"
            OrderStatus.SHIPPED -> "SHIPPED"
            OrderStatus.OUT_FOR_DELIVERY -> "OUT FOR DELIVERY"
            OrderStatus.DELIVERED -> "DELIVERED"
            OrderStatus.CANCELLED -> "CANCELLED"
            OrderStatus.REFUNDED -> "REFUNDED"
        }

        val badgeColor = when (order.status) {
            OrderStatus.PENDING -> Color.parseColor("#F59E0B")
            OrderStatus.PAID -> Color.parseColor("#3B82F6")
            OrderStatus.PROCESSING -> Color.parseColor("#6366F1")
            OrderStatus.SHIPPED -> Color.parseColor("#8B5CF6")
            OrderStatus.OUT_FOR_DELIVERY -> Color.parseColor("#EC4899")
            OrderStatus.DELIVERED -> Color.parseColor("#00A884")
            OrderStatus.CANCELLED -> Color.parseColor("#EF4444")
            OrderStatus.REFUNDED -> Color.parseColor("#6B7280")
        }
        binding.tvOrderStatusBadge.backgroundTintList = ColorStateList.valueOf(badgeColor)

        // Destination info
        binding.tvRecipientName.text = order.buyerName.ifBlank { "Valued Customer" }
        binding.tvRecipientPhone.text = order.shippingPhone?.ifBlank { "" } ?: ""
        binding.tvShippingAddress.text = order.shippingAddress?.ifBlank { "Victoria Island, Lagos, Nigeria" } ?: "Victoria Island, Lagos, Nigeria"

        // Chat with Seller
        binding.btnChatWithParty.text = "💬 Chat with ${order.storeName.ifBlank { "Seller" }}"
        binding.btnChatWithParty.setOnClickListener {
            openChatWithOrderParty(order)
        }

        // Setup Live Courier Tracking Card & Stepper
        setupLiveTrackingCard(order)
        setupHorizontalTimeline(order)
    }

    private fun setupLiveTrackingCard(order: Order) {
        val hasCourier = !order.trackingNumber.isNullOrBlank() ||
                order.status == OrderStatus.SHIPPED ||
                order.status == OrderStatus.OUT_FOR_DELIVERY ||
                order.status == OrderStatus.DELIVERED

        if (!hasCourier) {
            binding.cardLiveTracking.visibility = View.GONE
            return
        }

        binding.cardLiveTracking.visibility = View.VISIBLE

        val carrier = order.trackingCarrier?.ifBlank { "GoChat Logistics Partner" } ?: "GoChat Logistics Partner"
        binding.tvCarrierName.text = carrier

        val trkNumber = order.trackingNumber?.ifBlank {
            "GC-${order.id.takeLast(8).uppercase()}"
        } ?: "GC-${order.id.takeLast(8).uppercase()}"
        binding.tvTrackingNumber.text = trkNumber

        // Estimated Delivery
        if (!order.estimatedDeliveryDate.isNullOrBlank()) {
            binding.tvEstimatedDelivery.visibility = View.VISIBLE
            binding.tvEstimatedDelivery.text = "Est: ${order.estimatedDeliveryDate}"
        } else {
            binding.tvEstimatedDelivery.visibility = View.VISIBLE
            binding.tvEstimatedDelivery.text = when (order.status) {
                OrderStatus.DELIVERED -> "Delivered"
                OrderStatus.OUT_FOR_DELIVERY -> "Arriving Today"
                else -> "In Transit"
            }
        }

        // Delivery Notes / Live scan
        val notes = order.deliveryNotes?.ifBlank {
            when (order.status) {
                OrderStatus.OUT_FOR_DELIVERY -> "Courier rider is en route to delivery destination."
                OrderStatus.DELIVERED -> "Package successfully received and confirmed."
                OrderStatus.SHIPPED -> "Package in transit through local sorting facility."
                else -> "Awaiting carrier pickup and first dispatch scan."
            }
        } ?: "Courier is processing shipment."
        binding.tvDeliveryNotes.text = notes

        // 1-Click Copy Tracking Number
        binding.btnCopyTracking.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Tracking Number", trkNumber)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Tracking number copied to clipboard! 📋", Toast.LENGTH_SHORT).show()
        }

        // Web Tracking Button
        binding.btnTrackPackage.setOnClickListener {
            val trackingUrl = order.trackingUrl
            if (!trackingUrl.isNullOrBlank() && (trackingUrl.startsWith("http://") || trackingUrl.startsWith("https://"))) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(trackingUrl))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open tracking page", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Open web search for tracking number as helpful fallback
                val searchUrl = "https://www.google.com/search?q=${Uri.encode("$carrier tracking $trkNumber")}"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Tracking: $trkNumber via $carrier", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupHorizontalTimeline(order: Order) {
        // Step indices:
        // 1: Payment Confirmed (PAID, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED)
        // 2: Shipped (SHIPPED, OUT_FOR_DELIVERY, DELIVERED)
        // 3: Out for Delivery (OUT_FOR_DELIVERY, DELIVERED)
        // 4: Delivered (DELIVERED)
        val stepLevel = when (order.status) {
            OrderStatus.PENDING, OrderStatus.CANCELLED -> 0
            OrderStatus.PAID, OrderStatus.PROCESSING -> 1
            OrderStatus.SHIPPED -> 2
            OrderStatus.OUT_FOR_DELIVERY -> 3
            OrderStatus.DELIVERED -> 4
            OrderStatus.REFUNDED -> 1
        }

        // Step 1: Payment Confirmed
        val step1Active = stepLevel >= 1
        binding.dotStep1.backgroundTintList = ColorStateList.valueOf(if (step1Active) colorCompleted else colorInactive)
        binding.iconStep1.imageTintList = ColorStateList.valueOf(if (step1Active) Color.WHITE else colorIconInactive)
        binding.tvStep1Label.setTextColor(if (step1Active) Color.WHITE else colorIconInactive)
        binding.tvStep1Time.text = shortDateFormat.format(Date(order.createdAt))

        // Line 1 -> 2
        val line1to2Active = stepLevel >= 2
        binding.lineStep1to2.setBackgroundColor(if (line1to2Active) colorCompleted else colorInactive)

        // Step 2: Shipped
        val step2Active = stepLevel >= 2
        binding.dotStep2.backgroundTintList = ColorStateList.valueOf(if (step2Active) colorCompleted else colorInactive)
        binding.iconStep2.imageTintList = ColorStateList.valueOf(if (step2Active) Color.WHITE else colorIconInactive)
        binding.tvStep2Label.setTextColor(if (step2Active) Color.WHITE else colorIconInactive)
        binding.tvStep2Time.text = if (step2Active) "Shipped" else "Pending"

        // Line 2 -> 3
        val line2to3Active = stepLevel >= 3
        binding.lineStep2to3.setBackgroundColor(if (line2to3Active) colorCompleted else colorInactive)

        // Step 3: Out for Delivery
        val step3Active = stepLevel >= 3
        binding.dotStep3.backgroundTintList = ColorStateList.valueOf(if (step3Active) colorCompleted else colorInactive)
        binding.iconStep3.imageTintList = ColorStateList.valueOf(if (step3Active) Color.WHITE else colorIconInactive)
        binding.tvStep3Label.setTextColor(if (step3Active) Color.WHITE else colorIconInactive)
        binding.tvStep3Time.text = if (step3Active) "On the way" else "Pending"

        // Line 3 -> 4
        val line3to4Active = stepLevel >= 4
        binding.lineStep3to4.setBackgroundColor(if (line3to4Active) colorCompleted else colorInactive)

        // Step 4: Delivered
        val step4Active = stepLevel >= 4
        binding.dotStep4.backgroundTintList = ColorStateList.valueOf(if (step4Active) colorCompleted else colorInactive)
        binding.iconStep4.imageTintList = ColorStateList.valueOf(if (step4Active) Color.WHITE else colorIconInactive)
        binding.tvStep4Label.setTextColor(if (step4Active) Color.WHITE else colorIconInactive)
        binding.tvStep4Time.text = if (step4Active) "Delivered" else "Pending"
    }

    private fun loadLiveUpdates(orderId: String) {
        lifecycleScope.launch {
            // 1. Fetch fresh order details from server (in case webhook updated status)
            val orderResult = marketplaceRepository.getOrderById(orderId)
            orderResult.onSuccess { updatedOrder ->
                renderOrder(updatedOrder)
            }

            // 2. Fetch status history
            val historyResult = marketplaceRepository.getOrderStatusHistory(orderId)
            historyResult.onSuccess { historyList ->
                displayHistoryList(historyList)
            }.onFailure {
                binding.tvHistoryEmpty.visibility = View.VISIBLE
                binding.layoutHistoryList.visibility = View.GONE
            }
        }
    }

    private fun displayHistoryList(history: List<OrderStatusHistoryItem>) {
        if (history.isEmpty()) {
            binding.tvHistoryEmpty.visibility = View.VISIBLE
            binding.layoutHistoryList.visibility = View.GONE
            return
        }

        binding.tvHistoryEmpty.visibility = View.GONE
        binding.layoutHistoryList.visibility = View.VISIBLE
        binding.layoutHistoryList.removeAllViews()

        val inflater = LayoutInflater.from(this)
        // Show in reverse chronological order (newest first)
        val sortedList = history.reversed()

        sortedList.forEachIndexed { index, item ->
            val itemBinding = ItemOrderStatusHistoryBinding.inflate(inflater, binding.layoutHistoryList, false)

            val statusText = when (item.toStatus.lowercase()) {
                "out_for_delivery" -> "🚚 Out for Delivery"
                "shipped" -> "📦 Shipped & In Transit"
                "delivered" -> "✅ Order Delivered"
                "paid" -> "💳 Payment Confirmed"
                "processing" -> "⚙️ Processing by Seller"
                "cancelled" -> "❌ Order Cancelled"
                "refunded" -> "💰 Order Refunded"
                else -> item.toStatus.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            itemBinding.tvHistoryStatus.text = statusText
            itemBinding.tvHistoryReason.text = item.changeReason?.ifBlank { "Status changed to ${item.toStatus}" } ?: "Status changed"
            itemBinding.tvHistoryTime.text = formatHistoryTimestamp(item.createdAt)

            // Last item hides connector line
            if (index == sortedList.lastIndex) {
                itemBinding.historyLine.visibility = View.GONE
            } else {
                itemBinding.historyLine.visibility = View.VISIBLE
            }

            binding.layoutHistoryList.addView(itemBinding.root)
        }
    }

    private fun formatHistoryTimestamp(raw: String): String {
        if (raw.isBlank()) return "Recently"
        return try {
            val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val date = isoParser.parse(raw)
            if (date != null) dateFormat.format(date) else raw
        } catch (_: Exception) {
            raw
        }
    }

    private fun openChatWithOrderParty(order: Order) {
        val currentUserId = tokenManager.userId.orEmpty()
        val isSeller = currentUserId.isNotBlank() && currentUserId != order.buyerId && (currentUserId == order.userId || currentUserId == order.storeId)

        val targetTitle = if (isSeller) order.buyerName.ifBlank { "Customer" } else order.storeName.ifBlank { "Seller" }
        binding.btnChatWithParty.isEnabled = false
        binding.btnChatWithParty.text = "Connecting…"

        lifecycleScope.launch {
            try {
                val targetUserId = if (isSeller) {
                    if (order.buyerId.isNotBlank()) {
                        order.buyerId
                    } else if (order.buyerPin.isNotBlank()) {
                        authRepository.lookupUserByPin(order.buyerPin).getOrNull()?.id.orEmpty()
                    } else ""
                } else {
                    val store = marketplaceRepository.getStore(order.storeId).getOrNull()
                    if (!store?.ownerId.isNullOrBlank()) {
                        store?.ownerId.orEmpty()
                    } else if (!store?.ownerPin.isNullOrBlank()) {
                        authRepository.lookupUserByPin(store?.ownerPin.orEmpty()).getOrNull()?.id.orEmpty()
                    } else ""
                }

                if (targetUserId.isBlank()) {
                    Toast.makeText(this@OrderDetailsActivity, "Chat partner contact unavailable", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val existingConv = withContext(Dispatchers.IO) {
                    chatRepository.getAllConversationsList().firstOrNull { conv ->
                        !conv.isGroup && conv.memberIds.contains(targetUserId)
                    }
                }

                val conversation = existingConv ?: run {
                    val createResult = chatRepository.createConversation(
                        name = targetTitle,
                        memberIds = listOf(targetUserId),
                        isGroup = false
                    )
                    createResult.getOrNull()
                }

                if (conversation == null) {
                    Toast.makeText(this@OrderDetailsActivity, "Failed to start chat. Please try again.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val intent = Intent(this@OrderDetailsActivity, ChatRoomActivity::class.java).apply {
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, targetTitle)
                    putExtra(ChatRoomActivity.EXTRA_ORDER_ID, order.id)
                    putExtra(ChatRoomActivity.EXTRA_ORDER_NUMBER, order.orderNumber)
                    putExtra(ChatRoomActivity.EXTRA_ORDER_TOTAL, order.totalAmount)
                    putExtra(ChatRoomActivity.EXTRA_IS_ONLINE, conversation.isOnline)
                    putExtra(ChatRoomActivity.EXTRA_LAST_SEEN, conversation.lastSeen ?: 0L)
                    val partnerId = conversation.memberIds.firstOrNull { it != currentUserId } ?: targetUserId
                    putExtra(ChatRoomActivity.EXTRA_PARTNER_ID, partnerId)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@OrderDetailsActivity, "Connection error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnChatWithParty.isEnabled = true
                binding.btnChatWithParty.text = "💬 Chat with $targetTitle"
            }
        }
    }
}
