package com.example.gochat.ui.marketplace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.model.Category
import com.example.gochat.data.model.Order
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MarketplaceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MarketplaceRepository(application)

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _cartCount = MutableStateFlow(0)
    val cartCount: StateFlow<Int> = _cartCount.asStateFlow()

    private val _myStore = MutableStateFlow<Store?>(null)
    val myStore: StateFlow<Store?> = _myStore.asStateFlow()

    private val _myProducts = MutableStateFlow<List<Product>>(emptyList())
    val myProducts: StateFlow<List<Product>> = _myProducts.asStateFlow()

    private val _sellerOrders = MutableStateFlow<List<Order>>(emptyList())
    val sellerOrders: StateFlow<List<Order>> = _sellerOrders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentCategory: String? = null
    private var currentSearch: String? = null
    private var isVerifiedOnly: Boolean = false
    private var currentSortBy: String? = null

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            loadExploreProducts()
            loadCategories()
            loadMyStore()
            refreshCartCount()

            _isLoading.value = false
        }
    }

    private suspend fun loadExploreProducts() {
        val result = repository.getProducts(
            categoryId = currentCategory,
            search = currentSearch,
            sortBy = currentSortBy
        )
        if (result.isSuccess) {
            var list = result.getOrDefault(emptyList())
            if (isVerifiedOnly) {
                list = list.filter { it.isVerifiedSeller }
            }
            _products.value = list
        } else {
            _error.value = result.exceptionOrNull()?.message ?: "Failed to load products"
        }
    }

    private suspend fun loadCategories() {
        val result = repository.getCategories()
        if (result.isSuccess) {
            _categories.value = result.getOrDefault(emptyList())
        }
    }

    fun loadMyStore() {
        viewModelScope.launch {
            val storeRes = repository.getBusinessProfile()
            val store = storeRes.getOrNull()
            _myStore.value = store

            if (store != null) {
                val myProdRes = repository.getMyProducts()
                _myProducts.value = myProdRes.getOrDefault(emptyList())

                val ordersRes = repository.getSellerOrders()
                _sellerOrders.value = ordersRes.getOrDefault(emptyList())
            }
        }
    }

    fun filterByCategory(categoryId: String?) {
        currentCategory = if (categoryId == "all" || categoryId.isNullOrBlank()) null else categoryId
        viewModelScope.launch {
            _isLoading.value = true
            loadExploreProducts()
            _isLoading.value = false
        }
    }

    fun setSearchQuery(query: String?) {
        currentSearch = query?.ifBlank { null }
        viewModelScope.launch {
            loadExploreProducts()
        }
    }

    fun setVerifiedOnly(verified: Boolean) {
        isVerifiedOnly = verified
        viewModelScope.launch {
            loadExploreProducts()
        }
    }

    fun refreshCartCount() {
        viewModelScope.launch {
            val result = repository.getCart()
            val totalQuantity = result.getOrNull()?.sumOf { it.quantity } ?: 0
            _cartCount.value = totalQuantity
        }
    }

    fun createStore(
        name: String,
        category: String,
        location: String,
        phone: String,
        description: String,
        logoUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val newStore = Store(
                id = "store_${System.currentTimeMillis()}",
                name = name,
                category = category.ifBlank { "General Retail" },
                address = location,
                phone = phone,
                description = description,
                logoUrl = logoUrl.ifBlank { null },
                isVerified = true
            )
            val result = repository.createBusinessProfile(newStore)
            _isLoading.value = false
            if (result.isSuccess) {
                _myStore.value = result.getOrThrow()
                loadMyStore()
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to create store")
            }
        }
    }

    fun addProduct(
        name: String,
        price: Double,
        originalPrice: Double,
        category: String,
        stock: Int,
        imageUrl: String,
        description: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentStore = _myStore.value
            val newProduct = Product(
                id = "prod_${System.currentTimeMillis()}",
                name = name,
                description = description,
                price = price,
                originalPrice = if (originalPrice > price) originalPrice else 0.0,
                category = category.ifBlank { "Electronics" },
                categoryId = category.lowercase(),
                stock = stock,
                imageUrls = if (imageUrl.isNotBlank()) listOf(imageUrl) else emptyList(),
                imageUrl = imageUrl,
                storeId = currentStore?.id ?: "my_store",
                storeName = currentStore?.name ?: "My Store",
                isVerifiedSeller = true
            )
            val result = repository.createProduct(newProduct)
            _isLoading.value = false
            if (result.isSuccess) {
                loadMyStore()
                loadExploreProducts()
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to add product")
            }
        }
    }

    fun deleteProduct(productId: String) {
        viewModelScope.launch {
            repository.deleteProduct(productId)
            loadMyStore()
            loadExploreProducts()
        }
    }
}
