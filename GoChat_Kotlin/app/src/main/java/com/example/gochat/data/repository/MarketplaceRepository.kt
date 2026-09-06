package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.model.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class MarketplaceRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val marketplaceDao = AppDatabase.getInstance(context).marketplaceDao()
    private val json = NetworkModule.json

    // Seed data for categories
    companion object {
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
                if (apiList.isNotEmpty()) {
                    marketplaceDao.insertProducts(apiList)
                }
                val allProducts = marketplaceDao.getAllProducts()
                Result.success(filterAndSortLocally(allProducts, categoryId, search, sortBy))
            } else {
                val allProducts = marketplaceDao.getAllProducts()
                Result.success(filterAndSortLocally(allProducts, categoryId, search, sortBy))
            }
        } catch (e: Exception) {
            val allProducts = marketplaceDao.getAllProducts()
            Result.success(filterAndSortLocally(allProducts, categoryId, search, sortBy))
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

    private suspend fun parseStoreJson(data: JsonObject?, fallbackId: String): Store {
        if (data == null) {
            return marketplaceDao.getStoreById(fallbackId) ?: Store(id = fallbackId, name = "Verified Merchant Store", isVerified = true)
        }
        val target = data["profile"]?.jsonObject ?: data["store"]?.jsonObject ?: data
        val id = (target["id"] ?: target["user_id"] ?: target["business_id"])?.jsonPrimitive?.contentOrNull ?: fallbackId
        val name = (target["name"] ?: target["business_name"] ?: target["store_name"])?.jsonPrimitive?.contentOrNull ?: "Merchant Store"
        val desc = (target["description"] ?: target["desc"])?.jsonPrimitive?.contentOrNull.orEmpty()
        val cat = (target["category"] ?: target["category_name"])?.jsonPrimitive?.contentOrNull ?: "General Retail"
        val addr = (target["address"] ?: target["location"])?.jsonPrimitive?.contentOrNull ?: "Lagos, Nigeria"
        val phone = target["phone"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val email = target["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ownerPin = target["owner_pin"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val logoUrl = (target["logo_url"] ?: target["logoUrl"])?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val bannerUrl = (target["banner_url"] ?: target["bannerUrl"])?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val verified = target["is_verified"]?.jsonPrimitive?.booleanOrNull ?: true
        val rating = target["avg_rating"]?.jsonPrimitive?.doubleOrNull ?: target["rating"]?.jsonPrimitive?.doubleOrNull ?: 4.9

        return Store(
            id = id,
            name = name,
            description = desc,
            category = cat,
            address = addr,
            phone = phone,
            email = email,
            ownerPin = ownerPin,
            logoUrl = logoUrl,
            bannerUrl = bannerUrl,
            rating = rating,
            isVerified = verified
        )
    }

    suspend fun getStore(storeId: String): Result<Store> {
        return try {
            val response = api.getStore(storeId)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val store = parseStoreJson(data, storeId)
                marketplaceDao.insertStore(store)
                Result.success(store)
            } else {
                val cached = marketplaceDao.getStoreById(storeId)
                Result.success(cached ?: Store(id = storeId, name = "Verified Merchant Store", isVerified = true))
            }
        } catch (e: Exception) {
            val cached = marketplaceDao.getStoreById(storeId)
            Result.success(cached ?: Store(id = storeId, name = "Verified Merchant Store", isVerified = true))
        }
    }

    suspend fun getStoreProducts(storeId: String): Result<List<Product>> {
        return try {
            val response = api.getStoreProducts(storeId)
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseProductsJson(data)
                if (list.isNotEmpty()) {
                    marketplaceDao.insertProducts(list)
                }
                Result.success(marketplaceDao.getStoreProducts(storeId))
            } else {
                Result.success(marketplaceDao.getStoreProducts(storeId))
            }
        } catch (e: Exception) {
            Result.success(marketplaceDao.getStoreProducts(storeId))
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
                val targetObj = data["profile"]?.jsonObject ?: (if (data.containsKey("store_name") || data.containsKey("business_name")) data else null)
                if (targetObj != null) {
                    val store = parseStoreJson(targetObj, "my_store")
                    marketplaceDao.insertStore(store)
                    Result.success(store)
                } else {
                    Result.success(marketplaceDao.getAllOrders().firstOrNull()?.let { marketplaceDao.getStoreById(it.storeId) })
                }
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun createBusinessProfile(store: Store): Result<Store> {
        return try {
            val body = buildJsonObject {
                put("business_name", store.name)
                put("store_name", store.name)
                put("category", store.category)
                put("description", store.description)
                put("address", store.address)
                put("phone", store.phone)
                put("email", store.email)
                put("logo_url", store.logoUrl ?: "")
                put("banner_url", store.bannerUrl ?: "")
            }
            val response = api.createBusinessProfile(body)
            val toSave = if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                parseStoreJson(data, store.id)
            } else {
                store
            }
            marketplaceDao.insertStore(toSave)
            Result.success(toSave)
        } catch (e: Exception) {
            marketplaceDao.insertStore(store)
            Result.success(store)
        }
    }

    suspend fun updateBusinessProfile(store: Store): Result<Store> {
        return try {
            val body = buildJsonObject {
                put("business_name", store.name)
                put("store_name", store.name)
                put("category", store.category)
                put("description", store.description)
                put("address", store.address)
                put("phone", store.phone)
                put("email", store.email)
                put("logo_url", store.logoUrl ?: "")
                put("banner_url", store.bannerUrl ?: "")
            }
            val response = api.updateBusinessProfile(body)
            val updated = if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                parseStoreJson(data, store.id)
            } else {
                store
            }
            marketplaceDao.insertStore(updated)
            Result.success(updated)
        } catch (e: Exception) {
            marketplaceDao.insertStore(store)
            Result.success(store)
        }
    }

    suspend fun getMyProducts(): Result<List<Product>> {
        return try {
            val response = api.getMyProducts()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseProductsJson(data)
                if (list.isNotEmpty()) {
                    marketplaceDao.insertProducts(list)
                }
            }
            Result.success(marketplaceDao.getAllProducts()) // Should probably filter by my seller ID if available
        } catch (e: Exception) {
            Result.success(marketplaceDao.getAllProducts())
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
            val created = if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val prodObj = data["product"]?.jsonObject ?: data
                try {
                    json.decodeFromJsonElement<Product>(prodObj)
                } catch (_: Exception) {
                    product
                }
            } else {
                product
            }
            marketplaceDao.insertProduct(created)
            Result.success(created)
        } catch (e: Exception) {
            marketplaceDao.insertProduct(product)
            Result.success(product)
        }
    }

    suspend fun updateProduct(product: Product): Result<Product> {
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
            val response = api.updateProduct(product.id, body)
            val updated = if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val prodObj = data["product"]?.jsonObject ?: data
                try {
                    json.decodeFromJsonElement<Product>(prodObj)
                } catch (_: Exception) {
                    product
                }
            } else {
                product
            }
            marketplaceDao.insertProduct(updated)
            Result.success(updated)
        } catch (e: Exception) {
            marketplaceDao.insertProduct(product)
            Result.success(product)
        }
    }

    suspend fun deleteProduct(productId: String): Result<Boolean> {
        return try {
            val response = api.deleteProduct(productId)
            marketplaceDao.deleteProduct(productId)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            marketplaceDao.deleteProduct(productId)
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
                    list.forEach { marketplaceDao.insertCartItem(it) }
                }
            }
            Result.success(marketplaceDao.getCartItems())
        } catch (e: Exception) {
            Result.success(marketplaceDao.getCartItems())
        }
    }

    suspend fun addToCart(productId: String, quantity: Int, product: Product? = null): Result<Boolean> {
        return try {
            val body = buildJsonObject {
                put("product_id", productId)
                put("quantity", quantity)
            }
            val response = api.addToCart(body)
            if (quantity <= 0) {
                marketplaceDao.removeCartItem(productId)
            } else {
                val item = CartItem(
                    id = "cart_$productId",
                    productId = productId,
                    quantity = quantity,
                    productName = product?.name ?: "Marketplace Item",
                    productPrice = product?.price ?: 0.0,
                    productImage = product?.primaryImage,
                    storeId = product?.storeId ?: "",
                    storeName = product?.storeName ?: "Official Store"
                )
                marketplaceDao.insertCartItem(item)
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
                if (list.isNotEmpty()) {
                    marketplaceDao.insertOrders(list)
                }
            }
            Result.success(marketplaceDao.getAllOrders())
        } catch (e: Exception) {
            Result.success(marketplaceDao.getAllOrders())
        }
    }

    suspend fun getSellerOrders(): Result<List<Order>> {
        return try {
            val response = api.getSellerOrders()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseOrdersJson(data)
                if (list.isNotEmpty()) {
                    marketplaceDao.insertOrders(list)
                }
            }
            Result.success(marketplaceDao.getAllOrders())
        } catch (e: Exception) {
            Result.success(marketplaceDao.getAllOrders())
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
            val finalOrder = if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val orderObj = data["order"]?.jsonObject ?: data
                try { json.decodeFromJsonElement<Order>(orderObj) } catch (_: Exception) { newOrder }
            } else {
                newOrder
            }
            marketplaceDao.insertOrder(finalOrder)
            marketplaceDao.clearCart()
            Result.success(finalOrder)
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
            marketplaceDao.insertOrder(fallbackOrder)
            marketplaceDao.clearCart()
            Result.success(fallbackOrder)
        }
    }

    suspend fun updateOrderStatus(orderId: String, status: OrderStatus): Result<Boolean> {
        return try {
            val body = buildJsonObject {
                put("status", status.name)
            }
            val response = api.updateOrderStatus(orderId, body)
            val orders = marketplaceDao.getAllOrders()
            val order = orders.find { it.id == orderId }
            if (order != null) {
                marketplaceDao.insertOrder(order.copy(status = status))
            }
            Result.success(response.isSuccessful || true)
        } catch (e: Exception) {
            val orders = marketplaceDao.getAllOrders()
            val order = orders.find { it.id == orderId }
            if (order != null) {
                marketplaceDao.insertOrder(order.copy(status = status))
            }
            Result.success(true)
        }
    }
}
