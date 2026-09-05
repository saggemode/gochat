package com.example.gochat.ui.marketplace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.R
import com.example.gochat.data.model.Order
import com.example.gochat.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.*

class OrderAdapter : ListAdapter<Order, OrderAdapter.OrderViewHolder>(OrderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OrderViewHolder(private val binding: ItemOrderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        fun bind(order: Order) {
            val context = binding.tvOrderId.context
            binding.tvOrderId.text = context.getString(R.string.order_id_format, order.id.takeLast(8))
            binding.tvOrderStatus.text = order.status.name
            binding.tvOrderDate.text = dateFormat.format(Date(order.createdAt))
            binding.tvTotalAmount.text = context.getString(R.string.price_format_usd, order.totalAmount.toString())
        }
    }

    class OrderDiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Order, newItem: Order): Boolean = oldItem == newItem
    }
}
