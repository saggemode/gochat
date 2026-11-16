package com.example.gochat.ui.wallet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.model.Wallet
import com.example.gochat.data.repository.WalletRepository
import com.example.gochat.databinding.ActivityWalletBinding
import com.example.gochat.databinding.ItemWalletLedgerBinding
import com.example.gochat.ui.marketplace.PaystackPaymentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * The in-app wallet: balance, top-up (via Paystack), and a transaction history.
 * Red packets are sent from the chat attachment menu; this screen is where the
 * money that funds them lives.
 */
@AndroidEntryPoint
class WalletActivity : AppCompatActivity() {

    @Inject
    lateinit var walletRepository: WalletRepository

    private lateinit var binding: ActivityWalletBinding
    private var currency: String = "NGN"
    private var pendingReference: String? = null

    private val paymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val ref = res.data?.getStringExtra(PaystackPaymentActivity.RESULT_REFERENCE) ?: pendingReference
        pendingReference = null
        if (res.resultCode == Activity.RESULT_OK && !ref.isNullOrBlank()) {
            verifyTopUp(ref)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWalletBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackWallet.setOnClickListener { finish() }
        binding.swipeWallet.setOnRefreshListener { load() }
        binding.btnTopUp.setOnClickListener { showTopUpDialog(null) }
        load()
    }

    private fun load() {
        binding.swipeWallet.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { walletRepository.getWallet() }
            binding.swipeWallet.isRefreshing = false
            result.onSuccess { render(it) }
                .onFailure { Toast.makeText(this@WalletActivity, it.message ?: "Could not load wallet", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun render(wallet: Wallet) {
        currency = wallet.currency
        binding.tvWalletBalance.text = formatMinor(wallet.balance, currency)

        binding.containerLedger.removeAllViews()
        if (wallet.ledger.isEmpty()) {
            binding.tvLedgerEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvLedgerEmpty.visibility = View.GONE
        wallet.ledger.forEach { entry ->
            val item = ItemWalletLedgerBinding.inflate(layoutInflater, binding.containerLedger, false)
            item.tvLedgerKind.text = kindLabel(entry.kind)
            item.tvLedgerDate.text = formatDate(entry.createdAt)
            val credit = entry.amount >= 0
            item.tvLedgerAmount.text = (if (credit) "+" else "−") + formatMinor(kotlin.math.abs(entry.amount), currency)
            item.tvLedgerAmount.setTextColor(if (credit) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            binding.containerLedger.addView(item.root)
        }
    }

    private fun showTopUpDialog(prefillEmail: String?) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Amount"
        }
        AlertDialog.Builder(this)
            .setTitle("Add money")
            .setMessage("Enter the amount to add to your wallet.")
            .setView(input)
            .setPositiveButton("Continue") { _, _ ->
                val major = input.text.toString().trim().toDoubleOrNull()
                if (major == null || major <= 0) {
                    Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val minor = Math.round(major * 100)
                chooseProviderThenTopUp(minor, prefillEmail)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lets the user pick which licensed provider to pay through. */
    private fun chooseProviderThenTopUp(amountMinor: Long, email: String?) {
        val labels = arrayOf("Paystack — Card, Bank, USSD", "Flutterwave")
        val providers = arrayOf("paystack", "flutterwave")
        AlertDialog.Builder(this)
            .setTitle("Pay with")
            .setItems(labels) { _, which -> startTopUp(amountMinor, email, providers[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTopUp(amountMinor: Long, email: String?, provider: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { walletRepository.topUpInit(amountMinor, email, provider) }
            result.onSuccess { init ->
                pendingReference = init.reference
                val intent = Intent(this@WalletActivity, PaystackPaymentActivity::class.java).apply {
                    putExtra(PaystackPaymentActivity.EXTRA_AUTHORIZATION_URL, init.authorizationUrl)
                    putExtra(PaystackPaymentActivity.EXTRA_REFERENCE, init.reference)
                }
                paymentLauncher.launch(intent)
            }.onFailure { err ->
                val msg = err.message.orEmpty()
                if (msg.contains("email", ignoreCase = true)) {
                    promptEmailThenTopUp(amountMinor, provider)
                } else {
                    Toast.makeText(this@WalletActivity, msg.ifBlank { "Could not start top-up" }, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Phone-signup users may have no email on file; providers need one. */
    private fun promptEmailThenTopUp(amountMinor: Long, provider: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = "you@example.com"
        }
        AlertDialog.Builder(this)
            .setTitle("Email for receipt")
            .setMessage("Paystack needs an email address for your payment.")
            .setView(input)
            .setPositiveButton("Continue") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isBlank() || !email.contains("@")) {
                    Toast.makeText(this, "Enter a valid email", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startTopUp(amountMinor, email, provider)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun verifyTopUp(reference: String) {
        binding.swipeWallet.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { walletRepository.topUpVerify(reference) }
            binding.swipeWallet.isRefreshing = false
            result.onSuccess { r ->
                if (r.paid) {
                    Toast.makeText(this@WalletActivity, "Added ${formatMinor(r.credited, currency)}", Toast.LENGTH_LONG).show()
                    load()
                } else {
                    Toast.makeText(this@WalletActivity, "Payment not completed", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(this@WalletActivity, "Could not confirm payment", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "topup" -> "Top-up"
        "redpacket_send" -> "Red packet sent"
        "redpacket_claim" -> "Red packet received"
        "withdrawal" -> "Withdrawal"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    companion object {
        private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val dateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

        /** Formats minor units (kobo/cents) for display with a currency symbol. */
        fun formatMinor(amountMinor: Long, currency: String): String {
            val symbol = when (currency.uppercase(Locale.US)) {
                "NGN" -> "₦"
                "USD" -> "$"
                "GHS" -> "GH₵"
                "KES" -> "KSh"
                "ZAR" -> "R"
                else -> "$currency "
            }
            return symbol + String.format(Locale.US, "%,.2f", amountMinor / 100.0)
        }
    }

    private fun formatDate(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            val trimmed = iso.substringBefore("Z").substringBefore("+").take(19)
            val d = isoParser.parse(trimmed)
            if (d != null) dateFormat.format(d) else iso
        } catch (_: Exception) {
            iso
        }
    }
}
