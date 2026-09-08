package com.example.gochat.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.ProductRemoteKeys

@OptIn(ExperimentalPagingApi::class)
class ProductRemoteMediator(
    private val api: GoChatApiService,
    private val db: AppDatabase,
    private val repository: MarketplaceRepository,
    private val categoryId: String?,
    private val search: String?,
    private val sortBy: String?,
    private val isNearbyOnly: Boolean = false,
    private val isFollowingOnly: Boolean = false,
    private val userLat: Double = 6.46,
    private val userLng: Double = 3.40
) : RemoteMediator<Int, Product>() {



    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, Product>
    ): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> {
                val remoteKeys = getRemoteKeyClosestToPosition(state)
                remoteKeys?.nextKey?.minus(1) ?: 1
            }
            LoadType.PREPEND -> {
                val remoteKeys = getRemoteKeyForFirstItem(state)
                val prevKey = remoteKeys?.prevKey
                    ?: return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                prevKey
            }
            LoadType.APPEND -> {
                val remoteKeys = getRemoteKeyForLastItem(state)
                val nextKey = remoteKeys?.nextKey
                    ?: return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                nextKey
            }
        }

        try {
            val response = if (isFollowingOnly) {
                api.getFollowedProducts(page = page, limit = state.config.pageSize)
            } else {
                api.getProducts(
                    categoryId = if (categoryId == "all" || categoryId.isNullOrBlank()) null else categoryId,
                    search = search?.ifBlank { null },
                    sortBy = sortBy,
                    page = page,
                    limit = state.config.pageSize
                )
            }

            if (response.isSuccessful) {

                val data = response.body()
                val products = repository.parseProductsJson(data)
                val endOfPaginationReached = products.isEmpty()

                db.withTransaction {
                    if (loadType == LoadType.REFRESH) {
                        db.marketplaceDao().clearRemoteKeys()
                        db.marketplaceDao().clearProducts()
                    }
                    val prevKey = if (page == 1) null else page - 1
                    val nextKey = if (endOfPaginationReached) null else page + 1
                    val keys = products.map {
                        ProductRemoteKeys(productId = it.id, prevKey = prevKey, nextKey = nextKey)
                    }
                    db.marketplaceDao().insertAllRemoteKeys(keys)
                    db.marketplaceDao().insertProducts(products)
                }
                return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
            } else {
                return MediatorResult.Error(Exception("Failed to load products: ${response.code()}"))
            }
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }

    private suspend fun getRemoteKeyForLastItem(state: PagingState<Int, Product>): ProductRemoteKeys? {
        return state.pages.lastOrNull { it.data.isNotEmpty() }?.data?.lastOrNull()
            ?.let { product ->
                db.marketplaceDao().getRemoteKeysForProduct(product.id)
            }
    }

    private suspend fun getRemoteKeyForFirstItem(state: PagingState<Int, Product>): ProductRemoteKeys? {
        return state.pages.firstOrNull { it.data.isNotEmpty() }?.data?.firstOrNull()
            ?.let { product ->
                db.marketplaceDao().getRemoteKeysForProduct(product.id)
            }
    }

    private suspend fun getRemoteKeyClosestToPosition(state: PagingState<Int, Product>): ProductRemoteKeys? {
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.id?.let { productId ->
                db.marketplaceDao().getRemoteKeysForProduct(productId)
            }
        }
    }
}
