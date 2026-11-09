package com.example.gochat.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.core.ui.applyEdgeToEdge
import com.example.gochat.core.ui.applyStatusBarInset
import com.example.gochat.core.ui.applySystemBarInsets
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.databinding.ActivityChangeNumberBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WhatsApp-style "Change number". The account (user_id) is unchanged, so all chats,
 * groups and E2EE keys are preserved — only the phone number moves, after an OTP sent
 * to the NEW number proves the user controls it.
 */
@AndroidEntryPoint
class ChangeNumberActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangeNumberBinding

    @Inject lateinit var authRepo: AuthRepository

    private var pendingPhone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChangeNumberBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarChangeNumber.applyStatusBarInset()
        binding.root.applySystemBarInsets(applyTop = false, applyBottom = true)
        binding.toolbarChangeNumber.setNavigationOnClickListener { finish() }

        binding.btnSendCode.setOnClickListener { sendCode() }
        binding.btnConfirmChange.setOnClickListener { confirmChange() }
        binding.btnResend.setOnClickListener { requestCode(pendingPhone, resend = true) }
    }

    private fun sendCode() {
        val phone = binding.etNewPhone.text?.toString()?.trim().orEmpty()
        // Minimal validation: digits only, at least 7 (matches the server's threshold).
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 7) {
            binding.tilNewPhone.error = "Enter a valid phone number"
            return
        }
        binding.tilNewPhone.error = null
        requestCode(phone, resend = false)
    }

    private fun requestCode(phone: String, resend: Boolean) {
        if (phone.isBlank()) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.requestNumberChange(phone)
            setLoading(false)
            result.onSuccess {
                pendingPhone = phone
                binding.tvCodeSentTo.text = "Enter the 6-digit code sent to $phone"
                binding.layoutStep1.visibility = View.GONE
                binding.layoutStep2.visibility = View.VISIBLE
                if (resend) Toast.makeText(this@ChangeNumberActivity, "Code resent", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@ChangeNumberActivity, err.message ?: "Could not send code", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmChange() {
        val otp = binding.etOtp.text?.toString()?.trim().orEmpty()
        if (otp.length < 4) {
            binding.tilOtp.error = "Enter the code"
            return
        }
        binding.tilOtp.error = null
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.confirmNumberChange(pendingPhone, otp)
            setLoading(false)
            result.onSuccess {
                AlertDialog.Builder(this@ChangeNumberActivity)
                    .setTitle("Number changed")
                    .setMessage("Your number is now $pendingPhone. Your chats and account are unchanged. Contacts will see the new number once they have it saved.")
                    .setPositiveButton("Done") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }.onFailure { err ->
                Toast.makeText(this@ChangeNumberActivity, err.message ?: "Could not change number", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSendCode.isEnabled = !loading
        binding.btnConfirmChange.isEnabled = !loading
        binding.btnResend.isEnabled = !loading
    }
}
