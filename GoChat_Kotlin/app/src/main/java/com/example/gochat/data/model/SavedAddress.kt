package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A delivery address the buyer has saved, so checkout can reuse it. */
@Entity(tableName = "saved_addresses")
data class SavedAddress(
    @PrimaryKey val id: String,
    val label: String = "",
    val fullAddress: String,
    val phone: String = "",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
