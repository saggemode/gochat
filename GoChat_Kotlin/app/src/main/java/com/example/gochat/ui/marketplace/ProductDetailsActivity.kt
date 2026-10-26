package com.example.gochat.ui.marketplace

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
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
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.data.repository.StoryRepository
import com.example.gochat.databinding.ActivityProductDetailsBinding
import com.example.gochat.databinding.DialogWriteReviewBinding
import com.example.gochat.ui.chat.ChatRoomActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.Locale
import javax.inject.Inject

import android.transition.TransitionInflater
import androidx.transition.TransitionManager
import androidx.transition.AutoTransition
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyNavigationBarInset
import com.example.gochat.core.ui.applyStatusBarInset

@AndroidEntryPoint
class ProductDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductDetailsBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository

    @Inject
    lateinit var storyRepository: StoryRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var tokenManager: com.example.gochat.data.api.TokenManager

    private var product: Product? = null
    private var store: Store? = null
    private var selectedVariant: ProductVariant? = null
    private var selectedQuantity: Int = 1
    private lateinit var variantAdapterPrimary: VariantChipAdapter
    private lateinit var variantAdapterSecondary: VariantChipAdapter
    private lateinit var reviewAdapter: ReviewAdapter
    private var myExistingReview: Review? = null

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
        applyEdgeToEdge()
        window.sharedElementEnterTransition = TransitionInflater.from(this)
            .inflateTransition(android.R.transition.move)
        window.sharedElementReturnTransition = TransitionInflater.from(this)
            .inflateTransition(android.R.transition.move)

        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Status-bar inset is handled by fitsSystemWindows on the collapsing toolbar chain,
        // so the cart/back icons sit below the clock. (No manual toolbar padding here.)
        binding.cardBottomActions.applyNavigationBarInset()

        // Live cart badge — same source of truth as every other cart icon in the app.
        lifecycleScope.launch {
            repository.observeCartCount().collect { count ->
                binding.tvCartBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
                binding.tvCartBadge.text = if (count > 99) "99+" else count.toString()
            }
        }

        val productId = intent.getStringExtra("product_id") ?: return finish()
        binding.ivProductImage.transitionName = "product_image_$productId"

        val initialImage = intent.getStringExtra("product_image")
        if (!initialImage.isNullOrBlank()) {
            MediaImageHelper.loadSafeImage(binding.ivProductImage, initialImage)
        }
        val initialName = intent.getStringExtra("product_name")
        if (!initialName.isNullOrBlank()) {
            binding.tvProductName.text = initialName
        }
        val initialPrice = intent.getDoubleExtra("product_price", 0.0)
        val initialCurrency = intent.getStringExtra("product_currency") ?: "USD"
        if (initialPrice > 0.0) {
            binding.tvProductPrice.text = com.example.gochat.core.currency.CurrencyManager.formatPrice(initialPrice, initialCurrency)
        }

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

        binding.btnShareProduct.setOnClickListener {
            val p = product ?: return@setOnClickListener
            val shareDialog = com.example.gochat.ui.chat.ShareToChatBottomSheet(p)
            shareDialog.show(supportFragmentManager, "ShareToChat")
        }

        binding.btnShareToStatus.setOnClickListener {
            val p = product ?: return@setOnClickListener
            shareProductDirectlyToStatus(p)
        }

        binding.btnReportProduct.setOnClickListener {
            val p = product ?: return@setOnClickListener
            com.example.gochat.ui.common.ReportContentDialog.show(
                context = this,
                title = "Report this product?",
            ) { reason ->
                lifecycleScope.launch {
                    chatRepository.reportContent("product", p.id, reason)
                    Toast.makeText(
                        this@ProductDetailsActivity,
                        getString(R.string.report_submitted_toast),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // WhatsApp-style inquiry: Chat with Seller
        binding.btnChatSeller.setOnClickListener {
            val p = product ?: return@setOnClickListener
            openChatWithSeller(p)
        }

        binding.btnCartIcon.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }

        binding.btnDecreaseQty.setOnClickListener { changeQuantity(-1) }
        binding.btnIncreaseQty.setOnClickListener { changeQuantity(1) }

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
            val currentUserId = repository.userId
            if (currentUserId.isNullOrBlank()) {
                Toast.makeText(this, "Please log in to write a review", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (p.sellerId.isNotBlank() && p.sellerId == currentUserId) {
                Toast.makeText(this, "You cannot review your own product", Toast.LENGTH_SHORT).show()
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
            // Use the dedicated single-product endpoint which returns ALL images
            val result = repository.getProductById(productId)
            product = result.getOrNull()

            product?.let { p ->
                displayProduct(p)
                loadStore(p.storeId)
                loadReviews(p.id)
                if (intent.getBooleanExtra("auto_buy", false)) {
                    intent.removeExtra("auto_buy")
                    addToCart(p, andProceedToCheckout = true)
                }
            } ?: run {
                val err = result.exceptionOrNull()?.message.orEmpty()
                val isExplicit404 = err.contains("404") || err.contains("not found on server", ignoreCase = true)
                val name = intent.getStringExtra("product_name")
                if (!name.isNullOrBlank() && !isExplicit404) {
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
                val list = result.getOrThrow()
                val currentUserId = repository.userId
                myExistingReview = if (!currentUserId.isNullOrBlank()) {
                    list.find { it.userId == currentUserId }
                } else null

                updateReviewUI()
                reviewAdapter.submitList(list)
            }
        }
    }

    private fun updateReviewUI() {
        val currentUserId = repository.userId
        val isOwner = !currentUserId.isNullOrBlank() && (product?.sellerId == currentUserId)
        if (isOwner) {
            binding.btnWriteReview.visibility = View.GONE
            return
        }

        binding.btnWriteReview.visibility = View.VISIBLE
        if (myExistingReview != null) {
            binding.btnWriteReview.text = "✏️ Edit Your Review"
        } else {
            binding.btnWriteReview.text = "Write a Review"
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
        val currentUserId = repository.userId
        if (!currentUserId.isNullOrBlank() && product.sellerId.isNotBlank() && product.sellerId == currentUserId) {
            Toast.makeText(this, "You cannot review your own product", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogWriteReviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        reviewPhotos.clear()

        val existing = myExistingReview
        if (existing != null) {
            dialogBinding.tvDialogTitle.text = "Edit Your Review"
            dialogBinding.ratingBar.rating = existing.rating.coerceIn(1, 5).toFloat()
            dialogBinding.etComment.setText(existing.comment)
            dialogBinding.btnSubmitReview.text = "Update Review"
            if (existing.imageUrls.isNotEmpty()) {
                dialogBinding.tvPhotosLabel.text = "${existing.imageUrls.size} existing photos (tap to replace)"
            }
        } else {
            dialogBinding.tvDialogTitle.text = "Rate this Product"
            dialogBinding.ratingBar.rating = 5f
            dialogBinding.etComment.setText("")
            dialogBinding.btnSubmitReview.text = "Submit Review"
        }

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

            val isUpdate = (existing != null)
            dialogBinding.btnSubmitReview.isEnabled = false
            dialogBinding.btnSubmitReview.text = if (isUpdate) "Updating..." else "Submitting..."

            lifecycleScope.launch {
                val uploadedUrls = mutableListOf<String>()
                for (uri in reviewPhotos) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val remote = repository.uploadMedia(bytes, "image/jpeg", "review_${System.currentTimeMillis()}.jpg")
                        if (!remote.isNullOrBlank()) uploadedUrls.add(remote)
                    }
                }

                // If user didn't pick new photos but had existing photos, preserve existing
                val finalImageUrls = if (uploadedUrls.isNotEmpty()) {
                    uploadedUrls
                } else {
                    existing?.imageUrls ?: emptyList()
                }

                val res = repository.createReview(product.id, rating, comment, finalImageUrls)
                if (res.isSuccess) {
                    val successMsg = if (isUpdate) "Review updated successfully!" else "Review submitted! Thank you."
                    Toast.makeText(this@ProductDetailsActivity, successMsg, Toast.LENGTH_SHORT).show()
                    loadReviews(product.id)
                    dialog.dismiss()
                } else {
                    dialogBinding.btnSubmitReview.isEnabled = true
                    dialogBinding.btnSubmitReview.text = if (isUpdate) "Update Review" else "Submit Review"
                    val errMsg = res.exceptionOrNull()?.message ?: "Failed to submit review"
                    Toast.makeText(this@ProductDetailsActivity, errMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }



    private fun displayProduct(product: Product) {
        TransitionManager.beginDelayedTransition(binding.root, AutoTransition().apply {
            duration = 200
        })
        updateReviewUI()
        binding.tvProductName.text = product.displayTitle
        binding.tvProductPrice.text = product.getFormattedPrice()

        setupVariantsUI(product)

        if (product.hasDiscount) {
            binding.tvOriginalPrice.visibility = View.VISIBLE
            binding.tvOriginalPrice.paintFlags = binding.tvOriginalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            binding.tvOriginalPrice.text = product.getFormattedOriginalPrice()

            binding.tvDiscountBadge.visibility = View.VISIBLE
            binding.tvDiscountBadge.text = String.format(Locale.US, "-%d%%", product.discountPercent)
        } else {
            binding.tvOriginalPrice.visibility = View.GONE
            binding.tvDiscountBadge.visibility = View.GONE
        }

        binding.tvFxConversionInfo.visibility = View.GONE

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
                    TransitionManager.beginDelayedTransition(binding.appBar, AutoTransition().apply {
                        duration = 180
                    })
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

        applyOwnershipUI(product)
    }

    /**
     * You can't buy from or message yourself: hide the whole Chat/Add-to-Cart/Buy-Now bar
     * when the product belongs to the current user.
     */
    private fun applyOwnershipUI(product: Product) {
        val mine = isMyProduct(product)
        binding.cardBottomActions.visibility = if (mine) View.GONE else View.VISIBLE
        binding.layoutQuantity.visibility = if (mine) View.GONE else View.VISIBLE
    }

    private fun isMyProduct(product: Product): Boolean {
        val myId = repository.userId?.takeIf { it.isNotBlank() }
        if (myId != null) {
            if (product.sellerId == myId) return true
            if (store?.ownerId == myId) return true
        }
        val myPin = tokenManager.userPin?.takeIf { it.isNotBlank() }
        if (myPin != null) {
            if (product.sellerPin.isNotBlank() && product.sellerPin.equals(myPin, ignoreCase = true)) return true
            if (store?.ownerPin?.equals(myPin, ignoreCase = true) == true) return true
        }
        return false
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
            binding.tvProductPrice.text = com.example.gochat.core.currency.CurrencyManager.formatPrice(displayPrice, p.currency)
        }
    }

    private fun displayStore(store: Store) {
        binding.tvStoreName.text = store.name

        // The store carries owner id/PIN the product fields may lack — re-check ownership now.
        product?.let { applyOwnershipUI(it) }

        val followers = store.followersCount
        val followerText = when (followers) {
            0 -> "0 followers"
            1 -> "1 follower"
            else -> if (followers >= 1000) String.format(Locale.US, "%.1fk followers", followers / 1000.0) else "$followers followers"
        }

        binding.tvStoreRating.text = String.format(
            Locale.US,
            "%s • %s",
            followerText,
            store.address.ifBlank { "Lagos, Nigeria" }
        )
        binding.ivStoreVerified.visibility = if (store.isVerified) View.VISIBLE else View.GONE
        MediaImageHelper.loadSafeImage(binding.ivStoreLogo, store.logoUrl, isCircle = true)

        lifecycleScope.launch {
            val myProfile = repository.getBusinessProfile().getOrNull()
            val isOwner = (myProfile != null && (myProfile.id == store.id || myProfile.id == store.ownerId || myProfile.name == store.name))
            if (isOwner) {
                binding.btnFollowStore.visibility = View.GONE
            } else {
                binding.btnFollowStore.visibility = View.VISIBLE
                checkFollowStatus(store.id)
                binding.btnFollowStore.setOnClickListener {
                    toggleFollow(store.id)
                }
            }
        }
    }

    private fun checkFollowStatus(storeId: String) {
        lifecycleScope.launch {
            val res = repository.isFollowingStore(storeId)
            if (res.isSuccess) {
                updateFollowButton(res.getOrThrow())
            }
        }
    }

    private fun toggleFollow(storeId: String) {
        lifecycleScope.launch {
            val res = repository.toggleFollowStore(storeId)
            if (res.isSuccess) {
                val following = res.getOrThrow()
                updateFollowButton(following)
                store?.let { s ->
                    val updatedCount = if (following) s.followersCount + 1 else maxOf(0, s.followersCount - 1)
                    store = s.copy(followersCount = updatedCount)
                    displayStore(store!!)
                }
                Toast.makeText(
                    this@ProductDetailsActivity,
                    if (following) "🔔 Following ${store?.name ?: "store"}! You'll receive alerts on new products and updates." else "Unfollowed store",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val err = res.exceptionOrNull()?.message ?: "Failed to update follow status"
                Toast.makeText(this@ProductDetailsActivity, err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFollowButton(isFollowing: Boolean) {
        if (isFollowing) {
            binding.btnFollowStore.text = "Following"
            binding.btnFollowStore.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#374151")))
            binding.btnFollowStore.setTextColor(Color.WHITE)
        } else {
            binding.btnFollowStore.text = "Follow"
            binding.btnFollowStore.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.gochat_accent)))
            binding.btnFollowStore.setTextColor(Color.BLACK)
        }
    }

    private fun openChatWithSeller(product: Product) {
        val inquiry = "\uD83D\uDC4B Hi ${product.storeName}! I am interested in purchasing \"${product.displayTitle}\" listed for ${product.getFormattedPrice()} on GoChat Marketplace."

        // Show loading state
        binding.btnChatSeller.isEnabled = false
        binding.btnChatSeller.text = "Connecting…"

        lifecycleScope.launch {
            try {
                var targetSellerId = product.sellerId.ifBlank { store?.ownerId.orEmpty() }
                var targetSellerPin = product.sellerPin.ifBlank { store?.ownerPin.orEmpty() }

                // If both are empty, try loading the store directly
                if (targetSellerId.isBlank() && targetSellerPin.isBlank() && product.storeId.isNotBlank()) {
                    val fetchedStore = repository.getStore(product.storeId).getOrNull()
                    if (fetchedStore != null) {
                        store = fetchedStore
                        targetSellerId = fetchedStore.ownerId
                        targetSellerPin = fetchedStore.ownerPin
                    }
                }

                // 1. Resolve seller user ID
                val resolvedSellerId = if (targetSellerId.isNotBlank()) {
                    targetSellerId
                } else if (targetSellerPin.isNotBlank()) {
                    val lookupResult = authRepository.lookupUserByPin(targetSellerPin)
                    lookupResult.getOrNull()?.id.orEmpty()
                } else {
                    ""
                }

                if (resolvedSellerId.isBlank()) {
                    Toast.makeText(this@ProductDetailsActivity, "Could not find seller contact info", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. Find existing conversation with this seller, or create one
                val existingConv = withContext(Dispatchers.IO) {
                    chatRepository.getAllConversationsList().firstOrNull { conv ->
                        !conv.isGroup && conv.memberIds.contains(resolvedSellerId)
                    }
                }

                val conversation = existingConv ?: run {
                    val createResult = chatRepository.createConversation(
                        name = product.storeName.ifBlank { "Seller" },
                        memberIds = listOf(resolvedSellerId),
                        isGroup = false
                    )
                    createResult.getOrNull()
                }

                if (conversation == null) {
                    Toast.makeText(this@ProductDetailsActivity, "Failed to start chat. Please try again.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 3. Open ChatRoomActivity with the REAL conversation
                val intent = Intent(this@ProductDetailsActivity, ChatRoomActivity::class.java).apply {
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversation.id)
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, product.storeName.ifBlank { conversation.title })
                    putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, store?.logoUrl ?: product.primaryImage)
                    putExtra(ChatRoomActivity.EXTRA_INITIAL_MESSAGE, inquiry)
                    putExtra(ChatRoomActivity.EXTRA_PRODUCT_ID, product.id)
                    putExtra(ChatRoomActivity.EXTRA_PRODUCT_NAME, product.displayTitle)
                    putExtra(ChatRoomActivity.EXTRA_PRODUCT_PRICE, product.price)
                    putExtra(ChatRoomActivity.EXTRA_PRODUCT_IMAGE, product.primaryImage)
                    putExtra(ChatRoomActivity.EXTRA_IS_ONLINE, conversation.isOnline)
                    putExtra(ChatRoomActivity.EXTRA_LAST_SEEN, conversation.lastSeen ?: 0L)
                    val partnerId = conversation.memberIds.firstOrNull()
                    if (!partnerId.isNullOrEmpty()) {
                        putExtra(ChatRoomActivity.EXTRA_PARTNER_ID, partnerId)
                    }
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@ProductDetailsActivity, "Connection error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnChatSeller.isEnabled = true
                binding.btnChatSeller.text = getString(R.string.chat_with_seller)
            }
        }
    }

    /** Adjusts the selected quantity, clamped between 1 and available stock. */
    private fun changeQuantity(delta: Int) {
        val maxStock = (product?.stock ?: 1).coerceAtLeast(1)
        val newQty = (selectedQuantity + delta).coerceIn(1, maxStock)
        if (newQty == selectedQuantity) {
            if (delta > 0 && newQty == maxStock) {
                Toast.makeText(this, "Only $maxStock in stock", Toast.LENGTH_SHORT).show()
            }
            return
        }
        selectedQuantity = newQty
        binding.tvQuantity.text = newQty.toString()
    }

    private fun addToCart(product: Product, andProceedToCheckout: Boolean) {
        lifecycleScope.launch {
            // Include variant info in cart if selected
            val finalPrice = selectedVariant?.priceOverride?.let { if (it > 0) it else null } ?: product.price
            val result = repository.addToCart(product.id, selectedQuantity, product.copy(price = finalPrice))
            if (result.isSuccess) {
                if (andProceedToCheckout) {
                    startActivity(Intent(this@ProductDetailsActivity, CheckoutActivity::class.java))
                } else {
                    val qtyLabel = if (selectedQuantity > 1) " ×$selectedQuantity" else ""
                    Toast.makeText(this@ProductDetailsActivity, "🛒 Added \"${product.displayTitle}\"$qtyLabel to cart!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@ProductDetailsActivity, getString(R.string.error_add_to_cart), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareProductDirectlyToStatus(p: Product) {
        binding.btnShareToStatus.isEnabled = false
        val priceFormatted = String.format(Locale.US, "%.2f", p.price)
        val shortDesc = p.description.lineSequence().firstOrNull()?.take(100) ?: ""
        val caption = "🏷️ ${p.displayTitle} • $$priceFormatted\n$shortDesc\n#prod_${p.id}".trim()
        val mediaUrl = p.primaryImage
        val mediaType = if (mediaUrl.isNotBlank()) "image" else "text"

        lifecycleScope.launch {
            val res = storyRepository.postStory(
                mediaUrl = mediaUrl,
                caption = caption,
                mediaType = mediaType
            )
            binding.btnShareToStatus.isEnabled = true
            res.onSuccess {
                Toast.makeText(this@ProductDetailsActivity, "✅ Product shared to your Status!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@ProductDetailsActivity, "Failed to share: ${err.message ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
