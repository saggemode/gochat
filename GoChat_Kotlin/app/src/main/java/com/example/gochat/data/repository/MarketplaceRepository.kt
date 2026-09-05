package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.*
import kotlinx.serialization.json.*

class MarketplaceRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val json = NetworkModule.json

    suspend fun getProducts(): Result<List<Product>> {
        return try {
            val response = api.getProducts()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.map { json.decodeFromJsonElement<Product>(it) }
                    is JsonObject -> data["products"]?.jsonArray?.map { json.decodeFromJsonElement<Product>(it) } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch products"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = api.getCategories()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.map { json.decodeFromJsonElement<Category>(it) }
                    is JsonObject -> data["categories"]?.jsonArray?.map { json.decodeFromJsonElement<Category>(it) } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch categories"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
                Result.failure(Exception("Failed to fetch store"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStoreProducts(storeId: String): Result<List<Product>> {
        return try {
            val response = api.getStoreProducts(storeId)
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.map { json.decodeFromJsonElement<Product>(it) }
                    is JsonObject -> data["products"]?.jsonArray?.map { json.decodeFromJsonElement<Product>(it) } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch store products"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCart(): Result<List<CartItem>> {
        return try {
            val response = api.getCart()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.map { json.decodeFromJsonElement<CartItem>(it) }
                    is JsonObject -> data["cart"]?.jsonObject?.get("items")?.jsonArray?.map { json.decodeFromJsonElement<CartItem>(it) } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch cart"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addToCart(productId: String, quantity: Int): Result<Boolean> {
        return try {
            val body = buildJsonObject {
                put("product_id", productId)
                put("quantity", quantity)
            }
            val response = api.addToCart(body)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrders(): Result<List<Order>> {
        return try {
            val response = api.getOrders()
            if (response.isSuccessful) {
                val data = response.body()
                val list = when (data) {
                    is JsonArray -> data.map { json.decodeFromJsonElement<Order>(it) }
                    is JsonObject -> data["orders"]?.jsonArray?.map { json.decodeFromJsonElement<Order>(it) } ?: emptyList()
                    else -> emptyList()
                }
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch orders"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val orderObj = data["order"]?.jsonObject ?: data
                Result.success(json.decodeFromJsonElement<Order>(orderObj))
            } else {
                Result.failure(Exception("Failed to place order"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
