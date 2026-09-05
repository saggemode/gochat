package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    @SerialName("icon_url") val iconUrl: String? = null
)

@Serializable
data class Product(
    val id: String,
    val name: String,
    val description: String,
    val price: Double,
    val currency: String = "USD",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("store_id") val storeId: String,
    @SerialName("category_id") val categoryId: String? = null,
    val stock: Int = 0,
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Store(
    val id: String,
    val name: String,
    val description: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val rating: Double = 0.0,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class CartItem(
    val id: String,
    @SerialName("product_id") val productId: String,
    val quantity: Int,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_price") val productPrice: Double? = null,
    @SerialName("product_image") val productImage: String? = null
)

@Serializable
data class Order(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("store_id") val storeId: String,
    val items: List<CartItem>,
    @SerialName("total_amount") val totalAmount: Double,
    val status: OrderStatus = OrderStatus.PENDING,
    @SerialName("shipping_address") val shippingAddress: String? = null,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class OrderStatus {
    @SerialName("pending") PENDING,
    @SerialName("processing") PROCESSING,
    @SerialName("shipped") SHIPPED,
    @SerialName("delivered") DELIVERED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("refunded") REFUNDED
}
