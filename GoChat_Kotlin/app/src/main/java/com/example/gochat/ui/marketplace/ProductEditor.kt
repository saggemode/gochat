package com.example.gochat.ui.marketplace

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.core.currency.CurrencyManager
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.DialogAddProductBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Shared product add/edit editor (a bottom sheet) used by both the Seller Hub
 * (MarketplaceFragment) and the standalone My Products page. The host supplies a
 * context, inflater, coroutine scope, the MarketplaceViewModel, and an image
 * picker so the sheet stays free of Fragment/Activity specifics.
 */
class ProductEditor(private val host: Host) {

    interface Host {
        val ctx: Context
        val inflater: LayoutInflater
        val scope: CoroutineScope
        val vm: MarketplaceViewModel
        var onProductImagesPicked: ((List<Uri>) -> Unit)?
        fun launchProductImagePicker()
    }

    fun showAdd() {
        val dialog = BottomSheetDialog(host.ctx)
        val dialogBinding = DialogAddProductBinding.inflate(host.inflater)
        dialog.setContentView(dialogBinding.root)

        val userCurrencySymbol = com.example.gochat.core.currency.CurrencyManager.getUserCurrencySymbol()
        val userCurrencyCode = com.example.gochat.core.currency.CurrencyManager.getUserCurrency()
        dialogBinding.tilProductPrice.hint = "Selling Price ($userCurrencySymbol $userCurrencyCode) *"
        dialogBinding.tilProductOriginalPrice.hint = "Original Price ($userCurrencySymbol $userCurrencyCode)"

        val selectedProductUris = mutableListOf<Uri>()

        // Category Dropdown setup
        val categoryList = (host.vm.categories.value.takeIf { it.isNotEmpty() } ?: MarketplaceRepository.cachedCategories)
            .filter { !it.id.equals("all", ignoreCase = true) }
        val categoryNames = categoryList.map { it.name }
        var selectedCategoryId: String? = null

        val categoryDropdownAdapter = ArrayAdapter(
            host.ctx,
            R.layout.item_dropdown_category,
            R.id.tvDropdownItem,
            categoryNames
        )
        dialogBinding.etProductCategory.setAdapter(categoryDropdownAdapter)

        val defaultCat = categoryList.firstOrNull { it.name.equals("Electronics", ignoreCase = true) }
            ?: categoryList.firstOrNull()
        if (defaultCat != null) {
            selectedCategoryId = defaultCat.id
            dialogBinding.etProductCategory.setText(defaultCat.name, false)
        }

        dialogBinding.etProductCategory.setOnClickListener {
            dialogBinding.etProductCategory.showDropDown()
        }

        dialogBinding.etProductCategory.setOnItemClickListener { _, _, position, _ ->
            val chosen = categoryList.getOrNull(position)
            if (chosen != null) {
                selectedCategoryId = chosen.id
                dialogBinding.etProductCategory.setText(chosen.name, false)
            }
        }

        // Variants setup
        val variantAdapter = VariantInputAdapter()
        dialogBinding.rvVariants.layoutManager = LinearLayoutManager(host.ctx)
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
                Toast.makeText(host.ctx, "Maximum 3 photos allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            host.onProductImagesPicked = { uris ->
                if (uris.isNotEmpty()) {
                    val available = 3 - selectedProductUris.size
                    val toAdd = uris.take(available)
                    selectedProductUris.addAll(toAdd)
                    if (uris.size > available) {
                        Toast.makeText(host.ctx, "Maximum 3 photos allowed. Added $available photo(s).", Toast.LENGTH_SHORT).show()
                    }
                    updatePreviews()
                }
            }
            host.launchProductImagePicker()
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

            host.scope.launch {
                val imageUrls = mutableListOf<String>()
                for ((idx, uri) in selectedProductUris.withIndex()) {
                    dialogBinding.tvUploadStatus.text = "Compressing & uploading image ${idx + 1}/${selectedProductUris.size}..."
                    val compressed = withContext(Dispatchers.IO) {
                        ImageCompressor.compressImageUri(
                            host.ctx,
                            uri,
                            maxDimension = 1024,
                            quality = 80
                        )
                    }
                    if (compressed != null) {
                        val uploaded = host.vm.uploadMedia(
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
                val categoryId = selectedCategoryId ?: MarketplaceRepository.resolveCategoryId(null, category)
                host.vm.addProduct(
                    name = title,
                    price = price,
                    originalPrice = originalPrice,
                    category = category,
                    categoryId = categoryId,
                    stock = stock,
                    imageUrls = imageUrls,
                    description = description,
                    variants = variants,
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(host.ctx, "✅ Product published successfully!", Toast.LENGTH_LONG).show()
                    },
                    onError = { err ->
                        dialogBinding.btnSubmitProduct.isEnabled = true
                        dialogBinding.layoutUploadProgress.visibility = View.GONE
                        Toast.makeText(host.ctx, err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        updatePreviews()
        dialog.show()
    }

    fun showEdit(product: Product) {
        val dialog = BottomSheetDialog(host.ctx)
        val dialogBinding = DialogAddProductBinding.inflate(host.inflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvDialogTitle.text = "Edit Product"
        dialogBinding.tvDialogSubtitle.text = "Update item details, photos and pricing"
        dialogBinding.btnSubmitProduct.text = "Save Product Changes"

        dialogBinding.etProductName.setText(product.name)
        dialogBinding.etProductPrice.setText(String.format(Locale.US, "%.2f", product.price))
        if (product.originalPrice > 0.0) {
            dialogBinding.etProductOriginalPrice.setText(String.format(Locale.US, "%.2f", product.originalPrice))
        }

        // Category Dropdown setup
        val editCategoryList = (host.vm.categories.value.takeIf { it.isNotEmpty() } ?: MarketplaceRepository.cachedCategories)
            .filter { !it.id.equals("all", ignoreCase = true) }
        val editCategoryNames = editCategoryList.map { it.name }
        var editSelectedCategoryId: String? = product.categoryId.takeIf { MarketplaceRepository.isValidUuid(it) }

        val editCategoryAdapter = ArrayAdapter(
            host.ctx,
            R.layout.item_dropdown_category,
            R.id.tvDropdownItem,
            editCategoryNames
        )
        dialogBinding.etProductCategory.setAdapter(editCategoryAdapter)

        val currentCat = editCategoryList.firstOrNull { it.id.equals(product.categoryId, ignoreCase = true) }
            ?: editCategoryList.firstOrNull { it.name.equals(product.category, ignoreCase = true) }
        if (currentCat != null) {
            editSelectedCategoryId = currentCat.id
            dialogBinding.etProductCategory.setText(currentCat.name, false)
        } else {
            dialogBinding.etProductCategory.setText(product.category.ifBlank { "Electronics" }, false)
        }

        dialogBinding.etProductCategory.setOnClickListener {
            dialogBinding.etProductCategory.showDropDown()
        }

        dialogBinding.etProductCategory.setOnItemClickListener { _, _, position, _ ->
            val chosen = editCategoryList.getOrNull(position)
            if (chosen != null) {
                editSelectedCategoryId = chosen.id
                dialogBinding.etProductCategory.setText(chosen.name, false)
            }
        }

        dialogBinding.etProductStock.setText(product.stock.toString())
        dialogBinding.etProductDescription.setText(product.description)

        // Variants setup
        val variantAdapter = VariantInputAdapter()
        dialogBinding.rvVariants.layoutManager = LinearLayoutManager(host.ctx)
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
                Toast.makeText(host.ctx, "Maximum 3 photos allowed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            host.onProductImagesPicked = { uris ->
                if (uris.isNotEmpty()) {
                    val available = 3 - totalCount()
                    val toAdd = uris.take(available)
                    newlyPickedUris.addAll(toAdd)
                    updateEditPreviews()
                }
            }
            host.launchProductImagePicker()
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

            host.scope.launch {
                val finalUrls = mutableListOf<String>()
                finalUrls.addAll(existingPhotoUrls)

                if (newlyPickedUris.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        newlyPickedUris.forEachIndexed { idx, uri ->
                            val compressed = ImageCompressor.compressImageUri(
                                host.ctx,
                                uri,
                                maxDimension = 1024,
                                quality = 80
                            )
                            if (compressed != null) {
                                val uploaded = host.vm.uploadMedia(
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

                val editCategoryId = editSelectedCategoryId ?: MarketplaceRepository.resolveCategoryId(product.categoryId, category)
                host.vm.updateProduct(
                    product = product,
                    name = title,
                    price = price,
                    originalPrice = originalPrice,
                    category = category,
                    categoryId = editCategoryId,
                    stock = stock,
                    imageUrls = finalUrls,
                    description = description,
                    variants = variants,
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(host.ctx, "Product updated!", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        dialogBinding.btnSubmitProduct.isEnabled = true
                        dialogBinding.layoutUploadProgress.visibility = View.GONE
                        Toast.makeText(host.ctx, err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        updateEditPreviews()
        dialog.show()

        // Fetch complete product details by ID asynchronously to guarantee all photos are populated
        host.scope.launch {
            val fresh = host.vm.getProductById(product.id)
            if (fresh != null && fresh.imageUrls.size > existingPhotoUrls.size) {
                existingPhotoUrls.clear()
                existingPhotoUrls.addAll(fresh.imageUrls)
                updateEditPreviews()
            }
        }
    }
}
