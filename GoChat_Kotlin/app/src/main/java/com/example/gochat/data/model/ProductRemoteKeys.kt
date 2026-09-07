package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "product_remote_keys")
data class ProductRemoteKeys(
    @PrimaryKey val productId: String,
    val prevKey: Int?,
    val nextKey: Int?
)
