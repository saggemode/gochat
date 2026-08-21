class StoreCoupon {
  final String id;
  final String code;
  final String type; // percentage, fixed
  final double value;
  final double minSpend;
  final int maxUses;
  final int usedCount;
  final DateTime? expiresAt;
  final bool isActive;

  StoreCoupon({
    required this.id,
    required this.code,
    this.type = 'percentage',
    required this.value,
    this.minSpend = 0.0,
    this.maxUses = 100,
    this.usedCount = 0,
    this.expiresAt,
    this.isActive = true,
  });

  factory StoreCoupon.fromJson(Map<String, dynamic> json) {
    return StoreCoupon(
      id: json['id']?.toString() ?? '',
      code: (json['code'] ?? '').toString().toUpperCase(),
      type: json['type'] ?? 'percentage',
      value: (json['value'] is num) ? (json['value'] as num).toDouble() : 0.0,
      minSpend: (json['min_spend'] is num) ? (json['min_spend'] as num).toDouble() : 0.0,
      maxUses: json['max_uses'] ?? 100,
      usedCount: json['used_count'] ?? 0,
      expiresAt: json['expires_at'] != null ? DateTime.tryParse(json['expires_at'].toString()) : null,
      isActive: json['is_active'] != false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'code': code,
      'type': type,
      'value': value,
      'min_spend': minSpend,
      'max_uses': maxUses,
      'used_count': usedCount,
      if (expiresAt != null) 'expires_at': expiresAt!.toIso8601String(),
      'is_active': isActive,
    };
  }
}
