package com.example.gochat.ui.marketplace

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.databinding.ItemProductBinding
import java.util.Locale

class ProductAdapter(private val onClick: (Product) -> Unit) :
    PagingDataAdapter<Product, ProductAdapter.ProductViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val binding = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProductViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it) }
    }

    inner class ProductViewHolder(private val binding: ItemProductBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            binding.tvProductName.text = product.displayTitle
            binding.tvProductPrice.text = String.format(Locale.US, "$%.2f", product.price)

            if (product.hasDiscount) {
                binding.tvOriginalPrice.visibility = View.VISIBLE
                binding.tvOriginalPrice.paintFlags = binding.tvOriginalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.tvOriginalPrice.text = String.format(Locale.US, "$%.2f", product.originalPrice)

                binding.tvDiscountBadge.visibility = View.VISIBLE
                binding.tvDiscountBadge.text = String.format(Locale.US, "-%d%%", product.discountPercent)
            } else {
                binding.tvOriginalPrice.visibility = View.GONE
                binding.tvDiscountBadge.visibility = View.GONE
            }

            binding.tvRatingBadge.text = String.format(Locale.US, "%.1f ★", product.rating)
            binding.tvStoreName.text = product.storeName
            binding.ivVerifiedBadge.visibility = if (product.isVerifiedSeller) View.VISIBLE else View.GONE

            MediaImageHelper.loadSafeImage(binding.ivProductImage, product.primaryImage, thumbnailWidth = 400)

            binding.root.setOnClickListener { onClick(product) }

        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean = oldItem == newItem
    }
}
