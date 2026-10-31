package com.example.gochat.ui.marketplace

import com.example.gochat.R

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.currency.CurrencyManager
import com.example.gochat.data.repository.MarketplaceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Seller payout statement: a running total of gross sales, the platform
 * commission, and net earnings, plus a per-order breakdown. Earnings are derived
 * from the seller's paid orders; with Paystack subaccount splits the net settles
 * directly to the seller at payment time, so paid rows show as settled.
 */
@AndroidEntryPoint
class PayoutLedgerActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: MarketplaceRepository

    private var currency: String? = null

    private lateinit var progress: ProgressBar
    private lateinit var summaryBox: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.payout_ledger_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        summaryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_card_dark)
        }
        root.addView(summaryBox)

        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16) }
        }
        root.addView(progress)

        root.addView(sectionHeader(getString(R.string.payout_ledger_orders_header)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(20)
        })
        emptyView = TextView(this).apply {
            text = getString(R.string.payout_ledger_empty)
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            textSize = 14f
            visibility = View.GONE
        }
        root.addView(emptyView)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun load() {
        lifecycleScope.launch {
            val res = repository.getPayoutLedger()
            progress.visibility = View.GONE
            res.onSuccess { render(it) }.onFailure {
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.payout_ledger_failed)
            }
        }
    }

    private fun render(l: MarketplaceRepository.PayoutLedger) {
        currency = null // formatPrice falls back to the user's currency
        summaryBox.removeAllViews()
        summaryBox.addView(summaryRow(getString(R.string.payout_ledger_net), money(l.net), bold = true, accent = true))
        summaryBox.addView(summaryRow(getString(R.string.payout_ledger_gross), money(l.gross)))
        summaryBox.addView(summaryRow(
            getString(R.string.payout_ledger_commission, trimPct(l.commissionPercent)),
            "-" + money(l.commission)
        ))
        summaryBox.addView(summaryRow(getString(R.string.payout_ledger_order_count), l.orders.toString()))

        listContainer.removeAllViews()
        emptyView.visibility = if (l.entries.isEmpty()) View.VISIBLE else View.GONE
        for (e in l.entries) listContainer.addView(entryRow(e))
    }

    private fun entryRow(e: MarketplaceRepository.LedgerEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setBackgroundResource(R.drawable.bg_card_dark)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = e.orderNumber.ifBlank { getString(R.string.payout_ledger_order_fallback) }
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.gochat_text_primary, theme))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = money(e.net)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.whatsapp_teal_light, theme))
        })
        row.addView(head)
        row.addView(TextView(this).apply {
            text = getString(R.string.payout_ledger_entry_meta, money(e.gross), money(e.commission)) +
                "  •  " + (if (e.settled) getString(R.string.payout_ledger_settled) else e.status)
            textSize = 12f
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            setPadding(0, dp(2), 0, 0)
        })
        if (e.createdAt.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = e.createdAt.take(10)
                textSize = 11f
                setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            })
        }
        return row
    }

    private fun summaryRow(label: String, value: String, bold: Boolean = false, accent: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = if (bold) 15f else 13f
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = if (bold) 17f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                resources.getColor(
                    if (accent) R.color.whatsapp_teal_light else R.color.gochat_text_primary, theme
                )
            )
        })
        return row
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(resources.getColor(R.color.gochat_text_primary, theme))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun money(v: Double) = CurrencyManager.formatPrice(v, currency)
    private fun trimPct(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
