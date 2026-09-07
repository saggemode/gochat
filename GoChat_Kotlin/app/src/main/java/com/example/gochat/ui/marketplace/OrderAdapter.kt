package com.example.gochat.ui.marketplace

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.model.Order
import com.example.gochat.data.model.OrderStatus
import com.example.gochat.databinding.ItemOrderBinding
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderAdapter(
    private val isSellerView: Boolean = false,
    private val onUpdateStatus: ((Order, OrderStatus) -> Unit)? = null
) : ListAdapter<Order, OrderAdapter.OrderViewHolder>(OrderDiffCallback()) {

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
            val orderNum = order.orderNumber.ifBlank { "ORD-${order.id.takeLast(6)}" }
            binding.tvOrderId.text = "Order #$orderNum"
            binding.tvOrderStatus.text = order.status.name
            binding.tvOrderDate.text = dateFormat.format(Date(order.createdAt))
            binding.tvTotalAmount.text = String.format(Locale.US, "$%.2f", order.totalAmount)

            updateStatusTracker(order.status)

            // Status Badge Styling
            when (order.status) {
                OrderStatus.PENDING -> {
                    binding.tvOrderStatus.setBackgroundColor(Color.parseColor("#F59E0B")) // Amber
                    binding.tvOrderStatus.setTextColor(Color.BLACK)
                }
                OrderStatus.PAID -> {
                    binding.tvOrderStatus.setBackgroundColor(Color.parseColor("#3B82F6")) // Blue
                    binding.tvOrderStatus.setTextColor(Color.WHITE)
                }
                OrderStatus.SHIPPED -> {
                    binding.tvOrderStatus.setBackgroundColor(Color.parseColor("#8B5CF6")) // Purple
                    binding.tvOrderStatus.setTextColor(Color.WHITE)
                }
                OrderStatus.DELIVERED -> {
                    binding.tvOrderStatus.setBackgroundColor(Color.parseColor("#00A884")) // Teal
                    binding.tvOrderStatus.setTextColor(Color.BLACK)
                }
                OrderStatus.CANCELLED, OrderStatus.REFUNDED -> {
                    binding.tvOrderStatus.setBackgroundColor(Color.parseColor("#EF4444")) // Red
                    binding.tvOrderStatus.setTextColor(Color.WHITE)
                }
                else -> {
                    binding.tvOrderStatus.setBackgroundColor(Color.parseColor("#374151"))
                    binding.tvOrderStatus.setTextColor(Color.WHITE)
                }
            }

            if (isSellerView) {
                binding.tvStoreOrBuyer.text = "Customer: ${order.buyerName.ifBlank { "Buyer" }}"
            } else {
                binding.tvStoreOrBuyer.text = "Store: ${order.storeName.ifBlank { "Official Store" }}"
            }

            val itemCount = if (order.items.isNotEmpty()) order.items.sumOf { it.quantity } else 1
            binding.tvItemsSummary.text = String.format(
                Locale.US,
                "%d item%s • %s",
                itemCount,
                if (itemCount > 1) "s" else "",
                order.shippingAddress?.ifBlank { "Lagos, Nigeria" } ?: "Lagos, Nigeria"
            )

            // Seller status updater
            if (isSellerView && order.status != OrderStatus.DELIVERED && order.status != OrderStatus.CANCELLED) {
                binding.btnUpdateStatus.visibility = View.VISIBLE
                val nextStatus = when (order.status) {
                    OrderStatus.PENDING -> OrderStatus.PAID
                    OrderStatus.PAID, OrderStatus.PROCESSING -> OrderStatus.SHIPPED
                    OrderStatus.SHIPPED -> OrderStatus.DELIVERED
                    else -> OrderStatus.DELIVERED
                }
                binding.btnUpdateStatus.text = "Advance Status: Mark as ${nextStatus.name}"
                binding.btnUpdateStatus.setOnClickListener {
                    onUpdateStatus?.invoke(order, nextStatus)
                }
            } else {
                binding.btnUpdateStatus.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                val context = binding.root.context
                val intent = Intent(context, OrderDetailsActivity::class.java).apply {
                    val orderJson = Json.encodeToString(Order.serializer(), order)
                    putExtra("order_json", orderJson)
                }
                context.startActivity(intent)
            }
        }


        private fun updateStatusTracker(status: OrderStatus) {
            val mutedColor = Color.parseColor("#374151")
            val activeColor = Color.parseColor("#00A884")

            binding.dotPending.backgroundTintList = ColorStateList.valueOf(activeColor)
            binding.dotPaid.backgroundTintList = ColorStateList.valueOf(if (status.ordinal >= OrderStatus.PAID.ordinal) activeColor else mutedColor)
            binding.dotShipped.backgroundTintList = ColorStateList.valueOf(if (status.ordinal >= OrderStatus.SHIPPED.ordinal) activeColor else mutedColor)
            binding.dotDelivered.backgroundTintList = ColorStateList.valueOf(if (status.ordinal >= OrderStatus.DELIVERED.ordinal) activeColor else mutedColor)
        }
    }

    class OrderDiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Order, newItem: Order): Boolean = oldItem == newItem
    }
}
