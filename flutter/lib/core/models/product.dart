class Product {
  final String id;
  final String title;
  final String description;
  final double price;
  final String currency;
  final String imageUrl;
  final String category;
  final String storeName;
  final double rating;
  final int reviewsCount;
  final bool inStock;

  Product({
    required this.id,
    required this.title,
    required this.description,
    required this.price,
    this.currency = 'USD',
    this.imageUrl = '',
    this.category = 'General',
    this.storeName = 'Official Store',
    this.rating = 4.8,
    this.reviewsCount = 120,
    this.inStock = true,
  });

  factory Product.fromJson(Map<String, dynamic> json) {
    return Product(
      id: json['id']?.toString() ?? '',
      title: json['title'] ?? json['name'] ?? 'Product',
      description: json['description'] ?? '',
      price: (json['price'] is num) ? (json['price'] as num).toDouble() : 0.0,
      currency: json['currency'] ?? 'USD',
      imageUrl: json['image_url'] ?? '',
      category: json['category'] ?? 'General',
      storeName: json['store_name'] ?? 'Official Store',
      rating: (json['rating'] is num) ? (json['rating'] as num).toDouble() : 4.8,
      reviewsCount: json['reviews_count'] ?? 120,
      inStock: json['in_stock'] != false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'description': description,
      'price': price,
      'currency': currency,
      'image_url': imageUrl,
      'category': category,
      'store_name': storeName,
      'rating': rating,
      'reviews_count': reviewsCount,
      'in_stock': inStock,
    };
  }
}
