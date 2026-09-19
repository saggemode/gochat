package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.CatalogItemData
import com.example.gochat.databinding.ItemCatalogGridProductBinding
import java.util.Locale

class CatalogGridAdapter(
    private val products: List<CatalogItemData>,
    private val onProductClick: (CatalogItemData) -> Unit
) : RecyclerView.Adapter<CatalogGridAdapter.CatalogViewHolder>() {

    inner class CatalogViewHolder(val binding: ItemCatalogGridProductBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CatalogItemData) {
            binding.tvCatalogProductTitle.text = item.name.ifBlank { "Product" }
            binding.tvCatalogProductPrice.text = String.format(Locale.US, "$%.2f", item.price)

            MediaImageHelper.loadSafeImage(
                imageView = binding.ivCatalogProductImage,
                url = item.image,
                cornerRadiusDp = 8f
            )

            binding.root.setOnClickListener {
                onProductClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatalogViewHolder {
        val binding = ItemCatalogGridProductBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CatalogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CatalogViewHolder, position: Int) {
        holder.bind(products[position])
    }

    override fun getItemCount(): Int = products.size
}
