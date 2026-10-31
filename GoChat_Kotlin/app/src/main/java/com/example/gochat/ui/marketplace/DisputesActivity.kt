package com.example.gochat.ui.marketplace

import com.example.gochat.R

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.repository.MarketplaceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Seller view of disputes buyers have raised against the store. Open disputes can
 * be resolved or rejected with a note; the buyer is notified of the outcome.
 * Issuing an actual refund stays on the order's refund action.
 */
@AndroidEntryPoint
class DisputesActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: MarketplaceRepository

    private lateinit var progress: ProgressBar
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.disputes_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16) }
        }
        root.addView(progress)
        emptyView = TextView(this).apply {
            text = getString(R.string.disputes_empty)
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
            val disputes = repository.getStoreDisputes().getOrDefault(emptyList())
            progress.visibility = View.GONE
            listContainer.removeAllViews()
            emptyView.visibility = if (disputes.isEmpty()) View.VISIBLE else View.GONE
            for (d in disputes) listContainer.addView(disputeRow(d))
        }
    }

    private fun disputeRow(d: MarketplaceRepository.Dispute): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setBackgroundResource(R.drawable.bg_card_dark)
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.disputes_order_reason, d.orderNumber.ifBlank { d.orderId.take(8) }, d.reason)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.gochat_text_primary, theme))
        })
        if (d.buyerName.isNotBlank() || d.createdAt.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = listOf(d.buyerName, d.createdAt.take(10)).filter { it.isNotBlank() }.joinToString("  •  ")
                textSize = 12f
                setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            })
        }
        if (d.details.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = d.details
                textSize = 13f
                setTextColor(resources.getColor(R.color.gochat_text_primary, theme))
                setPadding(0, dp(4), 0, 0)
            })
        }

        if (d.status == "open") {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            actions.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.disputes_resolve)
                setOnClickListener { resolveDialog(d, "resolved") }
            })
            actions.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.disputes_reject)
                setOnClickListener { resolveDialog(d, "rejected") }
            })
            row.addView(actions)
        } else {
            row.addView(TextView(this).apply {
                val label = if (d.status == "rejected") getString(R.string.disputes_status_rejected)
                            else getString(R.string.disputes_status_resolved)
                text = if (d.resolution.isNotBlank()) "$label — ${d.resolution}" else label
                textSize = 12f
                setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
                setPadding(0, dp(4), 0, 0)
            })
        }
        return row
    }

    private fun resolveDialog(d: MarketplaceRepository.Dispute, action: String) {
        val note = EditText(this).apply {
            hint = getString(R.string.disputes_note_hint)
            val pad = dp(16)
            setPadding(pad, dp(8), pad, 0)
        }
        val titleRes = if (action == "rejected") R.string.disputes_reject else R.string.disputes_resolve
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(note)
            .setNegativeButton(getString(R.string.common_no), null)
            .setPositiveButton(getString(R.string.dispute_submit)) { _, _ ->
                lifecycleScope.launch {
                    val res = repository.resolveDispute(d.id, action, note.text.toString().trim())
                    Toast.makeText(
                        this@DisputesActivity,
                        if (res.isSuccess) getString(R.string.disputes_updated)
                        else getString(R.string.disputes_update_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (res.isSuccess) load()
                }
            }
            .show()
    }
}
