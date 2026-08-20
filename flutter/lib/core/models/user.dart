class User {
  final String id;
  final String displayName;
  final String email;
  final String phone;
  final String avatarUrl;
  final String statusText;
  final bool isOnline;
  final DateTime lastSeen;
  final bool isBusiness;
  final String pin;
  final String countryCode;

  User({
    required this.id,
    required this.displayName,
    this.email = '',
    this.phone = '',
    this.avatarUrl = '',
    this.statusText = 'Hey there! I am using GoChat.',
    this.isOnline = false,
    DateTime? lastSeen,
    this.isBusiness = false,
    this.pin = '',
    this.countryCode = '',
  }) : lastSeen = lastSeen ?? DateTime.now();

  String get effectivePin {
    if (pin.isNotEmpty) return pin.toUpperCase();
    if (id.isNotEmpty) {
      final clean = id.replaceAll('-', '').toUpperCase();
      return clean.length >= 6 ? clean.substring(0, 6) : clean;
    }
    return '8492A1';
  }

  factory User.fromJson(Map<String, dynamic> json) {
    return User(
      id: json['id']?.toString() ?? '',
      displayName: json['display_name'] ?? json['displayName'] ?? json['name'] ?? 'User',
      email: json['email'] ?? '',
      phone: json['phone'] ?? '',
      avatarUrl: json['avatar_url'] ?? json['avatarUrl'] ?? '',
      statusText: json['status_text'] ?? json['statusText'] ?? 'Hey there! I am using GoChat.',
      isOnline: json['is_online'] == true || json['isOnline'] == true,
      lastSeen: json['last_seen'] != null || json['lastSeen'] != null
          ? DateTime.tryParse((json['last_seen'] ?? json['lastSeen']).toString()) ?? DateTime.now()
          : DateTime.now(),
      isBusiness: json['is_business'] == true || json['isBusiness'] == true,
      pin: json['pin']?.toString() ?? json['Pin']?.toString() ?? '',
      countryCode: json['country_code'] ?? json['countryCode'] ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'display_name': displayName,
      'email': email,
      'phone': phone,
      'avatar_url': avatarUrl,
      'status_text': statusText,
      'is_online': isOnline,
      'last_seen': lastSeen.toIso8601String(),
      'is_business': isBusiness,
      'pin': pin,
      'country_code': countryCode,
    };
  }
}
