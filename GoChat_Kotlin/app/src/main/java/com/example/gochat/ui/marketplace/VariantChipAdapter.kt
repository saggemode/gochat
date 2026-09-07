package com.example.gochat.ui.marketplace

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.data.model.ProductVariant
import com.example.gochat.databinding.ItemVariantChipBinding
import java.util.Locale

class VariantChipAdapter(
    private val onVariantSelected: (ProductVariant) -> Unit
) : RecyclerView.Adapter<VariantChipAdapter.VariantViewHolder>() {

    private val variants = mutableListOf<ProductVariant>()
    private var selectedIndex = -1

    fun submitList(newVariants: List<ProductVariant>) {
        variants.clear()
        variants.addAll(newVariants)
        selectedIndex = if (variants.isNotEmpty()) 0 else -1
        if (selectedIndex != -1) onVariantSelected(variants[selectedIndex])
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VariantViewHolder {
        val binding = ItemVariantChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VariantViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VariantViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount(): Int = variants.size

    inner class VariantViewHolder(private val binding: ItemVariantChipBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int) {
            val variant = variants[position]
            binding.tvVariantTitle.text = variant.title
            
            if (variant.priceOverride > 0) {
                binding.tvVariantPrice.text = String.format(Locale.US, "+$%.2f", variant.priceOverride)
            } else {
                binding.tvVariantPrice.text = "Default Price"
            }

            val isSelected = position == selectedIndex
            binding.cardVariant.strokeColor = if (isSelected) Color.parseColor("#00A884") else Color.parseColor("#1F2C33")
            binding.cardVariant.setCardBackgroundColor(if (isSelected) Color.parseColor("#062E2A") else Color.parseColor("#121B22"))

            binding.root.setOnClickListener {
                val prev = selectedIndex
                selectedIndex = adapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedIndex)
                onVariantSelected(variant)
            }
        }
    }
}
