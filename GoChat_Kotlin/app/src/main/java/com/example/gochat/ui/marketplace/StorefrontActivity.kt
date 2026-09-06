package com.example.gochat.ui.marketplace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityStorefrontBinding
import com.example.gochat.databinding.DialogEditStoreBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorefrontActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorefrontBinding
    private val repository by lazy { MarketplaceRepository(this) }
    private lateinit var productAdapter: ProductAdapter
    private var currentStore: Store? = null
    private var isOwner: Boolean = false

    private var selectedLogoUri: Uri? = null
    private var onLogoPicked: ((Uri?) -> Unit)? = null
    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onLogoPicked?.invoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorefrontBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val storeId = intent.getStringExtra("store_id") ?: return finish()

        // Instant intent extras rendering
        val name = intent.getStringExtra("store_name")
        val logo = intent.getStringExtra("store_logo")
        val banner = intent.getStringExtra("store_banner")
        val desc = intent.getStringExtra("store_description")
        val category = intent.getStringExtra("store_category") ?: "General Retail"
        val address = intent.getStringExtra("store_address") ?: "Lagos, Nigeria"
        val pin = intent.getStringExtra("store_pin") ?: "1P0YE4WZ"

        if (!name.isNullOrBlank()) {
            binding.tvStoreName.text = name
            binding.tvStoreMeta.text = "$category • $address • PIN: $pin"
            binding.tvStoreDescription.text = desc.orEmpty()
            if (!logo.isNullOrBlank()) {
                MediaImageHelper.loadSafeImage(binding.ivStoreLogo, logo, isCircle = true)
            }
            if (!banner.isNullOrBlank()) {
                MediaImageHelper.loadSafeImage(binding.ivStoreBanner, banner)
            }
        }

        setupAdapter()
        setupListeners(storeId)
        loadStoreData(storeId)
    }

    private fun setupAdapter() {
        productAdapter = ProductAdapter { product ->
            val intent = Intent(this, ProductDetailsActivity::class.java).apply {
                putExtra("product_id", product.id)
            }
            startActivity(intent)
        }
        binding.rvProducts.apply {
            adapter = productAdapter
            layoutManager = GridLayoutManager(this@StorefrontActivity, 2)
        }
    }

    private fun setupListeners(storeId: String) {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnStorefrontAction.setOnClickListener {
            if (isOwner) {
                currentStore?.let { showEditStoreDialog(it) }
            }
        }
    }

    private fun loadStoreData(storeId: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE

            val myProfile = repository.getBusinessProfile().getOrNull()
            val storeResult = repository.getStore(storeId)
            val productsResult = repository.getStoreProducts(storeId)

            binding.progressBar.visibility = View.GONE

            if (storeResult.isSuccess) {
                val store = storeResult.getOrThrow()
                currentStore = store
                isOwner = (myProfile != null && (myProfile.id == store.id || myProfile.id == storeId || myProfile.name == store.name))
                displayStore(store)
            }

            if (productsResult.isSuccess) {
                productAdapter.submitList(productsResult.getOrThrow())
            }
        }
    }

    private fun displayStore(store: Store) {
        binding.tvStoreName.text = store.name
        binding.tvStoreMeta.text = String.format("%s • %s • PIN: %s",
            store.category.ifBlank { "General Retail" },
            store.address.ifBlank { "Lagos, Nigeria" },
            store.ownerPin.ifBlank { "1P0YE4WZ" }
        )
        binding.tvStoreDescription.text = store.description.ifBlank {
            "Welcome to ${store.name}! Browse our curated collection on GoChat Marketplace."
        }
        binding.ivStoreVerified.visibility = if (store.isVerified) View.VISIBLE else View.GONE
        MediaImageHelper.loadSafeImage(binding.ivStoreLogo, store.logoUrl, isCircle = true)
        MediaImageHelper.loadSafeImage(binding.ivStoreBanner, store.bannerUrl)

        if (isOwner) {
            binding.btnStorefrontAction.visibility = View.VISIBLE
            binding.btnStorefrontAction.text = "Edit Store"
        }
    }

    private fun showEditStoreDialog(store: Store) {
        val dialog = BottomSheetDialog(this)
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
            dialogBinding.tvEditStoreLogoSubtitle.text = "Tap to change logo"
        }

        dialogBinding.layoutSelectEditStoreLogo.setOnClickListener {
            onLogoPicked = { uri ->
                if (uri != null) {
                    newLogoUri = uri
                    MediaImageHelper.loadSafeImage(dialogBinding.ivEditStoreLogoPreview, uri.toString(), cornerRadiusDp = 12f)
                    dialogBinding.btnRemoveEditStoreLogo.visibility = View.VISIBLE
                    dialogBinding.tvEditStoreLogoSubtitle.text = "New logo selected from phone"
                }
            }
            pickLogoLauncher.launch("image/*")
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
                            this@StorefrontActivity,
                            newLogoUri!!,
                            maxDimension = 512,
                            quality = 80
                        )
                        if (compressed != null) {
                            val uploaded = repository.uploadMedia(compressed.bytes, compressed.mimeType, "store_logo_${System.currentTimeMillis()}.jpg")
                            finalLogoUrl = if (!uploaded.isNullOrBlank()) uploaded else compressed.dataUri
                        }
                    }
                }

                val updatedStore = store.copy(
                    name = name,
                    category = category.ifBlank { "General Retail" },
                    address = location,
                    phone = phone,
                    description = desc,
                    logoUrl = finalLogoUrl
                )

                val res = repository.updateBusinessProfile(updatedStore)
                if (res.isSuccess) {
                    val saved = res.getOrThrow()
                    currentStore = saved
                    displayStore(saved)
                    dialog.dismiss()
                    Toast.makeText(this@StorefrontActivity, "Store profile updated!", Toast.LENGTH_SHORT).show()
                } else {
                    dialogBinding.btnSaveStoreChanges.isEnabled = true
                    dialogBinding.btnSaveStoreChanges.text = "Save Store Changes"
                    Toast.makeText(this@StorefrontActivity, res.exceptionOrNull()?.message ?: "Failed to update store", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }
}
