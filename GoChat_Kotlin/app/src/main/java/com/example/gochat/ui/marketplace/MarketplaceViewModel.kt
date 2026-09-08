package com.example.gochat.ui.marketplace

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.*
import com.example.gochat.core.sync.MarketplaceSyncWorker
import com.example.gochat.data.model.*
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.data.websocket.GoChatWebSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    application: Application,
    private val repository: MarketplaceRepository,
    private val webSocket: GoChatWebSocket,
    private val json: Json,
    private val workManager: WorkManager
) : AndroidViewModel(application) {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val filterTrigger = MutableStateFlow(FilterParams())

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedProducts: Flow<PagingData<Product>> = filterTrigger
        .flatMapLatest { params ->
            repository.getProductsPaged(params.cat, params.search, params.sort, params.isNearby, params.isFollowing, params.lat, params.lng)
        }
        .cachedIn(viewModelScope)

    data class FilterParams(
        val cat: String? = null,
        val search: String? = null,
        val sort: String? = null,
        val isNearby: Boolean = false,
        val isFollowing: Boolean = false,
        val lat: Double = 6.46,
        val lng: Double = 3.40
    )



    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()


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

    private val _refreshEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshEvent = _refreshEvent.asSharedFlow()

    private var currentCategory: String? = null
    private var currentSearch: String? = null
    private var isVerifiedOnly: Boolean = false
    private var isNearbyOnly: Boolean = false
    private var currentSortBy: String? = null

    // Mock user location for Demo (Lagos)
    private var userLat: Double = 6.46
    private var userLng: Double = 3.40


    init {
        observeWebSocketEvents()
        loadData()
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocket.events.collect { event ->
                val newProd = repository.handleIncomingWebSocketEvent(event)
                if (newProd != null) {
                    // Prepend to the local memory list for immediate non-paged UI if needed
                    _products.value = listOf(newProd) + _products.value.filter { it.id != newProd.id }
                    
                    // Trigger refresh for the PagingAdapter
                    _refreshEvent.tryEmit(Unit)
                    
                    // If it's my product, update my products list too
                    if (newProd.sellerId == repository.userId) {
                        _myProducts.value = listOf(newProd) + _myProducts.value.filter { it.id != newProd.id }
                    }
                }
            }
        }
    }

    fun loadData(tabIndex: Int = 0) {
        currentTabIndex = tabIndex
        updateFilterTrigger()
        
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            if (tabIndex == 2) {
                loadMyStore()
            }
            
            loadCategories()
            refreshCartCount()

            _isLoading.value = false
        }
    }

    private var currentTabIndex: Int = 0


    private suspend fun loadFollowingProducts() {
        val followedRes = repository.getFollowedStores()
        if (followedRes.isSuccess) {
            val followedStores = followedRes.getOrThrow()
            val allFollowedProducts = mutableListOf<Product>()
            
            followedStores.forEach { store ->
                val prodRes = repository.getStoreProducts(store.id)
                if (prodRes.isSuccess) {
                    allFollowedProducts.addAll(prodRes.getOrThrow())
                }
            }
            
            // Sort by newest
            _products.value = allFollowedProducts.sortedByDescending { it.createdAt }
        } else {
            _error.value = "Failed to load followed stores"
            _products.value = emptyList()
        }
    }


    private suspend fun loadExploreProducts() {
        val result = repository.getProducts(
            categoryId = currentCategory,
            search = currentSearch,
            sortBy = currentSortBy,
            isNearbyOnly = isNearbyOnly,
            userLat = userLat,
            userLng = userLng
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
        updateFilterTrigger()
    }

    fun setSearchQuery(query: String?) {
        currentSearch = query?.ifBlank { null }
        updateFilterTrigger()
        generateSearchSuggestions(currentSearch)
    }

    private fun generateSearchSuggestions(query: String?) {
        if (query.isNullOrBlank() || query.length < 2) {
            _searchSuggestions.value = emptyList()
            return
        }

        val q = query.lowercase()
        val suggestions = mutableListOf<String>()

        // Suggest from categories
        _categories.value.forEach {
            if (it.name.lowercase().contains(q)) {
                suggestions.add(it.name)
            }
        }

        // Suggest from product names (using what's already in memory)
        _products.value.forEach {
            if (it.name.lowercase().contains(q)) {
                suggestions.add(it.name)
            }
        }

        _searchSuggestions.value = suggestions.distinct().take(8)
    }


    private fun updateFilterTrigger() {
        filterTrigger.value = FilterParams(
            cat = currentCategory,
            search = currentSearch,
            sort = currentSortBy,
            isNearby = isNearbyOnly,
            isFollowing = (currentTabIndex == 1),
            lat = userLat,
            lng = userLng
        )
    }



    fun setVerifiedOnly(verified: Boolean) {
        isVerifiedOnly = verified
        updateFilterTrigger()
        viewModelScope.launch {
            loadExploreProducts()
        }
    }

    fun setNearbyOnly(nearby: Boolean) {
        isNearbyOnly = nearby
        updateFilterTrigger()
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
                ownerId = repository.userId ?: "",
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
        variants: List<ProductVariant> = emptyList(),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentStore = _myStore.value

            // 1. Upload any local device image URIs / file paths to get public remote URLs
            val uploadedUrls = mutableListOf<String>()
            for (url in imageUrls) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    uploadedUrls.add(url)
                } else {
                    val bytes = readUriBytes(url)
                    if (bytes != null) {
                        val remote = repository.uploadMedia(bytes, "image/jpeg", "prod_${System.currentTimeMillis()}.jpg")
                        if (!remote.isNullOrBlank()) {
                            uploadedUrls.add(remote)
                        } else {
                            uploadedUrls.add(url)
                        }
                    } else {
                        uploadedUrls.add(url)
                    }
                }
            }

            val primaryImage = uploadedUrls.firstOrNull().orEmpty()
            val newProduct = Product(
                id = "prod_${System.currentTimeMillis()}",
                sellerId = repository.userId ?: "",
                name = name,
                description = description,
                price = price,
                originalPrice = if (originalPrice > price) originalPrice else 0.0,
                category = category.ifBlank { "Electronics" },
                categoryId = category.lowercase(),
                stock = stock,
                imageUrls = uploadedUrls,
                imageUrl = primaryImage,
                storeId = currentStore?.id ?: "my_store",
                storeName = currentStore?.name ?: "My Store",
                isVerifiedSeller = true,
                variants = variants
            )

            // 2. Direct online creation on the backend
            val result = repository.createProduct(newProduct)
            if (result.isSuccess) {
                val created = result.getOrNull() ?: newProduct
                _products.value = listOf(created) + _products.value.filter { it.id != created.id && it.id != newProduct.id }
                _myProducts.value = listOf(created) + _myProducts.value.filter { it.id != created.id && it.id != newProduct.id }
                
                // 3. Broadcast to others via WebSocket so they see it "like chat"
                webSocket.send(buildJsonObject {
                    put("type", JsonPrimitive("new_product"))
                    put("product", json.encodeToJsonElement(created))
                })

                _refreshEvent.tryEmit(Unit)
                _isLoading.value = false
                onSuccess()
            } else {
                // Offline fallback: optimistic insert and queue background sync
                _products.value = listOf(newProduct) + _products.value.filter { it.id != newProduct.id }
                _myProducts.value = listOf(newProduct) + _myProducts.value.filter { it.id != newProduct.id }

                // Optimistic broadcast
                webSocket.send(buildJsonObject {
                    put("type", JsonPrimitive("new_product"))
                    put("product", json.encodeToJsonElement(newProduct))
                })

                try {
                    val workData = workDataOf(
                        MarketplaceSyncWorker.KEY_PRODUCT_JSON to json.encodeToString(newProduct)
                    )
                    val workRequest = OneTimeWorkRequestBuilder<MarketplaceSyncWorker>()
                        .setInputData(workData)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    workManager.enqueueUniqueWork("product_sync_${newProduct.id}", ExistingWorkPolicy.REPLACE, workRequest)
                } catch (_: Exception) {}

                _refreshEvent.tryEmit(Unit)
                _isLoading.value = false
                onSuccess()
            }
        }
    }

    private fun readUriBytes(uriStr: String): ByteArray? {
        return try {
            val clean = uriStr.trim()
            if (clean.startsWith("file://") || !clean.startsWith("content://")) {
                val file = File(clean.removePrefix("file://"))
                if (file.exists()) return file.readBytes()
            }
            val uri = Uri.parse(clean)
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
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
            val updatedStore = (existing ?: Store(
                id = "store_${System.currentTimeMillis()}",
                ownerId = repository.userId ?: "",
                name = name
            )).copy(
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
        variants: List<ProductVariant> = emptyList(),
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
                imageUrl = primaryImage,
                variants = if (variants.isNotEmpty()) variants else product.variants
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
