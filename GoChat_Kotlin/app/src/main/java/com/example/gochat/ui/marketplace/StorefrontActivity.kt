package com.example.gochat.ui.marketplace

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import androidx.paging.PagingData
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityStorefrontBinding
import com.example.gochat.databinding.DialogEditStoreBinding
import com.example.gochat.databinding.DialogNewChatByPinBinding
import com.example.gochat.databinding.DialogStoreQrBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class StorefrontActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorefrontBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository
    
    private lateinit var productAdapter: ProductAdapter
    private var currentStore: Store? = null
    private var isOwner: Boolean = false

    private var selectedLogoUri: Uri? = null
    private var onLogoPicked: ((Uri?) -> Unit)? = null
    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onLogoPicked?.invoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityStorefrontBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)

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
            bindStoreLogoAndBanner(logo, banner)
        }

        setupAdapter()
        setupListeners(storeId)
        loadStoreData(storeId)
    }

    /**
     * Binds the store header images. The logo circle must NEVER display the banner image:
     * if the store has no distinct logo (blank, or the same URL as the banner), we show a
     * clean storefront placeholder instead. The banner keeps the layout's default wallpaper
     * when none is set.
     */
    private fun bindStoreLogoAndBanner(logoUrl: String?, bannerUrl: String?) {
        if (!bannerUrl.isNullOrBlank()) {
            MediaImageHelper.loadSafeImage(binding.ivStoreBanner, bannerUrl)
        }
        val hasDistinctLogo = !logoUrl.isNullOrBlank() && logoUrl != bannerUrl
        if (hasDistinctLogo) {
            MediaImageHelper.loadSafeImage(
                binding.ivStoreLogo, logoUrl, isCircle = true,
                placeholderRes = R.drawable.ic_storefront, errorRes = R.drawable.ic_storefront
            )
        } else {
            binding.ivStoreLogo.setImageResource(R.drawable.ic_storefront)
        }
    }

    private fun setupAdapter() {
        productAdapter = ProductAdapter(onClick = { product ->
            val intent = Intent(this, ProductDetailsActivity::class.java).apply {
                putExtra("product_id", product.id)
            }
            startActivity(intent)
        })
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

        binding.btnStoreQR.setOnClickListener {
            val s = currentStore ?: return@setOnClickListener
            showStoreQR(s)
        }

        binding.btnFollowStore.setOnClickListener {
            val s = currentStore ?: return@setOnClickListener
            toggleFollow(s.id)
        }
    }

    private fun toggleFollow(storeId: String) {
        lifecycleScope.launch {
            val res = repository.toggleFollowStore(storeId)
            if (res.isSuccess) {
                val following = res.getOrThrow()
                updateFollowButton(following)
                currentStore?.let { s ->
                    val updatedCount = if (following) s.followersCount + 1 else maxOf(0, s.followersCount - 1)
                    currentStore = s.copy(followersCount = updatedCount)
                    displayStore(currentStore!!)
                }
                Toast.makeText(this@StorefrontActivity, if (following) "Following store!" else "Unfollowed store", Toast.LENGTH_SHORT).show()
            } else {
                val err = res.exceptionOrNull()?.message ?: "Failed to update follow status"
                Toast.makeText(this@StorefrontActivity, err, Toast.LENGTH_SHORT).show()
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


    private fun showStoreQR(store: Store) {
        val dialog = BottomSheetDialog(this)
        val qrContent = "gochat://store/${store.ownerPin.ifBlank { store.id }}"
        
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(qrContent, BarcodeFormat.QR_CODE, 600, 600)
            
            val dialogBinding = DialogStoreQrBinding.inflate(layoutInflater)
            dialog.setContentView(dialogBinding.root)
            
            dialogBinding.tvQrTitle.text = "${store.name} QR Code"
            dialogBinding.ivStoreQrCode.setImageBitmap(bitmap)
            dialogBinding.btnCloseQr.setOnClickListener { dialog.dismiss() }
            
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
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
                productAdapter.submitData(lifecycle, PagingData.from(productsResult.getOrThrow()))
            }
        }
    }

    private fun displayStore(store: Store) {
        binding.tvStoreName.text = store.name

        val followers = store.followersCount
        val followerText = when (followers) {
            0 -> "0 followers"
            1 -> "1 follower"
            else -> if (followers >= 1000) String.format(java.util.Locale.US, "%.1fk followers", followers / 1000.0) else "$followers followers"
        }

        binding.tvStoreMeta.text = String.format("%s • %s • %s",
            followerText,
            store.category.ifBlank { "General Retail" },
            store.address.ifBlank { "Lagos, Nigeria" }
        )
        binding.tvStoreDescription.text = store.description.ifBlank {
            "Welcome to ${store.name}! Browse our curated collection on GoChat Marketplace."
        }
        binding.ivStoreVerified.visibility = if (store.isVerified) View.VISIBLE else View.GONE
        bindStoreLogoAndBanner(store.logoUrl, store.bannerUrl)

        if (isOwner) {
            binding.btnStorefrontAction.visibility = View.VISIBLE
            binding.btnStorefrontAction.text = "Edit Store"
            binding.btnFollowStore.visibility = View.GONE
        } else {
            binding.btnStorefrontAction.visibility = View.GONE
            binding.btnFollowStore.visibility = View.VISIBLE
            checkFollowStatus(store.id)
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
