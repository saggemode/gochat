package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object TimestampSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TimestampSerializer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long {
        return try {
            if (decoder is JsonDecoder) {
                val element = decoder.decodeJsonElement()
                element.jsonPrimitive.longOrNull
                    ?: parseIsoTimestamp(element.jsonPrimitive.content)
            } else {
                decoder.decodeLong()
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun parseIsoTimestamp(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(isoString).toEpochMilli()
        } catch (_: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(isoString)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}

@Serializable
data class Category(
    val id: String,
    val name: String,
    @SerialName("icon_url") val iconUrl: String? = null,
    val iconName: String = "grid"
)

@Serializable
data class ProductVariant(
    val id: String = "",
    @SerialName("product_id") val productId: String = "",
    val sku: String = "",
    val title: String = "",
    @SerialName("attributes_json") val attributesJson: String = "{}", // e.g. {"size":"M", "color":"Blue"}
    @SerialName("price_override") val priceOverride: Double = 0.0,
    @SerialName("stock_quantity") val stockQuantity: Int = 0,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("is_active") val isActive: Boolean = true
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
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("category_id") val categoryId: String? = null,

    val category: String = "General",
    val stock: Int = 10,
    @SerialName("in_stock") val inStock: Boolean = true,
    @SerialName("is_verified") val isVerifiedSeller: Boolean = true,
    val rating: Double = 4.8,
    @SerialName("reviews_count") val reviewsCount: Int = 120,
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("order_count") val orderCount: Int = 0,
    val tags: List<String> = listOf("Verified Merchant", "Fast Delivery"),
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("created_at") @Serializable(with = TimestampSerializer::class) val createdAt: Long = System.currentTimeMillis(),
    val variants: List<ProductVariant> = emptyList()
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
    @SerialName("store_name") val storeName: String = "",
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("variant_title") val variantTitle: String? = null
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
    @SerialName("buyer_avatar") val buyerAvatar: String? = null,
    @SerialName("buyer_pin") val buyerPin: String = "",
    @SerialName("store_id") val storeId: String = "",
    @SerialName("store_name") val storeName: String = "Official Store",
    val items: List<CartItem> = emptyList(),
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
    @SerialName("discount_amount") val discountAmount: Double = 0.0,
    @SerialName("shipping_fee") val shippingFee: Double = 0.0,
    val status: OrderStatus = OrderStatus.PAID,
    @SerialName("shipping_address") val shippingAddress: String? = "Lagos, Nigeria",
    @SerialName("shipping_name") val shippingName: String? = null,
    @SerialName("shipping_phone") val shippingPhone: String? = null,
    @SerialName("tracking_number") val trackingNumber: String? = null,
    @SerialName("tracking_carrier") val trackingCarrier: String? = null,
    @SerialName("tracking_url") val trackingUrl: String? = null,
    @SerialName("shipped_at") val shippedAt: String? = null,
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

@Serializable
data class SellerInsights(
    val grossRevenue: Double = 0.0,
    val totalOrders: Int = 0,
    val listedProducts: Int = 0,
    val totalViews: Int = 0,
    val salesTrend: List<Pair<Long, Double>> = emptyList(), // Date to revenue
    val viewsTrend: List<Pair<Long, Int>> = emptyList(), // Date to views
    val mostViewedProducts: List<Product> = emptyList()
)

