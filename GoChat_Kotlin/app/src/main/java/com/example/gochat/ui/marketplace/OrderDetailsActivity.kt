package com.example.gochat.ui.marketplace

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.gochat.data.model.Order

import com.example.gochat.data.model.OrderStatus
import com.example.gochat.databinding.ActivityOrderDetailsBinding
import com.example.gochat.databinding.LayoutTimelineStepBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class OrderDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrderDetailsBinding
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrderDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val orderJson = intent.getStringExtra("order_json") ?: return finish()
        val order = try {
            Json.decodeFromString<Order>(orderJson)
        } catch (e: Exception) {
            return finish()
        }

        setupToolbar()
        displayOrderDetails(order)
        setupTimeline(order.status)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun displayOrderDetails(order: Order) {
        val orderNum = order.orderNumber.ifBlank { "ORD-${order.id.takeLast(6)}" }
        binding.tvOrderNumber.text = "Order #$orderNum"
        binding.tvOrderDate.text = "Placed on ${dateFormat.format(Date(order.createdAt))}"
        binding.tvStoreName.text = order.storeName.ifBlank { "Official Store" }
        binding.tvTotalAmount.text = String.format(Locale.US, "Total: $%.2f", order.totalAmount)
        binding.tvShippingAddress.text = order.shippingAddress?.ifBlank { "Lagos, Nigeria" } ?: "Lagos, Nigeria"
    }

    private fun setupTimeline(status: OrderStatus) {
        // Step 1: Pending

        bindStep(
            binding.stepPending,
            "Order Placed",
            "Your order has been received and is waiting for payment.",
            true
        )

        // Step 2: Paid
        bindStep(
            binding.stepPaid,
            "Payment Confirmed",
            "Payment successfully received. Seller is preparing your items.",
            status.ordinal >= OrderStatus.PAID.ordinal
        )

        // Step 3: Shipped
        bindStep(
            binding.stepShipped,
            "Shipped",
            "Your order has been handed over to the courier.",
            status.ordinal >= OrderStatus.SHIPPED.ordinal
        )

        // Step 4: Delivered
        bindStep(
            binding.stepDelivered,
            "Delivered",
            "Order successfully delivered to your address.",
            status.ordinal >= OrderStatus.DELIVERED.ordinal,
            isLast = true
        )
    }

    private fun bindStep(
        stepBinding: LayoutTimelineStepBinding,
        title: String,
        description: String,
        isActive: Boolean,
        isLast: Boolean = false
    ) {
        val activeColor = Color.parseColor("#00A884")
        val mutedColor = Color.parseColor("#374151")

        stepBinding.tvTitle.text = title
        stepBinding.tvDescription.text = description
        
        stepBinding.dot.backgroundTintList = ColorStateList.valueOf(if (isActive) activeColor else mutedColor)
        stepBinding.line.backgroundTintList = ColorStateList.valueOf(if (isActive) activeColor else mutedColor)
        stepBinding.line.visibility = if (isLast) View.GONE else View.VISIBLE
        
        if (isActive) {
            stepBinding.tvTitle.setTextColor(Color.WHITE)
            stepBinding.tvDescription.setTextColor(Color.parseColor("#B0BEC5"))
        } else {
            stepBinding.tvTitle.setTextColor(mutedColor)
            stepBinding.tvDescription.setTextColor(mutedColor)
        }
    }
}
