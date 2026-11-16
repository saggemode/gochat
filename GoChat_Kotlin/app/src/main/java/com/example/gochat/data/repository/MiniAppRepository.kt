package com.example.gochat.data.repository

import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.model.MiniApp
import com.example.gochat.data.model.MiniAppLaunch
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Mini-apps (WeChat-style mini-programs). Backed by the miniapp service. */
@Singleton
class MiniAppRepository @Inject constructor(
    private val api: GoChatApiService
) {

    suspend fun list(): Result<List<MiniApp>> = try {
        val resp = api.listMiniApps()
        if (resp.isSuccessful) Result.success(resp.body()?.miniapps ?: emptyList())
        else Result.failure(Exception("Failed to load mini-apps"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun launch(id: String): Result<MiniAppLaunch> = try {
        val resp = api.launchMiniApp(id, buildJsonObject {})
        if (resp.isSuccessful && resp.body() != null) Result.success(resp.body()!!)
        else Result.failure(Exception("Failed to launch"))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
