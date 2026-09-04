package com.example.gochat.ui.auth

data class Country(
    val name: String,
    val code: String,
    val dial: String,
    val flag: String
)

object CountryData {
    val countries = listOf(
        Country(name = "Australia", code = "AU", dial = "+61", flag = "🇦🇺"),
        Country(name = "Brazil", code = "BR", dial = "+55", flag = "🇧🇷"),
        Country(name = "Canada", code = "CA", dial = "+1", flag = "🇨🇦"),
        Country(name = "France", code = "FR", dial = "+33", flag = "🇫🇷"),
        Country(name = "Germany", code = "DE", dial = "+49", flag = "🇩🇪"),
        Country(name = "Ghana", code = "GH", dial = "+233", flag = "🇬🇭"),
        Country(name = "India", code = "IN", dial = "+91", flag = "🇮🇳"),
        Country(name = "Kenya", code = "KE", dial = "+254", flag = "🇰🇪"),
        Country(name = "Nigeria", code = "NG", dial = "+234", flag = "🇳🇬"),
        Country(name = "South Africa", code = "ZA", dial = "+27", flag = "🇿🇦"),
        Country(name = "United Arab Emirates", code = "AE", dial = "+971", flag = "🇦🇪"),
        Country(name = "United Kingdom", code = "GB", dial = "+44", flag = "🇬🇧"),
        Country(name = "United States", code = "US", dial = "+1", flag = "🇺🇸")
    )

    fun getCountry(code: String): Country {
        return countries.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: countries.first { it.code == "NG" }
    }

    fun filter(query: String): List<Country> {
        if (query.isBlank()) return countries
        val q = query.trim().lowercase()
        return countries.filter {
            it.name.lowercase().contains(q) ||
            it.dial.contains(q) ||
            it.code.lowercase().contains(q)
        }
    }
}
