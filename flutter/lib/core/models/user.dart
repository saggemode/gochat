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
  }) : lastSeen = lastSeen ?? DateTime.now();

  factory User.fromJson(Map<String, dynamic> json) {
    return User(
      id: json['id']?.toString() ?? '',
      displayName: json['display_name'] ?? json['name'] ?? 'User',
      email: json['email'] ?? '',
      phone: json['phone'] ?? '',
      avatarUrl: json['avatar_url'] ?? '',
      statusText: json['status_text'] ?? 'Hey there! I am using GoChat.',
      isOnline: json['is_online'] == true,
      lastSeen: json['last_seen'] != null
          ? DateTime.tryParse(json['last_seen'].toString()) ?? DateTime.now()
          : DateTime.now(),
      isBusiness: json['is_business'] == true,
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
    };
  }
}
