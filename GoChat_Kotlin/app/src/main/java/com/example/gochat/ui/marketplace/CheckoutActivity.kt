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

    @Inject
    lateinit var addressDao: com.example.gochat.data.db.AddressDao

    @Inject
    lateinit var tokenManager: com.example.gochat.data.api.TokenManager
    
    private var cartItems: List<CartItem> = emptyList()
    private var deliveryFee: Double = 0.0
    private var discount: Double = 0.0
    private var appliedCoupon: String = ""
    private var resolvedSellerId: String = ""
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
        binding.btnPlaceOrder.text = getString(R.string.checkout_confirm_pay)

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
        loadSavedAddresses()
    }

    /** Loads the buyer's saved addresses as tap-to-fill chips above the field. */
    private fun loadSavedAddresses() {
        lifecycleScope.launch {
            val addresses = try { addressDao.getAddresses() } catch (_: Exception) { emptyList() }
            val group = binding.chipGroupAddresses
            group.removeAllViews()
            if (addresses.isEmpty()) {
                group.visibility = View.GONE
                return@launch
            }
            group.visibility = View.VISIBLE
            addresses.forEach { addr ->
                val chip = com.google.android.material.chip.Chip(this@CheckoutActivity).apply {
                    text = addr.label.ifBlank { addr.fullAddress.take(24) }
                    isCheckable = false
                    setOnClickListener { binding.etAddress.setText(addr.fullAddress) }
                }
                group.addView(chip)
            }
            // Prefill with the default/most-recent address if the field is empty.
            if (binding.etAddress.text.isNullOrBlank()) {
                binding.etAddress.setText(addresses.first().fullAddress)
            }
        }
    }

    /** Saves the address used for an order so it can be reused next time. */
    private suspend fun rememberAddress(address: String) {
        if (address.isBlank()) return
        try {
            if (addressDao.findByAddress(address) == null) {
                addressDao.insert(
                    com.example.gochat.data.model.SavedAddress(
                        id = java.util.UUID.randomUUID().toString(),
                        fullAddress = address,
                        phone = tokenManager.userPhone.orEmpty()
                    )
                )
            }
        } catch (_: Exception) {}
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
                Toast.makeText(this@CheckoutActivity, getString(R.string.checkout_cart_update_failed), Toast.LENGTH_SHORT).show()
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
        binding.btnApplyCoupon.setOnClickListener { applyCoupon() }
    }

    private fun loadSummary() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.getCart()
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                cartItems = result.getOrDefault(emptyList())
                displaySummary(cartItems)
                resolveDeliveryFee(cartItems)
            } else {
                Toast.makeText(this@CheckoutActivity, getString(R.string.error_load_cart_summary), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /** Looks up the seller's flat delivery fee (via the first product's store). */
    private fun resolveDeliveryFee(items: List<CartItem>) {
        val productId = items.firstOrNull()?.productId ?: return
        lifecycleScope.launch {
            var sellerId = items.firstOrNull()?.storeId.orEmpty()
            if (sellerId.isBlank()) {
                sellerId = repository.getProductById(productId).getOrNull()?.sellerId.orEmpty()
            }
            if (sellerId.isNotBlank()) {
                resolvedSellerId = sellerId
                deliveryFee = repository.getStore(sellerId).getOrNull()?.deliveryFee ?: 0.0
                displaySummary(cartItems)
                maybeAutoApplyBestCoupon()
            }
        }
    }

    /**
     * On load, if the buyer hasn't entered a code, ask the backend for the best
     * auto-apply coupon for this cart and apply it automatically. The buyer can
     * still overwrite it by typing their own code.
     */
    private fun maybeAutoApplyBestCoupon() {
        if (appliedCoupon.isNotBlank() || resolvedSellerId.isBlank()) return
        val subtotal = cartItems.sumOf { (it.productPrice ?: 0.0) * it.quantity }
        if (subtotal <= 0.0) return
        lifecycleScope.launch {
            val best = repository.getBestCoupon(
                resolvedSellerId, subtotal, cartItems.map { it.productId }
            ).getOrNull() ?: return@launch
            if (appliedCoupon.isNotBlank()) return@launch // buyer applied one meanwhile
            if (best.discount > 0) {
                discount = best.discount
                appliedCoupon = best.code
                binding.etCoupon.setText(best.code)
                Toast.makeText(
                    this@CheckoutActivity,
                    getString(R.string.checkout_coupon_auto_applied, best.code),
                    Toast.LENGTH_SHORT
                ).show()
                displaySummary(cartItems)
            }
        }
    }

    private fun applyCoupon() {
        val code = binding.etCoupon.text.toString().trim()
        if (code.isBlank()) return
        if (resolvedSellerId.isBlank()) {
            Toast.makeText(this, getString(R.string.checkout_coupon_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val subtotal = cartItems.sumOf { (it.productPrice ?: 0.0) * it.quantity }
        lifecycleScope.launch {
            val res = repository.validateCoupon(
                resolvedSellerId, code, subtotal, cartItems.map { it.productId }
            ).getOrNull()
            if (res?.valid == true && res.discount > 0) {
                discount = res.discount
                appliedCoupon = code
                Toast.makeText(this@CheckoutActivity, getString(R.string.checkout_coupon_applied), Toast.LENGTH_SHORT).show()
            } else {
                discount = 0.0
                appliedCoupon = ""
                Toast.makeText(this@CheckoutActivity, res?.message?.ifBlank { getString(R.string.checkout_coupon_invalid) } ?: getString(R.string.checkout_coupon_invalid), Toast.LENGTH_LONG).show()
            }
            displaySummary(cartItems)
        }
    }

    private fun displaySummary(items: List<CartItem>) {
        cartItems = items
        itemsAdapter.submitList(items)

        val subtotal = items.sumOf { (it.productPrice ?: 0.0) * it.quantity }
        binding.tvSubtotalAmount.text = CurrencyManager.formatPrice(subtotal)
        binding.tvDeliveryAmount.text = if (deliveryFee > 0) CurrencyManager.formatPrice(deliveryFee) else getString(R.string.checkout_free)
        if (discount > 0) {
            binding.rowDiscount.visibility = View.VISIBLE
            binding.tvDiscountAmount.text = "-${CurrencyManager.formatPrice(discount)}"
        } else {
            binding.rowDiscount.visibility = View.GONE
        }
        val total = (subtotal + deliveryFee - discount).coerceAtLeast(0.0)
        binding.tvTotalAmount.text = CurrencyManager.formatPrice(total)

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
            val result = repository.placeOrder(storeId, cartItems, total, address, clearCartOnSuccess = false, couponCode = appliedCoupon)
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                val orders = result.getOrNull().orEmpty()
                val order = orders.firstOrNull() // primary order for the success UI below

                // Collect payment for ALL created orders (one per seller) before
                // finalising. If payments aren't configured, runPaystack returns true.
                val paid = if (orders.isNotEmpty()) runPaystack(orders.map { it.id }) else false
                if (!paid) {
                    Toast.makeText(
                        this@CheckoutActivity,
                        getString(R.string.checkout_payment_incomplete),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                repository.clearCart()
                rememberAddress(address)

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
                val err = result.exceptionOrNull()
                if (err is MarketplaceRepository.OutOfStockException) {
                    Toast.makeText(
                        this@CheckoutActivity,
                        err.message?.takeIf { it.isNotBlank() } ?: getString(R.string.checkout_out_of_stock),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@CheckoutActivity, getString(R.string.error_place_order), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Runs the Paystack checkout for an order and returns whether it was paid.
     * Opens the hosted checkout in [PaystackPaymentActivity], then confirms the
     * result server-side via verify. If the backend has no Paystack key, returns
     * true so checkout falls back to the legacy immediate-order behaviour.
     */
    private suspend fun runPaystack(orderIds: List<String>): Boolean {
        if (orderIds.isEmpty()) return false
        val initRes = repository.initPaystackPayment(orderIds, null)
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
