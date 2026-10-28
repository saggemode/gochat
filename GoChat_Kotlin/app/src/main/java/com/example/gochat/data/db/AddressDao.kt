package com.example.gochat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.gochat.data.model.SavedAddress
import kotlinx.coroutines.flow.Flow

@Dao
interface AddressDao {

    @Query("SELECT * FROM saved_addresses ORDER BY isDefault DESC, createdAt DESC")
    fun observeAddresses(): Flow<List<SavedAddress>>

    @Query("SELECT * FROM saved_addresses ORDER BY isDefault DESC, createdAt DESC")
    suspend fun getAddresses(): List<SavedAddress>

    @Query("SELECT * FROM saved_addresses WHERE fullAddress = :fullAddress LIMIT 1")
    suspend fun findByAddress(fullAddress: String): SavedAddress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(address: SavedAddress)

    @Query("DELETE FROM saved_addresses WHERE id = :id")
    suspend fun delete(id: String)
}
