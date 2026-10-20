package com.example.gochat.ui.marketplace

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.data.model.CartItem
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityCartBinding
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyNavigationBarInset
import com.example.gochat.core.ui.applyStatusBarInset
import dagger.hilt.android.AndroidEntryPoint
import com.example.gochat.core.currency.CurrencyManager
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCartBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository
    
    private lateinit var cartAdapter: CartAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.applyStatusBarInset()
        binding.cardCheckout.applyNavigationBarInset()

        setupAdapter()
        setupListeners()
        loadCart()
    }

    private fun setupAdapter() {
        cartAdapter = CartAdapter(
            onRemove = { item -> updateQuantity(item, 0) },
            onQuantityChange = { item, q -> updateQuantity(item, q) }
        )
        binding.rvCartItems.apply {
            adapter = cartAdapter
            layoutManager = LinearLayoutManager(this@CartActivity)
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCheckout.setOnClickListener {
            if (cartAdapter.currentList.isNotEmpty()) {
                startActivity(Intent(this, CheckoutActivity::class.java))
            } else {
                Toast.makeText(this, getString(R.string.empty_cart_message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadCart() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.getCart()
            binding.progressBar.visibility = View.GONE
            
            if (result.isSuccess) {
                val items = result.getOrDefault(emptyList())
                cartAdapter.submitList(items)
                updateTotal(items)
                binding.tvEmptyCart.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } else {
                Toast.makeText(this@CartActivity, getString(R.string.error_load_cart), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateQuantity(item: CartItem, quantity: Int) {
        lifecycleScope.launch {
            // Use the per-line endpoints keyed by the server cart-item id so quantity is SET,
            // not added, and so removing one line doesn't clear the whole cart.
            val result = if (quantity <= 0) {
                repository.removeCartItemById(item.id)
            } else {
                repository.updateCartItem(item.id, quantity)
            }
            if (result.isSuccess) {
                val items = result.getOrDefault(emptyList())
                cartAdapter.submitList(items)
                updateTotal(items)
                binding.tvEmptyCart.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } else {
                Toast.makeText(this@CartActivity, getString(R.string.error_update_quantity), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTotal(items: List<CartItem>) {
        val total = items.sumOf { (it.productPrice ?: 0.0) * it.quantity }
        binding.tvTotalAmount.text = CurrencyManager.formatPrice(total)
    }
}
