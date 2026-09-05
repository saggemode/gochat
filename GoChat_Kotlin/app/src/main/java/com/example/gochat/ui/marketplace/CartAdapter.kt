package com.example.gochat.ui.marketplace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.CartItem
import com.example.gochat.databinding.ItemCartBinding

class CartAdapter(
    private val onRemove: (CartItem) -> Unit,
    private val onQuantityChange: (CartItem, Int) -> Unit
) : ListAdapter<CartItem, CartAdapter.CartViewHolder>(CartDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CartViewHolder {
        val binding = ItemCartBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CartViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CartViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CartViewHolder(private val binding: ItemCartBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CartItem) {
            val context = binding.root.context
            binding.tvProductName.text = item.productName ?: context.getString(R.string.placeholder_product_name)
            binding.tvProductPrice.text = context.getString(R.string.price_format_usd, (item.productPrice ?: 0.0).toString())
            binding.tvQuantity.text = item.quantity.toString()
            
            MediaImageHelper.loadSafeImage(binding.ivProductImage, item.productImage)

            binding.btnRemove.setOnClickListener { onRemove(item) }
            binding.btnAdd.setOnClickListener { onQuantityChange(item, item.quantity + 1) }
            // Assuming btnRemove is also used for decrementing if quantity > 1
            // But here I'll just use it to remove for simplicity as per the layout
        }
    }

    class CartDiffCallback : DiffUtil.ItemCallback<CartItem>() {
        override fun areItemsTheSame(oldItem: CartItem, newItem: CartItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CartItem, newItem: CartItem): Boolean = oldItem == newItem
    }
}
