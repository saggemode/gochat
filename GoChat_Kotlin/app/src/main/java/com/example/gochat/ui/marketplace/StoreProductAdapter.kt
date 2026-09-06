package com.example.gochat.ui.marketplace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.databinding.ItemStoreProductBinding
import java.util.Locale

class StoreProductAdapter(
    private val onClick: (Product) -> Unit,
    private val onDelete: (Product) -> Unit
) : ListAdapter<Product, StoreProductAdapter.StoreProductViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreProductViewHolder {
        val binding = ItemStoreProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StoreProductViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoreProductViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StoreProductViewHolder(private val binding: ItemStoreProductBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            binding.tvProductName.text = product.displayTitle
            binding.tvProductPrice.text = String.format(Locale.US, "$%.2f", product.price)
            binding.tvProductStock.text = String.format(
                Locale.US,
                "Stock: %d • %s",
                product.stock,
                product.category.ifBlank { "General" }
            )

            MediaImageHelper.loadSafeImage(binding.ivProductImage, product.primaryImage, isCircle = true)

            binding.root.setOnClickListener { onClick(product) }
            binding.btnDeleteProduct.setOnClickListener { onDelete(product) }
        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
    }
}
