class OrderItem {
  final String productId;
  final String title;
  final double price;
  final int quantity;
  final String imageUrl;

  OrderItem({
    required this.productId,
    required this.title,
    required this.price,
    this.quantity = 1,
    this.imageUrl = '',
  });

  factory OrderItem.fromJson(Map<String, dynamic> json) {
    return OrderItem(
      productId: json['product_id']?.toString() ?? json['id']?.toString() ?? '',
      title: json['title'] ?? json['name'] ?? 'Product',
      price: (json['price'] is num) ? (json['price'] as num).toDouble() : 0.0,
      quantity: (json['quantity'] is int) ? json['quantity'] : 1,
      imageUrl: json['image_url'] ?? json['imageUrl'] ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'product_id': productId,
      'title': title,
      'price': price,
      'quantity': quantity,
      'image_url': imageUrl,
    };
  }
}

class MarketplaceOrder {
  final String id;
  final String orderNumber;
  final String buyerId;
  final String buyerName;
  final String buyerPhone;
  final String buyerPin;
  final String sellerId;
  final String storeName;
  final double grandTotal;
  final String status; // PENDING, PAID, SHIPPED, DELIVERED, CANCELLED
  final List<OrderItem> items;
  final DateTime createdAt;
  final String shippingAddress;

  MarketplaceOrder({
    required this.id,
    required this.orderNumber,
    required this.buyerId,
    this.buyerName = 'Customer',
    this.buyerPhone = '',
    this.buyerPin = '',
    required this.sellerId,
    required this.storeName,
    required this.grandTotal,
    this.status = 'PAID',
    this.items = const [],
    required this.createdAt,
    this.shippingAddress = 'Lagos, Nigeria',
  });

  factory MarketplaceOrder.fromJson(Map<String, dynamic> json) {
    List<OrderItem> parsedItems = [];
    if (json['items'] is List) {
      parsedItems = (json['items'] as List).map((i) => OrderItem.fromJson(i as Map<String, dynamic>)).toList();
    }

    return MarketplaceOrder(
      id: json['id']?.toString() ?? '',
      orderNumber: json['order_number'] ?? json['orderNumber'] ?? 'ORD-${DateTime.now().millisecondsSinceEpoch.toString().substring(7)}',
      buyerId: json['buyer_id']?.toString() ?? '',
      buyerName: json['buyer_name'] ?? 'Customer',
      buyerPhone: json['buyer_phone'] ?? '',
      buyerPin: json['buyer_pin'] ?? '',
      sellerId: json['seller_id']?.toString() ?? json['business_id']?.toString() ?? '',
      storeName: json['store_name'] ?? json['business_name'] ?? 'My Store',
      grandTotal: (json['grand_total'] is num)
          ? (json['grand_total'] as num).toDouble()
          : ((json['total_amount'] is num) ? (json['total_amount'] as num).toDouble() : 0.0),
      status: json['status'] ?? 'PAID',
      items: parsedItems,
      createdAt: json['created_at'] != null ? (DateTime.tryParse(json['created_at'].toString()) ?? DateTime.now()) : DateTime.now(),
      shippingAddress: json['shipping_address'] ?? json['shipping_country'] ?? 'Lagos, Nigeria',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'order_number': orderNumber,
      'buyer_id': buyerId,
      'buyer_name': buyerName,
      'buyer_phone': buyerPhone,
      'buyer_pin': buyerPin,
      'seller_id': sellerId,
      'store_name': storeName,
      'grand_total': grandTotal,
      'status': status,
      'items': items.map((i) => i.toJson()).toList(),
      'created_at': createdAt.toIso8601String(),
      'shipping_address': shippingAddress,
    };
  }
}
