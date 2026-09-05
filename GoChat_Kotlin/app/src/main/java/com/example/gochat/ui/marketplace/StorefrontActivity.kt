package com.example.gochat.ui.marketplace

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityStorefrontBinding
import kotlinx.coroutines.launch

class StorefrontActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorefrontBinding
    private val repository by lazy { MarketplaceRepository(this) }
    private lateinit var productAdapter: ProductAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorefrontBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val storeId = intent.getStringExtra("store_id") ?: return finish()

        setupAdapter()
        setupListeners()
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

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadStoreData(storeId: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            val storeResult = repository.getStore(storeId)
            val productsResult = repository.getStoreProducts(storeId)
            
            binding.progressBar.visibility = View.GONE

            if (storeResult.isSuccess) {
                displayStore(storeResult.getOrThrow())
            }
            
            if (productsResult.isSuccess) {
                productAdapter.submitList(productsResult.getOrThrow())
            }
        }
    }

    private fun displayStore(store: Store) {
        binding.tvStoreName.text = store.name
        binding.tvStoreDescription.text = store.description
        MediaImageHelper.loadSafeImage(binding.ivStoreBanner, store.bannerUrl)
        MediaImageHelper.loadSafeImage(binding.ivStoreLogo, store.logoUrl, isCircle = true)
    }
}
