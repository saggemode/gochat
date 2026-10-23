package com.example.gochat.core.marketplace

import com.example.gochat.data.model.CartItem

/**
 * Pure cart calculations, extracted so they can be unit-tested without Android/DB dependencies.
 */
object CartMath {

    /** Sum of price × quantity across all items (items with no price count as 0). */
    fun total(items: List<CartItem>): Double =
        items.sumOf { (it.productPrice ?: 0.0) * it.quantity }

    /** Total number of units across all items. */
    fun itemCount(items: List<CartItem>): Int =
        items.sumOf { it.quantity }
}
