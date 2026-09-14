package com.example.gochat.ui.marketplace

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Category
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.databinding.DialogAddProductBinding
import com.example.gochat.databinding.DialogEditStoreBinding
import androidx.paging.LoadState
import com.example.gochat.databinding.FragmentMarketplaceBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@AndroidEntryPoint
class MarketplaceFragment : Fragment() {

    private var _binding: FragmentMarketplaceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MarketplaceViewModel by viewModels()

    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var storeProductAdapter: StoreProductAdapter
    private lateinit var searchSuggestionAdapter: SearchSuggestionAdapter

    private var selectedStoreLogoUri: Uri? = null

    private var onProductImagesPicked: ((List<Uri>) -> Unit)? = null
    private var onSingleImagePicked: ((Uri?) -> Unit)? = null

    private val pickSingleImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        onSingleImagePicked?.invoke(uri)
    }

    private val pickStoreLogoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedStoreLogoUri = uri
        if (uri != null) {
            binding.ivStoreLogoPreview.setPadding(0, 0, 0, 0)
            MediaImageHelper.loadSafeImage(binding.ivStoreLogoPreview, uri.toString(), cornerRadiusDp = 12f)
            binding.btnRemoveStoreLogo.visibility = View.VISIBLE
            binding.tvStoreLogoSubtitle.text = "Logo selected from phone"
        } else {
            resetStoreLogoPreview()
        }
    }

    private val pickProductImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        onProductImagesPicked?.invoke(uris)
    }

    private fun resetStoreLogoPreview() {
        selectedStoreLogoUri = null
        binding.ivStoreLogoPreview.setImageDrawable(null)
        binding.ivStoreLogoPreview.setImageResource(R.drawable.ic_camera_status)
        val pad = (14 * resources.displayMetrics.density).toInt()
        binding.ivStoreLogoPreview.setPadding(pad, pad, pad, pad)
        binding.btnRemoveStoreLogo.visibility = View.GONE
        binding.tvStoreLogoSubtitle.text = "Tap to select logo from phone"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMarketplaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapters()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCartCount()
        viewModel.loadMyStore()
    }

    private fun setupAdapters() {
        // 1. Explore Products Adapter
        productAdapter = ProductAdapter { product ->
            val intent = Intent(requireContext(), ProductDetailsActivity::class.java).apply {
                putExtra("product_id", product.id)
            }
            startActivity(intent)
        }
        binding.rvProducts.apply {
            adapter = productAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
        }

        // 2. Categories Adapter
        categoryAdapter = CategoryAdapter { category ->
            if (category.id.equals("all", ignoreCase = true)) {
                viewModel.filterByCategory(null)
            } else {
                viewModel.filterByCategory(category.id)
            }
        }
        binding.rvCategories.apply {
            adapter = categoryAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        // 3. Store Inventory Adapter (Seller Hub)
        storeProductAdapter = StoreProductAdapter(
            onClick = { product ->
                val intent = Intent(requireContext(), ProductDetailsActivity::class.java).apply {
                    putExtra("product_id", product.id)
                }
                startActivity(intent)
            },
            onEdit = { product ->
                showEditProductBottomSheet(product)
            },
            onDelete = { product ->
                showDeleteProductDialog(product)
            }
        )
        binding.rvStoreProducts.apply {
            adapter = storeProductAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        // 4. Search Suggestions
        searchSuggestionAdapter = SearchSuggestionAdapter { suggestion ->
            binding.etSearch.setText(suggestion)
            binding.etSearch.setSelection(suggestion.length)
            viewModel.setSearchQuery(suggestion)
        }
        binding.rvSearchSuggestions.apply {
            adapter = searchSuggestionAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }
    }


    private fun setupListeners() {
        // Tab switching: Marketplace (0) vs Seller Hub (1)
        binding.tabLayoutMain.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: 0
                if (index == 2) {
                    binding.viewFlipper.displayedChild = 1
                    viewModel.loadMyStore()
                } else {
                    binding.viewFlipper.displayedChild = 0
                    viewModel.loadData(index)
                }
            }


            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Search text watcher
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()
                binding.btnClearSearch.visibility = if (!query.isNullOrEmpty()) View.VISIBLE else View.GONE
                viewModel.setSearchQuery(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }

        // Verified-only filter chip
        binding.chipVerifiedOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setVerifiedOnly(isChecked)
        }

        binding.chipNearby.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkLocationPermissionAndToggleNearby()
            } else {
                viewModel.setNearbyOnly(false)
            }
        }

        // SwipeRefresh
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadData()
        }

        // Topbar navigation actions
        binding.btnCartContainer.setOnClickListener {
            startActivity(Intent(requireContext(), CartActivity::class.java))
        }

        binding.btnOrders.setOnClickListener {
            startActivity(Intent(requireContext(), OrdersActivity::class.java))
        }

        // Seller Hub actions
        binding.cardHubStore.setOnClickListener {
            openMyStorefront()
        }

        binding.btnEditStore.setOnClickListener {
            showEditStoreBottomSheet()
        }

        binding.btnViewInsights.setOnClickListener {
            startActivity(Intent(requireContext(), SellerInsightsActivity::class.java))
        }

        binding.layoutSelectStoreLogo.setOnClickListener {
            pickStoreLogoLauncher.launch("image/*")
        }

        binding.btnRemoveStoreLogo.setOnClickListener {
            resetStoreLogoPreview()
        }

        binding.btnCreateStore.setOnClickListener {
            handleCreateStore()
        }

        binding.btnAddProduct.setOnClickListener {
            showAddProductBottomSheet()
        }

        binding.btnShareStore.setOnClickListener {
            val store = viewModel.myStore.value
            val pin = store?.ownerPin?.ifBlank { "1P0YE4WZ" } ?: "1P0YE4WZ"
            val storeUrl = "https://gochat.app/store/$pin"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GoChat Store Link", storeUrl))
            Toast.makeText(requireContext(), "📋 Store link copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCreateStore() {
        val name = binding.etStoreName.text.toString().trim()
        val category = binding.etStoreCategory.text.toString().trim()
        val location = binding.etStoreLocation.text.toString().trim()
        val phone = binding.etStorePhone.text.toString().trim()
        val description = binding.etStoreDescription.text.toString().trim()

        if (name.isBlank()) {
            binding.etStoreName.error = "Store name is required"
            return
        }
        if (location.isBlank()) {
            binding.etStoreLocation.error = "Location is required"
            return
        }

        binding.btnCreateStore.isEnabled = false
        binding.btnCreateStore.text = "Creating store..."

        lifecycleScope.launch {
            var uploadedLogoUrl: String? = null
            val logoUri = selectedStoreLogoUri
            if (logoUri != null) {
                withContext(Dispatchers.IO) {
                    val compressed = ImageCompressor.compressImageUri(
                        requireContext(),
                        logoUri,
                        maxDimension = 512,
                        quality = 80
                    )
                    if (compressed != null) {
                        uploadedLogoUrl = viewModel.uploadMedia(
                            bytes = compressed.bytes,
                            mimeType = compressed.mimeType,
                            fileName = "store_logo_${System.currentTimeMillis()}.jpg"
                        )
                        if (uploadedLogoUrl.isNullOrBlank()) {
                            uploadedLogoUrl = compressed.dataUri
                        }
                    }
                }
            }

            viewModel.createStore(
                name = name,
                category = category,
                location = location,
                phone = phone,
                description = description,
                logoUrl = uploadedLogoUrl,
                onSuccess = {
                    binding.btnCreateStore.isEnabled = true
                    binding.btnCreateStore.text = "Create Business Store"
                    resetStoreLogoPreview()
                    Toast.makeText(requireContext(), "🎉 Store \"$name\" is now live on GoChat!", Toast.LENGTH_LONG).show()
                },
                onError = { err ->
                    binding.btnCreateStore.isEnabled = true
                    binding.btnCreateStore.text = "Create Business Store"
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun showAddProductBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogAddProductBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val selectedProductUris = mutableListOf<Uri>()

        // Variants setup
        val variantAdapter = VariantInputAdapter()
        dialogBinding.rvVariants.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvVariants.adapter = variantAdapter
        dialogBinding.btnAddVariant.setOnClickListener { variantAdapter.addVariant() }

        fun updatePreviews() {
            val count = selectedProductUris.size
            dialogBinding.tvPhotosCount.text = "$count/3 selected"

            // Preview 1
            if (count >= 1) {
                dialogBinding.framePreview1.visibility = View.VISIBLE
                MediaImageHelper.loadSafeImage(dialogBinding.ivPreview1, selectedProductUris[0].toString(), cornerRadiusDp = 8f)
                dialogBinding.btnRemovePhoto1.setOnClickListener {
                    selectedProductUris.removeAt(0)
                    updatePreviews()
                }
            } else {
                dialogBinding.framePreview1.visibility = View.GONE
            }

            // Preview 2
            if (count >= 2) {
                dialogBinding.framePreview2.visibility = View.VISIBLE
                MediaImageHelper.loadSafeImage(dialogBinding.ivPreview2, selectedProductUris[1].toString(), cornerRadiusDp = 8f)
                dialogBinding.btnRemovePhoto2.setOnClickListener {
                    selectedProductUris.removeAt(1)
                    updatePreviews()
                }
            } else {
                dialogBinding.framePreview2.visibility = View.GONE
            }

            // Preview 3
            if (count >= 3) {
                dialogBinding.framePreview3.visibility = View.VISIBLE
                MediaImageHelper.loadSafeImage(dialogBinding.ivPreview3, selectedProductUris[2].toString(), cornerRadiusDp = 8f)
                dialogBinding.btnRemovePhoto3.setOnClickListener {
                    selectedProductUris.removeAt(2)
                    updatePreviews()
                }
            } else {
                dialogBinding.framePreview3.visibility = View.GONE
            }

            // Add photo button slot visibility
            dialogBinding.layoutAddPhoto.visibility = if (count >= 3) View.GONE else View.VISIBLE
        }

        dialogBinding.layoutAddPhoto.setOnClickListener {
            val remaining = 3 - selectedProductUris.size
            if (remaining <= 0) {
                Toast.makeText(requireContext(), "Maximum 3 photos allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onProductImagesPicked = { uris ->
                if (uris.isNotEmpty()) {
                    val available = 3 - selectedProductUris.size
                    val toAdd = uris.take(available)
                    selectedProductUris.addAll(toAdd)
                    if (uris.size > available) {
                        Toast.makeText(requireContext(), "Maximum 3 photos allowed. Added $available photo(s).", Toast.LENGTH_SHORT).show()
                    }
                    updatePreviews()
                }
            }
            pickProductImagesLauncher.launch("image/*")
        }

        dialogBinding.btnSubmitProduct.setOnClickListener {
            val title = dialogBinding.etProductName.text.toString().trim()
            val priceStr = dialogBinding.etProductPrice.text.toString().trim()
            val originalPriceStr = dialogBinding.etProductOriginalPrice.text.toString().trim()
            val category = dialogBinding.etProductCategory.text.toString().trim()
            val stockStr = dialogBinding.etProductStock.text.toString().trim()
            val description = dialogBinding.etProductDescription.text.toString().trim()

            if (title.isBlank()) {
                dialogBinding.etProductName.error = "Title is required"
                return@setOnClickListener
            }
            val price = priceStr.toDoubleOrNull() ?: run {
                dialogBinding.etProductPrice.error = "Valid price required"
                return@setOnClickListener
            }
            val originalPrice = originalPriceStr.toDoubleOrNull() ?: 0.0
            val stock = stockStr.toIntOrNull() ?: 10
            val variants = variantAdapter.getVariants()

            dialogBinding.btnSubmitProduct.isEnabled = false
            dialogBinding.layoutUploadProgress.visibility = View.VISIBLE
            dialogBinding.tvUploadStatus.text = if (selectedProductUris.isNotEmpty()) {
                "Uploading ${selectedProductUris.size} photo(s)..."
            } else {
                "Publishing product..."
            }

            lifecycleScope.launch {
                val imageUrls = mutableListOf<String>()
                for ((idx, uri) in selectedProductUris.withIndex()) {
                    dialogBinding.tvUploadStatus.text = "Compressing & uploading image ${idx + 1}/${selectedProductUris.size}..."
                    val compressed = withContext(Dispatchers.IO) {
                        ImageCompressor.compressImageUri(
                            requireContext(),
                            uri,
                            maxDimension = 1024,
                            quality = 80
                        )
                    }
                    if (compressed != null) {
                        val uploaded = viewModel.uploadMedia(
                            compressed.bytes,
                            compressed.mimeType,
                            "product_${System.currentTimeMillis()}_$idx.jpg"
                        )
                        if (!uploaded.isNullOrBlank()) {
                            imageUrls.add(uploaded)
                        } else {
                            imageUrls.add(compressed.dataUri)
                        }
                    } else {
                        imageUrls.add(uri.toString())
                    }
                }

                dialogBinding.tvUploadStatus.text = "Publishing product..."
                viewModel.addProduct(
                    name = title,
                    price = price,
                    originalPrice = originalPrice,
                    category = category,
                    stock = stock,
                    imageUrls = imageUrls,
                    description = description,
                    variants = variants,
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "✅ Product published successfully!", Toast.LENGTH_LONG).show()
                    },
                    onError = { err ->
                        dialogBinding.btnSubmitProduct.isEnabled = true
                        dialogBinding.layoutUploadProgress.visibility = View.GONE
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        updatePreviews()
        dialog.show()
    }

    private fun openMyStorefront() {
        val store = viewModel.myStore.value ?: return
        val intent = Intent(requireContext(), StorefrontActivity::class.java).apply {
            putExtra("store_id", store.id)
            putExtra("store_name", store.name)
            putExtra("store_logo", store.logoUrl)
            putExtra("store_banner", store.bannerUrl)
            putExtra("store_description", store.description)
            putExtra("store_category", store.category)
            putExtra("store_address", store.address)
            putExtra("store_pin", store.ownerPin)
        }
        startActivity(intent)
    }

    private fun showEditStoreBottomSheet() {
        val store = viewModel.myStore.value ?: return
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogEditStoreBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.etEditStoreName.setText(store.name)
        dialogBinding.etEditStoreCategory.setText(store.category)
        dialogBinding.etEditStorePhone.setText(store.phone)
        dialogBinding.etEditStoreLocation.setText(store.address)
        dialogBinding.etEditStoreDescription.setText(store.description)

        var newLogoUri: Uri? = null
        var currentLogoUrl = store.logoUrl

        if (!currentLogoUrl.isNullOrBlank()) {
            MediaImageHelper.loadSafeImage(dialogBinding.ivEditStoreLogoPreview, currentLogoUrl, cornerRadiusDp = 12f)
            dialogBinding.btnRemoveEditStoreLogo.visibility = View.VISIBLE
            dialogBinding.tvEditStoreLogoSubtitle.text = "Tap to change logo from phone"
        }

        dialogBinding.layoutSelectEditStoreLogo.setOnClickListener {
            onSingleImagePicked = { uri ->
                if (uri != null) {
                    newLogoUri = uri
                    MediaImageHelper.loadSafeImage(dialogBinding.ivEditStoreLogoPreview, uri.toString(), cornerRadiusDp = 12f)
                    dialogBinding.btnRemoveEditStoreLogo.visibility = View.VISIBLE
                    dialogBinding.tvEditStoreLogoSubtitle.text = "New logo selected from phone"
                }
            }
            pickSingleImageLauncher.launch("image/*")
        }

        dialogBinding.btnRemoveEditStoreLogo.setOnClickListener {
            newLogoUri = null
            currentLogoUrl = null
            dialogBinding.ivEditStoreLogoPreview.setImageResource(R.drawable.ic_camera_status)
            dialogBinding.btnRemoveEditStoreLogo.visibility = View.GONE
            dialogBinding.tvEditStoreLogoSubtitle.text = "Tap to select logo from phone"
        }

        dialogBinding.btnSaveStoreChanges.setOnClickListener {
            val name = dialogBinding.etEditStoreName.text.toString().trim()
            val category = dialogBinding.etEditStoreCategory.text.toString().trim()
            val location = dialogBinding.etEditStoreLocation.text.toString().trim()
            val phone = dialogBinding.etEditStorePhone.text.toString().trim()
            val desc = dialogBinding.etEditStoreDescription.text.toString().trim()

            if (name.isBlank()) {
                dialogBinding.etEditStoreName.error = "Store name is required"
                return@setOnClickListener
            }

            dialogBinding.btnSaveStoreChanges.isEnabled = false
            dialogBinding.btnSaveStoreChanges.text = "Saving..."

            lifecycleScope.launch {
                var finalLogoUrl = currentLogoUrl
                if (newLogoUri != null) {
                    withContext(Dispatchers.IO) {
                        val compressed = ImageCompressor.compressImageUri(
                            requireContext(),
                            newLogoUri!!,
                            maxDimension = 512,
                            quality = 80
                        )
                        if (compressed != null) {
                            val uploaded = viewModel.uploadMedia(compressed.bytes, compressed.mimeType, "store_logo_${System.currentTimeMillis()}.jpg")
                            finalLogoUrl = if (!uploaded.isNullOrBlank()) uploaded else compressed.dataUri
                        }
                    }
                }

                viewModel.updateStore(
                    name = name,
                    category = category,
                    location = location,
                    phone = phone,
                    description = desc,
                    logoUrl = finalLogoUrl,
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Store profile updated!", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        dialogBinding.btnSaveStoreChanges.isEnabled = true
                        dialogBinding.btnSaveStoreChanges.text = "Save Store Changes"
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        dialog.show()
    }

    private fun showEditProductBottomSheet(product: Product) {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogAddProductBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvDialogTitle.text = "Edit Product"
        dialogBinding.tvDialogSubtitle.text = "Update item details, photos and pricing"
        dialogBinding.btnSubmitProduct.text = "Save Product Changes"

        dialogBinding.etProductName.setText(product.name)
        dialogBinding.etProductPrice.setText(String.format(Locale.US, "%.2f", product.price))
        if (product.originalPrice > 0.0) {
            dialogBinding.etProductOriginalPrice.setText(String.format(Locale.US, "%.2f", product.originalPrice))
        }
        dialogBinding.etProductCategory.setText(product.category)
        dialogBinding.etProductStock.setText(product.stock.toString())
        dialogBinding.etProductDescription.setText(product.description)

        // Variants setup
        val variantAdapter = VariantInputAdapter()
        dialogBinding.rvVariants.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvVariants.adapter = variantAdapter
        variantAdapter.setVariants(product.variants)
        dialogBinding.btnAddVariant.setOnClickListener { variantAdapter.addVariant() }

        // Photo slots management
        val existingPhotoUrls = product.imageUrls.toMutableList()
        if (existingPhotoUrls.isEmpty() && product.primaryImage.isNotBlank()) {
            existingPhotoUrls.add(product.primaryImage)
        }
        val newlyPickedUris = mutableListOf<Uri>()

        fun totalCount() = existingPhotoUrls.size + newlyPickedUris.size

        fun updateEditPreviews() {
            val count = totalCount()
            dialogBinding.tvPhotosCount.text = "$count/3 photos"

            val allSlots = mutableListOf<Any>()
            allSlots.addAll(existingPhotoUrls)
            allSlots.addAll(newlyPickedUris)

            // Slot 1
            if (allSlots.size >= 1) {
                dialogBinding.framePreview1.visibility = View.VISIBLE
                val item = allSlots[0]
                MediaImageHelper.loadSafeImage(dialogBinding.ivPreview1, item.toString(), cornerRadiusDp = 8f)
                dialogBinding.btnRemovePhoto1.setOnClickListener {
                    if (0 < existingPhotoUrls.size) {
                        existingPhotoUrls.removeAt(0)
                    } else {
                        newlyPickedUris.removeAt(0 - existingPhotoUrls.size)
                    }
                    updateEditPreviews()
                }
            } else {
                dialogBinding.framePreview1.visibility = View.GONE
            }

            // Slot 2
            if (allSlots.size >= 2) {
                dialogBinding.framePreview2.visibility = View.VISIBLE
                val item = allSlots[1]
                MediaImageHelper.loadSafeImage(dialogBinding.ivPreview2, item.toString(), cornerRadiusDp = 8f)
                dialogBinding.btnRemovePhoto2.setOnClickListener {
                    if (1 < existingPhotoUrls.size) {
                        existingPhotoUrls.removeAt(1)
                    } else {
                        newlyPickedUris.removeAt(1 - existingPhotoUrls.size)
                    }
                    updateEditPreviews()
                }
            } else {
                dialogBinding.framePreview2.visibility = View.GONE
            }

            // Slot 3
            if (allSlots.size >= 3) {
                dialogBinding.framePreview3.visibility = View.VISIBLE
                val item = allSlots[2]
                MediaImageHelper.loadSafeImage(dialogBinding.ivPreview3, item.toString(), cornerRadiusDp = 8f)
                dialogBinding.btnRemovePhoto3.setOnClickListener {
                    if (2 < existingPhotoUrls.size) {
                        existingPhotoUrls.removeAt(2)
                    } else {
                        newlyPickedUris.removeAt(2 - existingPhotoUrls.size)
                    }
                    updateEditPreviews()
                }
            } else {
                dialogBinding.framePreview3.visibility = View.GONE
            }

            dialogBinding.layoutAddPhoto.visibility = if (count >= 3) View.GONE else View.VISIBLE
        }

        dialogBinding.layoutAddPhoto.setOnClickListener {
            val remaining = 3 - totalCount()
            if (remaining <= 0) {
                Toast.makeText(requireContext(), "Maximum 3 photos allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onProductImagesPicked = { uris ->
                if (uris.isNotEmpty()) {
                    val available = 3 - totalCount()
                    val toAdd = uris.take(available)
                    newlyPickedUris.addAll(toAdd)
                    updateEditPreviews()
                }
            }
            pickProductImagesLauncher.launch("image/*")
        }

        dialogBinding.btnSubmitProduct.setOnClickListener {
            val title = dialogBinding.etProductName.text.toString().trim()
            val priceStr = dialogBinding.etProductPrice.text.toString().trim()
            val originalPriceStr = dialogBinding.etProductOriginalPrice.text.toString().trim()
            val category = dialogBinding.etProductCategory.text.toString().trim()
            val stockStr = dialogBinding.etProductStock.text.toString().trim()
            val description = dialogBinding.etProductDescription.text.toString().trim()

            if (title.isBlank()) {
                dialogBinding.etProductName.error = "Title is required"
                return@setOnClickListener
            }
            val price = priceStr.toDoubleOrNull() ?: run {
                dialogBinding.etProductPrice.error = "Valid price required"
                return@setOnClickListener
            }
            val originalPrice = originalPriceStr.toDoubleOrNull() ?: 0.0
            val stock = stockStr.toIntOrNull() ?: 10
            val variants = variantAdapter.getVariants()

            dialogBinding.btnSubmitProduct.isEnabled = false
            dialogBinding.layoutUploadProgress.visibility = View.VISIBLE
            dialogBinding.tvUploadStatus.text = "Saving product changes..."

            lifecycleScope.launch {
                val finalUrls = mutableListOf<String>()
                finalUrls.addAll(existingPhotoUrls)

                if (newlyPickedUris.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        newlyPickedUris.forEachIndexed { idx, uri ->
                            val compressed = ImageCompressor.compressImageUri(
                                requireContext(),
                                uri,
                                maxDimension = 1024,
                                quality = 80
                            )
                            if (compressed != null) {
                                val uploaded = viewModel.uploadMedia(
                                    bytes = compressed.bytes,
                                    mimeType = compressed.mimeType,
                                    fileName = "product_${System.currentTimeMillis()}_$idx.jpg"
                                )
                                if (!uploaded.isNullOrBlank()) {
                                    finalUrls.add(uploaded)
                                } else {
                                    finalUrls.add(compressed.dataUri)
                                }
                            }
                        }
                    }
                }

                viewModel.updateProduct(
                    product = product,
                    name = title,
                    price = price,
                    originalPrice = originalPrice,
                    category = category,
                    stock = stock,
                    imageUrls = finalUrls,
                    description = description,
                    variants = variants,
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Product updated!", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        dialogBinding.btnSubmitProduct.isEnabled = true
                        dialogBinding.layoutUploadProgress.visibility = View.GONE
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        updateEditPreviews()
        dialog.show()
    }

    private fun showDeleteProductDialog(product: Product) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Product")
            .setMessage("Are you sure you want to remove \"${product.displayTitle}\" from your store?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteProduct(product.id)
                Toast.makeText(requireContext(), "Product removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. Explore Products (Paged)
                launch {
                    viewModel.pagedProducts.collectLatest { pagingData ->
                        productAdapter.submitData(pagingData)
                    }
                }

                // Search Suggestions
                launch {
                    viewModel.searchSuggestions.collect { suggestions ->
                        searchSuggestionAdapter.submitList(suggestions)
                        binding.rvSearchSuggestions.visibility = if (suggestions.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }


                // Real-time refresh trigger
                launch {
                    viewModel.refreshEvent.collect {
                        productAdapter.refresh()
                    }
                }

                // Load State for Explore Products
                launch {
                    productAdapter.loadStateFlow.collectLatest { loadStates ->
                        binding.swipeRefresh.isRefreshing = loadStates.refresh is LoadState.Loading
                        val isListEmpty = loadStates.refresh is LoadState.NotLoading && productAdapter.itemCount == 0
                        binding.layoutEmptyExplore.visibility = if (isListEmpty) View.VISIBLE else View.GONE
                    }
                }

                // 2. Categories
                launch {
                    viewModel.categories.collect { categories ->
                        val allCategory = Category("all", "All", iconName = "grid")
                        val list = if (categories.any { it.id == "all" }) categories else listOf(allCategory) + categories
                        categoryAdapter.submitList(list)
                    }
                }

                // 3. Cart Badge Count
                launch {
                    viewModel.cartCount.collect { count ->
                        if (count > 0) {
                            binding.tvCartBadge.visibility = View.VISIBLE
                            binding.tvCartBadge.text = if (count > 99) "99+" else count.toString()
                        } else {
                            binding.tvCartBadge.visibility = View.GONE
                        }
                    }
                }

                // 4. Store State (Seller Hub)
                launch {
                    viewModel.myStore.collect { store ->
                        bindStoreState(store)
                    }
                }

                // 5. Store Inventory
                launch {
                    viewModel.myProducts.collect { products ->
                        storeProductAdapter.submitList(products)
                        binding.tvEmptyStoreProducts.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvMetricProducts.text = products.size.toString()
                        binding.tvInventoryTitle.text = String.format(Locale.US, "STORE INVENTORY (%d)", products.size)
                    }
                }

                // 6. Seller Orders & Revenue
                launch {
                    viewModel.sellerOrders.collect { orders ->
                        binding.tvMetricOrders.text = orders.size.toString()
                        val revenue = orders.sumOf { it.totalAmount }
                        binding.tvMetricRevenue.text = String.format(Locale.US, "$%.2f", revenue)
                    }
                }

                // 7. Loading state
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.visibility = if (loading && !binding.swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
                    }
                }

                // 8. Error messages
                launch {
                    viewModel.error.collect { error ->
                        error?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun checkLocationPermissionAndToggleNearby() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.setNearbyOnly(true)
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setNearbyOnly(true)
        } else {
            binding.chipNearby.isChecked = false
            Toast.makeText(requireContext(), "Location permission denied. Nearby feature unavailable.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindStoreState(store: Store?) {


        if (store != null) {
            binding.layoutNoStore.visibility = View.GONE
            binding.layoutHasStore.visibility = View.VISIBLE

            binding.tvHubStoreName.text = store.name
            binding.tvHubStoreCategory.text = String.format(
                Locale.US,
                "%s • PIN: %s",
                store.category.ifBlank { "Retail" },
                store.ownerPin.ifBlank { "1P0YE4WZ" }
            )
            MediaImageHelper.loadSafeImage(binding.ivHubStoreLogo, store.logoUrl, isCircle = true)

            // Update store tab title
            binding.tabLayoutMain.getTabAt(2)?.text = store.name.take(12)
        } else {
            binding.layoutNoStore.visibility = View.VISIBLE
            binding.layoutHasStore.visibility = View.GONE
            binding.tabLayoutMain.getTabAt(2)?.text = getString(R.string.tab_open_store)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
