package com.example.gochat.ui.auth

data class Country(
    val name: String,
    val code: String,
    val dial: String,
    val flag: String,
    val currencyCode: String = "USD",
    val currencySymbol: String = "$",
    val continent: String = "North America"
)

object CountryData {
    val countries = listOf(
        Country(name = "Nigeria", code = "NG", dial = "+234", flag = "🇳🇬", currencyCode = "NGN", currencySymbol = "₦", continent = "Africa"),
        Country(name = "Ghana", code = "GH", dial = "+233", flag = "🇬🇭", currencyCode = "GHS", currencySymbol = "GH₵", continent = "Africa"),
        Country(name = "Kenya", code = "KE", dial = "+254", flag = "🇰🇪", currencyCode = "KES", currencySymbol = "KSh", continent = "Africa"),
        Country(name = "South Africa", code = "ZA", dial = "+27", flag = "🇿🇦", currencyCode = "ZAR", currencySymbol = "R", continent = "Africa"),
        Country(name = "Egypt", code = "EG", dial = "+20", flag = "🇪🇬", currencyCode = "EGP", currencySymbol = "E£", continent = "Africa"),
        Country(name = "Rwanda", code = "RW", dial = "+250", flag = "🇷🇼", currencyCode = "RWF", currencySymbol = "RF", continent = "Africa"),
        Country(name = "Uganda", code = "UG", dial = "+256", flag = "🇺🇬", currencyCode = "UGX", currencySymbol = "USh", continent = "Africa"),
        Country(name = "United States", code = "US", dial = "+1", flag = "🇺🇸", currencyCode = "USD", currencySymbol = "$", continent = "North America"),
        Country(name = "Canada", code = "CA", dial = "+1", flag = "🇨🇦", currencyCode = "CAD", currencySymbol = "C$", continent = "North America"),
        Country(name = "United Kingdom", code = "GB", dial = "+44", flag = "🇬🇧", currencyCode = "GBP", currencySymbol = "£", continent = "Europe"),
        Country(name = "France", code = "FR", dial = "+33", flag = "🇫🇷", currencyCode = "EUR", currencySymbol = "€", continent = "Europe"),
        Country(name = "Germany", code = "DE", dial = "+49", flag = "🇩🇪", currencyCode = "EUR", currencySymbol = "€", continent = "Europe"),
        Country(name = "India", code = "IN", dial = "+91", flag = "🇮🇳", currencyCode = "INR", currencySymbol = "₹", continent = "Asia"),
        Country(name = "United Arab Emirates", code = "AE", dial = "+971", flag = "🇦🇪", currencyCode = "AED", currencySymbol = "د.إ", continent = "Asia"),
        Country(name = "Australia", code = "AU", dial = "+61", flag = "🇦🇺", currencyCode = "AUD", currencySymbol = "A$", continent = "Oceania"),
        Country(name = "Brazil", code = "BR", dial = "+55", flag = "🇧🇷", currencyCode = "BRL", currencySymbol = "R$", continent = "South America"),
        Country(name = "Japan", code = "JP", dial = "+81", flag = "🇯🇵", currencyCode = "JPY", currencySymbol = "¥", continent = "Asia"),
        Country(name = "China", code = "CN", dial = "+86", flag = "🇨🇳", currencyCode = "CNY", currencySymbol = "¥", continent = "Asia")
    )

    fun getCountry(code: String): Country {
        return countries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
            ?: countries.first { it.code == "NG" }
    }

    fun getByCountryCode(code: String): Country = getCountry(code)

    fun getByCurrencyCode(currency: String): Country {
        val curr = currency.trim().uppercase()
        return countries.firstOrNull { it.currencyCode.equals(curr, ignoreCase = true) }
            ?: when (curr) {
                "EUR" -> countries.first { it.code == "DE" }
                "NGN" -> countries.first { it.code == "NG" }
                "GBP" -> countries.first { it.code == "GB" }
                else -> countries.first { it.code == "US" }
            }
    }

    fun getByPhone(phone: String): Country {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        val normalized = if (!clean.startsWith("+") && clean.startsWith("0")) {
            "+234" + clean.removePrefix("0")
        } else clean

        for (c in countries.sortedByDescending { it.dial.length }) {
            if (normalized.startsWith(c.dial) || normalized.removePrefix("+").startsWith(c.dial.removePrefix("+"))) {
                return c
            }
        }
        return countries.first { it.code == "NG" }
    }

    fun filter(query: String): List<Country> {
        if (query.isBlank()) return countries
        val q = query.trim().lowercase()
        return countries.filter {
            it.name.lowercase().contains(q) ||
            it.dial.contains(q) ||
            it.code.lowercase().contains(q) ||
            it.currencyCode.lowercase().contains(q) ||
            it.continent.lowercase().contains(q)
        }
    }
}
