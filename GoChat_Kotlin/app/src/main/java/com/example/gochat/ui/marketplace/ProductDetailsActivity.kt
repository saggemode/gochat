package com.example.gochat.ui.marketplace

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.ProductVariant
import com.example.gochat.data.model.Review
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityProductDetailsBinding
import com.example.gochat.databinding.DialogWriteReviewBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ProductDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductDetailsBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository
    
    private var product: Product? = null
    private var store: Store? = null
    private var selectedVariant: ProductVariant? = null
    private lateinit var variantAdapterPrimary: VariantChipAdapter
    private lateinit var variantAdapterSecondary: VariantChipAdapter
    private lateinit var reviewAdapter: ReviewAdapter

    private var primaryAttributeName: String? = null

    private var secondaryAttributeName: String? = null
    private var selectedPrimaryValue: String? = null
    private var selectedSecondaryValue: String? = null

    private val reviewPhotos = mutableListOf<Uri>()
    private var onReviewPhotosPicked: ((List<Uri>) -> Unit)? = null
    private val pickReviewPhotosLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        onReviewPhotosPicked?.invoke(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val productId = intent.getStringExtra("product_id") ?: return finish()

        setupAdapters()
        setupListeners()
        loadProductDetails(productId)
    }

    private fun setupAdapters() {
        variantAdapterPrimary = VariantChipAdapter { variant ->
            selectedPrimaryValue = variant.title
            updateSecondaryVariants()
            findAndSetSelectedVariant()
        }
        binding.rvVariantsPrimary.apply {
            adapter = variantAdapterPrimary
            layoutManager = LinearLayoutManager(this@ProductDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
        }

        variantAdapterSecondary = VariantChipAdapter { variant ->
            selectedSecondaryValue = variant.title
            findAndSetSelectedVariant()
        }
        binding.rvVariantsSecondary.apply {
            adapter = variantAdapterSecondary
            layoutManager = LinearLayoutManager(this@ProductDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
        }

        reviewAdapter = ReviewAdapter { review ->
            toggleReviewHelpful(review)
        }
        binding.rvReviews.apply {

            adapter = reviewAdapter
            layoutManager = LinearLayoutManager(this@ProductDetailsActivity)
        }
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

        binding.btnWriteReview.setOnClickListener {
            val p = product
            if (p == null) {
                Toast.makeText(this, "Loading product details, please wait...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (repository.userId.isNullOrBlank()) {
                Toast.makeText(this, "Please log in to write a review", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showWriteReviewDialog(p)
        }

        binding.layoutStore.setOnClickListener {

            val s = store ?: product?.let { Store(id = it.storeId, name = it.storeName, address = it.sellerLocation, ownerPin = it.sellerPin) }
            s?.let {
                val intent = Intent(this, StorefrontActivity::class.java).apply {
                    putExtra("store_id", it.id)
                    putExtra("store_name", it.name)
                    putExtra("store_logo", it.logoUrl)
                    putExtra("store_banner", it.bannerUrl)
                    putExtra("store_description", it.description)
                    putExtra("store_category", it.category)
                    putExtra("store_address", it.address)
                    putExtra("store_pin", it.ownerPin)
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
                loadReviews(p.id)
            } ?: run {
                val name = intent.getStringExtra("product_name")
                if (!name.isNullOrBlank()) {
                    val fallback = Product(
                        id = productId,
                        name = name,
                        price = intent.getDoubleExtra("product_price", 0.0),
                        imageUrl = intent.getStringExtra("product_image").orEmpty(),
                        imageUrls = intent.getStringArrayListExtra("product_images") ?: listOfNotNull(intent.getStringExtra("product_image")),
                        storeId = intent.getStringExtra("store_id").orEmpty(),
                        storeName = intent.getStringExtra("store_name").orEmpty(),
                        sellerPin = intent.getStringExtra("seller_pin").orEmpty(),
                        sellerId = intent.getStringExtra("seller_id").orEmpty()
                    )
                    product = fallback
                    displayProduct(fallback)
                    if (fallback.storeId.isNotBlank()) loadStore(fallback.storeId)
                    loadReviews(productId)
                } else {
                    Toast.makeText(this@ProductDetailsActivity, getString(R.string.error_product_not_found), Toast.LENGTH_SHORT).show()
                    finish()
                }
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

    private fun loadReviews(productId: String) {
        lifecycleScope.launch {
            val result = repository.getReviews(productId)
            if (result.isSuccess) {
                reviewAdapter.submitList(result.getOrThrow())
            }
        }
    }

    private fun toggleReviewHelpful(review: Review) {
        lifecycleScope.launch {
            val res = repository.toggleReviewHelpful(review.id)
            if (res.isSuccess) {
                product?.let { loadReviews(it.id) }
            }
        }
    }


    private fun showWriteReviewDialog(product: Product) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogWriteReviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        reviewPhotos.clear()

        dialogBinding.layoutPickPhotos.setOnClickListener {
            onReviewPhotosPicked = { uris ->
                reviewPhotos.clear()
                reviewPhotos.addAll(uris)
                dialogBinding.tvPhotosLabel.text = "${uris.size} photos selected"
            }
            pickReviewPhotosLauncher.launch("image/*")
        }

        dialogBinding.btnSubmitReview.setOnClickListener {
            val rating = dialogBinding.ratingBar.rating.toInt()
            val comment = dialogBinding.etComment.text.toString().trim()

            if (rating < 1) {
                Toast.makeText(this@ProductDetailsActivity, "Please select at least 1 star", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSubmitReview.isEnabled = false
            dialogBinding.btnSubmitReview.text = "Submitting..."

            lifecycleScope.launch {
                val uploadedUrls = mutableListOf<String>()
                for (uri in reviewPhotos) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val remote = repository.uploadMedia(bytes, "image/jpeg", "review_${System.currentTimeMillis()}.jpg")
                        if (!remote.isNullOrBlank()) uploadedUrls.add(remote)
                    }
                }

                val res = repository.createReview(product.id, rating, comment, uploadedUrls)
                if (res.isSuccess) {
                    Toast.makeText(this@ProductDetailsActivity, "Review submitted! Thank you.", Toast.LENGTH_SHORT).show()
                    loadReviews(product.id)
                    dialog.dismiss()
                } else {
                    dialogBinding.btnSubmitReview.isEnabled = true
                    dialogBinding.btnSubmitReview.text = "Submit Review"
                    val errMsg = res.exceptionOrNull()?.message ?: "Failed to submit review"
                    Toast.makeText(this@ProductDetailsActivity, errMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }



    private fun displayProduct(product: Product) {
        binding.tvProductName.text = product.displayTitle
        binding.tvProductPrice.text = String.format(Locale.US, "$%.2f", product.price)

        setupVariantsUI(product)

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

        val allImages = if (product.imageUrls.isNotEmpty()) product.imageUrls else listOfNotNull(product.primaryImage.ifBlank { null })
        var currentImgIdx = 0
        if (allImages.isNotEmpty()) {
            MediaImageHelper.loadSafeImage(binding.ivProductImage, allImages[0])
            binding.ivProductImage.setOnClickListener {
                if (allImages.size > 1) {
                    currentImgIdx = (currentImgIdx + 1) % allImages.size
                    MediaImageHelper.loadSafeImage(binding.ivProductImage, allImages[currentImgIdx])
                    Toast.makeText(this@ProductDetailsActivity, "Photo ${currentImgIdx + 1}/${allImages.size}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            MediaImageHelper.loadSafeImage(binding.ivProductImage, product.primaryImage)
        }

        // Store initial display before fetch finishes
        binding.tvStoreName.text = product.storeName
        binding.tvStoreRating.text = String.format(Locale.US, "%s • PIN: %s", product.sellerLocation, product.sellerPin.ifBlank { "1P0YE4WZ" })
    }

    private fun setupVariantsUI(product: Product) {
        if (product.variants.isEmpty()) {
            binding.layoutVariantsPrimary.visibility = View.GONE
            binding.layoutVariantsSecondary.visibility = View.GONE
            return
        }

        // 1. Identify attributes
        val firstVariant = product.variants.first()
        val attrs = try {
            Json.decodeFromString<JsonObject>(firstVariant.attributesJson)
        } catch (_: Exception) {
            null
        }

        if (attrs == null || attrs.isEmpty()) {
            // Simple flat list of variants
            binding.layoutVariantsPrimary.visibility = View.VISIBLE
            binding.tvVariantPrimaryTitle.text = "SELECT OPTION"
            variantAdapterPrimary.submitList(product.variants)
            binding.layoutVariantsSecondary.visibility = View.GONE
            return
        }

        val keys = attrs.keys.toList()
        primaryAttributeName = keys[0]
        secondaryAttributeName = if (keys.size > 1) keys[1] else null

        binding.tvVariantPrimaryTitle.text = "SELECT ${primaryAttributeName?.uppercase()}"
        binding.layoutVariantsPrimary.visibility = View.VISIBLE
        
        val primaryValues = product.variants.mapNotNull { 
            getAttributeValue(it, primaryAttributeName)
        }.distinct().map { ProductVariant(title = it) }
        
        variantAdapterPrimary.submitList(primaryValues)

        if (secondaryAttributeName != null) {
            binding.tvVariantSecondaryTitle.text = "SELECT ${secondaryAttributeName?.uppercase()}"
            binding.layoutVariantsSecondary.visibility = View.VISIBLE
        } else {
            binding.layoutVariantsSecondary.visibility = View.GONE
        }
    }

    private fun getAttributeValue(v: ProductVariant, attr: String?): String? {
        if (attr == null) return null
        return try {
            Json.decodeFromString<JsonObject>(v.attributesJson)[attr]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun updateSecondaryVariants() {
        val p = product ?: return
        val attr = secondaryAttributeName ?: return
        
        val secondaryValues = p.variants.filter { 
            getAttributeValue(it, primaryAttributeName) == selectedPrimaryValue
        }.mapNotNull { 
            getAttributeValue(it, attr)
        }.distinct().map { ProductVariant(title = it) }

        variantAdapterSecondary.submitList(secondaryValues)
    }

    private fun findAndSetSelectedVariant() {
        val p = product ?: return
        
        val found = p.variants.find { v ->
            val pVal = getAttributeValue(v, primaryAttributeName)
            val sVal = getAttributeValue(v, secondaryAttributeName)
            
            pVal == selectedPrimaryValue && (secondaryAttributeName == null || sVal == selectedSecondaryValue)
        }

        selectedVariant = found
        found?.let {
            val displayPrice = if (it.priceOverride > 0) it.priceOverride else p.price
            binding.tvProductPrice.text = String.format(Locale.US, "$%.2f", displayPrice)
        }
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
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_ID, product.id)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_NAME, product.displayTitle)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_PRICE, product.price)
            putExtra(ChatRoomActivity.EXTRA_PRODUCT_IMAGE, product.primaryImage)
        }

        startActivity(intent)
    }

    private fun addToCart(product: Product, andProceedToCheckout: Boolean) {
        lifecycleScope.launch {
            // Include variant info in cart if selected
            val finalPrice = selectedVariant?.priceOverride?.let { if (it > 0) it else null } ?: product.price
            val result = repository.addToCart(product.id, 1, product.copy(price = finalPrice))
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
