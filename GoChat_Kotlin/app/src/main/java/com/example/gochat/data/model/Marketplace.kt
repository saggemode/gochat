package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    @SerialName("icon_url") val iconUrl: String? = null,
    val iconName: String = "grid"
)

@Serializable
@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val price: Double = 0.0,
    @SerialName("original_price") val originalPrice: Double = 0.0,
    val currency: String = "USD",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String = "Official Store",
    @SerialName("seller_id") val sellerId: String = "",
    @SerialName("seller_pin") val sellerPin: String = "",
    @SerialName("seller_location") val sellerLocation: String = "Lagos, Nigeria",
    @SerialName("category_id") val categoryId: String? = null,
    val category: String = "General",
    val stock: Int = 10,
    @SerialName("in_stock") val inStock: Boolean = true,
    @SerialName("is_verified") val isVerifiedSeller: Boolean = true,
    val rating: Double = 4.8,
    @SerialName("reviews_count") val reviewsCount: Int = 120,
    val tags: List<String> = listOf("Verified Merchant", "Fast Delivery"),
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
) {
    val primaryImage: String
        get() = imageUrls.firstOrNull() ?: imageUrl

    val hasDiscount: Boolean
        get() = originalPrice > price && originalPrice > 0.0

    val discountPercent: Int
        get() = if (hasDiscount) (((originalPrice - price) / originalPrice) * 100).toInt() else 0

    val displayTitle: String
        get() = name.ifBlank { "Product" }
}

@Serializable
@Entity(tableName = "stores")
data class Store(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val category: String = "General Retail",
    val address: String = "Lagos, Nigeria",
    val phone: String = "",
    val email: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("owner_pin") val ownerPin: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val rating: Double = 4.9,
    @SerialName("is_verified") val isVerified: Boolean = true,
    @SerialName("total_sales") val totalSales: Int = 0,
    @SerialName("total_revenue") val totalRevenue: Double = 0.0,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "cart_items")
data class CartItem(
    @PrimaryKey val id: String,
    @SerialName("product_id") val productId: String,
    var quantity: Int = 1,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_price") val productPrice: Double? = null,
    @SerialName("product_image") val productImage: String? = null,
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String = ""
)

@Serializable
@Entity(tableName = "marketplace_orders")
data class Order(
    @PrimaryKey val id: String,
    @SerialName("order_number") val orderNumber: String = "ORD-${System.currentTimeMillis()}",
    @SerialName("user_id") val userId: String = "",
    @SerialName("buyer_id") val buyerId: String = "",
    @SerialName("buyer_name") val buyerName: String = "Customer",
    @SerialName("buyer_phone") val buyerPhone: String = "",
    @SerialName("buyer_pin") val buyerPin: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String = "Official Store",
    val items: List<CartItem> = emptyList(),
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    val status: OrderStatus = OrderStatus.PAID,
    @SerialName("shipping_address") val shippingAddress: String? = "Lagos, Nigeria",
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class OrderStatus {
    @SerialName("pending") PENDING,
    @SerialName("paid") PAID,
    @SerialName("processing") PROCESSING,
    @SerialName("shipped") SHIPPED,
    @SerialName("delivered") DELIVERED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("refunded") REFUNDED
}
