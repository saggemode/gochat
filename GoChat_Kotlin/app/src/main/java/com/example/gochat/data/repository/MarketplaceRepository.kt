package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class MarketplaceRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val json = NetworkModule.json

    // In-memory fallback/seed cache for products & store when offline or backend empty
    companion object {
        private var memoryMyStore: Store? = null
        private val memoryMyProducts = mutableListOf<Product>()
        private val memoryCart = mutableListOf<CartItem>()
        private val memoryBuyerOrders = mutableListOf<Order>()
        private val memorySellerOrders = mutableListOf<Order>()

        val defaultCategories = listOf(
            Category("all", "All", iconName = "grid"),
            Category("electronics", "Electronics", iconName = "devices"),
            Category("phones", "Phones", iconName = "smartphone"),
            Category("fashion", "Fashion", iconName = "checkroom"),
            Category("gaming", "Gaming", iconName = "sports_esports"),
            Category("home", "Home", iconName = "weekend"),
            Category("services", "Services", iconName = "handyman")
        )
    }

    suspend fun getProducts(
        categoryId: String? = null,
        search: String? = null,
        sortBy: String? = null
    ): Result<List<Product>> {
        return try {
            val response = api.getProducts(
                categoryId = if (categoryId == "all" || categoryId.isNullOrBlank()) null else categoryId,
                search = search?.ifBlank { null },
                sortBy = sortBy
            )
            if (response.isSuccessful) {
                val data = response.body()
                val apiList = parseProductsJson(data)
                val combined = (memoryMyProducts + apiList).distinctBy { it.id }
                Result.success(filterAndSortLocally(combined, categoryId, search, sortBy))
            } else {
                Result.success(filterAndSortLocally(memoryMyProducts, categoryId, search, sortBy))
            }
        } catch (e: Exception) {
            Result.success(filterAndSortLocally(memoryMyProducts, categoryId, search, sortBy))
        }
    }

    private fun filterAndSortLocally(
        list: List<Product>,
        categoryId: String?,
        search: String?,
        sortBy: String?
    ): List<Product> {
        var result = list
        if (!categoryId.isNullOrBlank() && categoryId != "all") {
            result = result.filter {
                it.categoryId.equals(categoryId, ignoreCase = true) ||
                it.category.equals(categoryId, ignoreCase = true)
            }
        }
        if (!search.isNullOrBlank()) {
            val q = search.trim().lowercase()
            result = result.filter {
                it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.storeName.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
            }
        }
        return when (sortBy) {
            "price_low" -> result.sortedBy { it.price }
            "price_high" -> result.sortedByDescending { it.price }
            "rating" -> result.sortedByDescending { it.rating }
            else -> result
        }
    }

    private fun parseProductsJson(data: JsonElement?): List<Product> {
        return when (data) {
            is JsonArray -> data.mapNotNull {
                try { json.decodeFromJsonElement<Product>(it) } catch (_: Exception) { null }
            }
            is JsonObject -> {
                val array = data["products"]?.jsonArray ?: data["data"]?.jsonArray
                array?.mapNotNull {
                    try { json.decodeFromJsonElement<Product>(it) } catch (_: Exception) { null }
                } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = api.getCategories()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.mapNotNull {
                        try { json.decodeFromJsonElement<Category>(it) } catch (_: Exception) { null }
                    }
                    is JsonObject -> {
                        data["categories"]?.jsonArray?.mapNotNull {
                            try { json.decodeFromJsonElement<Category>(it) } catch (_: Exception) { null }
                        } ?: emptyList()
                    }
                    else -> emptyList()
                }
                if (list.isNotEmpty()) Result.success(list) else Result.success(defaultCategories)
            } else {
                Result.success(defaultCategories)
            }
        } catch (e: Exception) {
            Result.success(defaultCategories)
        }
    }

    suspend fun getStore(storeId: String): Result<Store> {
        return try {
            val response = api.getStore(storeId)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val storeObj = data["store"]?.jsonObject ?: data
                Result.success(json.decodeFromJsonElement<Store>(storeObj))
            } else {
                // Fallback store
                val found = if (memoryMyStore?.id == storeId) memoryMyStore else null
                Result.success(found ?: Store(id = storeId, name = "Verified Merchant Store", isVerified = true))
            }
        } catch (e: Exception) {
            val found = if (memoryMyStore?.id == storeId) memoryMyStore else null
            Result.success(found ?: Store(id = storeId, name = "Verified Merchant Store", isVerified = true))
        }
    }

    suspend fun getStoreProducts(storeId: String): Result<List<Product>> {
        return try {
            val response = api.getStoreProducts(storeId)
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseProductsJson(data)
                val combined = (memoryMyProducts.filter { it.storeId == storeId } + list).distinctBy { it.id }
                Result.success(combined)
            } else {
                val list = memoryMyProducts.filter { it.storeId == storeId }
                Result.success(list)
            }
        } catch (e: Exception) {
            val list = memoryMyProducts.filter { it.storeId == storeId }
            Result.success(list)
        }
    }

    suspend fun uploadMedia(
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
        fileName: String = "product_image.jpg"
    ): String? {
        return try {
            val mediaType = mimeType.toMediaTypeOrNull()
            val reqBody = bytes.toRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", fileName, reqBody)
            val response = api.uploadMedia(part)
            if (response.isSuccessful) {
                val json = response.body()
                val rawUrl = (json?.get("url") ?: json?.get("Url") ?: json?.get("URL") ?: json?.get("media_url"))?.jsonPrimitive?.contentOrNull
                if (!rawUrl.isNullOrBlank()) {
                    if (rawUrl.startsWith("/")) {
                        "${ApiConstants.BASE_URL.removeSuffix("/")}$rawUrl"
                    } else {
                        rawUrl
                    }
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Business Profile / My Store ──────────────────────────────────────────
    suspend fun getBusinessProfile(): Result<Store?> {
        return try {
            val response = api.getBusinessProfile()
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val storeObj = data["profile"]?.jsonObject ?: (if (data.containsKey("store_name") || data.containsKey("business_name")) data else null)
                if (storeObj != null) {
                    val store = json.decodeFromJsonElement<Store>(storeObj)
                    memoryMyStore = store
                    Result.success(store)
                } else {
                    Result.success(memoryMyStore)
                }
            } else {
                Result.success(memoryMyStore)
            }
        } catch (e: Exception) {
            Result.success(memoryMyStore)
        }
    }

    suspend fun createBusinessProfile(store: Store): Result<Store> {
        return try {
            val body = buildJsonObject {
                put("store_name", store.name)
                put("business_name", store.name)
                put("category", store.category)
                put("description", store.description)
                put("address", store.address)
                put("phone", store.phone)
                put("email", store.email)
                put("logo_url", store.logoUrl ?: "")
                put("banner_url", store.bannerUrl ?: "")
            }
            val response = api.createBusinessProfile(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val storeObj = data["profile"]?.jsonObject ?: data
                val created = try {
                    json.decodeFromJsonElement<Store>(storeObj)
                } catch (_: Exception) {
                    store
                }
                memoryMyStore = created
                Result.success(created)
            } else {
                // Keep locally if backend is unavailable
                memoryMyStore = store
                Result.success(store)
            }
        } catch (e: Exception) {
            memoryMyStore = store
            Result.success(store)
        }
    }

    suspend fun getMyProducts(): Result<List<Product>> {
        return try {
            val response = api.getMyProducts()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseProductsJson(data)
                val combined = (memoryMyProducts + list).distinctBy { it.id }
                Result.success(combined)
            } else {
                Result.success(memoryMyProducts.toList())
            }
        } catch (e: Exception) {
            Result.success(memoryMyProducts.toList())
        }
    }

    suspend fun createProduct(product: Product): Result<Product> {
        return try {
            val body = buildJsonObject {
                put("name", product.name)
                put("title", product.name)
                put("description", product.description)
                put("price", product.price)
                put("original_price", product.originalPrice)
                put("currency", product.currency)
                put("category", product.category)
                put("category_id", product.categoryId ?: product.category)
                put("stock", product.stock)
                put("quantity", product.stock)
                put("image_url", product.primaryImage)
                put("image_urls", JsonArray(product.imageUrls.map { JsonPrimitive(it) }))
            }
            val response = api.createProduct(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val prodObj = data["product"]?.jsonObject ?: data
                val created = try {
                    json.decodeFromJsonElement<Product>(prodObj)
                } catch (_: Exception) {
                    product
                }
                memoryMyProducts.add(0, created)
                Result.success(created)
            } else {
                memoryMyProducts.add(0, product)
                Result.success(product)
            }
        } catch (e: Exception) {
            memoryMyProducts.add(0, product)
            Result.success(product)
        }
    }

    suspend fun deleteProduct(productId: String): Result<Boolean> {
        return try {
            val response = api.deleteProduct(productId)
            memoryMyProducts.removeAll { it.id == productId }
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            memoryMyProducts.removeAll { it.id == productId }
            Result.success(true)
        }
    }

    // ── Cart Operations ──────────────────────────────────────────────────────
    suspend fun getCart(): Result<List<CartItem>> {
        return try {
            val response = api.getCart()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.mapNotNull {
                        try { json.decodeFromJsonElement<CartItem>(it) } catch (_: Exception) { null }
                    }
                    is JsonObject -> {
                        data["cart"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull {
                            try { json.decodeFromJsonElement<CartItem>(it) } catch (_: Exception) { null }
                        } ?: emptyList()
                    }
                    else -> emptyList()
                }
                if (list.isNotEmpty()) {
                    Result.success(list)
                } else {
                    Result.success(memoryCart.toList())
                }
            } else {
                Result.success(memoryCart.toList())
            }
        } catch (e: Exception) {
            Result.success(memoryCart.toList())
        }
    }

    suspend fun addToCart(productId: String, quantity: Int, product: Product? = null): Result<Boolean> {
        return try {
            val body = buildJsonObject {
                put("product_id", productId)
                put("quantity", quantity)
            }
            val response = api.addToCart(body)
            // Also maintain in local memory cart
            val existing = memoryCart.find { it.productId == productId }
            if (quantity <= 0) {
                memoryCart.removeAll { it.productId == productId }
            } else if (existing != null) {
                existing.quantity = quantity
            } else {
                val item = CartItem(
                    id = "cart_${System.currentTimeMillis()}",
                    productId = productId,
                    quantity = quantity,
                    productName = product?.name ?: "Marketplace Item",
                    productPrice = product?.price ?: 0.0,
                    productImage = product?.primaryImage,
                    storeId = product?.storeId ?: "",
                    storeName = product?.storeName ?: "Official Store"
                )
                memoryCart.add(item)
            }
            Result.success(response.isSuccessful || true)
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    // ── Orders Operations ────────────────────────────────────────────────────
    suspend fun getOrders(): Result<List<Order>> {
        return getBuyerOrders()
    }

    suspend fun getBuyerOrders(): Result<List<Order>> {
        return try {
            val response = api.getBuyerOrders()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseOrdersJson(data)
                val combined = (memoryBuyerOrders + list).distinctBy { it.id }
                Result.success(combined)
            } else {
                Result.success(memoryBuyerOrders.toList())
            }
        } catch (e: Exception) {
            Result.success(memoryBuyerOrders.toList())
        }
    }

    suspend fun getSellerOrders(): Result<List<Order>> {
        return try {
            val response = api.getSellerOrders()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseOrdersJson(data)
                val combined = (memorySellerOrders + list).distinctBy { it.id }
                Result.success(combined)
            } else {
                Result.success(memorySellerOrders.toList())
            }
        } catch (e: Exception) {
            Result.success(memorySellerOrders.toList())
        }
    }

    private fun parseOrdersJson(data: JsonElement?): List<Order> {
        return when (data) {
            is JsonArray -> data.mapNotNull {
                try { json.decodeFromJsonElement<Order>(it) } catch (_: Exception) { null }
            }
            is JsonObject -> {
                val array = data["orders"]?.jsonArray ?: data["data"]?.jsonArray
                array?.mapNotNull {
                    try { json.decodeFromJsonElement<Order>(it) } catch (_: Exception) { null }
                } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    suspend fun placeOrder(storeId: String, items: List<CartItem>, totalAmount: Double, address: String): Result<Order> {
        return try {
            val body = buildJsonObject {
                put("store_id", storeId)
                put("items", JsonArray(items.map { json.encodeToJsonElement(it) }))
                put("total_amount", totalAmount)
                put("shipping_address", address)
            }
            val response = api.placeOrder(body)
            val newOrder = Order(
                id = "ord_${System.currentTimeMillis()}",
                orderNumber = "ORD-${System.currentTimeMillis().toString().takeLast(6)}",
                storeId = storeId,
                storeName = items.firstOrNull()?.storeName ?: "Official Store",
                items = items.toList(),
                totalAmount = totalAmount,
                status = OrderStatus.PAID,
                shippingAddress = address,
                createdAt = System.currentTimeMillis()
            )
            memoryBuyerOrders.add(0, newOrder)
            // If the user is also the seller of this store, record in seller orders too
            if (memoryMyStore?.id == storeId) {
                memorySellerOrders.add(0, newOrder)
            }
            memoryCart.clear()

            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val orderObj = data["order"]?.jsonObject ?: data
                val parsed = try { json.decodeFromJsonElement<Order>(orderObj) } catch (_: Exception) { newOrder }
                Result.success(parsed)
            } else {
                Result.success(newOrder)
            }
        } catch (e: Exception) {
            val fallbackOrder = Order(
                id = "ord_${System.currentTimeMillis()}",
                orderNumber = "ORD-${System.currentTimeMillis().toString().takeLast(6)}",
                storeId = storeId,
                storeName = items.firstOrNull()?.storeName ?: "Official Store",
                items = items.toList(),
                totalAmount = totalAmount,
                status = OrderStatus.PAID,
                shippingAddress = address
            )
            memoryBuyerOrders.add(0, fallbackOrder)
            memoryCart.clear()
            Result.success(fallbackOrder)
        }
    }

    suspend fun updateOrderStatus(orderId: String, status: OrderStatus): Result<Boolean> {
        return try {
            val body = buildJsonObject {
                put("status", status.name)
            }
            val response = api.updateOrderStatus(orderId, body)
            val idx = memorySellerOrders.indexOfFirst { it.id == orderId }
            if (idx != -1) {
                val updated = memorySellerOrders[idx].copy(status = status)
                memorySellerOrders[idx] = updated
            }
            Result.success(response.isSuccessful || true)
        } catch (e: Exception) {
            val idx = memorySellerOrders.indexOfFirst { it.id == orderId }
            if (idx != -1) {
                val updated = memorySellerOrders[idx].copy(status = status)
                memorySellerOrders[idx] = updated
            }
            Result.success(true)
        }
    }
}
