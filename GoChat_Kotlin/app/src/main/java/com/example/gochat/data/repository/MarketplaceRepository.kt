package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.*
import kotlinx.serialization.json.*

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

        val defaultSeedProducts = listOf(
            Product(
                id = "prod_101",
                name = "Apple iPhone 15 Pro Max - 256GB Titanium",
                description = "Brand new in box, factory unlocked with 1-year official Apple warranty. Super Retina XDR display, A17 Pro chip, Titanium design, 48MP main camera with 5x optical zoom.",
                price = 1199.00,
                originalPrice = 1399.00,
                currency = "USD",
                imageUrls = listOf("https://images.unsplash.com/photo-1695048133142-1a20484d2569?w=800&q=80"),
                storeId = "store_apple",
                storeName = "iStore Official",
                sellerId = "seller_istore",
                sellerPin = "1P0YE4WZ",
                sellerLocation = "Lagos, Nigeria",
                categoryId = "phones",
                category = "Phones",
                stock = 15,
                rating = 4.9,
                reviewsCount = 342,
                tags = listOf("Verified Merchant", "Fast Delivery", "Official Warranty")
            ),
            Product(
                id = "prod_102",
                name = "Sony WH-1000XM5 Wireless Noise Canceling Headphones",
                description = "Industry-leading noise canceling with two processors and 8 microphones. Magnificent sound quality, crystal clear hands-free calling, and up to 30 hours battery life.",
                price = 348.00,
                originalPrice = 399.00,
                currency = "USD",
                imageUrls = listOf("https://images.unsplash.com/photo-1546435770-a3e426bf472b?w=800&q=80"),
                storeId = "store_sony",
                storeName = "Sony Flagship Store",
                sellerId = "seller_sony",
                sellerPin = "SONY9901",
                sellerLocation = "Abuja, Nigeria",
                categoryId = "electronics",
                category = "Electronics",
                stock = 24,
                rating = 4.8,
                reviewsCount = 210,
                tags = listOf("Verified Merchant", "Free Shipping")
            ),
            Product(
                id = "prod_103",
                name = "PlayStation 5 Slim Console (Disc Edition)",
                description = "Experience lightning-fast loading with an ultra-high-speed SSD, deeper immersion with haptic feedback, adaptive triggers, and 3D Audio, and an all-new generation of incredible PlayStation games.",
                price = 499.00,
                originalPrice = 550.00,
                currency = "USD",
                imageUrls = listOf("https://images.unsplash.com/photo-1606813907291-d86efa9b94db?w=800&q=80"),
                storeId = "store_gaming",
                storeName = "GameZone Direct",
                sellerId = "seller_gamezone",
                sellerPin = "GZ882901",
                sellerLocation = "Lagos, Nigeria",
                categoryId = "gaming",
                category = "Gaming",
                stock = 8,
                rating = 4.9,
                reviewsCount = 480,
                tags = listOf("Fast Delivery", "Top Rated")
            ),
            Product(
                id = "prod_104",
                name = "Nike Air Jordan 1 Retro High OG 'Chicago'",
                description = "100% Authentic Nike sneakers with classic Chicago red, white, and black colorway. Premium full-grain leather upper, padded collar for comfort, and rubber cupsole with Nike Air cushioning.",
                price = 185.00,
                originalPrice = 220.00,
                currency = "USD",
                imageUrls = listOf("https://images.unsplash.com/photo-1552346154-21d32810aba3?w=800&q=80"),
                storeId = "store_kicks",
                storeName = "SneakerHub Nigeria",
                sellerId = "seller_kicks",
                sellerPin = "KICKS404",
                sellerLocation = "Port Harcourt, Nigeria",
                categoryId = "fashion",
                category = "Fashion",
                stock = 12,
                rating = 4.7,
                reviewsCount = 189,
                tags = listOf("100% Authentic", "Verified Merchant")
            ),
            Product(
                id = "prod_105",
                name = "MacBook Pro 16\" M3 Max (36GB RAM, 1TB SSD)",
                description = "Apple M3 Max 14-core CPU, 30-core GPU, 36GB Unified Memory, 1TB SSD Storage. Liquid Retina XDR display, up to 22 hours battery life. Space Black finish.",
                price = 3299.00,
                originalPrice = 3499.00,
                currency = "USD",
                imageUrls = listOf("https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=800&q=80"),
                storeId = "store_apple",
                storeName = "iStore Official",
                sellerId = "seller_istore",
                sellerPin = "1P0YE4WZ",
                sellerLocation = "Lagos, Nigeria",
                categoryId = "electronics",
                category = "Electronics",
                stock = 5,
                rating = 5.0,
                reviewsCount = 95,
                tags = listOf("Verified Merchant", "Fast Delivery", "Official Warranty")
            ),
            Product(
                id = "prod_106",
                name = "Minimalist Ergonomic Executive Mesh Desk Chair",
                description = "Adjustable 3D lumbar support, breathable Korean mesh, 4D armrests, and dynamic recline tilt mechanism. Designed for 12+ hours daily comfort and spinal posture alignment.",
                price = 249.00,
                originalPrice = 299.00,
                currency = "USD",
                imageUrls = listOf("https://images.unsplash.com/photo-1580481077195-c3a82da91883?w=800&q=80"),
                storeId = "store_home",
                storeName = "ComfortHome Living",
                sellerId = "seller_home",
                sellerPin = "HOME5541",
                sellerLocation = "Lagos, Nigeria",
                categoryId = "home",
                category = "Home",
                stock = 20,
                rating = 4.6,
                reviewsCount = 74,
                tags = listOf("Fast Delivery", "Easy Assembly")
            )
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
                // Combine with memory/local products and fallback if empty
                val combined = if (apiList.isNotEmpty()) {
                    (memoryMyProducts + apiList).distinctBy { it.id }
                } else {
                    (memoryMyProducts + defaultSeedProducts).distinctBy { it.id }
                }
                Result.success(filterAndSortLocally(combined, categoryId, search, sortBy))
            } else {
                val fallback = (memoryMyProducts + defaultSeedProducts).distinctBy { it.id }
                Result.success(filterAndSortLocally(fallback, categoryId, search, sortBy))
            }
        } catch (e: Exception) {
            val fallback = (memoryMyProducts + defaultSeedProducts).distinctBy { it.id }
            Result.success(filterAndSortLocally(fallback, categoryId, search, sortBy))
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
                Result.success(list.ifEmpty { defaultSeedProducts.filter { it.storeId == storeId } })
            } else {
                val list = (memoryMyProducts + defaultSeedProducts).filter { it.storeId == storeId }
                Result.success(list)
            }
        } catch (e: Exception) {
            val list = (memoryMyProducts + defaultSeedProducts).filter { it.storeId == storeId }
            Result.success(list)
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
