package com.example.gochat.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.MainActivity
import com.example.gochat.R
import com.example.gochat.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: AuthViewModel by viewModels()
    private lateinit var countryAdapter: CountryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If user already has a valid session, skip login directly to MainActivity
        if (viewModel.isUserLoggedIn) {
            navigateToMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCountryList()
        setupListeners()
        observeState()
    }

    private fun setupCountryList() {
        countryAdapter = CountryAdapter(CountryData.countries) { selectedCountry ->
            viewModel.setSelectedCountry(selectedCountry)
        }
        binding.rvCountries.layoutManager = LinearLayoutManager(this)
        binding.rvCountries.adapter = countryAdapter
    }

    private fun setupListeners() {
        with(binding) {
            // Country picker toggle
            layoutCountryButton.setOnClickListener {
                viewModel.toggleCountryPicker()
            }

            // Country search input
            etCountrySearch.doAfterTextChanged { text ->
                val query = text?.toString().orEmpty()
                viewModel.setCountrySearchQuery(query)
                countryAdapter.updateList(CountryData.filter(query))
            }

            // Phone input
            etPhoneNumber.doAfterTextChanged { text ->
                viewModel.setPhoneInput(text?.toString().orEmpty())
            }

            // Identifier input (email, phone, PIN)
            etIdentifier.doAfterTextChanged { text ->
                viewModel.setIdentifierInput(text?.toString().orEmpty())
            }

            // OTP code input
            etOtpCode.doAfterTextChanged { text ->
                val code = text?.toString().orEmpty()
                if (code != viewModel.uiState.value.otpCode) {
                    viewModel.setOtpCode(code)
                }
            }

            // Continue with Phone button (Register)
            btnContinuePhone.setOnClickListener {
                hideKeyboard()
                viewModel.submitRegister()
            }

            // Send Verification Code button (Sign In)
            btnSendCode.setOnClickListener {
                hideKeyboard()
                viewModel.submitLogin()
            }

            // Start Messaging Now button (OTP View)
            btnStartMessaging.setOnClickListener {
                hideKeyboard()
                viewModel.triggerNavigation()
            }

            // Toggle Auth Mode (Register <-> Sign In)
            tvToggleAuthMode.setOnClickListener {
                viewModel.toggleAuthMode()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUi(state)
                    }
                }
                launch {
                    viewModel.navigationEvent.collect {
                        navigateToMain()
                    }
                }
            }
        }
    }

    private fun renderUi(state: AuthUiState) {
        with(binding) {
            // Error banner
            if (state.errorMessage != null) {
                layoutErrorBanner.visibility = View.VISIBLE
                tvErrorMessage.text = state.errorMessage
            } else {
                layoutErrorBanner.visibility = View.GONE
            }

            // Loading state
            pbLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            btnContinuePhone.isEnabled = !state.isLoading
            btnSendCode.isEnabled = !state.isLoading

            // Country code button displays
            tvSelectedFlag.text = state.selectedCountry.flag
            tvSelectedDial.text = state.selectedCountry.dial

            // Country picker dropdown
            layoutCountryPickerDropdown.visibility =
                if (state.isCountryPickerOpen) View.VISIBLE else View.GONE

            if (state.showOtpView) {
                // ── OTP Verification Mode ──
                layoutPhoneForm.visibility = View.GONE
                layoutSignInForm.visibility = View.GONE
                layoutOtpForm.visibility = View.VISIBLE
                tvToggleAuthMode.visibility = View.GONE

                tvAuthTitle.text = if (state.isRegister) "Account Created!" else "Welcome Back!"
                tvAuthSubtitle.text = "Enter the 6-digit code sent to your device"

                ivOtpStatusIcon.setImageResource(
                    if (state.isRegister) R.drawable.ic_vpn_key else R.drawable.ic_email_verify
                )
                tvOtpHeader.text = if (state.isRegister) "Account Created!" else "Verify Your Identity"

                // Assigned username chip
                if (!state.assignedUsername.isNullOrBlank()) {
                    layoutAssignedUsername.visibility = View.VISIBLE
                    tvAssignedUsername.text = "Assigned Username: ${state.assignedUsername}"
                } else {
                    layoutAssignedUsername.visibility = View.GONE
                }

                // Generated PIN
                if (!state.generatedPin.isNullOrBlank()) {
                    tvGeneratedPin.visibility = View.VISIBLE
                    tvPinSubtitle.visibility = View.VISIBLE
                    tvGeneratedPin.text = "Your unique GOCHAT PIN: ${state.generatedPin}"
                } else {
                    tvGeneratedPin.visibility = View.GONE
                    tvPinSubtitle.visibility = View.GONE
                }

                // OTP Status Banner
                if (state.isOtpVerified || state.otpCode.length == 6) {
                    tvOtpDetectionStatus.text = "SMS OTP Verified! Redirecting..."
                    btnStartMessaging.text = "Verifying & Opening Chat..."
                } else {
                    tvOtpDetectionStatus.text = "Detecting SMS OTP code..."
                    btnStartMessaging.text = "Start Messaging Now"
                }

                tvOtpDestination.text = "Verification code sent to ${state.otpDestination}"

                // Sync OTP EditText if filled programmatically
                if (etOtpCode.text.toString() != state.otpCode) {
                    etOtpCode.setText(state.otpCode)
                    etOtpCode.setSelection(state.otpCode.length)
                }

            } else if (state.isRegister) {
                // ── Registration Mode ──
                layoutPhoneForm.visibility = View.VISIBLE
                layoutSignInForm.visibility = View.GONE
                layoutOtpForm.visibility = View.GONE
                tvToggleAuthMode.visibility = View.VISIBLE

                tvAuthTitle.text = "Get Started with GoChat"
                tvAuthSubtitle.text = "Instant passwordless phone sign-up with SMS OTP verification"

                setToggleText(
                    prefix = "Already have an account? ",
                    action = "Sign In"
                )

            } else {
                // ── Sign In Mode ──
                layoutPhoneForm.visibility = View.GONE
                layoutSignInForm.visibility = View.VISIBLE
                layoutOtpForm.visibility = View.GONE
                tvToggleAuthMode.visibility = View.VISIBLE

                tvAuthTitle.text = "Welcome Back to GoChat"
                tvAuthSubtitle.text = "Sign in with your Phone, Email, or PIN via instant SMS OTP"

                setToggleText(
                    prefix = "New to GoChat? ",
                    action = "Create Account"
                )
            }
        }
    }

    private fun setToggleText(prefix: String, action: String) {
        val fullText = prefix + action
        val spannable = SpannableString(fullText)
        val start = prefix.length
        val end = fullText.length

        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.gochat_text_muted)),
            0,
            start,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.gochat_emerald_light)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tvToggleAuthMode.text = spannable
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { view ->
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
