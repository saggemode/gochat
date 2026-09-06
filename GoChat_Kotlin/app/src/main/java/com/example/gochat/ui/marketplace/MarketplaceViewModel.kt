package com.example.gochat.ui.marketplace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.Category
import com.example.gochat.data.model.Order
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.Store
import com.example.gochat.data.repository.MarketplaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class MarketplaceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MarketplaceRepository(application)
    private val webSocket = NetworkModule.getWebSocket(application)
    private val json = NetworkModule.json

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
        observeWebSocketEvents()
        loadData()
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocket.events.collect { event ->
                val type = event["type"]?.jsonPrimitive?.contentOrNull
                if (type == "new_product") {
                    val prodElement = event["product"]
                    if (prodElement != null) {
                        try {
                            val newProd = json.decodeFromJsonElement<Product>(prodElement)
                            // Real-time instant delivery: prepend to feed like chat!
                            _products.value = listOf(newProd) + _products.value.filter { it.id != newProd.id }
                        } catch (_: Exception) {
                            loadExploreProducts()
                        }
                    }
                }
            }
        }
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
        logoUrl: String?,
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
                logoUrl = logoUrl?.ifBlank { null },
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

    suspend fun uploadMedia(bytes: ByteArray, mimeType: String = "image/jpeg", fileName: String = "upload.jpg"): String? {
        return repository.uploadMedia(bytes, mimeType, fileName)
    }

    fun addProduct(
        name: String,
        price: Double,
        originalPrice: Double,
        category: String,
        stock: Int,
        imageUrls: List<String>,
        description: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentStore = _myStore.value
            val primaryImage = imageUrls.firstOrNull().orEmpty()
            val newProduct = Product(
                id = "prod_${System.currentTimeMillis()}",
                name = name,
                description = description,
                price = price,
                originalPrice = if (originalPrice > price) originalPrice else 0.0,
                category = category.ifBlank { "Electronics" },
                categoryId = category.lowercase(),
                stock = stock,
                imageUrls = imageUrls,
                imageUrl = primaryImage,
                storeId = currentStore?.id ?: "my_store",
                storeName = currentStore?.name ?: "My Store",
                isVerifiedSeller = true
            )

            // OPTIMISTIC UPDATE: appears immediately on device just like a sent chat message!
            _products.value = listOf(newProduct) + _products.value.filter { it.id != newProduct.id }
            _myProducts.value = listOf(newProduct) + _myProducts.value.filter { it.id != newProduct.id }

            val result = repository.createProduct(newProduct)
            _isLoading.value = false
            if (result.isSuccess) {
                val created = result.getOrNull() ?: newProduct
                _products.value = listOf(created) + _products.value.filter { it.id != created.id && it.id != newProduct.id }
                _myProducts.value = listOf(created) + _myProducts.value.filter { it.id != created.id && it.id != newProduct.id }
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to add product")
            }
        }
    }

    fun updateStore(
        name: String,
        category: String,
        location: String,
        phone: String,
        description: String,
        logoUrl: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val existing = _myStore.value
            val updatedStore = (existing ?: Store(id = "store_${System.currentTimeMillis()}", name = name)).copy(
                name = name,
                category = category.ifBlank { "General Retail" },
                address = location,
                phone = phone,
                description = description,
                logoUrl = logoUrl?.ifBlank { existing?.logoUrl },
                isVerified = true
            )
            val result = repository.updateBusinessProfile(updatedStore)
            _isLoading.value = false
            if (result.isSuccess) {
                _myStore.value = result.getOrThrow()
                loadMyStore()
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to update store")
            }
        }
    }

    fun updateProduct(
        product: Product,
        name: String,
        price: Double,
        originalPrice: Double,
        category: String,
        stock: Int,
        imageUrls: List<String>,
        description: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val primaryImage = imageUrls.firstOrNull() ?: product.primaryImage
            val updated = product.copy(
                name = name,
                description = description,
                price = price,
                originalPrice = if (originalPrice > price) originalPrice else 0.0,
                category = category.ifBlank { product.category },
                categoryId = category.lowercase(),
                stock = stock,
                imageUrls = if (imageUrls.isNotEmpty()) imageUrls else product.imageUrls,
                imageUrl = primaryImage
            )

            _products.value = _products.value.map { if (it.id == product.id) updated else it }
            _myProducts.value = _myProducts.value.map { if (it.id == product.id) updated else it }

            val result = repository.updateProduct(updated)
            _isLoading.value = false
            if (result.isSuccess) {
                val finalProd = result.getOrNull() ?: updated
                _products.value = _products.value.map { if (it.id == product.id) finalProd else it }
                _myProducts.value = _myProducts.value.map { if (it.id == product.id) finalProd else it }
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to update product")
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
