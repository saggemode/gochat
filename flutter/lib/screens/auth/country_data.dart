class CountryData {
  static const List<Map<String, String>> countries = [
    {'name': 'Australia', 'code': 'AU', 'dial': '+61', 'flag': '🇦🇺'},
    {'name': 'Brazil', 'code': 'BR', 'dial': '+55', 'flag': '🇧🇷'},
    {'name': 'Canada', 'code': 'CA', 'dial': '+1', 'flag': '🇨🇦'},
    {'name': 'France', 'code': 'FR', 'dial': '+33', 'flag': '🇫🇷'},
    {'name': 'Germany', 'code': 'DE', 'dial': '+49', 'flag': '🇩🇪'},
    {'name': 'Ghana', 'code': 'GH', 'dial': '+233', 'flag': '🇬🇭'},
    {'name': 'India', 'code': 'IN', 'dial': '+91', 'flag': '🇮🇳'},
    {'name': 'Kenya', 'code': 'KE', 'dial': '+254', 'flag': '🇰🇪'},
    {'name': 'Nigeria', 'code': 'NG', 'dial': '+234', 'flag': '🇳🇬'},
    {'name': 'South Africa', 'code': 'ZA', 'dial': '+27', 'flag': '🇿🇦'},
    {'name': 'United Arab Emirates', 'code': 'AE', 'dial': '+971', 'flag': '🇦🇪'},
    {'name': 'United Kingdom', 'code': 'GB', 'dial': '+44', 'flag': '🇬🇧'},
    {'name': 'United States', 'code': 'US', 'dial': '+1', 'flag': '🇺🇸'},
  ];

  static Map<String, String> getCountry(String code) {
    return countries.firstWhere(
      (c) => c['code'] == code,
      orElse: () => countries.firstWhere((c) => c['code'] == 'NG'),
    );
  }

  static List<Map<String, String>> filter(String query) {
    if (query.isEmpty) return countries;
    final q = query.toLowerCase();
    return countries.where((c) {
      return c['name']!.toLowerCase().contains(q) ||
          c['dial']!.contains(q) ||
          c['code']!.toLowerCase().contains(q);
    }).toList();
  }
}
