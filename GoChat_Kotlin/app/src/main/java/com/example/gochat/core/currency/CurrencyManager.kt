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
     * Checks whether an amount in [fromCurrency] needs conversion to [targetCurrency].
     */
    fun isConverted(fromCurrency: String, targetCurrency: String = getUserCurrency()): Boolean {
        return !fromCurrency.trim().equals(targetCurrency.trim(), ignoreCase = true)
    }

    /**
     * Converts an amount from [fromCurrency] to [targetCurrency] using exchange rates.
     */
    fun convert(
        amount: Double,
        fromCurrency: String,
        targetCurrency: String = getUserCurrency()
    ): Double {
        if (!isConverted(fromCurrency, targetCurrency) || amount <= 0.0) {
            return amount
        }

        val from = fromCurrency.trim().uppercase()
        val to = targetCurrency.trim().uppercase()

        val fromUsdRate = ratesInUsd[from] ?: DEFAULT_USD_RATES[from] ?: 1.0
        val toUsdRate = ratesInUsd[to] ?: DEFAULT_USD_RATES[to] ?: 1.0

        if (fromUsdRate <= 0.0) return amount

        // Calculate: amount in USD = amount / fromUsdRate
        val amountInUsd = amount / fromUsdRate
        // Converted amount = amountInUsd * toUsdRate
        return amountInUsd * toUsdRate
    }

    /**
     * Returns the formatted price with the assigned currency symbol (e.g. "₦30,400.00" or "$20.00").
     */
    fun formatPrice(
        amount: Double,
        fromCurrency: String,
        targetCurrency: String = getUserCurrency()
    ): String {
        val target = targetCurrency.trim().uppercase()
        val converted = convert(amount, fromCurrency, target)
        val symbol = getSymbol(target)

        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return "$symbol${format.format(converted)}"
    }

    /**
     * Returns the formatted price in the original seller currency (e.g. "$20.00 USD").
     */
    fun formatOriginalPrice(amount: Double, fromCurrency: String): String {
        val curr = fromCurrency.trim().uppercase()
        val symbol = getSymbol(curr)
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return "$symbol${format.format(amount)} $curr"
    }

    /**
     * Returns a note explaining the conversion, or null if the currency is already local.
     * E.g. "orig. $20.00 USD"
     */
    fun getOriginalPriceNote(
        amount: Double,
        fromCurrency: String,
        targetCurrency: String = getUserCurrency()
    ): String? {
        if (!isConverted(fromCurrency, targetCurrency)) return null
        return "orig. ${formatOriginalPrice(amount, fromCurrency)}"
    }

    /**
     * Returns an exchange rate quote string, e.g. "1 USD = ₦1,520.00"
     */
    fun getRateQuote(
        fromCurrency: String,
        targetCurrency: String = getUserCurrency()
    ): String? {
        if (!isConverted(fromCurrency, targetCurrency)) return null
        val unitConverted = convert(1.0, fromCurrency, targetCurrency)
        val target = targetCurrency.trim().uppercase()
        val symbol = getSymbol(target)
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
        return "1 ${fromCurrency.uppercase()} = $symbol${format.format(unitConverted)}"
    }

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
