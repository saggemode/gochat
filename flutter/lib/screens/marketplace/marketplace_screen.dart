import 'package:flutter/material.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import '../chat/chat_room_screen.dart';

class MarketplaceScreen extends StatefulWidget {
  final AppState appState;

  const MarketplaceScreen({super.key, required this.appState});

  @override
  State<MarketplaceScreen> createState() => _MarketplaceScreenState();
}

class _MarketplaceScreenState extends State<MarketplaceScreen> {
  String _selectedCategory = 'All';
  final TextEditingController _searchController = TextEditingController();
  bool _verifiedOnly = false;
  final String _sortBy = 'popular';

  final List<Map<String, dynamic>> _categories = [
    {'name': 'All', 'icon': Icons.grid_view_rounded},
    {'name': 'Electronics', 'icon': Icons.devices_rounded},
    {'name': 'Fashion', 'icon': Icons.checkroom_rounded},
    {'name': 'Gaming', 'icon': Icons.sports_esports_rounded},
    {'name': 'Home', 'icon': Icons.weekend_rounded},
    {'name': 'Services', 'icon': Icons.handyman_rounded},
    {'name': 'Phones', 'icon': Icons.smartphone_rounded},
  ];

  final List<Map<String, dynamic>> _heroBanners = [
    {
      'title': '⚡ Flash Sale - Up to 40% OFF',
      'subtitle': 'Verified electronics & gadgets on GoChat',
      'tag': 'HOT DEAL',
      'color': Color(0xFF00A884),
      'icon': Icons.bolt_rounded,
    },
    {
      'title': '🛡️ GoChat Escrow Protected',
      'subtitle': '100% money-back guarantee on all orders',
      'tag': 'SECURE',
      'color': Color(0xFF3B82F6),
      'icon': Icons.shield_rounded,
    },
    {
      'title': '🚀 Direct Seller Messaging',
      'subtitle': 'Inquire & negotiate directly via GoChat PIN',
      'tag': 'FAST DM',
      'color': Color(0xFF8B5CF6),
      'icon': Icons.chat_rounded,
    },
  ];

  List<Product> get _filteredProducts {
    var list = List<Product>.from(widget.appState.products);

    if (list.isEmpty) {
      list = _getDefaultProducts();
    }

    if (_selectedCategory != 'All') {
      list = list.where((p) => p.category.toLowerCase().contains(_selectedCategory.toLowerCase())).toList();
    }

    if (_verifiedOnly) {
      list = list.where((p) => p.isVerifiedSeller).toList();
    }

    final query = _searchController.text.trim().toLowerCase();
    if (query.isNotEmpty) {
      list = list.where((p) =>
        p.title.toLowerCase().contains(query) ||
        p.description.toLowerCase().contains(query) ||
        p.storeName.toLowerCase().contains(query) ||
        p.category.toLowerCase().contains(query)
      ).toList();
    }

    if (_sortBy == 'price_low') {
      list.sort((a, b) => a.price.compareTo(b.price));
    } else if (_sortBy == 'price_high') {
      list.sort((a, b) => b.price.compareTo(a.price));
    } else if (_sortBy == 'rating') {
      list.sort((a, b) => b.rating.compareTo(a.rating));
    }

    return list;
  }

  List<Product> _getDefaultProducts() {
    return [
      Product(
        id: 'prod_1',
        title: 'iPhone 15 Pro Max 256GB - Titanium',
        description: 'Brand new, sealed in box. 1 year international warranty. Unlocked for all GSM networks with 5G support.',
        price: 1199.0,
        originalPrice: 1399.0,
        category: 'Electronics',
        storeName: 'Apple Hub Lagos',
        sellerPin: '1P0YE4WZ',
        sellerLocation: 'Victoria Island, Lagos',
        rating: 4.9,
        reviewsCount: 340,
        imageUrl: 'https://images.unsplash.com/photo-1695048133142-1a20484d2569?w=600&auto=format&fit=crop&q=80',
        tags: ['Verified Store', '1-Day Delivery', 'Warranty'],
      ),
      Product(
        id: 'prod_2',
        title: 'Sony WH-1000XM5 Wireless Headphones',
        description: 'Industry-leading noise cancellation, 30-hour battery life, premium sound quality with Hi-Res Audio.',
        price: 349.0,
        originalPrice: 399.0,
        category: 'Electronics',
        storeName: 'SoundWave Electronics',
        sellerPin: 'LQZM9Z1P',
        sellerLocation: 'Ikeja, Lagos',
        rating: 4.8,
        reviewsCount: 185,
        imageUrl: 'https://images.unsplash.com/photo-1546435770-a3e426bf472b?w=600&auto=format&fit=crop&q=80',
        tags: ['Noise Canceling', 'Authentic'],
      ),
      Product(
        id: 'prod_3',
        title: 'Nike Air Max 270 Sneakers - Triple Black',
        description: 'Original Nike sneakers, breathable mesh upper, Max Air 270 unit for all-day comfort. Available in sizes 40-46.',
        price: 159.0,
        originalPrice: 199.0,
        category: 'Fashion',
        storeName: 'Kicks & Drips NG',
        sellerPin: 'IX13BWHK',
        sellerLocation: 'Abuja, FCT',
        rating: 4.7,
        reviewsCount: 92,
        imageUrl: 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=600&auto=format&fit=crop&q=80',
        tags: ['Original', 'Free Return'],
      ),
      Product(
        id: 'prod_4',
        title: 'MacBook Air 15" M2 Chip 512GB SSD',
        description: 'Supercharged by M2 chip, 15.3-inch Liquid Retina display, 18-hour battery life, 1080p FaceTime HD camera.',
        price: 1299.0,
        originalPrice: 1499.0,
        category: 'Electronics',
        storeName: 'TechDepot West Africa',
        sellerPin: '9VD2CPR3',
        sellerLocation: 'Port Harcourt, Rivers',
        rating: 5.0,
        reviewsCount: 210,
        imageUrl: 'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=600&auto=format&fit=crop&q=80',
        tags: ['Official Warranty', 'Best Seller'],
      ),
      Product(
        id: 'prod_5',
        title: 'Sony PlayStation 5 Slim Digital Edition',
        description: 'Includes 1 DualSense Wireless Controller, 1TB SSD storage, 4K 120Hz HDR gaming support.',
        price: 499.0,
        originalPrice: 549.0,
        category: 'Gaming',
        storeName: 'GameZone NG',
        sellerPin: 'Q4LHQ7QD',
        sellerLocation: 'Lekki Phase 1, Lagos',
        rating: 4.9,
        reviewsCount: 450,
        imageUrl: 'https://images.unsplash.com/photo-1606813907291-d86efa9b94db?w=600&auto=format&fit=crop&q=80',
        tags: ['Brand New', 'Escrow Protected'],
      ),
      Product(
        id: 'prod_6',
        title: 'Luxury Chronograph Designer Watch',
        description: 'Stainless steel waterproof wrist watch with sapphire crystal glass and luminous dial hands.',
        price: 189.0,
        originalPrice: 280.0,
        category: 'Fashion',
        storeName: 'Crown Horology',
        sellerPin: '1P0YE4WZ',
        sellerLocation: 'Lagos, Nigeria',
        rating: 4.6,
        reviewsCount: 78,
        imageUrl: 'https://images.unsplash.com/photo-1524805444758-089113d48a6d?w=600&auto=format&fit=crop&q=80',
        tags: ['Waterproof', 'Luxury'],
      ),
    ];
  }

  Future<void> _chatWithSeller(Product product) async {
    final sellerPin = product.sellerPin.isNotEmpty ? product.sellerPin : '1P0YE4WZ';
    final user = await widget.appState.lookupUserByPin(sellerPin);
    final targetTitle = user?.displayName ?? product.storeName;
    final targetId = user?.id ?? sellerPin;

    Conversation? existingConv;
    final idx = widget.appState.conversations.indexWhere(
      (c) => c.title.toLowerCase() == targetTitle.toLowerCase() || c.id == targetId,
    );

    if (idx != -1) {
      existingConv = widget.appState.conversations[idx];
    } else {
      existingConv = await widget.appState.createConversation(
        targetTitle,
        [targetId],
        invitationStatus: InvitationStatus.accepted,
        partnerPin: sellerPin,
      );
    }

    await widget.appState.sendMessage(
      existingConv.id,
      '👋 Hi ${product.storeName}! I am interested in purchasing "${product.title}" listed for \$${product.price.toStringAsFixed(2)} on GoChat Marketplace.',
    );

    if (!mounted) return;
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChatRoomScreen(conversation: existingConv!, appState: widget.appState),
      ),
    );
  }

  void _showProductDetail(Product product) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) {
        return DraggableScrollableSheet(
          initialChildSize: 0.85,
          minChildSize: 0.5,
          maxChildSize: 0.95,
          expand: false,
          builder: (_, scrollController) {
            return ListView(
              controller: scrollController,
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 5,
                    decoration: BoxDecoration(
                      color: isDark ? Colors.white24 : Colors.black12,
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: AspectRatio(
                    aspectRatio: 16 / 10,
                    child: Image.network(
                      product.imageUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => Container(
                        color: isDark ? AppTheme.darkCard : Colors.grey.shade200,
                        child: const Icon(Icons.shopping_bag_outlined, size: 64, color: AppTheme.primary),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        color: AppTheme.primary.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        product.category.toUpperCase(),
                        style: const TextStyle(
                          color: AppTheme.primary,
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 0.5,
                        ),
                      ),
                    ),
                    if (product.hasDiscount) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: AppTheme.dangerRed.withValues(alpha: 0.15),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(
                          '-${product.discountPercent}% OFF',
                          style: const TextStyle(
                            color: AppTheme.dangerRed,
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ],
                    const Spacer(),
                    IconButton(
                      icon: Icon(
                        product.isWishlisted ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                        color: product.isWishlisted ? AppTheme.dangerRed : AppTheme.textMuted,
                      ),
                      onPressed: () {
                        widget.appState.toggleWishlist(product.id);
                        Navigator.pop(ctx);
                        _showProductDetail(widget.appState.products.firstWhere((p) => p.id == product.id, orElse: () => product.copyWith(isWishlisted: !product.isWishlisted)));
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 8),

                Text(
                  product.title,
                  style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 10),

                Row(
                  crossAxisAlignment: CrossAxisAlignment.baseline,
                  textBaseline: TextBaseline.alphabetic,
                  children: [
                    Text(
                      '\$${product.price.toStringAsFixed(2)}',
                      style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.bold,
                        color: AppTheme.primary,
                      ),
                    ),
                    if (product.hasDiscount) ...[
                      const SizedBox(width: 10),
                      Text(
                        '\$${product.originalPrice.toStringAsFixed(2)}',
                        style: TextStyle(
                          fontSize: 16,
                          color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                          decoration: TextDecoration.lineThrough,
                        ),
                      ),
                    ],
                    const Spacer(),
                    Row(
                      children: [
                        const Icon(Icons.star_rounded, color: Colors.amber, size: 20),
                        const SizedBox(width: 4),
                        Text(
                          '${product.rating} (${product.reviewsCount})',
                          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                const Divider(),
                const SizedBox(height: 12),

                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: AppTheme.primary.withValues(alpha: 0.3),
                      width: 1,
                    ),
                  ),
                  child: Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: AppTheme.primary.withValues(alpha: 0.15),
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.store_rounded, color: AppTheme.primary, size: 22),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text(
                                  product.storeName,
                                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                                ),
                                const SizedBox(width: 4),
                                const Icon(Icons.verified_rounded, color: AppTheme.primary, size: 16),
                              ],
                            ),
                            const SizedBox(height: 2),
                            Text(
                              'PIN: ${product.sellerPin.isNotEmpty ? product.sellerPin : '1P0YE4WZ'} • ${product.sellerLocation}',
                              style: TextStyle(fontSize: 12, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                            ),
                          ],
                        ),
                      ),
                      OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                        ),
                        icon: const Icon(Icons.chat_bubble_outline_rounded, size: 14),
                        label: const Text('Inquire', style: TextStyle(fontSize: 12)),
                        onPressed: () {
                          Navigator.pop(ctx);
                          _chatWithSeller(product);
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 18),

                const Text(
                  'ABOUT THIS ITEM',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1),
                ),
                const SizedBox(height: 6),
                Text(
                  product.description,
                  style: TextStyle(
                    fontSize: 14,
                    height: 1.5,
                    color: isDark ? Colors.white70 : Colors.black87,
                  ),
                ),
                const SizedBox(height: 16),

                Wrap(
                  spacing: 10,
                  runSpacing: 8,
                  children: product.tags.map((tag) => Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    decoration: BoxDecoration(
                      color: isDark ? Colors.white10 : Colors.black.withValues(alpha: 0.05),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.check_circle_outline_rounded, size: 14, color: AppTheme.primary),
                        const SizedBox(width: 6),
                        Text(tag, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  )).toList(),
                ),
                const SizedBox(height: 28),

                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                        ),
                        icon: const Icon(Icons.chat_rounded, size: 18),
                        label: const Text('Chat Seller', style: TextStyle(fontWeight: FontWeight.bold)),
                        onPressed: () {
                          Navigator.pop(ctx);
                          _chatWithSeller(product);
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      flex: 2,
                      child: ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppTheme.primary,
                          foregroundColor: Colors.black,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                          elevation: 2,
                        ),
                        icon: const Icon(Icons.shopping_cart_checkout_rounded, size: 20),
                        label: const Text(
                          'Add to Cart',
                          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                        ),
                        onPressed: () {
                          widget.appState.addToCart(product);
                          Navigator.pop(ctx);
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text('Added "${product.title}" to cart!'),
                              backgroundColor: AppTheme.primary,
                              action: SnackBarAction(
                                label: 'VIEW CART',
                                textColor: Colors.black,
                                onPressed: _showCartSheet,
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ],
            );
          },
        );
      },
    );
  }

  void _showCartSheet() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    String promoCode = '';
    double discount = 0.0;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (context, setSheetState) {
            final cart = widget.appState.cart;
            final subtotal = cart.fold(0.0, (sum, p) => sum + p.price);
            final grandTotal = (subtotal - discount).clamp(0.0, double.infinity);

            return DraggableScrollableSheet(
              initialChildSize: 0.85,
              minChildSize: 0.5,
              maxChildSize: 0.95,
              expand: false,
              builder: (_, scrollController) {
                return Padding(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
                  child: Column(
                    children: [
                      Container(
                        width: 40,
                        height: 5,
                        decoration: BoxDecoration(
                          color: isDark ? Colors.white24 : Colors.black12,
                          borderRadius: BorderRadius.circular(3),
                        ),
                      ),
                      const SizedBox(height: 16),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Row(
                            children: [
                              const Icon(Icons.shopping_bag_rounded, color: AppTheme.primary, size: 24),
                              const SizedBox(width: 8),
                              Text(
                                'My Shopping Cart',
                                style: TextStyle(
                                  fontSize: 20,
                                  fontWeight: FontWeight.bold,
                                  color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                ),
                              ),
                            ],
                          ),
                          if (cart.isNotEmpty)
                            TextButton(
                              onPressed: () {
                                widget.appState.clearCart();
                                setSheetState(() {});
                              },
                              child: const Text('Clear All', style: TextStyle(color: AppTheme.dangerRed)),
                            ),
                        ],
                      ),
                      const Divider(height: 20),

                      if (cart.isEmpty)
                        Expanded(
                          child: Center(
                            child: EmptyStateView(
                              icon: Icons.remove_shopping_cart_rounded,
                              title: 'Your Cart is Empty',
                              description: 'Explore the GoChat marketplace and add items from verified sellers.',
                            ),
                          ),
                        )
                      else ...[
                        Expanded(
                          child: ListView.separated(
                            controller: scrollController,
                            itemCount: cart.length,
                            separatorBuilder: (_, __) => const SizedBox(height: 12),
                            itemBuilder: (_, index) {
                              final p = cart[index];
                              return Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                                  borderRadius: BorderRadius.circular(16),
                                ),
                                child: Row(
                                  children: [
                                    ClipRRect(
                                      borderRadius: BorderRadius.circular(12),
                                      child: Image.network(
                                        p.imageUrl,
                                        width: 60,
                                        height: 60,
                                        fit: BoxFit.cover,
                                        errorBuilder: (_, __, ___) => Container(
                                          width: 60,
                                          height: 60,
                                          color: Colors.grey.shade300,
                                          child: const Icon(Icons.shopping_bag, color: Colors.grey),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment: CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            p.title,
                                            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                          const SizedBox(height: 4),
                                          Text(
                                            p.storeName,
                                            style: TextStyle(fontSize: 12, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                                          ),
                                          const SizedBox(height: 4),
                                          Text(
                                            '\$${p.price.toStringAsFixed(2)}',
                                            style: const TextStyle(fontWeight: FontWeight.bold, color: AppTheme.primary, fontSize: 15),
                                          ),
                                        ],
                                      ),
                                    ),
                                    IconButton(
                                      icon: const Icon(Icons.delete_outline_rounded, color: AppTheme.dangerRed, size: 20),
                                      onPressed: () {
                                        widget.appState.removeFromCart(p.id);
                                        setSheetState(() {});
                                      },
                                    ),
                                  ],
                                ),
                              );
                            },
                          ),
                        ),

                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                          decoration: BoxDecoration(
                            color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(color: isDark ? Colors.white12 : Colors.black12),
                          ),
                          child: Row(
                            children: [
                              const Icon(Icons.discount_rounded, color: AppTheme.primary, size: 18),
                              const SizedBox(width: 10),
                              Expanded(
                                child: TextField(
                                  decoration: const InputDecoration(
                                    hintText: 'Enter Coupon (e.g. GOCHAT10)',
                                    border: InputBorder.none,
                                    isDense: true,
                                  ),
                                  onChanged: (val) => promoCode = val.trim().toUpperCase(),
                                ),
                              ),
                              TextButton(
                                onPressed: () {
                                  if (promoCode == 'GOCHAT10' || promoCode == 'SAVE10') {
                                    setSheetState(() {
                                      discount = subtotal * 0.10;
                                    });
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      const SnackBar(content: Text('🎉 Coupon applied! 10% Discount saved.')),
                                    );
                                  } else {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      const SnackBar(content: Text('Invalid coupon code. Try "GOCHAT10"')),
                                    );
                                  }
                                },
                                child: const Text('Apply', style: TextStyle(fontWeight: FontWeight.bold, color: AppTheme.primary)),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 16),

                        Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: Column(
                            children: [
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Text('Subtotal', style: TextStyle(color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                                  Text('\$${subtotal.toStringAsFixed(2)}', style: const TextStyle(fontWeight: FontWeight.w600)),
                                ],
                              ),
                              if (discount > 0) ...[
                                const SizedBox(height: 6),
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                  children: [
                                    const Text('Promo Discount', style: TextStyle(color: AppTheme.onlineGreen)),
                                    Text('-\$${discount.toStringAsFixed(2)}', style: const TextStyle(fontWeight: FontWeight.bold, color: AppTheme.onlineGreen)),
                                  ],
                                ),
                              ],
                              const SizedBox(height: 6),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Text('Escrow Protection & Shipping', style: TextStyle(color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                                  const Text('FREE', style: TextStyle(fontWeight: FontWeight.bold, color: AppTheme.primary)),
                                ],
                              ),
                              const Divider(height: 18),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  const Text('Grand Total', style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
                                  Text(
                                    '\$${grandTotal.toStringAsFixed(2)}',
                                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: AppTheme.primary),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 16),

                        SizedBox(
                          width: double.infinity,
                          child: ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppTheme.primary,
                              foregroundColor: Colors.black,
                              padding: const EdgeInsets.symmetric(vertical: 16),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                              elevation: 2,
                            ),
                            icon: const Icon(Icons.lock_outline_rounded, size: 20),
                            label: Text(
                              'Pay \$${grandTotal.toStringAsFixed(2)} via GoChat Escrow',
                              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                            ),
                            onPressed: () {
                              final orderId = 'ORD-${DateTime.now().millisecondsSinceEpoch.toString().substring(7)}';
                              widget.appState.clearCart();
                              Navigator.pop(ctx);
                              _showOrderSuccessDialog(orderId, grandTotal, cart);
                            },
                          ),
                        ),
                      ],
                    ],
                  ),
                );
              },
            );
          },
        );
      },
    );
  }

  void _showOrderSuccessDialog(String orderId, double total, List<Product> items) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.15),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.check_circle_rounded, color: AppTheme.primary, size: 48),
            ),
            const SizedBox(height: 16),
            const Text(
              'Order Placed Successfully!',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'Order ID: #$orderId\nAmount: \$${total.toStringAsFixed(2)} (Escrow Secured)',
              style: const TextStyle(fontSize: 14, color: AppTheme.textMuted),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            const Text(
              'Funds are securely held in GoChat Escrow until you receive and verify your item.',
              style: TextStyle(fontSize: 12),
              textAlign: TextAlign.center,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Done'),
          ),
          ElevatedButton.icon(
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.black,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            icon: const Icon(Icons.send_rounded, size: 16),
            label: const Text('Share to Chat'),
            onPressed: () async {
              Navigator.pop(ctx);
              if (items.isNotEmpty) {
                await _chatWithSeller(items.first);
              }
            },
          ),
        ],
      ),
    );
  }

  void _showSellProductModal() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final titleCtrl = TextEditingController();
    final priceCtrl = TextEditingController();
    final descCtrl = TextEditingController();
    final imageCtrl = TextEditingController();
    String category = 'Electronics';

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) {
        return Padding(
          padding: EdgeInsets.only(
            bottom: MediaQuery.of(ctx).viewInsets.bottom + 24,
            left: 20,
            right: 20,
            top: 16,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                child: Container(
                  width: 40,
                  height: 5,
                  decoration: BoxDecoration(
                    color: isDark ? Colors.white24 : Colors.black12,
                    borderRadius: BorderRadius.circular(3),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              const Row(
                children: [
                  Icon(Icons.storefront_rounded, color: AppTheme.primary, size: 24),
                  SizedBox(width: 8),
                  Text(
                    'Sell an Item on GoChat',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                  ),
                ],
              ),
              const SizedBox(height: 16),

              TextField(
                controller: titleCtrl,
                decoration: const InputDecoration(
                  labelText: 'Product Title',
                  hintText: 'e.g. Samsung Galaxy S24 Ultra',
                  prefixIcon: Icon(Icons.title_rounded),
                ),
              ),
              const SizedBox(height: 12),

              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: priceCtrl,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(
                        labelText: 'Price (\$)',
                        hintText: 'e.g. 299.99',
                        prefixIcon: Icon(Icons.attach_money_rounded),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      value: category,
                      decoration: const InputDecoration(
                        labelText: 'Category',
                        prefixIcon: Icon(Icons.category_rounded),
                      ),
                      items: ['Electronics', 'Fashion', 'Gaming', 'Home', 'Services', 'Phones']
                          .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                          .toList(),
                      onChanged: (val) {
                        if (val != null) category = val;
                      },
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),

              TextField(
                controller: imageCtrl,
                decoration: const InputDecoration(
                  labelText: 'Image URL (Optional)',
                  hintText: 'https://...',
                  prefixIcon: Icon(Icons.image_rounded),
                ),
              ),
              const SizedBox(height: 12),

              TextField(
                controller: descCtrl,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: 'Description & Specs',
                  hintText: 'Describe condition, warranty, features...',
                ),
              ),
              const SizedBox(height: 20),

              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppTheme.primary,
                    foregroundColor: Colors.black,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  icon: const Icon(Icons.check_circle_rounded),
                  label: const Text('Publish Product', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                  onPressed: () {
                    if (titleCtrl.text.trim().isEmpty || priceCtrl.text.trim().isEmpty) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Please enter product title and price')),
                      );
                      return;
                    }
                    final price = double.tryParse(priceCtrl.text.trim()) ?? 100.0;
                    final newProduct = Product(
                      id: 'user_prod_${DateTime.now().millisecondsSinceEpoch}',
                      title: titleCtrl.text.trim(),
                      description: descCtrl.text.trim().isNotEmpty ? descCtrl.text.trim() : 'Listed on GoChat Marketplace.',
                      price: price,
                      originalPrice: price * 1.2,
                      category: category,
                      storeName: widget.appState.currentUser?.displayName ?? 'My Store',
                      sellerPin: widget.appState.currentUser?.pin ?? '1P0YE4WZ',
                      imageUrl: imageCtrl.text.trim().isNotEmpty
                          ? imageCtrl.text.trim()
                          : 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80',
                      rating: 5.0,
                      reviewsCount: 1,
                      isVerifiedSeller: true,
                    );
                    widget.appState.addProduct(newProduct);
                    Navigator.pop(ctx);
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('🎉 Product listed live on GoChat Marketplace!')),
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final products = _filteredProducts;
    final cartCount = widget.appState.cart.length;

    return Scaffold(
      backgroundColor: isDark ? AppTheme.darkBg : AppTheme.lightBg,
      appBar: AppBar(
        title: const Text('Marketplace', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          Stack(
            alignment: Alignment.center,
            children: [
              IconButton(
                icon: const Icon(Icons.shopping_bag_outlined),
                tooltip: 'Shopping Cart',
                onPressed: _showCartSheet,
              ),
              if (cartCount > 0)
                Positioned(
                  top: 8,
                  right: 8,
                  child: Container(
                    padding: const EdgeInsets.all(4),
                    decoration: const BoxDecoration(
                      color: AppTheme.dangerRed,
                      shape: BoxShape.circle,
                    ),
                    constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
                    child: Text(
                      '$cartCount',
                      style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
                      textAlign: TextAlign.center,
                    ),
                  ),
                ),
            ],
          ),
          IconButton(
            icon: const Icon(Icons.add_business_rounded),
            tooltip: 'Sell on GoChat',
            onPressed: _showSellProductModal,
          ),
        ],
      ),
      body: CustomScrollView(
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: Container(
                decoration: BoxDecoration(
                  color: isDark ? AppTheme.darkCard : Colors.grey.shade200,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: TextField(
                  controller: _searchController,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    hintText: 'Search products, stores, or gadgets...',
                    prefixIcon: const Icon(Icons.search_rounded, color: AppTheme.primary),
                    suffixIcon: _searchController.text.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear_rounded),
                            onPressed: () {
                              _searchController.clear();
                              setState(() {});
                            },
                          )
                        : null,
                    border: InputBorder.none,
                    contentPadding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
              ),
            ),
          ),

          SliverToBoxAdapter(
            child: Container(
              height: 110,
              margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: PageView.builder(
                itemCount: _heroBanners.length,
                itemBuilder: (_, index) {
                  final banner = _heroBanners[index];
                  return Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [
                          (banner['color'] as Color).withValues(alpha: 0.9),
                          (banner['color'] as Color).withValues(alpha: 0.65),
                        ],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(20),
                      boxShadow: [
                        BoxShadow(
                          color: (banner['color'] as Color).withValues(alpha: 0.3),
                          blurRadius: 10,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                decoration: BoxDecoration(
                                  color: Colors.black.withValues(alpha: 0.3),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: Text(
                                  banner['tag'],
                                  style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
                                ),
                              ),
                              const SizedBox(height: 6),
                              Text(
                                banner['title'],
                                style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.bold),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              const SizedBox(height: 2),
                              Text(
                                banner['subtitle'],
                                style: const TextStyle(color: Colors.white70, fontSize: 11),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ],
                          ),
                        ),
                        Icon(banner['icon'] as IconData, color: Colors.white, size: 34),
                      ],
                    ),
                  );
                },
              ),
            ),
          ),

          SliverToBoxAdapter(
            child: SizedBox(
              height: 44,
              child: ListView.separated(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                scrollDirection: Axis.horizontal,
                itemCount: _categories.length,
                separatorBuilder: (_, __) => const SizedBox(width: 8),
                itemBuilder: (_, index) {
                  final cat = _categories[index];
                  final isSelected = _selectedCategory == cat['name'];
                  return ChoiceChip(
                    avatar: Icon(
                      cat['icon'] as IconData,
                      size: 16,
                      color: isSelected ? Colors.black : (isDark ? Colors.white70 : Colors.black87),
                    ),
                    label: Text(
                      cat['name'],
                      style: TextStyle(
                        fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                        color: isSelected ? Colors.black : (isDark ? Colors.white70 : Colors.black87),
                      ),
                    ),
                    selected: isSelected,
                    selectedColor: AppTheme.primary,
                    backgroundColor: isDark ? AppTheme.darkCard : Colors.grey.shade200,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    onSelected: (_) => setState(() => _selectedCategory = cat['name']),
                  );
                },
              ),
            ),
          ),

          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: Row(
                children: [
                  Text(
                    '${products.length} Products Available',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.bold,
                      color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                    ),
                  ),
                  const Spacer(),
                  FilterChip(
                    label: const Text('Verified', style: TextStyle(fontSize: 11)),
                    avatar: const Icon(Icons.verified_rounded, size: 14, color: AppTheme.primary),
                    selected: _verifiedOnly,
                    onSelected: (val) => setState(() => _verifiedOnly = val),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                ],
              ),
            ),
          ),

          if (products.isEmpty)
            SliverFillRemaining(
              hasScrollBody: false,
              child: Center(
                child: EmptyStateView(
                  icon: Icons.search_off_rounded,
                  title: 'No Products Found',
                  description: 'Try changing your search term or selecting another category.',
                ),
              ),
            )
          else
            SliverPadding(
              padding: const EdgeInsets.all(16),
              sliver: SliverGrid(
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 2,
                  childAspectRatio: 0.68,
                  crossAxisSpacing: 14,
                  mainAxisSpacing: 14,
                ),
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final product = products[index];
                    return _buildProductCard(product, isDark);
                  },
                  childCount: products.length,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildProductCard(Product product, bool isDark) {
    return InkWell(
      onTap: () => _showProductDetail(product),
      borderRadius: BorderRadius.circular(18),
      child: Container(
        decoration: BoxDecoration(
          color: isDark ? AppTheme.darkCard : Colors.white,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(
            color: isDark ? Colors.white12 : Colors.grey.shade200,
            width: 1,
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: isDark ? 0.2 : 0.05),
              blurRadius: 8,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              flex: 5,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ClipRRect(
                    borderRadius: const BorderRadius.vertical(top: Radius.circular(18)),
                    child: Image.network(
                      product.imageUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => Container(
                        color: isDark ? Colors.white10 : Colors.grey.shade100,
                        child: const Icon(Icons.shopping_bag_outlined, color: AppTheme.primary, size: 36),
                      ),
                    ),
                  ),
                  if (product.hasDiscount)
                    Positioned(
                      top: 8,
                      left: 8,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                        decoration: BoxDecoration(
                          color: AppTheme.dangerRed,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          '-${product.discountPercent}%',
                          style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
                        ),
                      ),
                    ),
                  Positioned(
                    top: 4,
                    right: 4,
                    child: IconButton(
                      icon: Icon(
                        product.isWishlisted ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                        color: product.isWishlisted ? AppTheme.dangerRed : Colors.white,
                        size: 20,
                      ),
                      onPressed: () => widget.appState.toggleWishlist(product.id),
                    ),
                  ),
                ],
              ),
            ),

            Expanded(
              flex: 6,
              child: Padding(
                padding: const EdgeInsets.all(10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Flexible(
                              child: Text(
                                product.storeName,
                                style: TextStyle(
                                  fontSize: 11,
                                  color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            if (product.isVerifiedSeller) ...[
                              const SizedBox(width: 3),
                              const Icon(Icons.verified_rounded, color: AppTheme.primary, size: 12),
                            ],
                          ],
                        ),
                        const SizedBox(height: 3),
                        Text(
                          product.title,
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),

                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.star_rounded, color: Colors.amber, size: 14),
                            const SizedBox(width: 2),
                            Text(
                              '${product.rating}',
                              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '\$${product.price.toStringAsFixed(0)}',
                                  style: const TextStyle(
                                    fontWeight: FontWeight.bold,
                                    fontSize: 16,
                                    color: AppTheme.primary,
                                  ),
                                ),
                                if (product.hasDiscount)
                                  Text(
                                    '\$${product.originalPrice.toStringAsFixed(0)}',
                                    style: TextStyle(
                                      fontSize: 10,
                                      color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                      decoration: TextDecoration.lineThrough,
                                    ),
                                  ),
                              ],
                            ),
                            InkWell(
                              onTap: () {
                                widget.appState.addToCart(product);
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(
                                    content: Text('Added "${product.title}" to cart!'),
                                    duration: const Duration(seconds: 2),
                                    action: SnackBarAction(
                                      label: 'VIEW CART',
                                      textColor: AppTheme.primary,
                                      onPressed: _showCartSheet,
                                    ),
                                  ),
                                );
                              },
                              borderRadius: BorderRadius.circular(10),
                              child: Container(
                                padding: const EdgeInsets.all(6),
                                decoration: BoxDecoration(
                                  color: AppTheme.primary,
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: const Icon(Icons.add_shopping_cart_rounded, color: Colors.black, size: 16),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
