package com.example.gochat.ui.marketplace

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityOrdersBinding
import kotlinx.coroutines.launch

class OrdersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrdersBinding
    private val repository by lazy { MarketplaceRepository(this) }
    private val orderAdapter = OrderAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdapter()
        setupListeners()
        loadOrders()
    }

    private fun setupAdapter() {
        binding.rvOrders.apply {
            adapter = orderAdapter
            layoutManager = LinearLayoutManager(this@OrdersActivity)
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadOrders() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.getOrders()
            binding.progressBar.visibility = View.GONE

            if (result.isSuccess) {
                val orders = result.getOrDefault(emptyList())
                orderAdapter.submitList(orders)
                binding.tvEmptyOrders.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
            } else {
                Toast.makeText(this@OrdersActivity, "Failed to load orders", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
