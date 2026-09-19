package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.databinding.ItemSellerCatalogProductBinding
import java.util.Locale

class SellerCatalogAdapter(
    private val onInquireClicked: (Product) -> Unit
) : ListAdapter<Product, SellerCatalogAdapter.ViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSellerCatalogProductBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSellerCatalogProductBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            binding.tvCatalogProductTitle.text = product.displayTitle
            binding.tvCatalogProductPrice.text = String.format(Locale.US, "$%.2f", product.price)

            MediaImageHelper.loadSafeImage(
                imageView = binding.ivCatalogProductImage,
                url = product.primaryImage,
                cornerRadiusDp = 12f
            )

            binding.btnInquireInChat.setOnClickListener {
                onInquireClicked(product)
            }

            binding.root.setOnClickListener {
                onInquireClicked(product)
            }
        }
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean =
            oldItem == newItem
    }
}
