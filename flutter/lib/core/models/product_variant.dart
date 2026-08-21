import 'dart:convert';

class ProductVariant {
  final String id;
  final String productId;
  final String sku;
  final String title;
  final Map<String, dynamic> attributes;
  final String attributesJson;
  final double? priceOverride;
  final int stockQuantity;
  final String imageUrl;
  final bool isActive;

  ProductVariant({
    required this.id,
    required this.productId,
    this.sku = '',
    required this.title,
    this.attributes = const {},
    this.attributesJson = '{}',
    this.priceOverride,
    this.stockQuantity = 10,
    this.imageUrl = '',
    this.isActive = true,
  });

  String? get size => attributes['size']?.toString();
  String? get color => attributes['color']?.toString();
  String? get material => attributes['material']?.toString();

  ProductVariant copyWith({
    String? id,
    String? productId,
    String? sku,
    String? title,
    Map<String, dynamic>? attributes,
    String? attributesJson,
    double? priceOverride,
    int? stockQuantity,
    String? imageUrl,
    bool? isActive,
  }) {
    return ProductVariant(
      id: id ?? this.id,
      productId: productId ?? this.productId,
      sku: sku ?? this.sku,
      title: title ?? this.title,
      attributes: attributes ?? this.attributes,
      attributesJson: attributesJson ?? this.attributesJson,
      priceOverride: priceOverride ?? this.priceOverride,
      stockQuantity: stockQuantity ?? this.stockQuantity,
      imageUrl: imageUrl ?? this.imageUrl,
      isActive: isActive ?? this.isActive,
    );
  }

  factory ProductVariant.fromJson(Map<String, dynamic> json) {
    Map<String, dynamic> parsedAttrs = {};
    String rawAttrsJson = json['attributes_json'] ?? json['attributesJson'] ?? '{}';
    if (rawAttrsJson.isNotEmpty) {
      try {
        parsedAttrs = jsonDecode(rawAttrsJson);
      } catch (_) {}
    } else if (json['attributes'] is Map) {
      parsedAttrs = Map<String, dynamic>.from(json['attributes']);
      rawAttrsJson = jsonEncode(parsedAttrs);
    }

    final rawPriceOverride = json['price_override'] ?? json['priceOverride'];
    final priceOverrideVal = (rawPriceOverride is num && rawPriceOverride > 0)
        ? rawPriceOverride.toDouble()
        : null;

    final rawStock = json['stock_quantity'] ?? json['stockQuantity'] ?? json['quantity'] ?? 10;
    final stockVal = (rawStock is num) ? rawStock.toInt() : 10;

    return ProductVariant(
      id: json['id']?.toString() ?? '',
      productId: json['product_id']?.toString() ?? json['productId']?.toString() ?? '',
      sku: json['sku']?.toString() ?? '',
      title: json['title']?.toString() ?? 'Default Variant',
      attributes: parsedAttrs,
      attributesJson: rawAttrsJson,
      priceOverride: priceOverrideVal,
      stockQuantity: stockVal,
      imageUrl: json['image_url'] ?? json['imageUrl'] ?? '',
      isActive: json['is_active'] != false && json['isActive'] != false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'product_id': productId,
      'sku': sku,
      'title': title,
      'attributes_json': attributesJson.isNotEmpty ? attributesJson : jsonEncode(attributes),
      'price_override': priceOverride ?? 0.0,
      'stock_quantity': stockQuantity,
      'image_url': imageUrl,
      'is_active': isActive,
    };
  }
}
