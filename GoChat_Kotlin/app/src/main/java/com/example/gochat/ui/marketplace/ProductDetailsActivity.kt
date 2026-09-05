package com.example.gochat.ui.marketplace

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityProductDetailsBinding
import kotlinx.coroutines.launch

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
        binding.btnAddToCart.setOnClickListener {
            product?.let { addToCart(it) }
        }
        binding.layoutStore.setOnClickListener {
            store?.let {
                val intent = Intent(this, StorefrontActivity::class.java).apply {
                    putExtra("store_id", it.id)
                }
                startActivity(intent)
            }
        }
    }

    private fun loadProductDetails(productId: String) {
        lifecycleScope.launch {
            val result = repository.getProducts() // For simplicity, we get all and find one, or update repo
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
        binding.tvProductName.text = product.name
        binding.tvProductPrice.text = getString(R.string.price_format, product.currency, product.price.toString())
        binding.tvProductDescription.text = product.description
        
        val imageUrl = product.imageUrls.firstOrNull()
        MediaImageHelper.loadSafeImage(binding.ivProductImage, imageUrl)
    }

    private fun displayStore(store: Store) {
        binding.tvStoreName.text = store.name
        val verifiedStatus = if (store.isVerified) getString(R.string.status_verified) else getString(R.string.status_standard)
        binding.tvStoreRating.text = getString(R.string.store_rating_format, store.rating.toString(), verifiedStatus)
        MediaImageHelper.loadSafeImage(binding.ivStoreLogo, store.logoUrl, isCircle = true)
    }

    private fun addToCart(product: Product) {
        lifecycleScope.launch {
            val result = repository.addToCart(product.id, 1)
            if (result.isSuccess) {
                Toast.makeText(this@ProductDetailsActivity, getString(R.string.toast_added_to_cart), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ProductDetailsActivity, getString(R.string.error_add_to_cart), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
