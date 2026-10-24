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

    /** Flag emoji from an ISO-3166 alpha-2 code (regional indicator symbols). */
    private fun fl(code: String): String {
        if (code.length != 2) return "🏳️"
        val base = 0x1F1E6
        return try {
            String(Character.toChars(base + (code[0].uppercaseChar() - 'A'))) +
                String(Character.toChars(base + (code[1].uppercaseChar() - 'A')))
        } catch (_: Exception) {
            "🏳️"
        }
    }

    private fun c(
        name: String, code: String, dial: String,
        currencyCode: String = "USD", currencySymbol: String = "$", continent: String = "Other"
    ) = Country(name, code, dial, fl(code), currencyCode, currencySymbol, continent)

    // Full list of countries/territories with international dialing codes. Ordered alphabetically.
    val countries = listOf(
        c("Afghanistan", "AF", "+93", "AFN", "؋", "Asia"),
        c("Albania", "AL", "+355", "ALL", "L", "Europe"),
        c("Algeria", "DZ", "+213", "DZD", "دج", "Africa"),
        c("Andorra", "AD", "+376", "EUR", "€", "Europe"),
        c("Angola", "AO", "+244", "AOA", "Kz", "Africa"),
        c("Antigua and Barbuda", "AG", "+1268", "XCD", "$", "North America"),
        c("Argentina", "AR", "+54", "ARS", "$", "South America"),
        c("Armenia", "AM", "+374", "AMD", "֏", "Asia"),
        c("Aruba", "AW", "+297", "AWG", "ƒ", "North America"),
        c("Australia", "AU", "+61", "AUD", "A$", "Oceania"),
        c("Austria", "AT", "+43", "EUR", "€", "Europe"),
        c("Azerbaijan", "AZ", "+994", "AZN", "₼", "Asia"),
        c("Bahamas", "BS", "+1242", "BSD", "$", "North America"),
        c("Bahrain", "BH", "+973", "BHD", ".د.ب", "Asia"),
        c("Bangladesh", "BD", "+880", "BDT", "৳", "Asia"),
        c("Barbados", "BB", "+1246", "BBD", "$", "North America"),
        c("Belarus", "BY", "+375", "BYN", "Br", "Europe"),
        c("Belgium", "BE", "+32", "EUR", "€", "Europe"),
        c("Belize", "BZ", "+501", "BZD", "BZ$", "North America"),
        c("Benin", "BJ", "+229", "XOF", "CFA", "Africa"),
        c("Bhutan", "BT", "+975", "BTN", "Nu.", "Asia"),
        c("Bolivia", "BO", "+591", "BOB", "Bs.", "South America"),
        c("Bosnia and Herzegovina", "BA", "+387", "BAM", "KM", "Europe"),
        c("Botswana", "BW", "+267", "BWP", "P", "Africa"),
        c("Brazil", "BR", "+55", "BRL", "R$", "South America"),
        c("Brunei", "BN", "+673", "BND", "B$", "Asia"),
        c("Bulgaria", "BG", "+359", "BGN", "лв", "Europe"),
        c("Burkina Faso", "BF", "+226", "XOF", "CFA", "Africa"),
        c("Burundi", "BI", "+257", "BIF", "FBu", "Africa"),
        c("Cambodia", "KH", "+855", "KHR", "៛", "Asia"),
        c("Cameroon", "CM", "+237", "XAF", "FCFA", "Africa"),
        c("Canada", "CA", "+1", "CAD", "C$", "North America"),
        c("Cape Verde", "CV", "+238", "CVE", "$", "Africa"),
        c("Central African Republic", "CF", "+236", "XAF", "FCFA", "Africa"),
        c("Chad", "TD", "+235", "XAF", "FCFA", "Africa"),
        c("Chile", "CL", "+56", "CLP", "$", "South America"),
        c("China", "CN", "+86", "CNY", "¥", "Asia"),
        c("Colombia", "CO", "+57", "COP", "$", "South America"),
        c("Comoros", "KM", "+269", "KMF", "CF", "Africa"),
        c("Congo (Republic)", "CG", "+242", "XAF", "FCFA", "Africa"),
        c("Congo (DRC)", "CD", "+243", "CDF", "FC", "Africa"),
        c("Costa Rica", "CR", "+506", "CRC", "₡", "North America"),
        c("Côte d'Ivoire", "CI", "+225", "XOF", "CFA", "Africa"),
        c("Croatia", "HR", "+385", "EUR", "€", "Europe"),
        c("Cuba", "CU", "+53", "CUP", "$", "North America"),
        c("Cyprus", "CY", "+357", "EUR", "€", "Europe"),
        c("Czechia", "CZ", "+420", "CZK", "Kč", "Europe"),
        c("Denmark", "DK", "+45", "DKK", "kr", "Europe"),
        c("Djibouti", "DJ", "+253", "DJF", "Fdj", "Africa"),
        c("Dominica", "DM", "+1767", "XCD", "$", "North America"),
        c("Dominican Republic", "DO", "+1809", "DOP", "RD$", "North America"),
        c("Ecuador", "EC", "+593", "USD", "$", "South America"),
        c("Egypt", "EG", "+20", "EGP", "E£", "Africa"),
        c("El Salvador", "SV", "+503", "USD", "$", "North America"),
        c("Equatorial Guinea", "GQ", "+240", "XAF", "FCFA", "Africa"),
        c("Eritrea", "ER", "+291", "ERN", "Nfk", "Africa"),
        c("Estonia", "EE", "+372", "EUR", "€", "Europe"),
        c("Eswatini", "SZ", "+268", "SZL", "E", "Africa"),
        c("Ethiopia", "ET", "+251", "ETB", "Br", "Africa"),
        c("Fiji", "FJ", "+679", "FJD", "FJ$", "Oceania"),
        c("Finland", "FI", "+358", "EUR", "€", "Europe"),
        c("France", "FR", "+33", "EUR", "€", "Europe"),
        c("Gabon", "GA", "+241", "XAF", "FCFA", "Africa"),
        c("Gambia", "GM", "+220", "GMD", "D", "Africa"),
        c("Georgia", "GE", "+995", "GEL", "₾", "Asia"),
        c("Germany", "DE", "+49", "EUR", "€", "Europe"),
        c("Ghana", "GH", "+233", "GHS", "GH₵", "Africa"),
        c("Greece", "GR", "+30", "EUR", "€", "Europe"),
        c("Grenada", "GD", "+1473", "XCD", "$", "North America"),
        c("Guatemala", "GT", "+502", "GTQ", "Q", "North America"),
        c("Guinea", "GN", "+224", "GNF", "FG", "Africa"),
        c("Guinea-Bissau", "GW", "+245", "XOF", "CFA", "Africa"),
        c("Guyana", "GY", "+592", "GYD", "$", "South America"),
        c("Haiti", "HT", "+509", "HTG", "G", "North America"),
        c("Honduras", "HN", "+504", "HNL", "L", "North America"),
        c("Hong Kong", "HK", "+852", "HKD", "HK$", "Asia"),
        c("Hungary", "HU", "+36", "HUF", "Ft", "Europe"),
        c("Iceland", "IS", "+354", "ISK", "kr", "Europe"),
        c("India", "IN", "+91", "INR", "₹", "Asia"),
        c("Indonesia", "ID", "+62", "IDR", "Rp", "Asia"),
        c("Iran", "IR", "+98", "IRR", "﷼", "Asia"),
        c("Iraq", "IQ", "+964", "IQD", "ع.د", "Asia"),
        c("Ireland", "IE", "+353", "EUR", "€", "Europe"),
        c("Israel", "IL", "+972", "ILS", "₪", "Asia"),
        c("Italy", "IT", "+39", "EUR", "€", "Europe"),
        c("Jamaica", "JM", "+1876", "JMD", "J$", "North America"),
        c("Japan", "JP", "+81", "JPY", "¥", "Asia"),
        c("Jordan", "JO", "+962", "JOD", "د.ا", "Asia"),
        c("Kazakhstan", "KZ", "+7", "KZT", "₸", "Asia"),
        c("Kenya", "KE", "+254", "KES", "KSh", "Africa"),
        c("Kiribati", "KI", "+686", "AUD", "A$", "Oceania"),
        c("Kuwait", "KW", "+965", "KWD", "د.ك", "Asia"),
        c("Kyrgyzstan", "KG", "+996", "KGS", "с", "Asia"),
        c("Laos", "LA", "+856", "LAK", "₭", "Asia"),
        c("Latvia", "LV", "+371", "EUR", "€", "Europe"),
        c("Lebanon", "LB", "+961", "LBP", "ل.ل", "Asia"),
        c("Lesotho", "LS", "+266", "LSL", "L", "Africa"),
        c("Liberia", "LR", "+231", "LRD", "L$", "Africa"),
        c("Libya", "LY", "+218", "LYD", "ل.د", "Africa"),
        c("Liechtenstein", "LI", "+423", "CHF", "Fr", "Europe"),
        c("Lithuania", "LT", "+370", "EUR", "€", "Europe"),
        c("Luxembourg", "LU", "+352", "EUR", "€", "Europe"),
        c("Macau", "MO", "+853", "MOP", "MOP$", "Asia"),
        c("Madagascar", "MG", "+261", "MGA", "Ar", "Africa"),
        c("Malawi", "MW", "+265", "MWK", "MK", "Africa"),
        c("Malaysia", "MY", "+60", "MYR", "RM", "Asia"),
        c("Maldives", "MV", "+960", "MVR", "Rf", "Asia"),
        c("Mali", "ML", "+223", "XOF", "CFA", "Africa"),
        c("Malta", "MT", "+356", "EUR", "€", "Europe"),
        c("Marshall Islands", "MH", "+692", "USD", "$", "Oceania"),
        c("Mauritania", "MR", "+222", "MRU", "UM", "Africa"),
        c("Mauritius", "MU", "+230", "MUR", "₨", "Africa"),
        c("Mexico", "MX", "+52", "MXN", "$", "North America"),
        c("Micronesia", "FM", "+691", "USD", "$", "Oceania"),
        c("Moldova", "MD", "+373", "MDL", "L", "Europe"),
        c("Monaco", "MC", "+377", "EUR", "€", "Europe"),
        c("Mongolia", "MN", "+976", "MNT", "₮", "Asia"),
        c("Montenegro", "ME", "+382", "EUR", "€", "Europe"),
        c("Morocco", "MA", "+212", "MAD", "د.م.", "Africa"),
        c("Mozambique", "MZ", "+258", "MZN", "MT", "Africa"),
        c("Myanmar", "MM", "+95", "MMK", "K", "Asia"),
        c("Namibia", "NA", "+264", "NAD", "N$", "Africa"),
        c("Nauru", "NR", "+674", "AUD", "A$", "Oceania"),
        c("Nepal", "NP", "+977", "NPR", "₨", "Asia"),
        c("Netherlands", "NL", "+31", "EUR", "€", "Europe"),
        c("New Zealand", "NZ", "+64", "NZD", "NZ$", "Oceania"),
        c("Nicaragua", "NI", "+505", "NIO", "C$", "North America"),
        c("Niger", "NE", "+227", "XOF", "CFA", "Africa"),
        c("Nigeria", "NG", "+234", "NGN", "₦", "Africa"),
        c("North Korea", "KP", "+850", "KPW", "₩", "Asia"),
        c("North Macedonia", "MK", "+389", "MKD", "ден", "Europe"),
        c("Norway", "NO", "+47", "NOK", "kr", "Europe"),
        c("Oman", "OM", "+968", "OMR", "ر.ع.", "Asia"),
        c("Pakistan", "PK", "+92", "PKR", "₨", "Asia"),
        c("Palau", "PW", "+680", "USD", "$", "Oceania"),
        c("Palestine", "PS", "+970", "ILS", "₪", "Asia"),
        c("Panama", "PA", "+507", "PAB", "B/.", "North America"),
        c("Papua New Guinea", "PG", "+675", "PGK", "K", "Oceania"),
        c("Paraguay", "PY", "+595", "PYG", "₲", "South America"),
        c("Peru", "PE", "+51", "PEN", "S/", "South America"),
        c("Philippines", "PH", "+63", "PHP", "₱", "Asia"),
        c("Poland", "PL", "+48", "PLN", "zł", "Europe"),
        c("Portugal", "PT", "+351", "EUR", "€", "Europe"),
        c("Qatar", "QA", "+974", "QAR", "ر.ق", "Asia"),
        c("Romania", "RO", "+40", "RON", "lei", "Europe"),
        c("Russia", "RU", "+7", "RUB", "₽", "Europe"),
        c("Rwanda", "RW", "+250", "RWF", "RF", "Africa"),
        c("Saint Kitts and Nevis", "KN", "+1869", "XCD", "$", "North America"),
        c("Saint Lucia", "LC", "+1758", "XCD", "$", "North America"),
        c("Saint Vincent and the Grenadines", "VC", "+1784", "XCD", "$", "North America"),
        c("Samoa", "WS", "+685", "WST", "T", "Oceania"),
        c("San Marino", "SM", "+378", "EUR", "€", "Europe"),
        c("Sao Tome and Principe", "ST", "+239", "STN", "Db", "Africa"),
        c("Saudi Arabia", "SA", "+966", "SAR", "ر.س", "Asia"),
        c("Senegal", "SN", "+221", "XOF", "CFA", "Africa"),
        c("Serbia", "RS", "+381", "RSD", "дин", "Europe"),
        c("Seychelles", "SC", "+248", "SCR", "₨", "Africa"),
        c("Sierra Leone", "SL", "+232", "SLL", "Le", "Africa"),
        c("Singapore", "SG", "+65", "SGD", "S$", "Asia"),
        c("Slovakia", "SK", "+421", "EUR", "€", "Europe"),
        c("Slovenia", "SI", "+386", "EUR", "€", "Europe"),
        c("Solomon Islands", "SB", "+677", "SBD", "SI$", "Oceania"),
        c("Somalia", "SO", "+252", "SOS", "Sh", "Africa"),
        c("South Africa", "ZA", "+27", "ZAR", "R", "Africa"),
        c("South Korea", "KR", "+82", "KRW", "₩", "Asia"),
        c("South Sudan", "SS", "+211", "SSP", "£", "Africa"),
        c("Spain", "ES", "+34", "EUR", "€", "Europe"),
        c("Sri Lanka", "LK", "+94", "LKR", "₨", "Asia"),
        c("Sudan", "SD", "+249", "SDG", "ج.س.", "Africa"),
        c("Suriname", "SR", "+597", "SRD", "$", "South America"),
        c("Sweden", "SE", "+46", "SEK", "kr", "Europe"),
        c("Switzerland", "CH", "+41", "CHF", "Fr", "Europe"),
        c("Syria", "SY", "+963", "SYP", "£", "Asia"),
        c("Taiwan", "TW", "+886", "TWD", "NT$", "Asia"),
        c("Tajikistan", "TJ", "+992", "TJS", "ЅМ", "Asia"),
        c("Tanzania", "TZ", "+255", "TZS", "TSh", "Africa"),
        c("Thailand", "TH", "+66", "THB", "฿", "Asia"),
        c("Timor-Leste", "TL", "+670", "USD", "$", "Asia"),
        c("Togo", "TG", "+228", "XOF", "CFA", "Africa"),
        c("Tonga", "TO", "+676", "TOP", "T$", "Oceania"),
        c("Trinidad and Tobago", "TT", "+1868", "TTD", "TT$", "North America"),
        c("Tunisia", "TN", "+216", "TND", "د.ت", "Africa"),
        c("Turkey", "TR", "+90", "TRY", "₺", "Asia"),
        c("Turkmenistan", "TM", "+993", "TMT", "m", "Asia"),
        c("Tuvalu", "TV", "+688", "AUD", "A$", "Oceania"),
        c("Uganda", "UG", "+256", "UGX", "USh", "Africa"),
        c("Ukraine", "UA", "+380", "UAH", "₴", "Europe"),
        c("United Arab Emirates", "AE", "+971", "AED", "د.إ", "Asia"),
        c("United Kingdom", "GB", "+44", "GBP", "£", "Europe"),
        c("United States", "US", "+1", "USD", "$", "North America"),
        c("Uruguay", "UY", "+598", "UYU", "\$U", "South America"),
        c("Uzbekistan", "UZ", "+998", "UZS", "сўм", "Asia"),
        c("Vanuatu", "VU", "+678", "VUV", "VT", "Oceania"),
        c("Vatican City", "VA", "+379", "EUR", "€", "Europe"),
        c("Venezuela", "VE", "+58", "VES", "Bs.", "South America"),
        c("Vietnam", "VN", "+84", "VND", "₫", "Asia"),
        c("Yemen", "YE", "+967", "YER", "﷼", "Asia"),
        c("Zambia", "ZM", "+260", "ZMW", "ZK", "Africa"),
        c("Zimbabwe", "ZW", "+263", "ZWL", "Z$", "Africa")
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

    /** Best-effort country from a phone number, or null if it can't be matched (no Nigeria default). */
    fun matchByPhoneOrNull(phone: String): Country? {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        if (!clean.startsWith("+")) return null
        for (c in countries.sortedByDescending { it.dial.length }) {
            if (clean.startsWith(c.dial)) return c
        }
        return null
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
