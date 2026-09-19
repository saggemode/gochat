package com.example.gochat.ui.marketplace

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.data.model.Order
import com.example.gochat.data.model.OrderStatus
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityOrdersBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class OrdersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrdersBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository

    @Inject
    lateinit var chatRepository: com.example.gochat.data.repository.ChatRepository

    @Inject
    lateinit var authRepository: com.example.gochat.data.repository.AuthRepository

    @Inject
    lateinit var tokenManager: com.example.gochat.data.api.TokenManager
    
    private var isSellerView: Boolean = false
    private var currentOrders: List<Order> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadOrders()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tabLayoutOrders.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isSellerView = (tab?.position == 1)
                loadOrders()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.swipeRefresh.setOnRefreshListener {
            loadOrders()
        }
    }

    private fun loadOrders() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = if (isSellerView) repository.getSellerOrders() else repository.getBuyerOrders()
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            if (result.isSuccess) {
                currentOrders = result.getOrDefault(emptyList())
                displayOrders(currentOrders)
            } else {
                Toast.makeText(this@OrdersActivity, "Failed to load orders", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayOrders(orders: List<Order>) {
        val adapter = OrderAdapter(
            isSellerView = isSellerView,
            onUpdateStatus = { order, nextStatus ->
                updateOrderStatus(order, nextStatus)
            },
            onChatClick = { order ->
                openChatWithParty(order)
            }
        )
        binding.rvOrders.adapter = adapter
        binding.rvOrders.layoutManager = LinearLayoutManager(this@OrdersActivity)
        adapter.submitList(orders)

        binding.tvEmptyOrders.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyOrders.text = if (isSellerView) {
            "No incoming sales orders yet.\nOrders from your store buyers will appear here."
        } else {
            "No purchases yet.\nExplore the Marketplace and place your first order!"
        }
    }

    private fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.updateOrderStatus(order.id, nextStatus)
            binding.progressBar.visibility = View.GONE
            if (result.isSuccess) {
                Toast.makeText(this@OrdersActivity, "Order status updated to ${nextStatus.name}", Toast.LENGTH_SHORT).show()

                // Post system message to the chat room for this order
                val orderNum = order.orderNumber.ifBlank { "ORD-${order.id.takeLast(6)}" }
                val (icon, desc) = when (nextStatus) {
                    OrderStatus.PAID -> "💳" to "payment has been confirmed and secured in Escrow"
                    OrderStatus.PROCESSING -> "⚙️" to "is now being prepared for shipment"
                    OrderStatus.SHIPPED -> "📦" to "has been marked as shipped 🚚"
                    OrderStatus.DELIVERED -> "✅" to "has been delivered to customer"
                    OrderStatus.CANCELLED -> "❌" to "has been cancelled"
                    OrderStatus.REFUNDED -> "↩️" to "has been refunded"
                    else -> "📦" to "status updated to ${nextStatus.name.lowercase()}"
                }
                val systemMessageText = "$icon Order #$orderNum $desc"
                val existingConv = withContext(Dispatchers.IO) {
                    val store = repository.getStore(order.storeId).getOrNull()
                    val targetUserId = store?.ownerId.orEmpty()
                    if (targetUserId.isNotBlank()) {
                        chatRepository.getAllConversationsList().firstOrNull { conv ->
                            !conv.isGroup && (conv.memberIds.contains(targetUserId) || conv.memberIds.contains(order.buyerId))
                        }
                    } else null
                }
                if (existingConv != null) {
                    chatRepository.sendSystemMessage(existingConv.id, systemMessageText)
                }

                loadOrders()
            } else {
                Toast.makeText(this@OrdersActivity, "Failed to update order status", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChatWithParty(order: Order) {
        val currentUserId = tokenManager.userId.orEmpty()
        val targetTitle = if (isSellerView) order.buyerName.ifBlank { "Customer" } else order.storeName.ifBlank { "Seller" }

        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val targetUserId = if (isSellerView) {
                    if (order.buyerId.isNotBlank()) {
                        order.buyerId
                    } else if (order.buyerPin.isNotBlank()) {
                        authRepository.lookupUserByPin(order.buyerPin).getOrNull()?.id.orEmpty()
                    } else ""
                } else {
                    val store = repository.getStore(order.storeId).getOrNull()
                    if (!store?.ownerId.isNullOrBlank()) {
                        store?.ownerId.orEmpty()
                    } else if (!store?.ownerPin.isNullOrBlank()) {
                        authRepository.lookupUserByPin(store?.ownerPin.orEmpty()).getOrNull()?.id.orEmpty()
                    } else ""
                }

                if (targetUserId.isBlank()) {
                    Toast.makeText(this@OrdersActivity, "Chat partner contact unavailable", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@OrdersActivity, "Failed to start chat. Please try again.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val intent = Intent(this@OrdersActivity, ChatRoomActivity::class.java).apply {
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
                Toast.makeText(this@OrdersActivity, "Connection error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
