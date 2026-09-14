package com.example.gochat.data.db

import androidx.paging.PagingSource
import androidx.room.*
import com.example.gochat.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketplaceDao {

    // ── Store ────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStore(store: Store)

    @Query("SELECT * FROM stores WHERE id = :storeId LIMIT 1")
    suspend fun getStoreById(storeId: String): Store?

    @Query("SELECT * FROM stores WHERE ownerId = :ownerId LIMIT 1")
    suspend fun getStoreByOwner(ownerId: String): Store?

    // ── Products ─────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: Product)

    @Query("SELECT * FROM products ORDER BY createdAt DESC")
    fun getProductsPaged(): PagingSource<Int, Product>

    @Query("""
        SELECT * FROM products 
        WHERE (:categoryId IS NULL OR :categoryId = '' OR LOWER(:categoryId) = 'all' 
               OR categoryId = :categoryId 
               OR LOWER(categoryId) = LOWER(:categoryId)
               OR LOWER(category) = LOWER(:categoryId))
          AND (:search IS NULL OR :search = '' 
               OR LOWER(name) LIKE '%' || LOWER(:search) || '%' 
               OR LOWER(description) LIKE '%' || LOWER(:search) || '%' 
               OR LOWER(storeName) LIKE '%' || LOWER(:search) || '%')
        ORDER BY createdAt DESC
    """)
    fun getProductsPagedFiltered(categoryId: String?, search: String?): PagingSource<Int, Product>

    @Query("SELECT * FROM products ORDER BY createdAt DESC")
    fun observeAllProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products")
    suspend fun getAllProducts(): List<Product>


    @Query("SELECT * FROM products WHERE categoryId = :categoryId OR LOWER(categoryId) = LOWER(:categoryId) OR LOWER(category) = LOWER(:categoryId)")
    suspend fun getProductsByCategory(categoryId: String): List<Product>

    @Query("SELECT * FROM products WHERE storeId = :storeId")
    suspend fun getStoreProducts(storeId: String): List<Product>

    @Query("SELECT * FROM products WHERE sellerId = :sellerId")
    suspend fun getMyProducts(sellerId: String): List<Product>

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteProduct(productId: String)

    @Query("DELETE FROM products")
    suspend fun clearProducts()

    // ── Remote Keys ─────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRemoteKeys(remoteKey: List<ProductRemoteKeys>)

    @Query("SELECT * FROM product_remote_keys WHERE productId = :productId")
    suspend fun getRemoteKeysForProduct(productId: String): ProductRemoteKeys?

    @Query("DELETE FROM product_remote_keys")
    suspend fun clearRemoteKeys()


    // ── Cart ─────────────────────────────────────────────────────
    @Query("SELECT * FROM cart_items")
    suspend fun getCartItems(): List<CartItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCartItem(item: CartItem)

    @Query("DELETE FROM cart_items WHERE productId = :productId")
    suspend fun removeCartItem(productId: String)

    @Query("DELETE FROM cart_items")
    suspend fun clearCart()

    // ── Orders ───────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrders(orders: List<Order>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order)

    @Query("SELECT * FROM marketplace_orders ORDER BY createdAt DESC")
    suspend fun getAllOrders(): List<Order>

    @Query("SELECT * FROM marketplace_orders WHERE buyerId = :buyerId ORDER BY createdAt DESC")
    suspend fun getBuyerOrders(buyerId: String): List<Order>

    @Query("SELECT * FROM marketplace_orders WHERE storeId = :storeId ORDER BY createdAt DESC")
    suspend fun getSellerOrders(storeId: String): List<Order>
}
