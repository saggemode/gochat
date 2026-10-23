package com.example.gochat

import com.example.gochat.core.marketplace.CartMath
import com.example.gochat.data.model.CartItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CartMathTest {

    private fun item(price: Double?, qty: Int, id: String = "1") =
        CartItem(id = id, productId = "p-$id", quantity = qty, productPrice = price)

    @Test
    fun total_emptyCart_isZero() {
        assertEquals(0.0, CartMath.total(emptyList()), 0.0001)
    }

    @Test
    fun total_sumsPriceTimesQuantity() {
        val items = listOf(item(10.0, 2, "a"), item(5.5, 3, "b"))
        assertEquals(20.0 + 16.5, CartMath.total(items), 0.0001)
    }

    @Test
    fun total_nullPriceCountsAsZero() {
        val items = listOf(item(null, 4, "a"), item(2.0, 1, "b"))
        assertEquals(2.0, CartMath.total(items), 0.0001)
    }

    @Test
    fun itemCount_sumsQuantities() {
        val items = listOf(item(1.0, 2, "a"), item(1.0, 3, "b"))
        assertEquals(5, CartMath.itemCount(items))
    }
}
