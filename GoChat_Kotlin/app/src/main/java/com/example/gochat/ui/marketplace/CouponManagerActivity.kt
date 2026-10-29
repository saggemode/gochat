package com.example.gochat.ui.marketplace

import com.example.gochat.R

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.currency.CurrencyManager
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

/**
 * Seller-facing coupon generator. A store owner can mint discount codes that are
 * either store-wide or scoped to a single product, percentage- or fixed-amount,
 * optionally time-limited, and capped by total uses and uses-per-customer. The
 * backend derives the owning business from the auth token, so no store id is sent.
 */
@AndroidEntryPoint
class CouponManagerActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: MarketplaceRepository

    private var products: List<Product> = emptyList()
    private var expiryMillis: Long? = null
    private var storeCurrency: String? = null

    private lateinit var codeInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var valueInput: EditText
    private lateinit var valueHint: TextView
    private lateinit var minSpendInput: EditText
    private lateinit var maxUsesInput: EditText
    private lateinit var perUserInput: EditText
    private lateinit var scopeSpinner: Spinner
    private lateinit var expiryView: TextView
    private lateinit var saveBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.coupon_manager_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        // ── Create form ──────────────────────────────────────────────────────
        root.addView(sectionHeader(getString(R.string.coupon_create_header)))

        root.addView(label(getString(R.string.coupon_code_label)))
        val codeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        codeInput = EditText(this).apply {
            hint = getString(R.string.coupon_code_hint)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val generateBtn = Button(this).apply {
            text = getString(R.string.coupon_generate)
            setOnClickListener { codeInput.setText(randomCode()) }
        }
        codeRow.addView(codeInput)
        codeRow.addView(generateBtn)
        root.addView(codeRow)

        root.addView(label(getString(R.string.coupon_discount_type)))
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CouponManagerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.coupon_type_percentage), getString(R.string.coupon_type_fixed))
            )
        }
        root.addView(typeSpinner)

        root.addView(label(getString(R.string.coupon_value_label)))
        valueInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(valueInput)
        valueHint = TextView(this).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            text = getString(R.string.coupon_value_hint_pct)
        }
        root.addView(valueHint)
        typeSpinner.onItemSelected { pos ->
            valueHint.text = getString(
                if (pos == 0) R.string.coupon_value_hint_pct else R.string.coupon_value_hint_fixed
            )
        }

        root.addView(label(getString(R.string.coupon_min_spend_label)))
        minSpendInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("0")
        }
        root.addView(minSpendInput)

        root.addView(label(getString(R.string.coupon_max_uses_label)))
        maxUsesInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        root.addView(maxUsesInput)

        root.addView(label(getString(R.string.coupon_max_uses_per_user_label)))
        perUserInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        root.addView(perUserInput)

        root.addView(label(getString(R.string.coupon_scope_label)))
        scopeSpinner = Spinner(this)
        root.addView(scopeSpinner)

        root.addView(label(getString(R.string.coupon_expiry_label)))
        expiryView = TextView(this).apply {
            text = getString(R.string.coupon_expiry_none)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(expiryView)
        val expiryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pickExpiryBtn = Button(this).apply {
            text = getString(R.string.coupon_expiry_set)
            setOnClickListener { pickExpiry() }
        }
        val clearExpiryBtn = Button(this).apply {
            text = getString(R.string.coupon_expiry_clear)
            setOnClickListener {
                expiryMillis = null
                expiryView.text = getString(R.string.coupon_expiry_none)
            }
        }
        expiryRow.addView(pickExpiryBtn)
        expiryRow.addView(clearExpiryBtn)
        root.addView(expiryRow)

        saveBtn = Button(this).apply {
            text = getString(R.string.coupon_save)
            setOnClickListener { submit() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        root.addView(saveBtn)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) }
        }
        root.addView(progress)

        // ── Existing coupons ─────────────────────────────────────────────────
        root.addView(sectionHeader(getString(R.string.coupon_existing_header)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(24)
        })
        emptyView = TextView(this).apply {
            text = getString(R.string.coupon_empty)
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            textSize = 14f
        }
        root.addView(emptyView)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        loadProducts()
        loadCoupons()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(resources.getColor(R.color.gochat_text_primary, theme))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun randomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return "SAVE" + (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun pickExpiry() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val c = Calendar.getInstance().apply {
                    set(year, month, day, 23, 59, 59)
                    set(Calendar.MILLISECOND, 0)
                }
                expiryMillis = c.timeInMillis
                expiryView.text = getString(R.string.coupon_expiry_prefix, dateLabel(c))
            },
            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = now.timeInMillis
        }.show()
    }

    private fun dateLabel(c: Calendar): String =
        android.text.format.DateFormat.getMediumDateFormat(this).format(c.time)

    /** Converts the chosen local date (end of day) to an RFC3339 UTC timestamp. */
    private fun expiryRfc3339(): String {
        val ms = expiryMillis ?: return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(ms))
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            products = repository.getMyProducts().getOrDefault(emptyList())
            storeCurrency = products.firstOrNull()?.currency
            val names = mutableListOf(getString(R.string.coupon_scope_all))
            names.addAll(products.map { it.name })
            scopeSpinner.adapter = ArrayAdapter(
                this@CouponManagerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                names
            )
        }
    }

    private fun loadCoupons() {
        lifecycleScope.launch {
            val coupons = repository.getMyCoupons().getOrDefault(emptyList())
            renderCoupons(coupons)
        }
    }

    private fun renderCoupons(coupons: List<MarketplaceRepository.Coupon>) {
        listContainer.removeAllViews()
        emptyView.visibility = if (coupons.isEmpty()) View.VISIBLE else View.GONE
        for (c in coupons) listContainer.addView(couponRow(c))
    }

    private fun couponRow(c: MarketplaceRepository.Coupon): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }

        val discount = if (c.discountType == "fixed") {
            getString(R.string.coupon_off_fixed, CurrencyManager.formatPrice(c.discountValue, storeCurrency))
        } else {
            getString(R.string.coupon_off_pct, trimNum(c.discountValue))
        }
        row.addView(TextView(this).apply {
            text = "${c.code}  •  $discount"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.gochat_text_primary, theme))
        })

        val scope = if (c.productId.isBlank()) getString(R.string.coupon_item_scope_all)
                    else c.productName.ifBlank { getString(R.string.coupon_item_scope_all) }
        val uses = getString(
            R.string.coupon_item_uses,
            c.usedCount,
            if (c.maxUses <= 0) getString(R.string.coupon_item_unlimited) else c.maxUses.toString()
        )
        val expiry = if (c.expiresAt.isBlank()) getString(R.string.coupon_item_no_expiry)
                     else getString(R.string.coupon_item_expires, c.expiresAt.take(10))
        val minSpend = if (c.minSpend > 0)
            "  •  " + getString(R.string.coupon_min_spend_prefix, CurrencyManager.formatPrice(c.minSpend, storeCurrency))
        else ""

        row.addView(TextView(this).apply {
            text = "$scope  •  $uses  •  $expiry$minSpend"
            textSize = 12f
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            setPadding(0, dp(2), 0, 0)
        })
        if (!c.isActive) {
            row.addView(TextView(this).apply {
                text = getString(R.string.coupon_item_inactive)
                textSize = 11f
                setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            })
        }
        return row
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun submit() {
        val code = codeInput.text.toString().trim()
        if (code.isBlank()) {
            toast(getString(R.string.coupon_err_code)); return
        }
        val isPercent = typeSpinner.selectedItemPosition == 0
        val value = valueInput.text.toString().toDoubleOrNull() ?: 0.0
        if (value <= 0.0) {
            toast(getString(R.string.coupon_err_value)); return
        }
        if (isPercent && (value < 1 || value > 100)) {
            toast(getString(R.string.coupon_err_pct_range)); return
        }
        val minSpend = minSpendInput.text.toString().toDoubleOrNull() ?: 0.0
        val maxUses = maxUsesInput.text.toString().toIntOrNull() ?: 0
        val perUser = perUserInput.text.toString().toIntOrNull() ?: 0
        // Spinner index 0 == "all products"; otherwise it maps to products[index-1].
        val scopeIndex = scopeSpinner.selectedItemPosition
        val productId = if (scopeIndex <= 0) "" else products.getOrNull(scopeIndex - 1)?.id.orEmpty()

        setBusy(true)
        lifecycleScope.launch {
            val res = repository.createCoupon(
                code = code,
                discountType = if (isPercent) "percentage" else "fixed",
                discountValue = value,
                minSpend = minSpend,
                maxUses = maxUses,
                maxUsesPerUser = perUser,
                expiresAt = expiryRfc3339(),
                productId = productId
            )
            setBusy(false)
            res.onSuccess {
                toast(getString(R.string.coupon_created))
                codeInput.setText("")
                valueInput.setText("")
                expiryMillis = null
                expiryView.text = getString(R.string.coupon_expiry_none)
                loadCoupons()
            }.onFailure {
                toast(getString(R.string.coupon_create_failed))
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        saveBtn.isEnabled = !busy
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun Spinner.onItemSelected(cb: (Int) -> Unit) {
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = cb(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }
}
