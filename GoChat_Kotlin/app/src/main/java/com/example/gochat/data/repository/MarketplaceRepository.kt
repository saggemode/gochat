package com.example.gochat.data.repository

import android.content.Context
import androidx.paging.*
import com.example.gochat.data.api.ApiConstants
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.db.MarketplaceDao

import com.example.gochat.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketplaceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GoChatApiService,
    private val db: AppDatabase,
    private val marketplaceDao: MarketplaceDao,
    private val tokenManager: TokenManager,
    private val json: Json
) {

    val userId: String? get() = tokenManager.userId


    @OptIn(ExperimentalPagingApi::class)
    fun getProductsPaged(
        categoryId: String? = null,
        search: String? = null,
        sortBy: String? = null,
        isNearbyOnly: Boolean = false,
        isFollowingOnly: Boolean = false,
        userLat: Double = 6.46,
        userLng: Double = 3.40
    ): Flow<PagingData<Product>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                initialLoadSize = 20
            ),
            remoteMediator = ProductRemoteMediator(api, db, this, categoryId, search, sortBy, isNearbyOnly, isFollowingOnly, userLat, userLng),
            pagingSourceFactory = {
                marketplaceDao.getProductsPagedFiltered(categoryId, search)
            }
        ).flow
    }

    suspend fun refreshProducts(
        categoryId: String? = null,
        search: String? = null,
        sortBy: String? = null
    ): Result<List<Product>> {
        return try {
            val response = api.getProducts(
                categoryId = if (categoryId == "all" || categoryId.isNullOrBlank()) null else {
                    if (isValidUuid(categoryId)) categoryId else resolveCategoryId(categoryId)
                },
                search = search?.ifBlank { null },
                sortBy = sortBy,
                page = 1,
                limit = 50
            )
            if (response.isSuccessful) {
                val data = response.body()
                val apiList = parseProductsJson(data)
                if (apiList.isNotEmpty()) {
                    marketplaceDao.insertProducts(apiList)
                }
                Result.success(apiList)
            } else {
                Result.failure(Exception("Failed to fetch products (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }




    // Seed data for categories with canonical UUIDs matching Postgres DB
    companion object {
        val defaultCategories = listOf(
            Category("all", "All", iconName = "grid"),
            Category("7acb1bee-6bd7-4b42-ae24-8d1e4a49cdf3", "Electronics", iconName = "devices"),
            Category("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d", "Phones", iconName = "smartphone"),
            Category("b2c3d4e5-f6a7-4b6c-9d0e-1f2a3b4c5d6e", "Fashion", iconName = "checkroom"),
            Category("c3d4e5f6-a7b8-4c7d-0e1f-2a3b4c5d6e7f", "Gaming", iconName = "sports_esports"),
            Category("d4e5f6a7-b8c9-4d8e-1f2a-3b4c5d6e7f8a", "Home", iconName = "weekend"),
            Category("e5f6a7b8-c9d0-4e9f-2a3b-4c5d6e7f8a9b", "Services", iconName = "handyman"),
            Category("f6a7b8c9-d0e1-4f0a-3b4c-5d6e7f8a9b0c", "Beauty", iconName = "spa"),
            Category("0a1b2c3d-4e5f-4a0b-8c9d-1e2f3a4b5c6d", "Sports", iconName = "fitness_center"),
            Category("1b2c3d4e-5f6a-4b1c-9d0e-2f3a4b5c6d7e", "Food", iconName = "restaurant"),
            Category("2c3d4e5f-6a7b-4c2d-0e1f-3a4b5c6d7e8f", "General", iconName = "category")
        )

        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isValidUuid(str: String?): Boolean {
            if (str.isNullOrBlank()) return false
            return UUID_REGEX.matches(str.trim())
        }

        fun resolveCategoryId(rawId: String?, rawName: String? = null): String {
            if (!rawId.isNullOrBlank() && isValidUuid(rawId)) {
                return rawId.trim().lowercase()
            }
            val query = (rawName?.takeIf { it.isNotBlank() } ?: rawId).orEmpty().trim().lowercase()
            return when {
                query.contains("elect") || query.contains("gadget") || query.contains("tech") || query.contains("laptop") || query.contains("pc") || query.contains("tv") ->
                    "7acb1bee-6bd7-4b42-ae24-8d1e4a49cdf3"
                query.contains("phone") || query.contains("mobile") || query.contains("smart") || query.contains("cell") || query.contains("iphone") || query.contains("android") ->
                    "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
                query.contains("fash") || query.contains("cloth") || query.contains("wear") || query.contains("shoe") || query.contains("dress") || query.contains("shirt") ->
                    "b2c3d4e5-f6a7-4b6c-9d0e-1f2a3b4c5d6e"
                query.contains("gam") || query.contains("playstation") || query.contains("xbox") || query.contains("nintendo") || query.contains("ps5") ->
                    "c3d4e5f6-a7b8-4c7d-0e1f-2a3b4c5d6e7f"
                query.contains("home") || query.contains("furn") || query.contains("decor") || query.contains("bed") || query.contains("kitchen") ->
                    "d4e5f6a7-b8c9-4d8e-1f2a-3b4c5d6e7f8a"
                query.contains("serv") || query.contains("handy") || query.contains("repair") || query.contains("plumb") || query.contains("electrician") ->
                    "e5f6a7b8-c9d0-4e9f-2a3b-4c5d6e7f8a9b"
                query.contains("beaut") || query.contains("cosmet") || query.contains("skin") || query.contains("hair") || query.contains("perfume") || query.contains("makeup") ->
                    "f6a7b8c9-d0e1-4f0a-3b4c-5d6e7f8a9b0c"
                query.contains("sport") || query.contains("fit") || query.contains("gym") || query.contains("athlet") || query.contains("ball") ->
                    "0a1b2c3d-4e5f-4a0b-8c9d-1e2f3a4b5c6d"
                query.contains("food") || query.contains("groc") || query.contains("drink") || query.contains("snack") || query.contains("eat") || query.contains("meal") ->
                    "1b2c3d4e-5f6a-4b1c-9d0e-2f3a4b5c6d7e"
                else ->
                    "2c3d4e5f-6a7b-4c2d-0e1f-3a4b5c6d7e8f" // General
            }
        }

        fun resolveCategoryName(rawId: String?, rawName: String? = null): String {
            if (!rawName.isNullOrBlank() && rawName.lowercase() != "all" && !isValidUuid(rawName)) {
                return rawName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            return when (rawId?.lowercase()?.trim()) {
                "7acb1bee-6bd7-4b42-ae24-8d1e4a49cdf3" -> "Electronics"
                "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" -> "Phones"
                "b2c3d4e5-f6a7-4b6c-9d0e-1f2a3b4c5d6e" -> "Fashion"
                "c3d4e5f6-a7b8-4c7d-0e1f-2a3b4c5d6e7f" -> "Gaming"
                "d4e5f6a7-b8c9-4d8e-1f2a-3b4c5d6e7f8a" -> "Home"
                "e5f6a7b8-c9d0-4e9f-2a3b-4c5d6e7f8a9b" -> "Services"
                "f6a7b8c9-d0e1-4f0a-3b4c-5d6e7f8a9b0c" -> "Beauty"
                "0a1b2c3d-4e5f-4a0b-8c9d-1e2f3a4b5c6d" -> "Sports"
                "1b2c3d4e-5f6a-4b1c-9d0e-2f3a4b5c6d7e" -> "Food"
                else -> "General"
            }
        }
    }

    suspend fun getProducts(
        categoryId: String? = null,
        search: String? = null,
        sortBy: String? = null,
        isNearbyOnly: Boolean = false,
        userLat: Double = 6.46,
        userLng: Double = 3.40
    ): Result<List<Product>> {

        return try {
            val response = api.getProducts(
                categoryId = if (categoryId == "all" || categoryId.isNullOrBlank()) null else {
                    if (isValidUuid(categoryId)) categoryId else resolveCategoryId(categoryId)
                },
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
                // Merge and deduplicate, keeping API results at the front if they matched the query
                val mergedList = (apiList + allProducts).distinctBy { it.id }
                Result.success(filterAndSortLocally(mergedList, categoryId, search, sortBy, isNearbyOnly, userLat, userLng))
            } else {
                val allProducts = marketplaceDao.getAllProducts()
                Result.success(filterAndSortLocally(allProducts, categoryId, search, sortBy, isNearbyOnly, userLat, userLng))
            }

        } catch (e: Exception) {
            val allProducts = marketplaceDao.getAllProducts()
            Result.success(filterAndSortLocally(allProducts, categoryId, search, sortBy, isNearbyOnly, userLat, userLng))
        }

    }

    fun filterAndSortLocally(
        list: List<Product>,
        categoryId: String?,
        search: String?,
        sortBy: String?,
        isNearbyOnly: Boolean = false,
        userLat: Double = 6.46,
        userLng: Double = 3.40
    ): List<Product> {
        var result = list
        if (!categoryId.isNullOrBlank() && categoryId != "all") {
            val resolvedId = resolveCategoryId(categoryId)
            result = result.filter {
                it.categoryId.equals(categoryId, ignoreCase = true) ||
                it.categoryId.equals(resolvedId, ignoreCase = true) ||
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

        if (isNearbyOnly) {
            result = result.filter { 
                val dist = calculateDistance(userLat, userLng, it.latitude ?: (userLat + 0.1), it.longitude ?: (userLng + 0.1))
                dist <= 10.0
            }
        }

        return when (sortBy) {
            "price_low" -> result.sortedBy { it.price }
            "price_high" -> result.sortedByDescending { it.price }
            "rating" -> result.sortedByDescending { it.rating }
            else -> result
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Radius of the earth in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }


    /**
     * Fetch a single product by ID from the dedicated detail endpoint.
     * This returns ALL images for the product (unlike the list endpoint which returns only 1).
     */
    suspend fun getProductById(productId: String): Result<Product> {
        return try {
            val response = api.getProductById(productId)
            if (response.isSuccessful) {
                val product = parseSingleProductJson(response.body())
                if (product != null) {
                    // Update local DB with full product data (including all images)
                    marketplaceDao.insertProducts(listOf(product))
                    Result.success(product)
                } else {
                    // Fallback to local DB
                    val local = marketplaceDao.getAllProducts().find { it.id == productId }
                    if (local != null) Result.success(local)
                    else Result.failure(Exception("Product not found"))
                }
            } else {
                // Fallback to local DB
                val local = marketplaceDao.getAllProducts().find { it.id == productId }
                if (local != null) Result.success(local)
                else Result.failure(Exception("Product not found"))
            }
        } catch (e: Exception) {
            // Fallback to local DB
            val local = marketplaceDao.getAllProducts().find { it.id == productId }
            if (local != null) Result.success(local)
            else Result.failure(e)
        }
    }

    suspend fun getLocalProducts(): List<Product> = marketplaceDao.getAllProducts()

    suspend fun handleIncomingWebSocketEvent(event: JsonObject): Product? {
        val type = event["type"]?.jsonPrimitive?.contentOrNull
        if (type == "new_product") {
            val prodElement = event["product"]
            val newProd = parseSingleProductJson(prodElement)
            if (newProd != null) {
                marketplaceDao.insertProduct(newProd)
                return newProd
            }
        }
        return null
    }

    fun parseSingleProductJson(element: JsonElement?): Product? {
        if (element == null) return null
        return try {
            if (element is JsonObject) {
                val id = (element["id"] ?: element["product_id"])?.jsonPrimitive?.contentOrNull
                if (id.isNullOrBlank()) return null

                val name = (element["name"] ?: element["title"])?.jsonPrimitive?.contentOrNull ?: "Product"
                val desc = (element["description"] ?: element["desc"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val price = element["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0

                val origPrice = (element["original_price"] ?: element["originalPrice"])?.jsonPrimitive?.doubleOrNull ?: 0.0
                val discountPct = (element["discount_percent"] ?: element["discountPercent"])?.jsonPrimitive?.doubleOrNull ?: 0.0
                val calculatedOrigPrice = if (origPrice > price) {
                    origPrice
                } else if (discountPct > 0.0 && discountPct < 100.0 && price > 0.0) {
                    price / (1.0 - (discountPct / 100.0))
                } else {
                    0.0
                }

                val currency = element["currency"]?.jsonPrimitive?.contentOrNull ?: "USD"

                val imgUrls = mutableListOf<String>()
                val imgUrlsElem = element["image_urls"] ?: element["imageUrls"]
                if (imgUrlsElem is JsonArray) {
                    imgUrls.addAll(imgUrlsElem.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() })
                }
                val singleImg = (element["image_url"] ?: element["imageUrl"] ?: element["primary_image"])?.jsonPrimitive?.contentOrNull
                if (!singleImg.isNullOrBlank() && !imgUrls.contains(singleImg)) {
                    imgUrls.add(0, singleImg)
                }

                val primaryImg = imgUrls.firstOrNull().orEmpty()

                val storeId = (element["store_id"] ?: element["storeId"] ?: element["business_id"] ?: element["businessId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val storeName = (element["store_name"] ?: element["storeName"] ?: element["seller_name"] ?: element["sellerName"])?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Official Store"
                val sellerId = (element["seller_id"] ?: element["sellerId"] ?: element["owner_id"] ?: element["ownerId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val sellerPin = (element["seller_pin"] ?: element["sellerPin"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val sellerLoc = (element["seller_location"] ?: element["sellerLocation"] ?: element["location"] ?: element["address"])?.jsonPrimitive?.contentOrNull ?: "Lagos, Nigeria"

                val catId = (element["category_id"] ?: element["categoryId"])?.jsonPrimitive?.contentOrNull
                val rawCat = (element["category"] ?: element["category_name"] ?: element["categoryName"])?.jsonPrimitive?.contentOrNull
                val resolvedCatId = resolveCategoryId(catId, rawCat)
                val resolvedCat = resolveCategoryName(resolvedCatId, rawCat)

                val stock = (element["stock"] ?: element["quantity"])?.jsonPrimitive?.intOrNull ?: 10
                val inStock = (element["in_stock"] ?: element["inStock"])?.jsonPrimitive?.booleanOrNull ?: (stock > 0)
                val isVerified = (element["is_verified"] ?: element["isVerified"] ?: element["is_verified_seller"] ?: element["isVerifiedSeller"])?.jsonPrimitive?.booleanOrNull ?: true
                val rating = (element["rating"] ?: element["rating_avg"] ?: element["ratingAvg"])?.jsonPrimitive?.doubleOrNull ?: 4.8
                val reviewsCount = (element["reviews_count"] ?: element["reviewsCount"] ?: element["review_count"] ?: element["reviewCount"])?.jsonPrimitive?.intOrNull ?: 0
                val viewCount = (element["view_count"] ?: element["viewCount"])?.jsonPrimitive?.intOrNull ?: 0
                val orderCount = (element["order_count"] ?: element["orderCount"])?.jsonPrimitive?.intOrNull ?: 0
                val isAvailable = (element["is_available"] ?: element["isAvailable"] ?: element["is_published"] ?: element["isPublished"])?.jsonPrimitive?.booleanOrNull ?: true

                val createdRaw = element["created_at"] ?: element["createdAt"]
                val createdAt = when {
                    createdRaw == null -> System.currentTimeMillis()
                    createdRaw.jsonPrimitive.longOrNull != null -> createdRaw.jsonPrimitive.long
                    else -> parseIsoDate(createdRaw.jsonPrimitive.contentOrNull)
                }

                val variants = mutableListOf<ProductVariant>()
                val variantsElem = element["variants"]
                if (variantsElem is JsonArray) {
                    variants.addAll(variantsElem.mapNotNull { 
                        try { json.decodeFromJsonElement<ProductVariant>(it) } catch (_: Exception) { null }
                    })
                }

                Product(
                    id = id,
                    name = name,
                    description = desc,
                    price = price,
                    originalPrice = calculatedOrigPrice,
                    currency = currency,
                    imageUrls = imgUrls,
                    imageUrl = primaryImg,
                    storeId = storeId,
                    storeName = storeName,
                    sellerId = sellerId,
                    sellerPin = sellerPin,
                    sellerLocation = sellerLoc,
                    categoryId = resolvedCatId,
                    category = resolvedCat,
                    stock = stock,
                    inStock = inStock,
                    isVerifiedSeller = isVerified,
                    rating = rating,
                    reviewsCount = reviewsCount,
                    viewCount = viewCount,
                    orderCount = orderCount,
                    isAvailable = isAvailable,
                    createdAt = createdAt,
                    variants = variants
                )
            } else {
                json.decodeFromJsonElement<Product>(element)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIsoDate(str: String?): Long {
        if (str.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(str).toEpochMilli()
        } catch (_: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(str)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    fun parseProductsJson(data: JsonElement?): List<Product> {
        return when (data) {
            is JsonArray -> data.mapNotNull { parseSingleProductJson(it) }
            is JsonObject -> {
                val array = data["products"]?.jsonArray ?: data["data"]?.jsonArray
                if (array != null) {
                    array.mapNotNull { parseSingleProductJson(it) }
                } else {
                    listOfNotNull(parseSingleProductJson(data))
                }
            }
            else -> emptyList()
        }
    }

    fun parseCategoryJson(element: JsonElement): Category? {
        return try {
            if (element is JsonObject) {
                val id = element["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val name = element["name"]?.jsonPrimitive?.contentOrNull ?: "Category"
                val icon = (element["icon"] ?: element["icon_name"] ?: element["iconName"])?.jsonPrimitive?.contentOrNull ?: "grid"
                val iconUrl = (element["icon_url"] ?: element["iconUrl"])?.jsonPrimitive?.contentOrNull
                Category(id = id, name = name, iconUrl = iconUrl, iconName = icon)
            } else {
                json.decodeFromJsonElement<Category>(element)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = api.getCategories()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.mapNotNull { parseCategoryJson(it) }
                    is JsonObject -> {
                        val arr = data["categories"]?.jsonArray ?: data["data"]?.jsonArray
                        arr?.mapNotNull { parseCategoryJson(it) } ?: emptyList()
                    }
                    else -> emptyList()
                }
                if (list.isNotEmpty()) {
                    val hasAll = list.any { it.id.equals("all", ignoreCase = true) }
                    val fullList = if (!hasAll) listOf(Category("all", "All", iconName = "grid")) + list else list
                    Result.success(fullList)
                } else {
                    Result.success(defaultCategories)
                }
            } else {
                Result.success(defaultCategories)
            }
        } catch (e: Exception) {
            Result.success(defaultCategories)
        }
    }

    private suspend fun parseStoreJson(data: JsonObject?, fallbackId: String, existing: Store? = null): Store {
        if (data == null) {
            return existing ?: marketplaceDao.getStoreById(fallbackId) ?: Store(id = fallbackId, name = "Verified Merchant Store", isVerified = true)
        }
        val target = data["profile"]?.jsonObject ?: data["store"]?.jsonObject ?: data
        val id = (target["id"] ?: target["user_id"] ?: target["business_id"])?.jsonPrimitive?.contentOrNull ?: existing?.id ?: fallbackId
        val name = (target["name"] ?: target["business_name"] ?: target["store_name"])?.jsonPrimitive?.contentOrNull ?: existing?.name ?: "Merchant Store"
        val desc = (target["description"] ?: target["desc"])?.jsonPrimitive?.contentOrNull ?: existing?.description.orEmpty()
        val cat = (target["category"] ?: target["category_name"])?.jsonPrimitive?.contentOrNull ?: existing?.category ?: "General Retail"
        val addr = (target["address"] ?: target["location"])?.jsonPrimitive?.contentOrNull ?: existing?.address ?: "Lagos, Nigeria"
        val phone = (target["phone"])?.jsonPrimitive?.contentOrNull ?: existing?.phone.orEmpty()
        val email = (target["email"])?.jsonPrimitive?.contentOrNull ?: existing?.email.orEmpty()
        val ownerPin = (target["owner_pin"])?.jsonPrimitive?.contentOrNull ?: existing?.ownerPin.orEmpty()
        val logoUrl = (target["logo_url"] ?: target["logoUrl"] ?: target["logo"] ?: target["avatar_url"] ?: target["avatarUrl"])?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: existing?.logoUrl
        val bannerUrl = (target["banner_url"] ?: target["bannerUrl"] ?: target["banner"])?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: existing?.bannerUrl
        val verified = (target["is_verified"] ?: target["isVerified"])?.jsonPrimitive?.booleanOrNull ?: existing?.isVerified ?: true
        val rating = (target["avg_rating"] ?: target["rating"])?.jsonPrimitive?.doubleOrNull ?: existing?.rating ?: 4.9

        return Store(
            id = id,
            name = name,
            description = desc,
            category = cat,
            address = addr,
            phone = phone,
            email = email,
            ownerId = (target["owner_id"] ?: target["user_id"])?.jsonPrimitive?.contentOrNull ?: existing?.ownerId ?: tokenManager.userId ?: "",
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
        val currentUserId = tokenManager.userId ?: ""
        return try {
            val response = api.getBusinessProfile()
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val targetObj = data["profile"]?.jsonObject ?: (if (data.containsKey("store_name") || data.containsKey("business_name")) data else null)
                if (targetObj != null) {
                    val cached = marketplaceDao.getStoreByOwner(currentUserId)
                    val store = parseStoreJson(targetObj, "my_store", cached)
                    marketplaceDao.insertStore(store)
                    Result.success(store)
                } else {

                    val cached = marketplaceDao.getStoreByOwner(currentUserId)
                    Result.success(cached)
                }
            } else {
                val cached = marketplaceDao.getStoreByOwner(currentUserId)
                Result.success(cached)
            }
        } catch (e: Exception) {
            val cached = marketplaceDao.getStoreByOwner(currentUserId)
            Result.success(cached)
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
                parseStoreJson(data, store.id, store)
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
                parseStoreJson(data, store.id, store)
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

    // ── Follow Operations ───────────────────────────────────────────────────
    suspend fun toggleFollowStore(storeId: String): Result<Boolean> {
        return try {
            val response = api.toggleFollowStore(storeId)
            if (response.isSuccessful) {
                val isFollowing = response.body()?.get("is_following")?.jsonPrimitive?.booleanOrNull ?: false
                Result.success(isFollowing)
            } else {
                val errorBodyStr = response.errorBody()?.string().orEmpty()
                val parsedMsg = try {
                    if (errorBodyStr.isNotBlank()) {
                        val errObj = json.parseToJsonElement(errorBodyStr).jsonObject
                        errObj["error"]?.jsonPrimitive?.contentOrNull
                    } else null
                } catch (_: Exception) { null }
                val msg = parsedMsg ?: when (response.code()) {
                    404 -> "Store not found or endpoint not deployed yet"
                    400 -> "Cannot follow your own store"
                    else -> "Failed to follow store (HTTP ${response.code()})"
                }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isFollowingStore(storeId: String): Result<Boolean> {
        return try {
            val response = api.isFollowingStore(storeId)
            if (response.isSuccessful) {
                val isFollowing = response.body()?.get("is_following")?.jsonPrimitive?.booleanOrNull ?: false
                Result.success(isFollowing)
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    suspend fun getFollowedStores(): Result<List<Store>> {
        return try {
            val response = api.getFollowedStores()
            if (response.isSuccessful) {
                val list = when (val data = response.body()) {
                    is JsonArray -> data.map { parseStoreJson(it.jsonObject, "") }
                    is JsonObject -> data["followed_stores"]?.jsonArray?.map { parseStoreJson(it.jsonObject, "") } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch followed stores"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Review Operations ───────────────────────────────────────────────────
    suspend fun getReviews(productId: String): Result<List<Review>> {
        return try {
            val response = api.getReviews(productId)
            if (response.isSuccessful) {
                val list = when (val data = response.body()) {
                    is JsonArray -> data.mapNotNull {
                        try { json.decodeFromJsonElement<Review>(it) } catch (_: Exception) { null }
                    }
                    is JsonObject -> data["reviews"]?.jsonArray?.mapNotNull {
                        try { json.decodeFromJsonElement<Review>(it) } catch (_: Exception) { null }
                    } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch reviews"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createReview(productId: String, rating: Int, comment: String, imageUrls: List<String>): Result<Review> {
        val currentUserId = tokenManager.userId ?: ""
        
        // Validate that productId is a valid UUID to avoid backend SQL errors
        if (productId.startsWith("prod_") || !productId.contains("-")) {
            return Result.failure(Exception("Invalid Product ID format. Reviews require a UUID. Please create a new product to test this feature."))
        }

        return try {
            val body = buildJsonObject {
                put("product_id", productId)
                put("user_id", currentUserId)
                put("rating", rating)
                put("comment", comment)
                put("image_urls", JsonArray(imageUrls.map { JsonPrimitive(it) }))
            }
            val response = api.createReview(productId, body)
            if (response.isSuccessful) {
                val res = response.body() ?: buildJsonObject {}
                val reviewJson = res["review"]?.jsonObject ?: res
                val parsed = try {
                    json.decodeFromJsonElement<Review>(reviewJson)
                } catch (_: Exception) {
                    Review(
                        id = reviewJson["id"]?.jsonPrimitive?.contentOrNull ?: java.util.UUID.randomUUID().toString(),
                        productId = productId,
                        userId = currentUserId,
                        userName = reviewJson["user_name"]?.jsonPrimitive?.contentOrNull ?: tokenManager.userDisplayName ?: "User",
                        userAvatar = reviewJson["user_avatar"]?.jsonPrimitive?.contentOrNull ?: tokenManager.userAvatarUrl,
                        rating = rating,
                        comment = comment,
                        imageUrls = imageUrls,
                        createdAt = reviewJson["created_at"]?.jsonPrimitive?.contentOrNull
                    )
                }
                Result.success(parsed)
            } else {
                val errorBodyStr = response.errorBody()?.string().orEmpty()
                val parsedMsg = try {
                    if (errorBodyStr.isNotBlank()) {
                        val errObj = json.parseToJsonElement(errorBodyStr).jsonObject
                        errObj["error"]?.jsonPrimitive?.contentOrNull ?: errorBodyStr
                    } else null
                } catch (_: Exception) {
                    errorBodyStr.ifBlank { null }
                }
                val rawMsg = parsedMsg ?: "Failed to submit review (HTTP ${response.code()})"
                val cleanMsg = when {
                    rawMsg.contains("foreign key constraint", ignoreCase = true) || rawMsg.contains("product not found", ignoreCase = true) ->
                        "This product is no longer available on the server."
                    rawMsg.contains("cannot review your own product", ignoreCase = true) ->
                        "You cannot review your own product."
                    rawMsg.startsWith("rpc error: code =") -> {
                        val desc = rawMsg.substringAfter("desc = ").substringBefore(" (SQLSTATE")
                        desc.ifBlank { rawMsg }
                    }
                    else -> rawMsg
                }
                Result.failure(Exception(cleanMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun getMyProducts(): Result<List<Product>> {
        val currentUserId = tokenManager.userId ?: ""
        return try {
            val response = api.getMyProducts()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseProductsJson(data)
                if (list.isNotEmpty()) {
                    marketplaceDao.insertProducts(list)
                    return Result.success(list)
                }
            }
            Result.success(marketplaceDao.getMyProducts(currentUserId))
        } catch (e: Exception) {
            Result.success(marketplaceDao.getMyProducts(currentUserId))
        }
    }

    suspend fun getStoreForUser(sellerUserId: String, sellerPin: String = ""): Store? {
        val trimmedUserId = sellerUserId.trim()
        val trimmedPin = sellerPin.trim()
        if (trimmedUserId.isBlank() && trimmedPin.isBlank()) return null

        var store: Store? = null
        if (trimmedUserId.isNotBlank()) {
            store = marketplaceDao.getStoreByOwner(trimmedUserId)
                ?: marketplaceDao.getStoreById(trimmedUserId)
        }
        if (store == null && trimmedPin.isNotBlank()) {
            store = marketplaceDao.getStoreByOwnerPin(trimmedPin)
        }
        if (store == null && trimmedUserId.isNotBlank()) {
            store = getStore(trimmedUserId).getOrNull()
        }
        return store
    }

    /**
     * Strictly retrieves products owned by a specific user or their store.
     * Guaranteed never to return products from other merchants or general catalog fallback.
     */
    suspend fun getUserProducts(sellerUserId: String, sellerPin: String = ""): List<Product> {
        val trimmedUserId = sellerUserId.trim()
        val trimmedPin = sellerPin.trim()
        if (trimmedUserId.isBlank() && trimmedPin.isBlank()) return emptyList()

        val currentUserId = tokenManager.userId.orEmpty()
        val isCurrentUser = trimmedUserId.isNotBlank() && trimmedUserId == currentUserId

        // If it's the current user, fetch their own products
        if (isCurrentUser) {
            val myResult = getMyProducts()
            val list = myResult.getOrNull()
            if (!list.isNullOrEmpty()) {
                return list.distinctBy { it.id }
            }
        }

        val store = getStoreForUser(trimmedUserId, trimmedPin)

        // 1. Fetch from network API if possible
        if (trimmedUserId.isNotBlank()) {
            try {
                getStoreProducts(trimmedUserId)
            } catch (_: Exception) {}
        }
        if (store != null && store.id.isNotBlank() && store.id != trimmedUserId) {
            try {
                getStoreProducts(store.id)
            } catch (_: Exception) {}
        }

        // 2. Query Room DB strictly for this user's products
        val candidateProducts = mutableListOf<Product>()
        if (trimmedUserId.isNotBlank()) {
            candidateProducts.addAll(marketplaceDao.getMyProducts(trimmedUserId))
            candidateProducts.addAll(marketplaceDao.getStoreProducts(trimmedUserId))
        }
        if (store != null && store.id.isNotBlank()) {
            candidateProducts.addAll(marketplaceDao.getStoreProducts(store.id))
        }

        // Also check all cached products in case some match sellerId, sellerPin, or store
        val allCached = marketplaceDao.getAllProducts()
        candidateProducts.addAll(allCached.filter { p ->
            isProductOwnedByUser(p, trimmedUserId, trimmedPin, store?.id)
        })

        // 3. Strict security filter: only keep products explicitly belonging to this user or store
        val strictlyOwned = candidateProducts.filter { p ->
            isProductOwnedByUser(p, trimmedUserId, trimmedPin, store?.id)
        }.distinctBy { it.id }

        return strictlyOwned
    }

    private fun isProductOwnedByUser(
        p: Product,
        sellerUserId: String,
        sellerPin: String,
        storeId: String?
    ): Boolean {
        if (p.id.isBlank()) return false
        val matchesSellerId = sellerUserId.isNotBlank() && p.sellerId.isNotBlank() && p.sellerId == sellerUserId
        val matchesStoreIdAsUser = sellerUserId.isNotBlank() && p.storeId.isNotBlank() && p.storeId == sellerUserId
        val matchesStore = !storeId.isNullOrBlank() && p.storeId.isNotBlank() && p.storeId == storeId
        val matchesPin = sellerPin.isNotBlank() && p.sellerPin.isNotBlank() && p.sellerPin.equals(sellerPin, ignoreCase = true)

        return matchesSellerId || matchesStoreIdAsUser || matchesStore || matchesPin
    }

    suspend fun insertProductLocally(product: Product) {
        marketplaceDao.insertProduct(product)
    }

    suspend fun createProduct(product: Product): Result<Product> {
        return try {
            val resolvedCategoryId = resolveCategoryId(product.categoryId, product.category)
            val resolvedCategoryName = resolveCategoryName(resolvedCategoryId, product.category)
            val body = buildJsonObject {
                put("name", product.name)
                put("title", product.name)
                put("description", product.description)
                put("price", product.price)
                put("original_price", product.originalPrice)
                val disc = if (product.originalPrice > product.price && product.originalPrice > 0) {
                    ((product.originalPrice - product.price) / product.originalPrice) * 100
                } else 0.0
                put("discount_percent", disc)
                put("currency", product.currency)
                put("category", resolvedCategoryName)
                put("category_id", resolvedCategoryId)
                put("stock", product.stock)
                put("quantity", product.stock)
                put("image_url", product.primaryImage)
                put("image_urls", JsonArray(product.imageUrls.map { JsonPrimitive(it) }))
            }
            val response = api.createProduct(body)
            if (response.isSuccessful) {
                val data = response.body()
                val targetObj = when (data) {
                    is JsonObject -> data["product"]?.jsonObject ?: data
                    else -> null
                }
                val parsed = parseSingleProductJson(targetObj)
                val created = if (parsed != null) {
                    if (parsed.id != product.id) {
                        marketplaceDao.deleteProduct(product.id)
                    }
                    parsed
                } else {
                    product.copy(categoryId = resolvedCategoryId, category = resolvedCategoryName)
                }
                marketplaceDao.insertProduct(created)
                Result.success(created)
            } else {
                val errBody = response.errorBody()?.string().orEmpty()
                Result.failure(Exception("Failed to create product (${response.code()}): $errBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProduct(product: Product): Result<Product> {
        return try {
            val resolvedCategoryId = resolveCategoryId(product.categoryId, product.category)
            val resolvedCategoryName = resolveCategoryName(resolvedCategoryId, product.category)
            val body = buildJsonObject {
                put("name", product.name)
                put("title", product.name)
                put("description", product.description)
                put("price", product.price)
                put("original_price", product.originalPrice)
                val disc = if (product.originalPrice > product.price && product.originalPrice > 0) {
                    ((product.originalPrice - product.price) / product.originalPrice) * 100
                } else 0.0
                put("discount_percent", disc)
                put("currency", product.currency)
                put("category", resolvedCategoryName)
                put("category_id", resolvedCategoryId)
                put("stock", product.stock)
                put("quantity", product.stock)
                put("image_url", product.primaryImage)
                put("image_urls", JsonArray(product.imageUrls.map { JsonPrimitive(it) }))
            }
            val response = api.updateProduct(product.id, body)
            if (response.isSuccessful) {
                val data = response.body()
                val targetObj = when (data) {
                    is JsonObject -> data["product"]?.jsonObject ?: data
                    else -> null
                }
                val updated = parseSingleProductJson(targetObj) ?: product.copy(categoryId = resolvedCategoryId, category = resolvedCategoryName)
                marketplaceDao.insertProduct(updated)
                Result.success(updated)
            } else {
                val errBody = response.errorBody()?.string().orEmpty()
                Result.failure(Exception("Failed to update product (${response.code()}): $errBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
                    id = UUID.randomUUID().toString(),
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
        val currentUserId = tokenManager.userId ?: ""
        return try {
            val response = api.getBuyerOrders()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseOrdersJson(data)
                if (list.isNotEmpty()) {
                    marketplaceDao.insertOrders(list)
                }
            }
            Result.success(marketplaceDao.getBuyerOrders(currentUserId))
        } catch (e: Exception) {
            Result.success(marketplaceDao.getBuyerOrders(currentUserId))
        }
    }

    suspend fun getSellerOrders(): Result<List<Order>> {
        val currentUserId = tokenManager.userId ?: ""
        return try {
            val response = api.getSellerOrders()
            if (response.isSuccessful) {
                val data = response.body()
                val list = parseOrdersJson(data)
                if (list.isNotEmpty()) {
                    marketplaceDao.insertOrders(list)
                }
            }
            // Usually seller hub shows orders for the store(s) owned by user.
            // For now, we fetch all orders where storeId matches user's store
            val myStore = marketplaceDao.getStoreByOwner(currentUserId)
            if (myStore != null) {
                Result.success(marketplaceDao.getSellerOrders(myStore.id))
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            val myStore = marketplaceDao.getStoreByOwner(currentUserId)
            if (myStore != null) {
                Result.success(marketplaceDao.getSellerOrders(myStore.id))
            } else {
                Result.success(emptyList())
            }
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

    suspend fun getOrderById(orderId: String): Result<Order> {
        return try {
            val response = api.getOrderById(orderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val orderObj = body["order"]?.let { if (it is JsonObject) it else null } ?: body
                val order = json.decodeFromJsonElement<Order>(orderObj)
                marketplaceDao.insertOrder(order)
                Result.success(order)
            } else {
                val localOrder = marketplaceDao.getOrderById(orderId)
                if (localOrder != null) Result.success(localOrder)
                else Result.failure(Exception("Order not found"))
            }
        } catch (e: Exception) {
            val localOrder = marketplaceDao.getOrderById(orderId)
            if (localOrder != null) Result.success(localOrder)
            else Result.failure(e)
        }
    }

    suspend fun getOrderStatusHistory(orderId: String): Result<List<OrderStatusHistoryItem>> {
        return try {
            val response = api.getOrderStatusHistory(orderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val histArray = body["history"]?.let { if (it is JsonArray) it else null } ?: JsonArray(emptyList())
                val list = histArray.mapNotNull {
                    try { json.decodeFromJsonElement<OrderStatusHistoryItem>(it) } catch (_: Exception) { null }
                }
                Result.success(list)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
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
                id = UUID.randomUUID().toString(),
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
                id = UUID.randomUUID().toString(),
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

    suspend fun getSellerInsights(): Result<SellerInsights> {
        return try {
            val productsResult = getMyProducts()
            val ordersResult = getSellerOrders()
            
            val products = productsResult.getOrDefault(emptyList())
            val orders = ordersResult.getOrDefault(emptyList())
            
            val grossRevenue = orders.sumOf { it.grandTotal.ifZero(it.totalAmount) }
            val totalOrders = orders.size
            val listedProducts = products.size
            val totalViews = products.sumOf { it.viewCount }
            
            // Group orders by day for trend
            val salesTrend = orders.groupBy { 
                val cal = Calendar.getInstance()
                cal.timeInMillis = it.createdAt
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }.map { (date, dailyOrders) ->
                date to dailyOrders.sumOf { it.grandTotal.ifZero(it.totalAmount) }
            }.sortedBy { it.first }

            // Mock views trend (last 7 days)
            val viewsTrend = mutableListOf<Pair<Long, Int>>()
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            for (i in 6 downTo 0) {
                val dayCal = cal.clone() as Calendar
                dayCal.add(Calendar.DAY_OF_YEAR, -i)
                viewsTrend.add(dayCal.timeInMillis to (10..50).random())
            }
            
            val mostViewed = products.sortedByDescending { it.viewCount }.take(5)
            
            Result.success(SellerInsights(
                grossRevenue = grossRevenue,
                totalOrders = totalOrders,
                listedProducts = listedProducts,
                totalViews = totalViews,
                salesTrend = salesTrend,
                viewsTrend = viewsTrend,
                mostViewedProducts = mostViewed
            ))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleReviewHelpful(reviewId: String): Result<Boolean> {
        return try {
            val response = api.toggleReviewHelpful(reviewId)
            if (response.isSuccessful) {
                val isHelpful = response.body()?.get("is_helpful")?.jsonPrimitive?.booleanOrNull ?: true
                Result.success(isHelpful)
            } else {
                Result.failure(Exception("Failed to upvote review"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun Double.ifZero(fallback: Double): Double = if (this == 0.0) fallback else this
}

