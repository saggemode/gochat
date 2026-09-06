package com.example.gochat.ui.marketplace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Category
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.databinding.DialogAddProductBinding
import com.example.gochat.databinding.FragmentMarketplaceBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.util.Locale

class MarketplaceFragment : Fragment() {

    private var _binding: FragmentMarketplaceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MarketplaceViewModel by viewModels()

    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var storeProductAdapter: StoreProductAdapter

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

    override fun onResume() {
        super.onResume()
        viewModel.refreshCartCount()
        viewModel.loadMyStore()
    }

    private fun setupAdapters() {
        // 1. Explore Products Adapter
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

        // 2. Categories Adapter
        categoryAdapter = CategoryAdapter { category ->
            if (category.id.equals("all", ignoreCase = true)) {
                viewModel.filterByCategory(null)
            } else {
                viewModel.filterByCategory(category.id)
            }
        }
        binding.rvCategories.apply {
            adapter = categoryAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        // 3. Store Inventory Adapter (Seller Hub)
        storeProductAdapter = StoreProductAdapter(
            onClick = { product ->
                val intent = Intent(requireContext(), ProductDetailsActivity::class.java).apply {
                    putExtra("product_id", product.id)
                }
                startActivity(intent)
            },
            onDelete = { product ->
                showDeleteProductDialog(product)
            }
        )
        binding.rvStoreProducts.apply {
            adapter = storeProductAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupListeners() {
        // Tab switching: Marketplace (0) vs Seller Hub (1)
        binding.tabLayoutMain.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: 0
                binding.viewFlipper.displayedChild = index
                if (index == 1) {
                    viewModel.loadMyStore()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Search text watcher
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()
                binding.btnClearSearch.visibility = if (!query.isNullOrEmpty()) View.VISIBLE else View.GONE
                viewModel.setSearchQuery(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }

        // Verified-only filter chip
        binding.chipVerifiedOnly.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setVerifiedOnly(isChecked)
        }

        // SwipeRefresh
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadData()
        }

        // Topbar navigation actions
        binding.btnCartContainer.setOnClickListener {
            startActivity(Intent(requireContext(), CartActivity::class.java))
        }

        binding.btnOrders.setOnClickListener {
            startActivity(Intent(requireContext(), OrdersActivity::class.java))
        }

        // Seller Hub actions
        binding.btnCreateStore.setOnClickListener {
            handleCreateStore()
        }

        binding.btnAddProduct.setOnClickListener {
            showAddProductBottomSheet()
        }

        binding.btnShareStore.setOnClickListener {
            val store = viewModel.myStore.value
            val pin = store?.ownerPin?.ifBlank { "1P0YE4WZ" } ?: "1P0YE4WZ"
            val storeUrl = "https://gochat.app/store/$pin"
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GoChat Store Link", storeUrl))
            Toast.makeText(requireContext(), "📋 Store link copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCreateStore() {
        val name = binding.etStoreName.text.toString().trim()
        val category = binding.etStoreCategory.text.toString().trim()
        val location = binding.etStoreLocation.text.toString().trim()
        val phone = binding.etStorePhone.text.toString().trim()
        val description = binding.etStoreDescription.text.toString().trim()
        val logoUrl = binding.etStoreLogoUrl.text.toString().trim()

        if (name.isBlank()) {
            binding.etStoreName.error = "Store name is required"
            return
        }
        if (location.isBlank()) {
            binding.etStoreLocation.error = "Location is required"
            return
        }

        viewModel.createStore(
            name = name,
            category = category,
            location = location,
            phone = phone,
            description = description,
            logoUrl = logoUrl,
            onSuccess = {
                Toast.makeText(requireContext(), "🎉 Store \"$name\" is now live on GoChat!", Toast.LENGTH_LONG).show()
            },
            onError = { err ->
                Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showAddProductBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogAddProductBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.btnSubmitProduct.setOnClickListener {
            val title = dialogBinding.etProductName.text.toString().trim()
            val priceStr = dialogBinding.etProductPrice.text.toString().trim()
            val originalPriceStr = dialogBinding.etProductOriginalPrice.text.toString().trim()
            val category = dialogBinding.etProductCategory.text.toString().trim()
            val stockStr = dialogBinding.etProductStock.text.toString().trim()
            val imageUrl = dialogBinding.etProductImageUrl.text.toString().trim()
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

            viewModel.addProduct(
                name = title,
                price = price,
                originalPrice = originalPrice,
                category = category,
                stock = stock,
                imageUrl = imageUrl,
                description = description,
                onSuccess = {
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "✅ Product listed successfully!", Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            )
        }

        dialog.show()
    }

    private fun showDeleteProductDialog(product: Product) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Product")
            .setMessage("Are you sure you want to remove \"${product.displayTitle}\" from your store?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteProduct(product.id)
                Toast.makeText(requireContext(), "Product removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. Explore Products
                launch {
                    viewModel.products.collect { products ->
                        productAdapter.submitList(products)
                        binding.swipeRefresh.isRefreshing = false
                        binding.layoutEmptyExplore.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                // 2. Categories
                launch {
                    viewModel.categories.collect { categories ->
                        val allCategory = Category("all", "All", iconName = "grid")
                        val list = if (categories.any { it.id == "all" }) categories else listOf(allCategory) + categories
                        categoryAdapter.submitList(list)
                    }
                }

                // 3. Cart Badge Count
                launch {
                    viewModel.cartCount.collect { count ->
                        if (count > 0) {
                            binding.tvCartBadge.visibility = View.VISIBLE
                            binding.tvCartBadge.text = if (count > 99) "99+" else count.toString()
                        } else {
                            binding.tvCartBadge.visibility = View.GONE
                        }
                    }
                }

                // 4. Store State (Seller Hub)
                launch {
                    viewModel.myStore.collect { store ->
                        bindStoreState(store)
                    }
                }

                // 5. Store Inventory
                launch {
                    viewModel.myProducts.collect { products ->
                        storeProductAdapter.submitList(products)
                        binding.tvEmptyStoreProducts.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvMetricProducts.text = products.size.toString()
                        binding.tvInventoryTitle.text = String.format(Locale.US, "STORE INVENTORY (%d)", products.size)
                    }
                }

                // 6. Seller Orders & Revenue
                launch {
                    viewModel.sellerOrders.collect { orders ->
                        binding.tvMetricOrders.text = orders.size.toString()
                        val revenue = orders.sumOf { it.totalAmount }
                        binding.tvMetricRevenue.text = String.format(Locale.US, "$%.2f", revenue)
                    }
                }

                // 7. Loading state
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.visibility = if (loading && !binding.swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
                    }
                }

                // 8. Error messages
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

    private fun bindStoreState(store: Store?) {
        if (store != null) {
            binding.layoutNoStore.visibility = View.GONE
            binding.layoutHasStore.visibility = View.VISIBLE

            binding.tvHubStoreName.text = store.name
            binding.tvHubStoreCategory.text = String.format(
                Locale.US,
                "%s • PIN: %s",
                store.category.ifBlank { "Retail" },
                store.ownerPin.ifBlank { "1P0YE4WZ" }
            )
            MediaImageHelper.loadSafeImage(binding.ivHubStoreLogo, store.logoUrl, isCircle = true)

            // Update store tab title
            binding.tabLayoutMain.getTabAt(1)?.text = store.name.take(12)
        } else {
            binding.layoutNoStore.visibility = View.VISIBLE
            binding.layoutHasStore.visibility = View.GONE
            binding.tabLayoutMain.getTabAt(1)?.text = getString(R.string.tab_open_store)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
