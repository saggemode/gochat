package com.example.gochat.ui.marketplace

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.core.media.ImageCompressor
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.core.currency.CurrencyManager
import com.example.gochat.data.model.Category
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.DialogAddProductBinding
import com.example.gochat.databinding.DialogEditStoreBinding
import androidx.paging.LoadState
import androidx.paging.PagingData
import com.example.gochat.databinding.FragmentMarketplaceBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@AndroidEntryPoint
class MarketplaceFragment : Fragment() {

    private var _binding: FragmentMarketplaceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MarketplaceViewModel by viewModels()

    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var storeProductAdapter: StoreProductAdapter
    private lateinit var searchSuggestionAdapter: SearchSuggestionAdapter
    private lateinit var followedStoreAdapter: FollowedStoreAdapter
    private lateinit var followedProductAdapter: ProductAdapter

    private var selectedStoreLogoUri: Uri? = null

    private var onProductImagesPicked: ((List<Uri>) -> Unit)? = null
    private var onSingleImagePicked: ((Uri?) -> Unit)? = null

    private val productEditor: ProductEditor by lazy {
        ProductEditor(object : ProductEditor.Host {
            override val ctx: android.content.Context get() = requireContext()
            override val inflater: android.view.LayoutInflater get() = layoutInflater
            override val scope: kotlinx.coroutines.CoroutineScope get() = lifecycleScope
            override val vm: MarketplaceViewModel get() = viewModel
            override var onProductImagesPicked: ((List<Uri>) -> Unit)?
                get() = this@MarketplaceFragment.onProductImagesPicked
                set(value) { this@MarketplaceFragment.onProductImagesPicked = value }
            override fun launchProductImagePicker() { pickProductImagesLauncher.launch("image/*") }
        })
    }

    private val pickSingleImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        onSingleImagePicked?.invoke(uri)
    }

    private val pickStoreLogoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedStoreLogoUri = uri
        if (uri != null) {
            val b = _binding ?: return@registerForActivityResult
            b.ivStoreLogoPreview.setPadding(0, 0, 0, 0)
            MediaImageHelper.loadSafeImage(b.ivStoreLogoPreview, uri.toString(), cornerRadiusDp = 12f)
            b.btnRemoveStoreLogo.visibility = View.VISIBLE
            b.tvStoreLogoSubtitle.text = "Logo selected from phone"
        } else {
            resetStoreLogoPreview()
        }
    }

    private val pickProductImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        onProductImagesPicked?.invoke(uris)
    }

    private fun resetStoreLogoPreview() {
        selectedStoreLogoUri = null
        val b = _binding ?: return
        b.ivStoreLogoPreview.setImageDrawable(null)
        b.ivStoreLogoPreview.setImageResource(R.drawable.ic_camera_status)
        val pad = (14 * resources.displayMetrics.density).toInt()
        b.ivStoreLogoPreview.setPadding(pad, pad, pad, pad)
        b.btnRemoveStoreLogo.visibility = View.GONE
        b.tvStoreLogoSubtitle.text = "Tap to select logo from phone"
    }

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
        when (binding.tabLayoutMain.selectedTabPosition) {
            1 -> viewModel.loadFollowedStores()
            2 -> viewModel.loadMyStore()
        }
    }

    private fun setupAdapters() {
        // 1. Explore Products Adapter
        productAdapter = ProductAdapter { product, sharedView ->
            val intent = Intent(requireContext(), ProductDetailsActivity::class.java).apply {
                putExtra("product_id", product.id)
                putExtra("product_name", product.displayTitle)
                putExtra("product_image", product.primaryImage)
                putExtra("product_price", product.price)
                putExtra("product_currency", product.currency)
                putExtra("store_id", product.storeId)
                putExtra("store_name", product.storeName)
                putExtra("seller_pin", product.sellerPin)
                putExtra("seller_id", product.sellerId)
            }
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                requireActivity(),
                sharedView,
                "product_image_${product.id}"
            )
            startActivity(intent, options.toBundle())
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
            onEdit = { product ->
                lifecycleScope.launch {
                    val fullProduct = viewModel.getProductById(product.id) ?: product
                    showEditProductBottomSheet(fullProduct)
                }
            },
            onDelete = { product ->
                showDeleteProductDialog(product)
            },
            onShare = { product ->
                val shareSheet = com.example.gochat.ui.chat.ShareToChatBottomSheet(product)
                shareSheet.show(childFragmentManager, "ShareToChat")
            }
        )
        binding.rvStoreProducts.apply {
            adapter = storeProductAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        // 4. Search Suggestions
        searchSuggestionAdapter = SearchSuggestionAdapter { suggestion ->
            binding.etSearch.setText(suggestion)
            binding.etSearch.setSelection(suggestion.length)
            viewModel.setSearchQuery(suggestion)
        }
        binding.rvSearchSuggestions.apply {
            adapter = searchSuggestionAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        // 5. Followed Stores Adapter
        followedStoreAdapter = FollowedStoreAdapter(
            onClick = { store ->
                val intent = Intent(requireContext(), StorefrontActivity::class.java).apply {
                    putExtra("store_id", store.id)
                    putExtra("store_name", store.name)
                    putExtra("store_logo", store.logoUrl)
                }
                startActivity(intent)
            },
            onToggleFollow = { store ->
                viewModel.toggleFollowStore(store)
            }
        )
        binding.rvFollowedStores.apply {
            adapter = followedStoreAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        // 6. Followed Products Adapter
        followedProductAdapter = ProductAdapter { product, sharedView ->
            val intent = Intent(requireContext(), ProductDetailsActivity::class.java).apply {
                putExtra("product_id", product.id)
                putExtra("product_name", product.displayTitle)
                putExtra("product_image", product.primaryImage)
                putExtra("product_price", product.price)
                putExtra("store_id", product.storeId)
                putExtra("store_name", product.storeName)
                putExtra("seller_pin", product.sellerPin)
                putExtra("seller_id", product.sellerId)
            }
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                requireActivity(),
                sharedView,
                "product_image_${product.id}"
            )
            startActivity(intent, options.toBundle())
        }
        binding.rvFollowedProducts.apply {
            adapter = followedProductAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
        }
    }


    private fun setupListeners() {
        // Tab switching: Marketplace (0), Following (1), Seller Hub (2)
        binding.tabLayoutMain.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position ?: 0) {
                    0 -> {
                        binding.viewFlipper.displayedChild = 0
                        viewModel.loadData(0)
                    }
                    1 -> {
                        binding.viewFlipper.displayedChild = 1
                        viewModel.loadFollowedStores()
                    }
                    2 -> {
                        binding.viewFlipper.displayedChild = 2
                        viewModel.loadMyStore()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // SwipeRefresh for Following tab
        binding.swipeRefreshFollowing.setOnRefreshListener {
            viewModel.loadFollowedStores()
        }

        // Explore button in empty state
        binding.btnExploreStores.setOnClickListener {
            binding.tabLayoutMain.getTabAt(0)?.select()
        }

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

        binding.chipNearby.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkLocationPermissionAndToggleNearby()
            } else {
                viewModel.setNearbyOnly(false)
            }
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
        binding.cardHubStore.setOnClickListener {
            openMyStorefront()
        }

        binding.btnEditStore.setOnClickListener {
            showEditStoreBottomSheet()
        }

        binding.btnViewInsights.setOnClickListener {
            startActivity(Intent(requireContext(), SellerInsightsActivity::class.java))
        }

        binding.cardPayoutAccount.setOnClickListener {
            startActivity(Intent(requireContext(), PayoutAccountActivity::class.java))
        }

        binding.cardManageCoupons.setOnClickListener {
            startActivity(Intent(requireContext(), CouponManagerActivity::class.java))
        }

        binding.cardPayoutLedger.setOnClickListener {
            startActivity(Intent(requireContext(), PayoutLedgerActivity::class.java))
        }

        binding.cardDisputes.setOnClickListener {
            startActivity(Intent(requireContext(), DisputesActivity::class.java))
        }

        binding.cardMetricRevenue.setOnClickListener {
            val intent = Intent(requireContext(), OrdersActivity::class.java).apply {
                putExtra("IS_SELLER_VIEW", true)
            }
            startActivity(intent)
        }

        binding.cardMetricOrders.setOnClickListener {
            val intent = Intent(requireContext(), OrdersActivity::class.java).apply {
                putExtra("IS_SELLER_VIEW", true)
            }
            startActivity(intent)
        }

        binding.cardMetricProducts.setOnClickListener {
            startActivity(Intent(requireContext(), MyProductsActivity::class.java))
        }

        binding.cardMetricFollowers.setOnClickListener {
            startActivity(Intent(requireContext(), SellerInsightsActivity::class.java))
        }

        binding.layoutSelectStoreLogo.setOnClickListener {
            pickStoreLogoLauncher.launch("image/*")
        }

        binding.btnRemoveStoreLogo.setOnClickListener {
            resetStoreLogoPreview()
        }

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

        if (name.isBlank()) {
            binding.etStoreName.error = "Store name is required"
            return
        }
        if (location.isBlank()) {
            binding.etStoreLocation.error = "Location is required"
            return
        }

        binding.btnCreateStore.isEnabled = false
        binding.btnCreateStore.text = "Creating store..."

        lifecycleScope.launch {
            var uploadedLogoUrl: String? = null
            val logoUri = selectedStoreLogoUri
            if (logoUri != null) {
                withContext(Dispatchers.IO) {
                    val compressed = ImageCompressor.compressImageUri(
                        requireContext(),
                        logoUri,
                        maxDimension = 512,
                        quality = 80
                    )
                    if (compressed != null) {
                        uploadedLogoUrl = viewModel.uploadMedia(
                            bytes = compressed.bytes,
                            mimeType = compressed.mimeType,
                            fileName = "store_logo_${System.currentTimeMillis()}.jpg"
                        )
                        if (uploadedLogoUrl.isNullOrBlank()) {
                            uploadedLogoUrl = compressed.dataUri
                        }
                    }
                }
            }

            viewModel.createStore(
                name = name,
                category = category,
                location = location,
                phone = phone,
                description = description,
                logoUrl = uploadedLogoUrl,
                onSuccess = {
                    val b = _binding ?: return@createStore
                    b.btnCreateStore.isEnabled = true
                    b.btnCreateStore.text = "Create Business Store"
                    resetStoreLogoPreview()
                    Toast.makeText(requireContext(), "🎉 Store \"$name\" is now live on GoChat!", Toast.LENGTH_LONG).show()
                    // Required step: set up the payout account so the seller can be paid.
                    startActivity(
                        Intent(requireContext(), PayoutAccountActivity::class.java)
                            .putExtra(PayoutAccountActivity.EXTRA_REQUIRED, true)
                    )
                },
                onError = { err ->
                    val b = _binding ?: return@createStore
                    b.btnCreateStore.isEnabled = true
                    b.btnCreateStore.text = "Create Business Store"
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun showAddProductBottomSheet() {
        productEditor.showAdd()
    }

    private fun openMyStorefront() {
        val store = viewModel.myStore.value ?: return
        val intent = Intent(requireContext(), StorefrontActivity::class.java).apply {
            putExtra("store_id", store.id)
            putExtra("store_name", store.name)
            putExtra("store_logo", store.logoUrl)
            putExtra("store_banner", store.bannerUrl)
            putExtra("store_description", store.description)
            putExtra("store_category", store.category)
            putExtra("store_address", store.address)
            putExtra("store_pin", store.ownerPin)
        }
        startActivity(intent)
    }

    private fun showEditStoreBottomSheet() {
        val store = viewModel.myStore.value ?: return
        val dialog = BottomSheetDialog(requireContext())
        val dialogBinding = DialogEditStoreBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.etEditStoreName.setText(store.name)
        dialogBinding.etEditStoreCategory.setText(store.category)
        dialogBinding.etEditStorePhone.setText(store.phone)
        dialogBinding.etEditStoreLocation.setText(store.address)
        dialogBinding.etEditStoreDescription.setText(store.description)
        if (store.deliveryFee > 0) {
            dialogBinding.etEditStoreDeliveryFee.setText(store.deliveryFee.toString())
        }

        var newLogoUri: Uri? = null
        var currentLogoUrl = store.logoUrl

        if (!currentLogoUrl.isNullOrBlank()) {
            MediaImageHelper.loadSafeImage(dialogBinding.ivEditStoreLogoPreview, currentLogoUrl, cornerRadiusDp = 12f)
            dialogBinding.btnRemoveEditStoreLogo.visibility = View.VISIBLE
            dialogBinding.tvEditStoreLogoSubtitle.text = "Tap to change logo from phone"
        }

        dialogBinding.layoutSelectEditStoreLogo.setOnClickListener {
            onSingleImagePicked = { uri ->
                if (uri != null) {
                    newLogoUri = uri
                    MediaImageHelper.loadSafeImage(dialogBinding.ivEditStoreLogoPreview, uri.toString(), cornerRadiusDp = 12f)
                    dialogBinding.btnRemoveEditStoreLogo.visibility = View.VISIBLE
                    dialogBinding.tvEditStoreLogoSubtitle.text = "New logo selected from phone"
                }
            }
            pickSingleImageLauncher.launch("image/*")
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
                            requireContext(),
                            newLogoUri!!,
                            maxDimension = 512,
                            quality = 80
                        )
                        if (compressed != null) {
                            val uploaded = viewModel.uploadMedia(compressed.bytes, compressed.mimeType, "store_logo_${System.currentTimeMillis()}.jpg")
                            finalLogoUrl = if (!uploaded.isNullOrBlank()) uploaded else compressed.dataUri
                        }
                    }
                }

                val deliveryFee = dialogBinding.etEditStoreDeliveryFee.text.toString().trim().toDoubleOrNull() ?: 0.0
                viewModel.updateStore(
                    name = name,
                    category = category,
                    location = location,
                    phone = phone,
                    description = desc,
                    logoUrl = finalLogoUrl,
                    deliveryFee = deliveryFee,
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Store profile updated!", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        dialogBinding.btnSaveStoreChanges.isEnabled = true
                        dialogBinding.btnSaveStoreChanges.text = "Save Store Changes"
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        dialog.show()
    }

    private fun showEditProductBottomSheet(product: Product) {
        productEditor.showEdit(product)
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
                // 1. Explore Products (Paged)
                launch {
                    viewModel.pagedProducts.collectLatest { pagingData ->
                        productAdapter.submitData(pagingData)
                    }
                }

                // Search Suggestions
                launch {
                    viewModel.searchSuggestions.collect { suggestions ->
                        searchSuggestionAdapter.submitList(suggestions)
                        binding.rvSearchSuggestions.visibility = if (suggestions.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }


                // Real-time refresh trigger
                launch {
                    viewModel.refreshEvent.collect {
                        productAdapter.refresh()
                    }
                }

                // Load State for Explore Products
                launch {
                    productAdapter.loadStateFlow.collectLatest { loadStates ->
                        binding.swipeRefresh.isRefreshing = loadStates.refresh is LoadState.Loading
                        val isListEmpty = loadStates.refresh is LoadState.NotLoading && productAdapter.itemCount == 0
                        binding.layoutEmptyExplore.visibility = if (isListEmpty) View.VISIBLE else View.GONE
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

                // 4b. Payout account display
                launch {
                    viewModel.payoutAccount.collect { payout ->
                        val b = _binding ?: return@collect
                        if (payout?.exists == true) {
                            val bank = if (payout.bankName.isNotBlank()) " • ${payout.bankName}" else ""
                            b.tvPayoutAccount.text = "${payout.accountName} • ${payout.accountNumber}$bank"
                            b.tvPayoutAction.text = "CHANGE"
                        } else {
                            b.tvPayoutAccount.text = "Not set — tap to add"
                            b.tvPayoutAction.text = "SET UP"
                        }
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
                        val revenue = orders.sumOf { if (it.grandTotal > 0.0) it.grandTotal else it.totalAmount }
                        binding.tvMetricRevenue.text = CurrencyManager.formatPrice(revenue)
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

                // 9. Followed Stores
                launch {
                    viewModel.followedStores.collect { stores ->
                        if (stores.isEmpty()) {
                            binding.layoutNoFollowedStores.visibility = View.VISIBLE
                            binding.layoutFollowingContent.visibility = View.GONE
                            binding.tvFollowedCount.visibility = View.GONE
                        } else {
                            binding.layoutNoFollowedStores.visibility = View.GONE
                            binding.layoutFollowingContent.visibility = View.VISIBLE
                            binding.tvFollowedCount.visibility = View.VISIBLE
                            binding.tvFollowedCount.text = stores.size.toString()
                            binding.tvFollowedStoresHeader.text = "Stores You Follow (${stores.size})"
                            followedStoreAdapter.submitList(stores.map { FollowedStoreAdapter.FollowedStoreItem(it, isFollowing = true) })
                        }
                    }
                }

                // 10. Followed Store Products
                launch {
                    viewModel.followedProducts.collect { products ->
                        if (products.isEmpty()) {
                            binding.layoutFollowedProductsHeader.visibility = View.GONE
                            binding.rvFollowedProducts.visibility = View.GONE
                        } else {
                            binding.layoutFollowedProductsHeader.visibility = View.VISIBLE
                            binding.rvFollowedProducts.visibility = View.VISIBLE
                            followedProductAdapter.submitData(lifecycle, PagingData.from(products))
                        }
                    }
                }

                // 11. Following Loading State
                launch {
                    viewModel.isFollowingLoading.collect { loading ->
                        binding.swipeRefreshFollowing.isRefreshing = loading
                    }
                }
            }
        }
    }

    private fun checkLocationPermissionAndToggleNearby() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.setNearbyOnly(true)
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setNearbyOnly(true)
        } else {
            binding.chipNearby.isChecked = false
            Toast.makeText(requireContext(), "Location permission denied. Nearby feature unavailable.", Toast.LENGTH_SHORT).show()
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

            // Total Followers in Store Dashboard
            binding.tvMetricViews.text = store.followersCount.toString()

            // Update store tab title
            binding.tabLayoutMain.getTabAt(2)?.text = store.name.take(12)

            // Surface the seller's payout account on the dashboard.
            viewModel.refreshPayoutAccount()
        } else {
            binding.layoutNoStore.visibility = View.VISIBLE
            binding.layoutHasStore.visibility = View.GONE
            binding.tvMetricViews.text = "0"
            binding.tabLayoutMain.getTabAt(2)?.text = getString(R.string.tab_open_store)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            viewModel.loadData()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
