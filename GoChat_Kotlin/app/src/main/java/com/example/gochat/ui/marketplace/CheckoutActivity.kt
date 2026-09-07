package com.example.gochat.ui.marketplace

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.R
import com.example.gochat.data.model.CartItem
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityCheckoutBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CheckoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckoutBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository
    
    private var cartItems: List<CartItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadSummary()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnPlaceOrder.setOnClickListener {
            placeOrder()
        }
    }

    private fun loadSummary() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.getCart()
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                cartItems = result.getOrDefault(emptyList())
                displaySummary(cartItems)
            } else {
                Toast.makeText(this@CheckoutActivity, getString(R.string.error_load_cart_summary), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun displaySummary(items: List<CartItem>) {
        binding.layoutSummary.removeAllViews()
        var total = 0.0
        for (item in items) {
            val tv = TextView(this).apply {
                text = getString(R.string.order_summary_item_format, item.productName, item.quantity, ((item.productPrice ?: 0.0) * item.quantity).toString())
                setPadding(0, 8, 0, 8)
                setTextColor(Color.BLACK) // Or use a resource
            }
            binding.layoutSummary.addView(tv)
            total += (item.productPrice ?: 0.0) * item.quantity
        }
        binding.tvTotalAmount.text = getString(R.string.price_format_usd, total.toString())
    }

    private fun placeOrder() {
        val address = binding.etAddress.text.toString()
        if (address.isBlank()) {
            Toast.makeText(this, getString(R.string.error_empty_address), Toast.LENGTH_SHORT).show()
            return
        }

        if (cartItems.isEmpty()) return

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val total = cartItems.sumOf { (it.productPrice ?: 0.0) * it.quantity }
            val storeId = cartItems.firstOrNull()?.storeId?.ifBlank { "store_istore" } ?: "store_istore"
            
            val result = repository.placeOrder(storeId, cartItems, total, address)
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                Toast.makeText(this@CheckoutActivity, getString(R.string.toast_order_placed_success), Toast.LENGTH_LONG).show()
                startActivity(Intent(this@CheckoutActivity, OrdersActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@CheckoutActivity, getString(R.string.error_place_order), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
