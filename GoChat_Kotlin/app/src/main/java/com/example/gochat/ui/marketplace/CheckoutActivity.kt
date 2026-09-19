package com.example.gochat.ui.marketplace

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.core.haptic.HapticEngine
import com.example.gochat.data.model.CartItem
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityCheckoutBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject

@AndroidEntryPoint
class CheckoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckoutBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository

    @Inject
    lateinit var chatRepository: com.example.gochat.data.repository.ChatRepository

    @Inject
    lateinit var authRepository: com.example.gochat.data.repository.AuthRepository
    
    private var cartItems: List<CartItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadSummary()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnPlaceOrder.setOnClickListener {
            placeOrder()
        }
    }

    private fun loadSummary() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.getCart()
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                cartItems = result.getOrDefault(emptyList())
                displaySummary(cartItems)
            } else {
                Toast.makeText(this@CheckoutActivity, getString(R.string.error_load_cart_summary), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun displaySummary(items: List<CartItem>) {
        binding.layoutSummary.removeAllViews()
        var total = 0.0
        for (item in items) {
            val tv = TextView(this).apply {
                text = getString(R.string.order_summary_item_format, item.productName, item.quantity, ((item.productPrice ?: 0.0) * item.quantity).toString())
                setPadding(0, 8, 0, 8)
                setTextColor(Color.BLACK) // Or use a resource
            }
            binding.layoutSummary.addView(tv)
            total += (item.productPrice ?: 0.0) * item.quantity
        }
        binding.tvTotalAmount.text = getString(R.string.price_format_usd, total.toString())
    }

    private fun placeOrder() {
        val address = binding.etAddress.text.toString()
        if (address.isBlank()) {
            Toast.makeText(this, getString(R.string.error_empty_address), Toast.LENGTH_SHORT).show()
            return
        }

        if (cartItems.isEmpty()) return

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val total = cartItems.sumOf { (it.productPrice ?: 0.0) * it.quantity }
            val storeId = cartItems.firstOrNull()?.storeId?.ifBlank { "store_istore" } ?: "store_istore"
            
            val result = repository.placeOrder(storeId, cartItems, total, address)
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                val order = result.getOrNull()
                if (order != null) {
                    val store = repository.getStore(order.storeId).getOrNull()
                    val targetUserId = if (!store?.ownerId.isNullOrBlank()) {
                        store?.ownerId.orEmpty()
                    } else if (!store?.ownerPin.isNullOrBlank()) {
                        authRepository.lookupUserByPin(store?.ownerPin.orEmpty()).getOrNull()?.id.orEmpty()
                    } else ""

                    val existingConv = if (targetUserId.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            chatRepository.getAllConversationsList().firstOrNull { c ->
                                !c.isGroup && c.memberIds.contains(targetUserId)
                            }
                        }
                    } else null

                    val conversation = existingConv ?: if (targetUserId.isNotBlank()) {
                        chatRepository.createConversation(
                            name = order.storeName.ifBlank { "Official Store" },
                            memberIds = listOf(targetUserId),
                            isGroup = false
                        ).getOrNull()
                    } else null

                    val realConvId = conversation?.id.orEmpty()
                    if (realConvId.isNotBlank()) {
                        val itemsSummary = if (order.items.isNotEmpty()) {
                            order.items.joinToString(", ") { "${it.productName} (x${it.quantity})" }
                        } else {
                            "Marketplace Order"
                        }
                        val orderJson = kotlinx.serialization.json.buildJsonObject {
                            put("type", "order")
                            put("order", kotlinx.serialization.json.buildJsonObject {
                                put("id", order.id)
                                put("order_number", order.orderNumber)
                                put("store_id", order.storeId)
                                put("store_name", order.storeName)
                                put("buyer_id", order.buyerId)
                                put("buyer_name", order.buyerName)
                                put("total_amount", order.totalAmount)
                                put("status", order.status.name)
                                put("shipping_address", order.shippingAddress ?: address)
                                put("items_count", order.items.size)
                                put("items_summary", itemsSummary)
                                put("created_at", order.createdAt)
                            })
                        }.toString()

                        chatRepository.sendMessage(
                            conversationId = realConvId,
                            content = orderJson,
                            type = 9 // Order
                        )
                    }
                }

                HapticEngine.playPaymentConfirmed(this@CheckoutActivity)
                Toast.makeText(this@CheckoutActivity, getString(R.string.toast_order_placed_success), Toast.LENGTH_LONG).show()

                // Offer immediate navigation to the linked seller chat thread or orders overview
                android.app.AlertDialog.Builder(this@CheckoutActivity)
                    .setTitle("Order Placed Successfully! 🎉")
                    .setMessage("Your order has been sent to ${order?.storeName ?: "the seller"}. Would you like to chat with them about your order?")
                    .setPositiveButton("Chat with Seller") { _, _ ->
                        val storeId = order?.storeId.orEmpty()
                        val storeName = order?.storeName ?: "Seller"
                        lifecycleScope.launch {
                            val store = if (storeId.isNotBlank()) repository.getStore(storeId).getOrNull() else null
                            val targetUserId = if (!store?.ownerId.isNullOrBlank()) {
                                store?.ownerId.orEmpty()
                            } else if (!store?.ownerPin.isNullOrBlank()) {
                                authRepository.lookupUserByPin(store?.ownerPin.orEmpty()).getOrNull()?.id.orEmpty()
                            } else ""

                            val conv = if (targetUserId.isNotBlank()) {
                                val existing = withContext(Dispatchers.IO) {
                                    chatRepository.getAllConversationsList().firstOrNull { c ->
                                        !c.isGroup && c.memberIds.contains(targetUserId)
                                    }
                                }
                                existing ?: chatRepository.createConversation(
                                    name = storeName,
                                    memberIds = listOf(targetUserId),
                                    isGroup = false
                                ).getOrNull()
                            } else null

                            if (conv != null) {
                                val chatIntent = Intent(this@CheckoutActivity, com.example.gochat.ui.chat.ChatRoomActivity::class.java).apply {
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_CONVERSATION_ID, conv.id)
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_CONVERSATION_TITLE, storeName)
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_ORDER_ID, order?.id)
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_ORDER_NUMBER, order?.orderNumber)
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_ORDER_TOTAL, order?.totalAmount ?: total)
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_IS_ONLINE, conv.isOnline)
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_LAST_SEEN, conv.lastSeen ?: 0L)
                                    val partnerId = conv.memberIds.firstOrNull() ?: targetUserId
                                    putExtra(com.example.gochat.ui.chat.ChatRoomActivity.EXTRA_PARTNER_ID, partnerId)
                                }
                                startActivity(chatIntent)
                            } else {
                                startActivity(Intent(this@CheckoutActivity, OrdersActivity::class.java))
                            }
                            finish()
                        }
                    }
                    .setNegativeButton("View Orders") { _, _ ->
                        startActivity(Intent(this@CheckoutActivity, OrdersActivity::class.java))
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(this@CheckoutActivity, getString(R.string.error_place_order), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
