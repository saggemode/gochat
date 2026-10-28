package com.example.gochat.ui.marketplace

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
        title = "Payout account"

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
                text = "Add the bank account where your sales will be paid. This is required before you can receive payments for orders."
                textSize = 13f
            })
        }

        root.addView(spacer(pad))
        root.addView(TextView(this).apply { text = "Select your bank"; textSize = 13f })
        bankSpinner = Spinner(this)
        root.addView(bankSpinner)

        root.addView(spacer(pad))
        root.addView(TextView(this).apply { text = "Account number"; textSize = 13f })
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
            text = "Verify account"
            setOnClickListener { verify() }
        }
        root.addView(verifyBtn)

        saveBtn = Button(this).apply {
            text = "Save payout account"
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
            title = "Payout account"
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
                .setTitle("Add a payout account")
                .setMessage("You won't be able to receive payments for orders until you add the bank account to be paid into. Set it up now?")
                .setPositiveButton("Set up now", null)
                .setNegativeButton("Later") { _, _ -> finish() }
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
                "Current: ${current?.accountName} • ${current?.accountNumber}$bank"
            } else {
                "No payout account yet. Add one so your sales are paid to you."
            }

            val banksRes = repository.getPaystackBanks()
            progress.visibility = View.GONE
            if (banksRes.isFailure) {
                val notConfigured = banksRes.exceptionOrNull() is MarketplaceRepository.PaymentsNotConfigured
                paymentsConfigured = !notConfigured
                Toast.makeText(
                    this@PayoutAccountActivity,
                    if (notConfigured) "Payments are not enabled yet — you can add this later" else "Could not load banks",
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
            Toast.makeText(this, "Enter a valid account number and bank", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@PayoutAccountActivity, "Could not verify this account", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@PayoutAccountActivity, "Payout account saved", Toast.LENGTH_LONG).show()
                hasPayoutAccount = true
                if (required) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    loadData()
                }
            } else {
                saveBtn.isEnabled = true
                Toast.makeText(this@PayoutAccountActivity, "Could not save payout account", Toast.LENGTH_LONG).show()
            }
        }
    }
}
