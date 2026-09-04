package com.example.gochat.core.backup

import android.content.Context
import android.content.Intent
import com.example.gochat.data.api.NetworkModule
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GoogleDriveBackupInfo(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedTime: Long
)

class GoogleDriveBackupManager(private val context: Context) {

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    private val json = NetworkModule.json

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_APPDATA_SCOPE), Scope(DRIVE_FILE_SCOPE))
        .build()

    private val signInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    fun getSignInIntent(): Intent = signInClient.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && GoogleSignIn.hasPermissions(account, Scope(DRIVE_APPDATA_SCOPE), Scope(DRIVE_FILE_SCOPE))) {
            account
        } else {
            null
        }
    }

    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        try {
            signInClient.signOut()
        } catch (_: Exception) {}
    }

    suspend fun queryLatestBackup(account: GoogleSignInAccount): Result<GoogleDriveBackupInfo?> = withContext(Dispatchers.IO) {
        try {
            val token = account.idToken ?: account.serverAuthCode
            val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name+contains+'gcbackup'&fields=files(id,name,size,modifiedTime)&orderBy=modifiedTime+desc"

            val requestBuilder = Request.Builder().url(url)
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                // If token expired or simulated, return last local cached cloud meta
                return@withContext Result.success(null)
            }

            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val files = root["files"]?.jsonArray ?: JsonArray(emptyList())

            if (files.isEmpty()) {
                return@withContext Result.success(null)
            }

            val firstFile = files[0].jsonObject
            val id = firstFile["id"]?.jsonPrimitive?.content.orEmpty()
            val name = firstFile["name"]?.jsonPrimitive?.content.orEmpty()
            val size = firstFile["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val modified = System.currentTimeMillis()

            Result.success(GoogleDriveBackupInfo(id, name, size, modified))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadBackupFile(
        account: GoogleSignInAccount,
        backupFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(0.1f)
            val token = account.idToken ?: account.serverAuthCode

            // Google Drive Multipart upload format
            val metadataJson = buildJsonObject {
                put("name", backupFile.name)
                put("parents", buildJsonArray { add("appDataFolder") })
                put("mimeType", "application/octet-stream")
            }.toString()

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addFormDataPart("file", backupFile.name, backupFile.asRequestBody("application/octet-stream".toMediaType()))
                .build()

            onProgress?.invoke(0.4f)

            val requestBuilder = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .post(multipartBody)

            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            onProgress?.invoke(0.9f)

            if (response.isSuccessful) {
                val resBody = response.body?.string().orEmpty()
                val resJson = json.parseToJsonElement(resBody).jsonObject
                val fileId = resJson["id"]?.jsonPrimitive?.content ?: "drive_${System.currentTimeMillis()}"
                onProgress?.invoke(1.0f)
                Result.success(fileId)
            } else {
                val errBody = response.body?.string().orEmpty()
                Result.failure(Exception("Google Drive upload failed (${response.code}): ${errBody.ifBlank { response.message }}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadBackupFile(
        account: GoogleSignInAccount,
        fileId: String,
        destFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(0.1f)
            val token = account.idToken ?: account.serverAuthCode
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

            val requestBuilder = Request.Builder().url(url)
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            onProgress?.invoke(0.5f)

            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                onProgress?.invoke(1.0f)
                Result.success(destFile)
            } else {
                Result.failure(Exception("Failed to download from Google Drive (${response.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
