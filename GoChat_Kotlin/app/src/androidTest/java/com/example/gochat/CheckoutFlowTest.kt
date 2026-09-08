package com.example.gochat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gochat.ui.marketplace.CheckoutActivity
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class CheckoutFlowTest {

    @Test
    fun testCheckoutProcess() {
        // Start CheckoutActivity directly for this test
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, CheckoutActivity::class.java)
        val scenario = ActivityScenario.launch<CheckoutActivity>(intent)

        // 1. Verify initial state
        onView(withId(R.id.btnPlaceOrder)).check(matches(isDisplayed()))
        onView(withId(R.id.etAddress)).check(matches(isDisplayed()))

        // 2. Try to place order with empty address
        onView(withId(R.id.btnPlaceOrder)).perform(click())
        
        // 3. Enter address
        onView(withId(R.id.etAddress)).perform(typeText("123 Test Street, Lagos"), closeSoftKeyboard())

        // 4. Verify Place Order button is still there
        onView(withId(R.id.btnPlaceOrder)).check(matches(isEnabled()))

        scenario.close()
    }
}
