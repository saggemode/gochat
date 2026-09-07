package com.example.gochat.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.model.Product

class ProductPagingSource(
    private val api: GoChatApiService,
    private val repository: MarketplaceRepository,
    private val categoryId: String?,
    private val search: String?,
    private val sortBy: String?
) : PagingSource<Int, Product>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Product> {
        val position = params.key ?: 1
        return try {
            val response = api.getProducts(
                categoryId = if (categoryId == "all" || categoryId.isNullOrBlank()) null else categoryId,
                search = search?.ifBlank { null },
                sortBy = sortBy,
                page = position,
                limit = params.loadSize
            )
            
            if (response.isSuccessful) {
                val data = response.body()
                var products = repository.parseProductsJson(data)
                
                if (products.isEmpty() && position == 1) {
                    val local = repository.getLocalProducts()
                    products = repository.filterAndSortLocally(local, categoryId, search, sortBy)
                }
                
                LoadResult.Page(
                    data = products,
                    prevKey = if (position == 1) null else position - 1,
                    nextKey = if (products.isEmpty() || products.size < params.loadSize) null else position + 1
                )
            } else {
                if (position == 1) {
                    val local = repository.getLocalProducts()
                    val products = repository.filterAndSortLocally(local, categoryId, search, sortBy)
                    LoadResult.Page(
                        data = products,
                        prevKey = null,
                        nextKey = null
                    )
                } else {
                    LoadResult.Error(Exception("Failed to load products: ${response.code()}"))
                }
            }
        } catch (e: Exception) {
            if (position == 1) {
                val local = repository.getLocalProducts()
                val products = repository.filterAndSortLocally(local, categoryId, search, sortBy)
                LoadResult.Page(
                    data = products,
                    prevKey = null,
                    nextKey = null
                )
            } else {
                LoadResult.Error(e)
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Product>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }
}
