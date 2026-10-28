package com.example.gochat.ui.marketplace

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.example.gochat.R
import com.example.gochat.core.haptic.HapticEngine
import com.example.gochat.data.model.CartItem
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityCheckoutBinding
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarMargins
import dagger.hilt.android.AndroidEntryPoint
import com.example.gochat.core.currency.CurrencyManager
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
    private lateinit var itemsAdapter: CartAdapter

    // Bridges the Paystack checkout Activity result back to the awaiting coroutine.
    private var paymentContinuation: ((String?) -> Unit)? = null
    private val paymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val ref = res.data?.getStringExtra(PaystackPaymentActivity.RESULT_REFERENCE)
        paymentContinuation?.invoke(ref)
        paymentContinuation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.applyStatusBarInset()
        binding.btnPlaceOrder.applySystemBarMargins(bottom = true)
        // Step 5/6: the action confirms the order and starts payment.
        binding.btnPlaceOrder.text = "Confirm & Pay"

        // Live cart badge — shared source of truth with every other cart icon.
        lifecycleScope.launch {
            repository.observeCartCount().collect { count ->
                binding.tvCartBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
                binding.tvCartBadge.text = if (count > 99) "99+" else count.toString()
            }
        }

        itemsAdapter = CartAdapter(
            onRemove = { item -> changeQuantity(item, 0) },
            onQuantityChange = { item, q -> changeQuantity(item, q) }
        )
        binding.rvCheckoutItems.apply {
            adapter = itemsAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@CheckoutActivity)
        }

        setupListeners()
        loadSummary()
    }

    /** Lets the buyer change quantity or remove a line right on the checkout page. */
    private fun changeQuantity(item: CartItem, quantity: Int) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = if (quantity <= 0) {
                repository.removeCartItemById(item.id)
            } else {
                repository.updateCartItem(item.id, quantity)
            }
            binding.progressBar.visibility = View.GONE
            if (result.isSuccess) {
                displaySummary(result.getOrDefault(emptyList()))
            } else {
                Toast.makeText(this@CheckoutActivity, "Could not update cart", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCartIcon.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }
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
        cartItems = items
        itemsAdapter.submitList(items)

        val subtotal = items.sumOf { (it.productPrice ?: 0.0) * it.quantity }
        binding.tvSubtotalAmount.text = CurrencyManager.formatPrice(subtotal)
        binding.tvTotalAmount.text = CurrencyManager.formatPrice(subtotal)

        val empty = items.isEmpty()
        binding.tvEmptyCheckout.visibility = if (empty) View.VISIBLE else View.GONE
        binding.btnPlaceOrder.isEnabled = !empty
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
            
            // Keep the cart until payment is confirmed (online payment flow).
            val result = repository.placeOrder(storeId, cartItems, total, address, clearCartOnSuccess = false)
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                val order = result.getOrNull()

                // Collect payment before finalising. If payments aren't configured
                // on the backend, runPaystack returns true (legacy immediate order).
                val paid = if (order != null) runPaystack(order.id) else false
                if (!paid) {
                    Toast.makeText(
                        this@CheckoutActivity,
                        "Payment not completed. You can finish it from My Orders.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                repository.clearCart()

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
                                put("currency", CurrencyManager.getUserCurrency())
                                put("currency_symbol", CurrencyManager.getUserCurrencySymbol())
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

    /**
     * Runs the Paystack checkout for an order and returns whether it was paid.
     * Opens the hosted checkout in [PaystackPaymentActivity], then confirms the
     * result server-side via verify. If the backend has no Paystack key, returns
     * true so checkout falls back to the legacy immediate-order behaviour.
     */
    private suspend fun runPaystack(orderId: String): Boolean {
        val initRes = repository.initPaystackPayment(listOf(orderId), null)
        if (initRes.isFailure) {
            return initRes.exceptionOrNull() is MarketplaceRepository.PaymentsNotConfigured
        }
        val init = initRes.getOrNull() ?: return false

        val returnedRef = suspendCancellableCoroutine<String?> { cont ->
            paymentContinuation = { ref -> if (cont.isActive) cont.resume(ref) }
            val intent = Intent(this, PaystackPaymentActivity::class.java).apply {
                putExtra(PaystackPaymentActivity.EXTRA_AUTHORIZATION_URL, init.authorizationUrl)
                putExtra(PaystackPaymentActivity.EXTRA_REFERENCE, init.reference)
            }
            paymentLauncher.launch(intent)
            cont.invokeOnCancellation { paymentContinuation = null }
        }

        val reference = returnedRef ?: init.reference
        return repository.verifyPaystackPayment(reference).getOrDefault(false)
    }
}
