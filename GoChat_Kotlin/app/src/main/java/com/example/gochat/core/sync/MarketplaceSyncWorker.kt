package com.example.gochat.core.sync

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import java.io.File

@HiltWorker
class MarketplaceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MarketplaceRepository,
    private val json: Json
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_PRODUCT_JSON = "key_product_json"
    }

    override suspend fun doWork(): Result {
        val productJson = inputData.getString(KEY_PRODUCT_JSON) ?: return Result.failure()
        val product = try {
            json.decodeFromString<Product>(productJson)
        } catch (_: Exception) {
            return Result.failure()
        }

        // 1. Upload local images
        val uploadedUrls = mutableListOf<String>()
        for (url in product.imageUrls) {
            if (url.startsWith("/") || url.startsWith("file://") || url.startsWith("content://")) {
                val bytes = readBytesFromUri(url)
                if (bytes != null) {
                    val remoteUrl = repository.uploadMedia(bytes)
                    if (remoteUrl != null) {
                        uploadedUrls.add(remoteUrl)
                    } else {
                        return Result.retry()
                    }
                }
            } else {
                uploadedUrls.add(url)
            }
        }

        // 2. Create or Update product on server
        val finalProduct = product.copy(
            imageUrls = uploadedUrls,
            imageUrl = uploadedUrls.firstOrNull() ?: product.imageUrl
        )

        val serverResult = repository.createProduct(finalProduct)
        
        return if (serverResult.isSuccess) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun readBytesFromUri(uriStr: String): ByteArray? {
        return try {
            val cleanPath = uriStr.removePrefix("file://")
            val file = File(cleanPath)
            if (file.exists()) {
                file.readBytes()
            } else {
                // Try content resolver if it's a content URI
                applicationContext.contentResolver.openInputStream(Uri.parse(uriStr))?.use { 
                    it.readBytes()
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
