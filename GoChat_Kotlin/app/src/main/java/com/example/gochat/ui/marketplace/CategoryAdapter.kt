package com.example.gochat.ui.marketplace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.model.Category
import com.example.gochat.databinding.ItemCategoryBinding
import com.example.gochat.R

class CategoryAdapter(private val onClick: (Category) -> Unit) :
    ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    private var selectedCategoryId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(category: Category) {
            binding.tvCategoryName.text = category.name
            
            val isSelected = category.id == selectedCategoryId
            binding.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )

            binding.root.setOnClickListener {
                selectedCategoryId = category.id
                notifyDataSetChanged()
                onClick(category)
            }
        }
    }

    class CategoryDiffCallback : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean = oldItem == newItem
    }
}
