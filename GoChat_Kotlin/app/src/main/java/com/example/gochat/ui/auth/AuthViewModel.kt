package com.example.gochat.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class AuthUiState(
    val isRegister: Boolean = true,
    val selectedCountry: Country = CountryData.getCountry("NG"),
    val isCountryPickerOpen: Boolean = false,
    val countrySearchQuery: String = "",
    val phoneInput: String = "",
    val identifierInput: String = "",
    val showOtpView: Boolean = false,
    val assignedUsername: String? = null,
    val generatedPin: String? = null,
    val otpDestination: String = "",
    val otpCode: String = "",
    val isOtpVerified: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    application: Application,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigationEvent: SharedFlow<Unit> = _navigationEvent.asSharedFlow()

    private var autoOtpJob: Job? = null

    val isUserLoggedIn: Boolean get() = authRepository.isLoggedIn

    fun toggleAuthMode() {
        _uiState.update {
            it.copy(
                isRegister = !it.isRegister,
                errorMessage = null,
                isCountryPickerOpen = false
            )
        }
    }

    fun setSelectedCountry(country: Country) {
        _uiState.update {
            it.copy(
                selectedCountry = country,
                isCountryPickerOpen = false,
                countrySearchQuery = ""
            )
        }
    }

    fun toggleCountryPicker() {
        _uiState.update { it.copy(isCountryPickerOpen = !it.isCountryPickerOpen) }
    }

    fun setCountrySearchQuery(query: String) {
        _uiState.update { it.copy(countrySearchQuery = query) }
    }

    fun setPhoneInput(phone: String) {
        _uiState.update { it.copy(phoneInput = phone, errorMessage = null) }
    }

    fun setIdentifierInput(identifier: String) {
        _uiState.update { it.copy(identifierInput = identifier, errorMessage = null) }
    }

    fun setOtpCode(code: String) {
        _uiState.update { it.copy(otpCode = code) }
        if (code.length == 6) {
            triggerNavigation()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ── Registration Flow ─────────────────────────────────────────

    fun submitRegister() {
        val rawPhone = _uiState.value.phoneInput
        val cleanPhone = rawPhone.replace(Regex("[^\\d]"), "").replaceFirst(Regex("^0+"), "")

        if (cleanPhone.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid phone number") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val country = _uiState.value.selectedCountry
        val fullPhone = "${country.dial}$cleanPhone"

        viewModelScope.launch {
            val result = authRepository.register(
                phone = fullPhone,
                countryCode = country.code
            )

            result.fold(
                onSuccess = { responseJson ->
                    val userObj = responseJson["user"]?.jsonObject ?: responseJson
                    val assignedName = tokenManager.userDisplayName
                        ?: userObj["display_name"]?.jsonPrimitive?.contentOrNull
                        ?: userObj["displayName"]?.jsonPrimitive?.contentOrNull
                        ?: "User ${if (fullPhone.length > 4) fullPhone.takeLast(4) else fullPhone}"

                    val userId = tokenManager.userId
                        ?: userObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val pin = tokenManager.userPin
                        ?: userObj["pin"]?.jsonPrimitive?.contentOrNull
                        ?: if (userId.isNotEmpty()) userId.replace("-", "").take(6).uppercase() else "8492A1"

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showOtpView = true,
                            assignedUsername = assignedName,
                            generatedPin = pin,
                            otpDestination = fullPhone
                        )
                    }

                    startAutoOtpSimulation()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message?.replaceFirst("Exception: ", "") ?: "Registration failed"
                        )
                    }
                }
            )
        }
    }

    // ── Sign-in Flow ─────────────────────────────────────────────

    fun submitLogin() {
        val identifier = _uiState.value.identifierInput.trim()

        if (identifier.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please enter your phone number, email, or PIN") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = authRepository.login(identifier = identifier, password = "")

            result.fold(
                onSuccess = { responseJson ->
                    val userObj = responseJson["user"]?.jsonObject ?: responseJson
                    val assignedName = tokenManager.userDisplayName
                        ?: userObj["display_name"]?.jsonPrimitive?.contentOrNull
                        ?: userObj["displayName"]?.jsonPrimitive?.contentOrNull
                        ?: identifier

                    val userId = tokenManager.userId
                        ?: userObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val pin = tokenManager.userPin
                        ?: userObj["pin"]?.jsonPrimitive?.contentOrNull
                        ?: if (userId.isNotEmpty()) userId.replace("-", "").take(6).uppercase() else "8492A1"

                    val destination = tokenManager.userPhone
                        ?: userObj["phone"]?.jsonPrimitive?.contentOrNull
                        ?: identifier

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showOtpView = true,
                            assignedUsername = assignedName,
                            generatedPin = pin,
                            otpDestination = destination
                        )
                    }

                    startAutoOtpSimulation()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message?.replaceFirst("Exception: ", "") ?: "Sign in failed"
                        )
                    }
                }
            )
        }
    }

    // ── Simulated Auto-OTP Flow (mirrors Flutter implementation) ──

    private fun startAutoOtpSimulation() {
        autoOtpJob?.cancel()
        autoOtpJob = viewModelScope.launch {
            delay(700)
            _uiState.update {
                it.copy(
                    otpCode = "849201",
                    isOtpVerified = true
                )
            }
            delay(600)
            triggerNavigation()
        }
    }

    fun triggerNavigation() {
        _navigationEvent.tryEmit(Unit)
    }

    override fun onCleared() {
        super.onCleared()
        autoOtpJob?.cancel()
    }
}
