package com.example.gochat.core.currency

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.TokenManager
import com.example.gochat.ui.auth.CountryData
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * CurrencyManager handles regional currency assignments, multi-currency conversion rates,
 * and currency formatting for marketplace products across countries and continents.
 */
object CurrencyManager {

    private const val TAG = "CurrencyManager"
    private const val PREFS_NAME = "gochat_currency_rates"

    // Default baseline rates relative to 1 USD
    private val DEFAULT_USD_RATES = mapOf(
        "USD" to 1.0,
        "NGN" to 1520.0,
        "GBP" to 0.77,
        "EUR" to 0.92,
        "KES" to 129.5,
        "GHS" to 15.6,
        "ZAR" to 18.1,
        "INR" to 83.9,
        "CAD" to 1.37,
        "AUD" to 1.51,
        "AED" to 3.67,
        "BRL" to 5.45,
        "JPY" to 154.0,
        "CNY" to 7.25,
        "EGP" to 48.5,
        "RWF" to 1350.0,
        "UGX" to 3700.0,
        "SAR" to 3.75
    )

    private val ratesInUsd = ConcurrentHashMap<String, Double>(DEFAULT_USD_RATES)
    private var tokenManager: TokenManager? = null
    private var prefs: SharedPreferences? = null

    fun init(context: Context, tm: TokenManager) {
        tokenManager = tm
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load cached rates from SharedPreferences
        prefs?.all?.forEach { (key, value) ->
            if (value is String) {
                value.toDoubleOrNull()?.let { rate ->
                    if (rate > 0) ratesInUsd[key.uppercase()] = rate
                }
            }
        }
    }

    fun getUserCountry(): String {
        return tokenManager?.userCountryCode?.ifBlank { null } ?: "NG"
    }

    fun getUserCurrency(): String {
        return tokenManager?.userCurrencyCode?.ifBlank { null } ?: "NGN"
    }

    fun getUserCurrencySymbol(): String {
        return tokenManager?.userCurrencySymbol?.ifBlank { null }
            ?: getSymbol(getUserCurrency())
    }

    fun getUserContinent(): String {
        return tokenManager?.userContinent?.ifBlank { null }
            ?: getContinentForCountry(getUserCountry())
    }

    fun setUserCountryAndCurrency(countryCode: String, currencyCode: String? = null) {
        val country = CountryData.getByCountryCode(countryCode)
        tokenManager?.setUserCountryAndCurrency(
            countryCode = country.code,
            currencyCode = currencyCode ?: country.currencyCode,
            currencySymbol = country.currencySymbol,
            continent = country.continent
        )
    }

    /**
     * Resolves the currency symbol for a 3-letter currency code (e.g. NGN -> ₦, USD -> $)
     */
    fun getSymbol(currencyCode: String): String {
        val curr = currencyCode.trim().uppercase()
        return when (curr) {
            "NGN" -> "₦"
            "USD" -> "$"
            "GBP" -> "£"
            "EUR" -> "€"
            "KES" -> "KSh "
            "GHS" -> "GH₵ "
            "ZAR" -> "R "
            "INR" -> "₹"
            "AED" -> "د.إ "
            "CAD" -> "C$"
            "AUD" -> "A$"
            "BRL" -> "R$ "
            "JPY", "CNY" -> "¥"
            "EGP" -> "E£ "
            "RWF" -> "RF "
            "UGX" -> "USh "
            "SAR" -> "﷼ "
            else -> CountryData.getByCurrencyCode(curr).currencySymbol
        }
    }

    fun getCountryForCurrency(currency: String): String {
        val curr = currency.trim().uppercase()
        return CountryData.getByCurrencyCode(curr).code
    }

    fun getContinentForCurrency(currency: String): String {
        val curr = currency.trim().uppercase()
        return CountryData.getByCurrencyCode(curr).continent
    }

    fun getContinentForCountry(countryCode: String): String {
        return CountryData.getByCountryCode(countryCode).continent
    }

    /**
     * Checks whether an amount needs conversion (disabled: prices remain exact).
     */
    fun isConverted(fromCurrency: String? = null, targetCurrency: String? = null): Boolean {
        return false
    }

    /**
     * Returns the exact product price without conversion.
     */
    fun convert(
        amount: Double,
        fromCurrency: String? = null,
        targetCurrency: String? = null
    ): Double {
        return amount
    }

    /**
     * Returns the formatted price with the assigned currency symbol (e.g. "₦200.00").
     */
    fun formatPrice(
        amount: Double,
        fromCurrency: String? = null,
        targetCurrency: String? = null
    ): String {
        val userCurr = getUserCurrency()
        val curr = if (!fromCurrency.isNullOrBlank() && !(fromCurrency.equals("USD", ignoreCase = true) && userCurr == "NGN")) {
            fromCurrency
        } else {
            userCurr
        }
        val symbol = getSymbol(curr)
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return "$symbol${format.format(amount)}"
    }

    /**
     * Returns the formatted price with currency symbol.
     */
    fun formatOriginalPrice(amount: Double, fromCurrency: String? = null): String {
        val userCurr = getUserCurrency()
        val curr = if (!fromCurrency.isNullOrBlank() && !(fromCurrency.equals("USD", ignoreCase = true) && userCurr == "NGN")) {
            fromCurrency
        } else {
            userCurr
        }
        val symbol = getSymbol(curr)
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return "$symbol${format.format(amount)}"
    }

    /**
     * Notes explaining conversion are disabled.
     */
    fun getOriginalPriceNote(
        amount: Double = 0.0,
        fromCurrency: String? = null,
        targetCurrency: String? = null
    ): String? = null

    /**
     * Rate quotes disabled.
     */
    fun getRateQuote(
        fromCurrency: String? = null,
        targetCurrency: String? = null
    ): String? = null

    /**
     * Syncs latest exchange rates with the backend `/api/v1/fx/rates` endpoint.
     */
    suspend fun syncRates(api: GoChatApiService) {
        try {
            val response = api.getFxRates("USD")
            if (response.isSuccessful) {
                val data = response.body() ?: return
                val ratesObj = data["rates"]?.jsonObject ?: return

                val editor = prefs?.edit()
                for ((curr, elem) in ratesObj) {
                    val rate = elem.jsonPrimitive.doubleOrNull
                    if (rate != null && rate > 0) {
                        val upper = curr.uppercase()
                        ratesInUsd[upper] = rate
                        editor?.putString(upper, rate.toString())
                    }
                }
                editor?.apply()
                Log.d(TAG, "Successfully synced FX rates: ${ratesInUsd.size} currencies available.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "FX sync skipped, using built-in rates: ${e.message}")
        }
    }
}
