package com.example.gochat.ui.marketplace

import com.example.gochat.R

import android.os.Bundle
import android.text.InputType
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
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.repository.MarketplaceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lets a seller add/update the bank account their marketplace earnings are paid
 * into. Under the hood this registers a Paystack subaccount; each sale is then
 * split so the seller is settled directly and the platform keeps its commission.
 * No banking credentials are collected — only the public account number, which
 * Paystack verifies and resolves to the owner's name.
 */
@AndroidEntryPoint
class PayoutAccountActivity : AppCompatActivity() {

    companion object {
        /** When true, the screen is shown as a required step (e.g. right after
         *  creating a store) and nudges the seller to finish before leaving. */
        const val EXTRA_REQUIRED = "required"
    }

    @Inject
    lateinit var repository: MarketplaceRepository

    private val required: Boolean by lazy { intent.getBooleanExtra(EXTRA_REQUIRED, false) }
    private var banks: List<MarketplaceRepository.Bank> = emptyList()
    private var resolvedName: String? = null
    private var hasPayoutAccount: Boolean = false
    private var paymentsConfigured: Boolean = true

    private lateinit var currentView: TextView
    private lateinit var bankSpinner: Spinner
    private lateinit var accountInput: EditText
    private lateinit var resolvedView: TextView
    private lateinit var verifyBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.payout_title)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        currentView = TextView(this).apply { textSize = 14f }
        root.addView(currentView)

        if (required) {
            root.addView(spacer(pad / 2))
            root.addView(TextView(this).apply {
                text = getString(R.string.payout_required_msg)
                textSize = 13f
            })
        }

        root.addView(spacer(pad))
        root.addView(TextView(this).apply { text = getString(R.string.payout_select_bank); textSize = 13f })
        bankSpinner = Spinner(this)
        root.addView(bankSpinner)

        root.addView(spacer(pad))
        root.addView(TextView(this).apply { text = getString(R.string.payout_account_number); textSize = 13f })
        accountInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "0123456789"
        }
        root.addView(accountInput)

        resolvedView = TextView(this).apply {
            textSize = 15f
            visibility = View.GONE
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(resolvedView)

        root.addView(spacer(pad))
        verifyBtn = Button(this).apply {
            text = getString(R.string.payout_verify)
            setOnClickListener { verify() }
        }
        root.addView(verifyBtn)

        saveBtn = Button(this).apply {
            text = getString(R.string.payout_save)
            isEnabled = false
            setOnClickListener { save() }
        }
        root.addView(saveBtn)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(root)
        }

        // Explicit toolbar with a visible back arrow (the app theme has no ActionBar).
        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0B141A"))
            title = getString(R.string.payout_title)
            setTitleTextColor(android.graphics.Color.WHITE)
            navigationIcon = androidx.core.content.ContextCompat.getDrawable(
                this@PayoutAccountActivity, com.example.gochat.R.drawable.ic_back
            )
            setNavigationOnClickListener { attemptExit() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(toolbar)
            addView(scroll)
        }
        setContentView(container)

        loadData()
    }

    override fun onSupportNavigateUp(): Boolean {
        attemptExit()
        return true
    }

    override fun onBackPressed() {
        attemptExit()
    }

    /** In required mode, nudge the seller to finish before leaving (unless the
     *  backend has no payment provider, in which case they genuinely can't yet). */
    private fun attemptExit() {
        if (required && !hasPayoutAccount && paymentsConfigured) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.payout_required_title))
                .setMessage(getString(R.string.payout_required_exit))
                .setPositiveButton(getString(R.string.payout_setup_now), null)
                .setNegativeButton(getString(R.string.payout_later)) { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun loadData() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val current = repository.getPayoutAccount().getOrNull()
            hasPayoutAccount = current?.exists == true
            currentView.text = if (hasPayoutAccount) {
                val bank = current?.bankName?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
                getString(R.string.payout_current, "${current?.accountName} • ${current?.accountNumber}$bank")
            } else {
                getString(R.string.payout_none)
            }

            val banksRes = repository.getPaystackBanks()
            progress.visibility = View.GONE
            if (banksRes.isFailure) {
                val notConfigured = banksRes.exceptionOrNull() is MarketplaceRepository.PaymentsNotConfigured
                paymentsConfigured = !notConfigured
                Toast.makeText(
                    this@PayoutAccountActivity,
                    if (notConfigured) getString(R.string.payout_not_enabled) else getString(R.string.payout_banks_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            banks = banksRes.getOrDefault(emptyList())
            bankSpinner.adapter = ArrayAdapter(
                this@PayoutAccountActivity,
                android.R.layout.simple_spinner_dropdown_item,
                banks.map { it.name }
            )
        }
    }

    private fun selectedBankCode(): String? = banks.getOrNull(bankSpinner.selectedItemPosition)?.code
    private fun selectedBankName(): String? = banks.getOrNull(bankSpinner.selectedItemPosition)?.name

    private fun verify() {
        val acct = accountInput.text.toString().trim()
        val bankCode = selectedBankCode()
        if (acct.length < 6 || bankCode == null) {
            Toast.makeText(this, getString(R.string.payout_invalid_input), Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            val res = repository.resolveAccount(acct, bankCode)
            progress.visibility = View.GONE
            if (res.isSuccess) {
                resolvedName = res.getOrNull()
                resolvedView.visibility = View.VISIBLE
                resolvedView.text = "✓ $resolvedName"
                saveBtn.isEnabled = true
            } else {
                resolvedView.visibility = View.GONE
                saveBtn.isEnabled = false
                Toast.makeText(this@PayoutAccountActivity, getString(R.string.payout_verify_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun save() {
        val acct = accountInput.text.toString().trim()
        val bankCode = selectedBankCode() ?: return
        progress.visibility = View.VISIBLE
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            val res = repository.createSellerSubaccount(bankCode, acct, resolvedName, selectedBankName())
            progress.visibility = View.GONE
            if (res.isSuccess) {
                Toast.makeText(this@PayoutAccountActivity, getString(R.string.payout_saved), Toast.LENGTH_LONG).show()
                hasPayoutAccount = true
                if (required) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    loadData()
                }
            } else {
                saveBtn.isEnabled = true
                Toast.makeText(this@PayoutAccountActivity, getString(R.string.payout_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
