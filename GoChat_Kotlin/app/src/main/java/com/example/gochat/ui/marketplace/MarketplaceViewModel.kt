package com.example.gochat.ui.marketplace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.model.Category
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MarketplaceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MarketplaceRepository(application)

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val productsResult = repository.getProducts()
            val categoriesResult = repository.getCategories()

            if (productsResult.isSuccess) {
                _products.value = productsResult.getOrDefault(emptyList())
            } else {
                _error.value = productsResult.exceptionOrNull()?.message ?: "Failed to load products"
            }

            if (categoriesResult.isSuccess) {
                _categories.value = categoriesResult.getOrDefault(emptyList())
            }

            _isLoading.value = false
        }
    }

    fun filterByCategory(categoryId: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = if (categoryId == null) {
                repository.getProducts()
            } else {
                // For simplicity, we filter locally if the API doesn't support it directly, 
                // but let's assume we can fetch all and filter or the API handles it.
                // The repository doesn't have getProductsByCategory, so we filter locally for now.
                val all = repository.getProducts()
                if (all.isSuccess) {
                    Result.success(all.getOrThrow().filter { it.categoryId == categoryId })
                } else {
                    all
                }
            }

            if (result.isSuccess) {
                _products.value = result.getOrDefault(emptyList())
            } else {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to filter products"
            }
            _isLoading.value = false
        }
    }
}
