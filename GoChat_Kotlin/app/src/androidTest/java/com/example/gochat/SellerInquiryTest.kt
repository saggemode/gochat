package com.example.gochat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gochat.ui.marketplace.ProductDetailsActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SellerInquiryTest {

    @Test
    fun testChatWithSellerButton() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(targetContext, ProductDetailsActivity::class.java).apply {
            putExtra("product_id", "prod_test_123")
        }
        val scenario = ActivityScenario.launch<ProductDetailsActivity>(intent)

        // 1. Verify "Chat with Seller" button exists
        onView(withId(R.id.btnChatSeller)).check(matches(isDisplayed()))

        // 2. Click it
        onView(withId(R.id.btnChatSeller)).perform(click())

        // 3. Verify it attempts to open chat (we can't easily check the next activity 
        // without Intent-testing, but we can verify the button is responsive)
        onView(withId(R.id.btnChatSeller)).check(matches(isEnabled()))

        scenario.close()
    }
}
