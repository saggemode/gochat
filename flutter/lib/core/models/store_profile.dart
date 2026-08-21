class StoreProfile {
  final String id;
  final String userId;
  final String storeName;
  final String category;
  final String description;
  final String address;
  final String phone;
  final String email;
  final String logoUrl;
  final String bannerUrl;
  final String state;
  final String countryCode;
  final String ownerPin;
  final bool isVerified;
  final DateTime? createdAt;

  StoreProfile({
    required this.id,
    required this.userId,
    required this.storeName,
    this.category = 'General Retail',
    this.description = '',
    this.address = '',
    this.phone = '',
    this.email = '',
    this.logoUrl = '',
    this.bannerUrl = '',
    this.state = '',
    this.countryCode = 'NG',
    this.ownerPin = '',
    this.isVerified = true,
    this.createdAt,
  });

  StoreProfile copyWith({
    String? id,
    String? userId,
    String? storeName,
    String? category,
    String? description,
    String? address,
    String? phone,
    String? email,
    String? logoUrl,
    String? bannerUrl,
    String? state,
    String? countryCode,
    String? ownerPin,
    bool? isVerified,
    DateTime? createdAt,
  }) {
    return StoreProfile(
      id: id ?? this.id,
      userId: userId ?? this.userId,
      storeName: storeName ?? this.storeName,
      category: category ?? this.category,
      description: description ?? this.description,
      address: address ?? this.address,
      phone: phone ?? this.phone,
      email: email ?? this.email,
      logoUrl: logoUrl ?? this.logoUrl,
      bannerUrl: bannerUrl ?? this.bannerUrl,
      state: state ?? this.state,
      countryCode: countryCode ?? this.countryCode,
      ownerPin: ownerPin ?? this.ownerPin,
      isVerified: isVerified ?? this.isVerified,
      createdAt: createdAt ?? this.createdAt,
    );
  }

  factory StoreProfile.fromJson(Map<String, dynamic> json) {
    return StoreProfile(
      id: json['id']?.toString() ?? '',
      userId: json['user_id']?.toString() ?? '',
      storeName: json['business_name'] ?? json['store_name'] ?? json['name'] ?? 'My Store',
      category: json['category'] ?? 'General Retail',
      description: json['description'] ?? '',
      address: json['address'] ?? json['location'] ?? '',
      phone: json['phone'] ?? '',
      email: json['email'] ?? '',
      logoUrl: json['logo_url'] ?? json['avatar_url'] ?? '',
      bannerUrl: json['banner_url'] ?? '',
      state: json['state'] ?? '',
      countryCode: json['country_code'] ?? 'NG',
      ownerPin: json['owner_pin'] ?? json['seller_pin'] ?? '',
      isVerified: json['is_verified'] != false,
      createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at'].toString()) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'user_id': userId,
      'business_name': storeName,
      'category': category,
      'description': description,
      'address': address,
      'phone': phone,
      'email': email,
      'logo_url': logoUrl,
      'banner_url': bannerUrl,
      'state': state,
      'country_code': countryCode,
      'owner_pin': ownerPin,
      'is_verified': isVerified,
      if (createdAt != null) 'created_at': createdAt!.toIso8601String(),
    };
  }
}
