import 'product_variant.dart';

class Product {
  final String id;
  final String title;
  final String description;
  final double price;
  final double originalPrice;
  final String currency;
  final String imageUrl;
  final List<String> images;
  final String category;
  final String storeName;
  final String sellerId;
  final String sellerPin;
  final String sellerLocation;
  final bool isVerifiedSeller;
  final double rating;
  final int reviewsCount;
  final bool inStock;
  final List<String> tags;
  final bool isWishlisted;
  final List<ProductVariant> variants;

  Product({
    required this.id,
    required this.title,
    required this.description,
    required this.price,
    this.originalPrice = 0.0,
    this.currency = 'USD',
    this.imageUrl = '',
    this.images = const [],
    this.category = 'General',
    this.storeName = 'Official Store',
    this.sellerId = '',
    this.sellerPin = '',
    this.sellerLocation = 'Lagos, Nigeria',
    this.isVerifiedSeller = true,
    this.rating = 4.8,
    this.reviewsCount = 120,
    this.inStock = true,
    this.tags = const ['Verified Merchant', 'Fast Delivery'],
    this.isWishlisted = false,
    this.variants = const [],
  });

  bool get hasDiscount => originalPrice > price && originalPrice > 0;
  int get discountPercent => hasDiscount ? (((originalPrice - price) / originalPrice) * 100).round() : 0;
  bool get hasVariants => variants.isNotEmpty;

  Product copyWith({
    String? id,
    String? title,
    String? description,
    double? price,
    double? originalPrice,
    String? currency,
    String? imageUrl,
    List<String>? images,
    String? category,
    String? storeName,
    String? sellerId,
    String? sellerPin,
    String? sellerLocation,
    bool? isVerifiedSeller,
    double? rating,
    int? reviewsCount,
    bool? inStock,
    List<String>? tags,
    bool? isWishlisted,
    List<ProductVariant>? variants,
  }) {
    return Product(
      id: id ?? this.id,
      title: title ?? this.title,
      description: description ?? this.description,
      price: price ?? this.price,
      originalPrice: originalPrice ?? this.originalPrice,
      currency: currency ?? this.currency,
      imageUrl: imageUrl ?? this.imageUrl,
      images: images ?? this.images,
      category: category ?? this.category,
      storeName: storeName ?? this.storeName,
      sellerId: sellerId ?? this.sellerId,
      sellerPin: sellerPin ?? this.sellerPin,
      sellerLocation: sellerLocation ?? this.sellerLocation,
      isVerifiedSeller: isVerifiedSeller ?? this.isVerifiedSeller,
      rating: rating ?? this.rating,
      reviewsCount: reviewsCount ?? this.reviewsCount,
      inStock: inStock ?? this.inStock,
      tags: tags ?? this.tags,
      isWishlisted: isWishlisted ?? this.isWishlisted,
      variants: variants ?? this.variants,
    );
  }

  factory Product.fromJson(Map<String, dynamic> json) {
    final rawPrice = (json['price'] is num) ? (json['price'] as num).toDouble() : 0.0;
    final rawOriginalPrice = (json['original_price'] is num)
        ? (json['original_price'] as num).toDouble()
        : (json['compare_at_price'] is num ? (json['compare_at_price'] as num).toDouble() : 0.0);

    List<String> parsedImages = [];
    if (json['images'] is List) {
      parsedImages = (json['images'] as List).map((e) => e.toString()).toList();
    } else if (json['image_urls'] is List) {
      parsedImages = (json['image_urls'] as List).map((e) => e.toString()).toList();
    } else if (json['image_url'] != null && json['image_url'].toString().isNotEmpty) {
      parsedImages = [json['image_url'].toString()];
    }

    List<ProductVariant> parsedVariants = [];
    if (json['variants'] is List) {
      parsedVariants = (json['variants'] as List)
          .map((v) => ProductVariant.fromJson(v as Map<String, dynamic>))
          .toList();
    }

    final rawSellerId = (json['seller_id'] ?? json['owner_id'] ?? json['business_id'] ?? json['user_id'] ?? '').toString();

    return Product(
      id: json['id']?.toString() ?? '',
      title: json['title'] ?? json['name'] ?? 'Product',
      description: json['description'] ?? '',
      price: rawPrice,
      originalPrice: rawOriginalPrice > 0 ? rawOriginalPrice : (rawPrice * 1.25),
      currency: json['currency'] ?? 'USD',
      imageUrl: (json['image_url'] ?? (parsedImages.isNotEmpty ? parsedImages.first : '')).toString(),
      images: parsedImages,
      category: json['category'] ?? json['category_name'] ?? 'General',
      storeName: json['store_name'] ?? json['seller_name'] ?? json['brand'] ?? 'Official Store',
      sellerId: rawSellerId,
      sellerPin: json['seller_pin']?.toString() ?? '',
      sellerLocation: json['seller_location'] ?? json['location'] ?? 'Lagos, Nigeria',
      isVerifiedSeller: json['is_verified'] != false,
      rating: (json['rating'] is num) ? (json['rating'] as num).toDouble() : (json['rating_avg'] is num ? (json['rating_avg'] as num).toDouble() : 4.8),
      reviewsCount: json['reviews_count'] ?? json['review_count'] ?? 120,
      inStock: json['in_stock'] != false,
      tags: json['tags'] is List ? (json['tags'] as List).map((e) => e.toString()).toList() : ['Verified Merchant', 'Fast Delivery'],
      isWishlisted: json['is_wishlisted'] == true,
      variants: parsedVariants,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'description': description,
      'price': price,
      'original_price': originalPrice,
      'currency': currency,
      'image_url': imageUrl,
      'images': images,
      'category': category,
      'store_name': storeName,
      'seller_id': sellerId,
      'seller_pin': sellerPin,
      'seller_location': sellerLocation,
      'is_verified': isVerifiedSeller,
      'rating': rating,
      'reviews_count': reviewsCount,
      'in_stock': inStock,
      'tags': tags,
      'is_wishlisted': isWishlisted,
      'variants': variants.map((v) => v.toJson()).toList(),
    };
  }
}
