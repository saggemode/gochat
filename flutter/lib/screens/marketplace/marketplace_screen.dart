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

  List<Product> get _filteredProducts {
    var list = List<Product>.from(widget.appState.products);

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

  // ── Open DM with Seller ─────────────────────────────────────────────────────
  Future<void> _chatWithSeller(Product product) async {
    final sellerPin = product.sellerPin.isNotEmpty ? product.sellerPin : (widget.appState.currentUser?.pin ?? '1P0YE4WZ');
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

  // ── Store Dashboard / Setup Modal ───────────────────────────────────────────
  void _openStoreManager() {
    if (widget.appState.hasStore) {
      _showSellerDashboardModal();
    } else {
      _showCreateStoreModal();
    }
  }

  // ── Step 1: Create Store Profile Modal ──────────────────────────────────────
  void _showCreateStoreModal() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final storeNameCtrl = TextEditingController(text: widget.appState.currentUser?.displayName != null ? '${widget.appState.currentUser!.displayName}\'s Store' : '');
    final locationCtrl = TextEditingController();
    final phoneCtrl = TextEditingController(text: widget.appState.currentUser?.phone ?? '');
    final descCtrl = TextEditingController();
    final logoCtrl = TextEditingController();
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
          child: SingleChildScrollView(
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
                    Icon(Icons.store_rounded, color: AppTheme.primary, size: 28),
                    SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Step 1: Set Up Your Store',
                            style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                          ),
                          Text(
                            'Create your merchant profile on GoChat',
                            style: TextStyle(fontSize: 12, color: AppTheme.textMuted),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const Divider(height: 24),

                TextField(
                  controller: storeNameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store / Business Name *',
                    hintText: 'e.g. Lagos Tech Mart',
                    prefixIcon: Icon(Icons.storefront_rounded),
                  ),
                ),
                const SizedBox(height: 12),

                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        value: category,
                        decoration: const InputDecoration(
                          labelText: 'Industry / Category',
                          prefixIcon: Icon(Icons.category_rounded),
                        ),
                        items: ['Electronics', 'Fashion', 'Gaming', 'Home', 'Services', 'Phones', 'General Retail']
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
                  controller: locationCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store Location / Address *',
                    hintText: 'e.g. 14 Broad Street, Lagos, Nigeria',
                    prefixIcon: Icon(Icons.location_on_rounded),
                  ),
                ),
                const SizedBox(height: 12),

                TextField(
                  controller: phoneCtrl,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                    labelText: 'Business Phone / WhatsApp',
                    hintText: '+234...',
                    prefixIcon: Icon(Icons.phone_rounded),
                  ),
                ),
                const SizedBox(height: 12),

                TextField(
                  controller: logoCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store Logo URL (Optional)',
                    hintText: 'https://...',
                    prefixIcon: Icon(Icons.image_rounded),
                  ),
                ),
                const SizedBox(height: 12),

                TextField(
                  controller: descCtrl,
                  maxLines: 2,
                  decoration: const InputDecoration(
                    labelText: 'Store Bio / Description',
                    hintText: 'Tell buyers what you sell, return policy, delivery info...',
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
                    label: const Text(
                      'Create My Store & Start Selling',
                      style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                    ),
                    onPressed: () async {
                      if (storeNameCtrl.text.trim().isEmpty || locationCtrl.text.trim().isEmpty) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Please enter store name and location')),
                        );
                        return;
                      }

                      final newStore = StoreProfile(
                        id: 'store_${DateTime.now().millisecondsSinceEpoch}',
                        userId: widget.appState.currentUser?.id ?? '',
                        storeName: storeNameCtrl.text.trim(),
                        category: category,
                        address: locationCtrl.text.trim(),
                        phone: phoneCtrl.text.trim(),
                        description: descCtrl.text.trim(),
                        logoUrl: logoCtrl.text.trim(),
                        ownerPin: widget.appState.currentUser?.pin ?? '',
                        isVerified: true,
                        createdAt: DateTime.now(),
                      );

                      final nav = Navigator.of(ctx);
                      await widget.appState.createStore(newStore);
                      if (!mounted) return;
                      nav.pop();
                      _showAddProductModal();
                    },
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  // ── Step 2: Add Product Under Store Modal ────────────────────────────────────
  void _showAddProductModal() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final titleCtrl = TextEditingController();
    final priceCtrl = TextEditingController();
    final originalPriceCtrl = TextEditingController();
    final descCtrl = TextEditingController();
    final imageCtrl = TextEditingController();
    String category = widget.appState.myStore?.category ?? 'Electronics';

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
          child: SingleChildScrollView(
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
                Row(
                  children: [
                    const Icon(Icons.add_shopping_cart_rounded, color: AppTheme.primary, size: 26),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Add Product to Store',
                            style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                          ),
                          Text(
                            'Selling from: ${widget.appState.myStore?.storeName ?? 'My Store'}',
                            style: const TextStyle(fontSize: 12, color: AppTheme.primary, fontWeight: FontWeight.bold),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const Divider(height: 20),

                TextField(
                  controller: titleCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Product Name / Title *',
                    hintText: 'e.g. iPhone 15 Pro Max 256GB',
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
                          labelText: 'Selling Price (\$) *',
                          hintText: 'e.g. 999.00',
                          prefixIcon: Icon(Icons.attach_money_rounded),
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextField(
                        controller: originalPriceCtrl,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: const InputDecoration(
                          labelText: 'Original Price (Optional)',
                          hintText: 'e.g. 1199.00',
                          prefixIcon: Icon(Icons.money_off_rounded),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),

                DropdownButtonFormField<String>(
                  value: category,
                  decoration: const InputDecoration(
                    labelText: 'Product Category',
                    prefixIcon: Icon(Icons.category_rounded),
                  ),
                  items: ['Electronics', 'Fashion', 'Gaming', 'Home', 'Services', 'Phones', 'General Retail']
                      .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                      .toList(),
                  onChanged: (val) {
                    if (val != null) category = val;
                  },
                ),
                const SizedBox(height: 12),

                TextField(
                  controller: imageCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Product Image URL',
                    hintText: 'https://images.unsplash.com/...',
                    prefixIcon: Icon(Icons.image_rounded),
                  ),
                ),
                const SizedBox(height: 12),

                TextField(
                  controller: descCtrl,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    labelText: 'Product Description & Specs',
                    hintText: 'Condition, key features, packaging, warranty details...',
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
                    icon: const Icon(Icons.publish_rounded),
                    label: const Text('Publish Product Live', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                    onPressed: () async {
                      if (titleCtrl.text.trim().isEmpty || priceCtrl.text.trim().isEmpty) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Please enter product title and selling price')),
                        );
                        return;
                      }

                      final price = double.tryParse(priceCtrl.text.trim()) ?? 0.0;
                      final origPrice = double.tryParse(originalPriceCtrl.text.trim()) ?? (price * 1.25);

                      final newProduct = Product(
                        id: 'prod_${DateTime.now().millisecondsSinceEpoch}',
                        title: titleCtrl.text.trim(),
                        description: descCtrl.text.trim().isNotEmpty ? descCtrl.text.trim() : 'Available on GoChat Marketplace.',
                        price: price,
                        originalPrice: origPrice,
                        category: category,
                        storeName: widget.appState.myStore?.storeName ?? (widget.appState.currentUser?.displayName ?? 'My Store'),
                        sellerId: widget.appState.currentUser?.id ?? '',
                        sellerPin: widget.appState.currentUser?.pin ?? '',
                        sellerLocation: widget.appState.myStore?.address ?? 'Lagos, Nigeria',
                        imageUrl: imageCtrl.text.trim().isNotEmpty
                            ? imageCtrl.text.trim()
                            : 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80',
                        rating: 5.0,
                        reviewsCount: 0,
                        isVerifiedSeller: true,
                        inStock: true,
                      );

                      final nav = Navigator.of(ctx);
                      final messenger = ScaffoldMessenger.of(context);
                      await widget.appState.createStoreProduct(newProduct);
                      if (!mounted) return;
                      nav.pop();
                      messenger.showSnackBar(
                        SnackBar(
                          content: Text('🎉 "${newProduct.title}" is now published on the Marketplace!'),
                          backgroundColor: AppTheme.primary,
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  // ── Seller Dashboard Modal ──────────────────────────────────────────────────
  void _showSellerDashboardModal() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final store = widget.appState.myStore;
    final myProducts = widget.appState.products.where((p) => p.sellerPin == widget.appState.currentUser?.pin || p.sellerId == widget.appState.currentUser?.id).toList();

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

                // Store Banner Card
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        AppTheme.primary.withValues(alpha: 0.2),
                        isDark ? AppTheme.darkCard : Colors.grey.shade100,
                      ],
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3)),
                  ),
                  child: Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(14),
                        decoration: const BoxDecoration(
                          color: AppTheme.primary,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.store_rounded, color: Colors.black, size: 28),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text(
                                  store?.storeName ?? 'My Store',
                                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                                ),
                                const SizedBox(width: 4),
                                const Icon(Icons.verified_rounded, color: AppTheme.primary, size: 16),
                              ],
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '${store?.category ?? 'Retail'} • ${store?.address ?? 'Lagos, Nigeria'}',
                              style: TextStyle(fontSize: 12, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'GOCHAT PIN: ${widget.appState.currentUser?.pin ?? '8492A1'}',
                              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: AppTheme.primary),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),

                // Action Buttons
                Row(
                  children: [
                    Expanded(
                      child: ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppTheme.primary,
                          foregroundColor: Colors.black,
                          padding: const EdgeInsets.symmetric(vertical: 14),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                        ),
                        icon: const Icon(Icons.add_rounded, size: 20),
                        label: const Text('Add Product', style: TextStyle(fontWeight: FontWeight.bold)),
                        onPressed: () {
                          Navigator.pop(ctx);
                          _showAddProductModal();
                        },
                      ),
                    ),
                    const SizedBox(width: 10),
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      ),
                      icon: const Icon(Icons.edit_rounded, size: 16),
                      label: const Text('Edit Store'),
                      onPressed: () {
                        Navigator.pop(ctx);
                        _showCreateStoreModal();
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 20),

                // My Store Products List
                Text(
                  'STORE INVENTORY (${myProducts.length})',
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1),
                ),
                const SizedBox(height: 10),

                if (myProducts.isEmpty)
                  Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      children: [
                        const Icon(Icons.inventory_2_outlined, size: 48, color: AppTheme.textMuted),
                        const SizedBox(height: 10),
                        const Text('No products listed yet', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                        const SizedBox(height: 4),
                        const Text('Start adding products so GoChat buyers can discover and purchase from your store.', style: TextStyle(fontSize: 12, color: AppTheme.textMuted), textAlign: TextAlign.center),
                        const SizedBox(height: 16),
                        ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppTheme.primary,
                            foregroundColor: Colors.black,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                          ),
                          icon: const Icon(Icons.add_rounded),
                          label: const Text('Add First Product'),
                          onPressed: () {
                            Navigator.pop(ctx);
                            _showAddProductModal();
                          },
                        ),
                      ],
                    ),
                  )
                else
                  ListView.separated(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: myProducts.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 10),
                    itemBuilder: (_, index) {
                      final p = myProducts[index];
                      return Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Row(
                          children: [
                            ClipRRect(
                              borderRadius: BorderRadius.circular(10),
                              child: Image.network(
                                p.imageUrl,
                                width: 50,
                                height: 50,
                                fit: BoxFit.cover,
                                errorBuilder: (_, __, ___) => Container(
                                  width: 50,
                                  height: 50,
                                  color: Colors.grey.shade300,
                                  child: const Icon(Icons.shopping_bag),
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(p.title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14), maxLines: 1, overflow: TextOverflow.ellipsis),
                                  const SizedBox(height: 2),
                                  Text('\$${p.price.toStringAsFixed(2)} • ${p.category}', style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold, fontSize: 13)),
                                ],
                              ),
                            ),
                            const Icon(Icons.check_circle_rounded, color: AppTheme.onlineGreen, size: 18),
                          ],
                        ),
                      );
                    },
                  ),
              ],
            );
          },
        );
      },
    );
  }

  // ── Product Detail Bottom Sheet ─────────────────────────────────────────────
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

  // ── Cart & Checkout Sheet ───────────────────────────────────────────────────
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

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final products = _filteredProducts;
    final cartCount = widget.appState.cart.length;
    final hasStore = widget.appState.hasStore;

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
          TextButton.icon(
            style: TextButton.styleFrom(
              backgroundColor: AppTheme.primary.withValues(alpha: 0.15),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            ),
            icon: Icon(hasStore ? Icons.store_rounded : Icons.add_business_rounded, color: AppTheme.primary, size: 18),
            label: Text(
              hasStore ? 'My Store' : 'Open Store',
              style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold, fontSize: 12),
            ),
            onPressed: _openStoreManager,
          ),
          const SizedBox(width: 8),
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
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: AppTheme.primary.withValues(alpha: 0.15),
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.storefront_rounded, size: 60, color: AppTheme.primary),
                      ),
                      const SizedBox(height: 18),
                      const Text(
                        'Marketplace is Open!',
                        style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'No items listed yet. Set up your store and be the first to publish products to GoChat buyers!',
                        style: TextStyle(fontSize: 14, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 24),
                      ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppTheme.primary,
                          foregroundColor: Colors.black,
                          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                          elevation: 2,
                        ),
                        icon: const Icon(Icons.add_business_rounded, size: 20),
                        label: Text(
                          hasStore ? 'Add Products to ${widget.appState.myStore?.storeName}' : 'Open My Store Now',
                          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                        ),
                        onPressed: _openStoreManager,
                      ),
                    ],
                  ),
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
