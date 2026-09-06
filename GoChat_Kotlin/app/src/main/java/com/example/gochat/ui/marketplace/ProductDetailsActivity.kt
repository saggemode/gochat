package com.example.gochat.ui.marketplace

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityProductDetailsBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import kotlinx.coroutines.launch
import java.util.Locale

class ProductDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductDetailsBinding
    private val repository by lazy { MarketplaceRepository(this) }
    private var product: Product? = null
    private var store: Store? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val productId = intent.getStringExtra("product_id") ?: return finish()

        setupListeners()
        loadProductDetails(productId)
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // WhatsApp-style inquiry: Chat with Seller
        binding.btnChatSeller.setOnClickListener {
            val p = product ?: return@setOnClickListener
            openChatWithSeller(p)
        }

        binding.btnAddToCart.setOnClickListener {
            product?.let { addToCart(it, andProceedToCheckout = false) }
        }

        binding.btnBuyNow.setOnClickListener {
            product?.let { addToCart(it, andProceedToCheckout = true) }
        }

        binding.layoutStore.setOnClickListener {
            val s = store ?: product?.let { Store(id = it.storeId, name = it.storeName) }
            s?.let {
                val intent = Intent(this, StorefrontActivity::class.java).apply {
                    putExtra("store_id", it.id)
                }
                startActivity(intent)
            }
        }
    }

    private fun loadProductDetails(productId: String) {
        lifecycleScope.launch {
            val result = repository.getProducts()
            product = result.getOrNull()?.find { it.id == productId }

            product?.let { p ->
                displayProduct(p)
                loadStore(p.storeId)
            } ?: run {
                Toast.makeText(this@ProductDetailsActivity, getString(R.string.error_product_not_found), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadStore(storeId: String) {
        lifecycleScope.launch {
            val result = repository.getStore(storeId)
            store = result.getOrNull()
            store?.let { displayStore(it) }
        }
    }

    private fun displayProduct(product: Product) {
        binding.tvProductName.text = product.displayTitle
        binding.tvProductPrice.text = String.format(Locale.US, "$%.2f", product.price)

        if (product.hasDiscount) {
            binding.tvOriginalPrice.visibility = View.VISIBLE
            binding.tvOriginalPrice.paintFlags = binding.tvOriginalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            binding.tvOriginalPrice.text = String.format(Locale.US, "$%.2f", product.originalPrice)

            binding.tvDiscountBadge.visibility = View.VISIBLE
            binding.tvDiscountBadge.text = String.format(Locale.US, "-%d%%", product.discountPercent)
        } else {
            binding.tvOriginalPrice.visibility = View.GONE
            binding.tvDiscountBadge.visibility = View.GONE
        }

        binding.tvRating.text = String.format(Locale.US, "%.1f ★ (%d)", product.rating, product.reviewsCount)
        binding.tvProductDescription.text = product.description.ifBlank {
            "Available on GoChat Marketplace. Fast delivery and secure escrow protection guaranteed."
        }

        MediaImageHelper.loadSafeImage(binding.ivProductImage, product.primaryImage)

        // Store initial display before fetch finishes
        binding.tvStoreName.text = product.storeName
        binding.tvStoreRating.text = String.format(Locale.US, "%s • PIN: %s", product.sellerLocation, product.sellerPin.ifBlank { "1P0YE4WZ" })
    }

    private fun displayStore(store: Store) {
        binding.tvStoreName.text = store.name
        binding.tvStoreRating.text = String.format(
            Locale.US,
            "%s • PIN: %s",
            store.address.ifBlank { "Lagos, Nigeria" },
            store.ownerPin.ifBlank { "1P0YE4WZ" }
        )
        binding.ivStoreVerified.visibility = if (store.isVerified) View.VISIBLE else View.GONE
        MediaImageHelper.loadSafeImage(binding.ivStoreLogo, store.logoUrl, isCircle = true)
    }

    private fun openChatWithSeller(product: Product) {
        val inquiry = String.format(
            Locale.US,
            "👋 Hi %s! I am interested in purchasing \"%s\" listed for $%.2f on GoChat Marketplace.",
            product.storeName,
            product.displayTitle,
            product.price
        )

        val sellerPin = product.sellerPin.ifBlank { "1P0YE4WZ" }
        val convId = "conv_store_${product.storeId.ifBlank { sellerPin }}"

        val intent = Intent(this, ChatRoomActivity::class.java).apply {
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, convId)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, product.storeName)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, store?.logoUrl ?: product.primaryImage)
            putExtra(ChatRoomActivity.EXTRA_INITIAL_MESSAGE, inquiry)
        }
        startActivity(intent)
    }

    private fun addToCart(product: Product, andProceedToCheckout: Boolean) {
        lifecycleScope.launch {
            val result = repository.addToCart(product.id, 1, product)
            if (result.isSuccess) {
                if (andProceedToCheckout) {
                    startActivity(Intent(this@ProductDetailsActivity, CheckoutActivity::class.java))
                } else {
                    Toast.makeText(this@ProductDetailsActivity, "🛒 Added \"${product.displayTitle}\" to cart!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@ProductDetailsActivity, getString(R.string.error_add_to_cart), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
