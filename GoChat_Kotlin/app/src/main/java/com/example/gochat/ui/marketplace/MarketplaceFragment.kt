package com.example.gochat.ui.marketplace

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.data.model.Category
import com.example.gochat.databinding.FragmentMarketplaceBinding
import kotlinx.coroutines.launch

class MarketplaceFragment : Fragment() {

    private var _binding: FragmentMarketplaceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MarketplaceViewModel by viewModels()

    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter

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

    private fun setupAdapters() {
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

        categoryAdapter = CategoryAdapter { category ->
            if (category.id == "all") {
                viewModel.filterByCategory(null)
            } else {
                viewModel.filterByCategory(category.id)
            }
        }
        binding.rvCategories.apply {
            adapter = categoryAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadData()
        }
        binding.btnCart.setOnClickListener {
            startActivity(Intent(requireContext(), CartActivity::class.java))
        }
        binding.btnOrders.setOnClickListener {
            startActivity(Intent(requireContext(), OrdersActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.products.collect { products ->
                        productAdapter.submitList(products)
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
                launch {
                    viewModel.categories.collect { categories ->
                        val allCategory = Category("all", "All")
                        categoryAdapter.submitList(listOf(allCategory) + categories)
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
