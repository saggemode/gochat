package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.BottomSheetProductPickerBinding
import com.example.gochat.databinding.ItemProductPickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ProductPickerBottomSheet(
    private val onProductPicked: (Product) -> Unit
) : BottomSheetDialogFragment() {

    @Inject
    lateinit var repository: MarketplaceRepository

    private var _binding: BottomSheetProductPickerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ProductPickerAdapter
    private var allProducts: List<Product> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetProductPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadProducts()
    }

    private fun setupUI() {
        adapter = ProductPickerAdapter { product ->
            dismiss()
            onProductPicked(product)
        }

        binding.rvProductsPicker.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProductsPicker.adapter = adapter

        binding.btnClosePicker.setOnClickListener {
            dismiss()
        }

        binding.etSearchProducts.doAfterTextChanged { text ->
            filterProducts(text?.toString().orEmpty())
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            binding.progressBarPicker.visibility = View.VISIBLE
            // Load products from local DB (or fallback)
            val local = repository.getLocalProducts()
            binding.progressBarPicker.visibility = View.GONE

            if (local.isNotEmpty()) {
                allProducts = local
                adapter.submitList(local)
                binding.tvEmptyProducts.visibility = View.GONE
            } else {
                // If local DB is empty, fetch fresh from server
                val result = repository.refreshProducts()
                val refreshed = repository.getLocalProducts()
                allProducts = refreshed
                adapter.submitList(refreshed)
                binding.tvEmptyProducts.visibility = if (refreshed.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun filterProducts(query: String) {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        val filtered = if (trimmed.isEmpty()) {
            allProducts
        } else {
            allProducts.filter {
                it.displayTitle.lowercase(Locale.ROOT).contains(trimmed) ||
                        it.storeName.lowercase(Locale.ROOT).contains(trimmed) ||
                        it.description.lowercase(Locale.ROOT).contains(trimmed) ||
                        it.category.lowercase(Locale.ROOT).contains(trimmed)
            }
        }
        adapter.submitList(filtered)
        binding.tvEmptyProducts.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ProductPickerAdapter(
        private val onSendClicked: (Product) -> Unit
    ) : ListAdapter<Product, ProductPickerAdapter.ViewHolder>(DiffCallback) {

        inner class ViewHolder(private val b: ItemProductPickerBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(item: Product) {
                b.tvProductTitle.text = item.displayTitle
                b.tvProductPrice.text = String.format(Locale.US, "$%.2f", item.price)
                b.tvStoreName.text = item.storeName.ifBlank { "Official Store" }

                MediaImageHelper.loadSafeImage(
                    imageView = b.ivProductImage,
                    url = item.primaryImage,
                    cornerRadiusDp = 8f
                )

                b.btnSendProduct.setOnClickListener {
                    onSendClicked(item)
                }

                b.root.setOnClickListener {
                    onSendClicked(item)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProductPickerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            val DiffCallback = object : DiffUtil.ItemCallback<Product>() {
                override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean =
                    oldItem == newItem
            }
        }
    }
}
