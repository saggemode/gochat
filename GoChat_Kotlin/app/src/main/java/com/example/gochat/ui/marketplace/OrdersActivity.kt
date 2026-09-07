package com.example.gochat.ui.marketplace

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.data.model.Order
import com.example.gochat.data.model.OrderStatus
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivityOrdersBinding
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OrdersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrdersBinding
    
    @Inject
    lateinit var repository: MarketplaceRepository
    
    private var isSellerView: Boolean = false
    private var currentOrders: List<Order> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadOrders()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tabLayoutOrders.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isSellerView = (tab?.position == 1)
                loadOrders()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.swipeRefresh.setOnRefreshListener {
            loadOrders()
        }
    }

    private fun loadOrders() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = if (isSellerView) repository.getSellerOrders() else repository.getBuyerOrders()
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            if (result.isSuccess) {
                currentOrders = result.getOrDefault(emptyList())
                displayOrders(currentOrders)
            } else {
                Toast.makeText(this@OrdersActivity, "Failed to load orders", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayOrders(orders: List<Order>) {
        val adapter = OrderAdapter(
            isSellerView = isSellerView,
            onUpdateStatus = { order, nextStatus ->
                updateOrderStatus(order, nextStatus)
            }
        )
        binding.rvOrders.adapter = adapter
        binding.rvOrders.layoutManager = LinearLayoutManager(this@OrdersActivity)
        adapter.submitList(orders)

        binding.tvEmptyOrders.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyOrders.text = if (isSellerView) {
            "No incoming sales orders yet.\nOrders from your store buyers will appear here."
        } else {
            "No purchases yet.\nExplore the Marketplace and place your first order!"
        }
    }

    private fun updateOrderStatus(order: Order, nextStatus: OrderStatus) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = repository.updateOrderStatus(order.id, nextStatus)
            binding.progressBar.visibility = View.GONE
            if (result.isSuccess) {
                Toast.makeText(this@OrdersActivity, "Order status updated to ${nextStatus.name}", Toast.LENGTH_SHORT).show()
                loadOrders()
            } else {
                Toast.makeText(this@OrdersActivity, "Failed to update order status", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
