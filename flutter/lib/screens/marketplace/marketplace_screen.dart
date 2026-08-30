import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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

class _MarketplaceScreenState extends State<MarketplaceScreen> with SingleTickerProviderStateMixin {
  late TabController _mainTabController;
  int _currentMainTabIndex = 0;

  // Marketplace Feed State
  String _selectedCategory = 'All';
  final TextEditingController _searchController = TextEditingController();
  bool _verifiedOnly = false;
  final String _sortBy = 'popular';

  // Step 1: Store Creation Form Controllers
  final _storeNameCtrl = TextEditingController();
  final _storeLocationCtrl = TextEditingController();
  final _storePhoneCtrl = TextEditingController();
  final _storeDescCtrl = TextEditingController();
  final _storeLogoCtrl = TextEditingController();
  final _storeBannerCtrl = TextEditingController();
  String _storeCategory = 'Electronics';
  bool _isCreatingStore = false;

  final List<Map<String, dynamic>> _categories = [
    {'name': 'All', 'icon': Icons.grid_view_rounded},
    {'name': 'Electronics', 'icon': Icons.devices_rounded},
    {'name': 'Fashion', 'icon': Icons.checkroom_rounded},
    {'name': 'Gaming', 'icon': Icons.sports_esports_rounded},
    {'name': 'Home', 'icon': Icons.weekend_rounded},
    {'name': 'Services', 'icon': Icons.handyman_rounded},
    {'name': 'Phones', 'icon': Icons.smartphone_rounded},
  ];

  // Preset Sizes & Colors for Variant Builder
  static const List<String> _presetSizes = [
    'XS', 'S', 'M', 'L', 'XL', '2XL', '3XL',
    'US 7', 'US 8', 'US 9', 'US 10', 'US 11', 'US 12',
    'EU 38', 'EU 40', 'EU 42', 'EU 44', '256GB', '512GB', '1TB'
  ];

  static const List<Map<String, dynamic>> _presetColors = [
    {'name': 'Black', 'color': Color(0xFF18181B)},
    {'name': 'White', 'color': Color(0xFFFFFFFF)},
    {'name': 'Red', 'color': Color(0xFFEF4444)},
    {'name': 'Blue', 'color': Color(0xFF3B82F6)},
    {'name': 'Green', 'color': Color(0xFF10B981)},
    {'name': 'Gold', 'color': Color(0xFFF59E0B)},
    {'name': 'Silver', 'color': Color(0xFF9CA3AF)},
    {'name': 'Space Gray', 'color': Color(0xFF4B5563)},
    {'name': 'Rose Gold', 'color': Color(0xFFB76E79)},
    {'name': 'Purple', 'color': Color(0xFF8B5CF6)},
  ];

  @override
  void initState() {
    super.initState();
    _mainTabController = TabController(length: 2, vsync: this);
    _mainTabController.addListener(() {
      if (_mainTabController.index != _currentMainTabIndex) {
        setState(() {
          _currentMainTabIndex = _mainTabController.index;
        });
      }
    });
    _initStoreFormDefaults();
  }

  void _initStoreFormDefaults() {
    final user = widget.appState.currentUser;
    if (_storeNameCtrl.text.isEmpty && user?.displayName != null) {
      _storeNameCtrl.text = '${user!.displayName}\'s Store';
    }
    if (_storePhoneCtrl.text.isEmpty && user?.phone != null) {
      _storePhoneCtrl.text = user!.phone;
    }
  }

  @override
  void dispose() {
    _mainTabController.dispose();
    _searchController.dispose();
    _storeNameCtrl.dispose();
    _storeLocationCtrl.dispose();
    _storePhoneCtrl.dispose();
    _storeDescCtrl.dispose();
    _storeLogoCtrl.dispose();
    _storeBannerCtrl.dispose();
    super.dispose();
  }

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

  // ── Open DM with Seller / Buyer ─────────────────────────────────────────────
  Future<void> _openChatWithUser({
    required String pin,
    required String fallbackName,
    String? initialMessage,
  }) async {
    final cleanPin = pin.isNotEmpty ? pin : (widget.appState.currentUser?.pin ?? '1P0YE4WZ');
    final user = await widget.appState.lookupUserByPin(cleanPin);
    final targetTitle = user?.displayName ?? fallbackName;
    final targetId = user?.id ?? cleanPin;

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
        partnerPin: cleanPin,
      );
    }

    if (initialMessage != null && initialMessage.isNotEmpty) {
      await widget.appState.sendMessage(existingConv.id, initialMessage);
    }

    if (!mounted) return;
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChatRoomScreen(conversation: existingConv!, appState: widget.appState),
      ),
    );
  }

  Future<void> _chatWithSeller(Product product) async {
    await _openChatWithUser(
      pin: product.sellerPin,
      fallbackName: product.storeName,
      initialMessage: '👋 Hi ${product.storeName}! I am interested in purchasing "${product.title}" listed for \$${product.price.toStringAsFixed(2)} on GoChat Marketplace.',
    );
  }

  // ── Step 1: Submit Store Creation ───────────────────────────────────────────
  Future<void> _handleCreateStore() async {
    if (_storeNameCtrl.text.trim().isEmpty || _storeLocationCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please enter store name and location')),
      );
      return;
    }

    setState(() => _isCreatingStore = true);

    final newStore = StoreProfile(
      id: 'store_${DateTime.now().millisecondsSinceEpoch}',
      userId: widget.appState.currentUser?.id ?? '',
      storeName: _storeNameCtrl.text.trim(),
      category: _storeCategory,
      address: _storeLocationCtrl.text.trim(),
      phone: _storePhoneCtrl.text.trim(),
      description: _storeDescCtrl.text.trim(),
      logoUrl: _storeLogoCtrl.text.trim(),
      bannerUrl: _storeBannerCtrl.text.trim(),
      ownerPin: widget.appState.currentUser?.pin ?? '',
      isVerified: true,
      createdAt: DateTime.now(),
    );

    await widget.appState.createStore(newStore);

    if (!mounted) return;
    setState(() => _isCreatingStore = false);

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('🎉 Store "${newStore.storeName}" is live! Add your first product below.'),
        backgroundColor: AppTheme.primary,
      ),
    );

    _showAddOrEditProductModal();
  }

  // ── Step 2: Add / Edit Product Modal with Product Variants ──────────────────
  void _showAddOrEditProductModal({Product? productToEdit}) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final isEditing = productToEdit != null;

    final titleCtrl = TextEditingController(text: productToEdit?.title ?? '');
    final priceCtrl = TextEditingController(text: isEditing ? productToEdit.price.toStringAsFixed(2) : '');
    final originalPriceCtrl = TextEditingController(text: (isEditing && productToEdit.hasDiscount) ? productToEdit.originalPrice.toStringAsFixed(2) : '');
    final descCtrl = TextEditingController(text: productToEdit?.description ?? '');
    final imageCtrl = TextEditingController(text: productToEdit?.imageUrl ?? '');
    String category = productToEdit?.category ?? (widget.appState.myStore?.category ?? 'Electronics');

    // Product Variants Local State
    List<ProductVariant> localVariants = List<ProductVariant>.from(productToEdit?.variants ?? []);
    bool showVariantForm = false;
    String varSelectedSize = '';
    String varSelectedColor = '';
    final varSkuCtrl = TextEditingController();
    final varPriceOverrideCtrl = TextEditingController();
    final varStockCtrl = TextEditingController(text: '10');

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (modalCtx, setModalState) {
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
                        Icon(isEditing ? Icons.edit_note_rounded : Icons.add_shopping_cart_rounded, color: AppTheme.primary, size: 26),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                isEditing ? 'Edit Product' : 'Add Product to Store',
                                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
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
                        if (val != null) setModalState(() => category = val);
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
                      maxLines: 2,
                      decoration: const InputDecoration(
                        labelText: 'Product Description & Specs',
                        hintText: 'Condition, key features, packaging, warranty details...',
                      ),
                    ),
                    const SizedBox(height: 16),

                    // ── PRODUCT VARIANTS SECTION ─────────────────────────────
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: isDark ? Colors.white.withValues(alpha: 0.04) : Colors.grey.shade100,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: isDark ? Colors.white12 : Colors.grey.shade300),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Row(
                                children: [
                                  const Icon(Icons.auto_awesome_rounded, color: AppTheme.primary, size: 18),
                                  const SizedBox(width: 6),
                                  Text(
                                    'Product Variants (${localVariants.length})',
                                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                                  ),
                                ],
                              ),
                              TextButton.icon(
                                style: TextButton.styleFrom(
                                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                  backgroundColor: AppTheme.primary.withValues(alpha: 0.15),
                                ),
                                icon: Icon(showVariantForm ? Icons.close_rounded : Icons.add_rounded, size: 16, color: AppTheme.primary),
                                label: Text(
                                  showVariantForm ? 'Cancel' : '+ Add Variant',
                                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.primary),
                                ),
                                onPressed: () {
                                  setModalState(() {
                                    showVariantForm = !showVariantForm;
                                  });
                                },
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Add size, storage (256GB/512GB), or color variations with individual prices & stock.',
                            style: TextStyle(fontSize: 11, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                          ),

                          // Active Variants Chips List
                          if (localVariants.isNotEmpty) ...[
                            const SizedBox(height: 10),
                            Wrap(
                              spacing: 8,
                              runSpacing: 8,
                              children: localVariants.map((v) {
                                final variantPrice = v.priceOverride != null ? '\$${v.priceOverride!.toStringAsFixed(0)}' : 'Default \$';
                                return Chip(
                                  backgroundColor: isDark ? AppTheme.darkCard : Colors.white,
                                  avatar: const Icon(Icons.style_rounded, size: 14, color: AppTheme.primary),
                                  label: Text(
                                    '${v.title} ($variantPrice • Qty: ${v.stockQuantity})',
                                    style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
                                  ),
                                  deleteIcon: const Icon(Icons.cancel_rounded, size: 16, color: AppTheme.dangerRed),
                                  onDeleted: () {
                                    setModalState(() {
                                      localVariants.removeWhere((item) => item.id == v.id);
                                    });
                                  },
                                );
                              }).toList(),
                            ),
                          ],

                          // Interactive Variant Creation Form
                          if (showVariantForm) ...[
                            const Divider(height: 20),
                            const Text('1. Pick Size or Option:', style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                            const SizedBox(height: 6),
                            Wrap(
                              spacing: 6,
                              runSpacing: 6,
                              children: _presetSizes.map((size) {
                                final isSelected = varSelectedSize == size;
                                return ChoiceChip(
                                  label: Text(size, style: TextStyle(fontSize: 11, fontWeight: isSelected ? FontWeight.bold : FontWeight.normal)),
                                  selected: isSelected,
                                  selectedColor: AppTheme.primary,
                                  onSelected: (val) {
                                    setModalState(() => varSelectedSize = val ? size : '');
                                  },
                                );
                              }).toList(),
                            ),
                            const SizedBox(height: 10),

                            const Text('2. Pick Color:', style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                            const SizedBox(height: 6),
                            Wrap(
                              spacing: 6,
                              runSpacing: 6,
                              children: _presetColors.map((c) {
                                final name = c['name'] as String;
                                final color = c['color'] as Color;
                                final isSelected = varSelectedColor == name;
                                return ChoiceChip(
                                  avatar: Container(
                                    width: 12,
                                    height: 12,
                                    decoration: BoxDecoration(
                                      color: color,
                                      shape: BoxShape.circle,
                                      border: Border.all(color: Colors.grey, width: 0.5),
                                    ),
                                  ),
                                  label: Text(name, style: TextStyle(fontSize: 11, fontWeight: isSelected ? FontWeight.bold : FontWeight.normal)),
                                  selected: isSelected,
                                  selectedColor: AppTheme.primary,
                                  onSelected: (val) {
                                    setModalState(() => varSelectedColor = val ? name : '');
                                  },
                                );
                              }).toList(),
                            ),
                            const SizedBox(height: 12),

                            Row(
                              children: [
                                Expanded(
                                  child: TextField(
                                    controller: varPriceOverrideCtrl,
                                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                                    decoration: const InputDecoration(
                                      labelText: 'Variant Price (\$) (Optional)',
                                      hintText: 'e.g. 1099.00',
                                      isDense: true,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: TextField(
                                    controller: varStockCtrl,
                                    keyboardType: TextInputType.number,
                                    decoration: const InputDecoration(
                                      labelText: 'Stock Qty',
                                      hintText: '10',
                                      isDense: true,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 10),

                            SizedBox(
                              width: double.infinity,
                              child: ElevatedButton.icon(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppTheme.primary,
                                  foregroundColor: Colors.black,
                                  padding: const EdgeInsets.symmetric(vertical: 10),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                ),
                                icon: const Icon(Icons.check_rounded, size: 16),
                                label: const Text('Add This Variant', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                                onPressed: () {
                                  final titleParts = <String>[];
                                  final attrs = <String, dynamic>{};

                                  if (varSelectedSize.isNotEmpty) {
                                    titleParts.add(varSelectedSize);
                                    attrs['size'] = varSelectedSize;
                                  }
                                  if (varSelectedColor.isNotEmpty) {
                                    titleParts.add(varSelectedColor);
                                    attrs['color'] = varSelectedColor;
                                  }

                                  final varTitle = titleParts.isNotEmpty ? titleParts.join(' / ') : 'Variant ${localVariants.length + 1}';
                                  final overridePrice = double.tryParse(varPriceOverrideCtrl.text.trim());
                                  final stock = int.tryParse(varStockCtrl.text.trim()) ?? 10;

                                  final newVariant = ProductVariant(
                                    id: 'var_${DateTime.now().millisecondsSinceEpoch}',
                                    productId: productToEdit?.id ?? '',
                                    sku: varSkuCtrl.text.trim().isNotEmpty ? varSkuCtrl.text.trim() : 'SKU-${DateTime.now().millisecondsSinceEpoch.toString().substring(8)}',
                                    title: varTitle,
                                    attributes: attrs,
                                    attributesJson: jsonEncode(attrs),
                                    priceOverride: overridePrice,
                                    stockQuantity: stock,
                                    isActive: true,
                                  );

                                  setModalState(() {
                                    localVariants.add(newVariant);
                                    showVariantForm = false;
                                    varSelectedSize = '';
                                    varSelectedColor = '';
                                    varPriceOverrideCtrl.clear();
                                    varStockCtrl.text = '10';
                                  });
                                },
                              ),
                            ),
                          ],
                        ],
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
                        icon: Icon(isEditing ? Icons.save_rounded : Icons.publish_rounded),
                        label: Text(
                          isEditing ? 'Save Product Changes' : 'Publish Product Live',
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                        ),
                        onPressed: () async {
                          if (titleCtrl.text.trim().isEmpty || priceCtrl.text.trim().isEmpty) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('Please enter product title and selling price')),
                            );
                            return;
                          }

                          final price = double.tryParse(priceCtrl.text.trim()) ?? 0.0;
                          final origPrice = double.tryParse(originalPriceCtrl.text.trim()) ?? (price * 1.25);

                          final savedProduct = Product(
                            id: productToEdit?.id ?? 'prod_${DateTime.now().millisecondsSinceEpoch}',
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
                                : (productToEdit?.imageUrl.isNotEmpty == true
                                    ? productToEdit!.imageUrl
                                    : 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80'),
                            rating: productToEdit?.rating ?? 5.0,
                            reviewsCount: productToEdit?.reviewsCount ?? 0,
                            isVerifiedSeller: true,
                            inStock: true,
                            variants: localVariants,
                          );

                          final nav = Navigator.of(ctx);
                          final messenger = ScaffoldMessenger.of(context);

                          if (isEditing) {
                            await widget.appState.updateStoreProduct(savedProduct);
                          } else {
                            await widget.appState.createStoreProduct(savedProduct);
                          }

                          // Save variants remotely if productId exists
                          for (final variant in localVariants) {
                            await widget.appState.addProductVariant(savedProduct.id, variant);
                          }

                          if (!mounted) return;
                          nav.pop();
                          messenger.showSnackBar(
                            SnackBar(
                              content: Text(isEditing ? '✅ "${savedProduct.title}" updated!' : '🎉 "${savedProduct.title}" is now published with ${localVariants.length} variants!'),
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
      },
    );
  }

  // ── Create Store Coupon Modal ───────────────────────────────────────────────
  void _showCreateCouponModal() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final codeCtrl = TextEditingController();
    final valueCtrl = TextEditingController();
    final minSpendCtrl = TextEditingController();
    String discountType = 'percentage';

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
                    Icon(Icons.discount_rounded, color: AppTheme.primary, size: 26),
                    SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Create Promo Discount', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                          Text('Generate a discount code for your store buyers', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
                        ],
                      ),
                    ),
                  ],
                ),
                const Divider(height: 20),

                TextField(
                  controller: codeCtrl,
                  textCapitalization: TextCapitalization.characters,
                  decoration: const InputDecoration(
                    labelText: 'Coupon Code *',
                    hintText: 'e.g. FLASH20, SAVE10, SUMMER',
                    prefixIcon: Icon(Icons.confirmation_number_rounded),
                  ),
                ),
                const SizedBox(height: 12),

                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        value: discountType,
                        decoration: const InputDecoration(labelText: 'Discount Type'),
                        items: const [
                          DropdownMenuItem(value: 'percentage', child: Text('Percentage (%)')),
                          DropdownMenuItem(value: 'fixed', child: Text('Fixed Amount (\$)')),
                        ],
                        onChanged: (val) {
                          if (val != null) discountType = val;
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextField(
                        controller: valueCtrl,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: InputDecoration(
                          labelText: discountType == 'percentage' ? 'Discount % *' : 'Amount (\$) *',
                          hintText: discountType == 'percentage' ? 'e.g. 15' : 'e.g. 25.00',
                          prefixIcon: const Icon(Icons.percent_rounded),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),

                TextField(
                  controller: minSpendCtrl,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(
                    labelText: 'Minimum Order Spend (\$) (Optional)',
                    hintText: 'e.g. 50.00',
                    prefixIcon: Icon(Icons.shopping_bag_outlined),
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
                    label: const Text('Create & Activate Coupon', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                    onPressed: () async {
                      if (codeCtrl.text.trim().isEmpty || valueCtrl.text.trim().isEmpty) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Please enter coupon code and discount value')),
                        );
                        return;
                      }

                      final val = double.tryParse(valueCtrl.text.trim()) ?? 10.0;
                      final minSpend = double.tryParse(minSpendCtrl.text.trim()) ?? 0.0;

                      final coupon = StoreCoupon(
                        id: 'coup_${DateTime.now().millisecondsSinceEpoch}',
                        code: codeCtrl.text.trim().toUpperCase(),
                        type: discountType,
                        value: val,
                        minSpend: minSpend,
                        maxUses: 100,
                        expiresAt: DateTime.now().add(const Duration(days: 30)),
                        isActive: true,
                      );

                      final nav = Navigator.of(ctx);
                      final messenger = ScaffoldMessenger.of(context);
                      await widget.appState.createStoreCoupon(coupon);
                      if (!mounted) return;
                      nav.pop();
                      messenger.showSnackBar(
                        SnackBar(
                          content: Text('🎉 Promo coupon "${coupon.code}" is now active!'),
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

  // ── Tab 2: Seller Hub / Store Setup View (Rendered in Tab) ──────────────────
  Widget _buildSellerHubTab(bool isDark) {
    final hasStore = widget.appState.hasStore;

    if (!hasStore) {
      // ── Step-by-Step Onboarding Form When User Has No Store ───────────────
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        children: [
          // Step Header Card
          Container(
            padding: const EdgeInsets.all(18),
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
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Set Up Your GoChat Store',
                        style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                      ),
                      SizedBox(height: 2),
                      Text(
                        'Complete the steps below to start selling to GoChat buyers.',
                        style: TextStyle(fontSize: 12, color: AppTheme.textMuted),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // Steps Progress Indicator
          Row(
            children: [
              _buildStepIndicator('1', 'Store Info', true),
              _buildStepLine(false),
              _buildStepIndicator('2', 'Add Products', false),
              _buildStepLine(false),
              _buildStepIndicator('3', 'Start Selling', false),
            ],
          ),
          const SizedBox(height: 24),

          // Form Card
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: isDark ? AppTheme.darkCard : Colors.white,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: isDark ? Colors.white12 : Colors.grey.shade200),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'STEP 1: STORE DETAILS',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.primary, letterSpacing: 1),
                ),
                const SizedBox(height: 16),

                TextField(
                  controller: _storeNameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store / Business Name *',
                    hintText: 'e.g. Lagos Tech Mart',
                    prefixIcon: Icon(Icons.storefront_rounded),
                  ),
                ),
                const SizedBox(height: 14),

                DropdownButtonFormField<String>(
                  value: _storeCategory,
                  decoration: const InputDecoration(
                    labelText: 'Industry / Category',
                    prefixIcon: Icon(Icons.category_rounded),
                  ),
                  items: ['Electronics', 'Fashion', 'Gaming', 'Home', 'Services', 'Phones', 'General Retail']
                      .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                      .toList(),
                  onChanged: (val) {
                    if (val != null) setState(() => _storeCategory = val);
                  },
                ),
                const SizedBox(height: 14),

                TextField(
                  controller: _storeLocationCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store Location / Address *',
                    hintText: 'e.g. 14 Broad Street, Lagos, Nigeria',
                    prefixIcon: Icon(Icons.location_on_rounded),
                  ),
                ),
                const SizedBox(height: 14),

                TextField(
                  controller: _storePhoneCtrl,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                    labelText: 'Business Phone / WhatsApp',
                    hintText: '+234...',
                    prefixIcon: Icon(Icons.phone_rounded),
                  ),
                ),
                const SizedBox(height: 14),

                TextField(
                  controller: _storeLogoCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store Logo URL (Optional)',
                    hintText: 'https://...',
                    prefixIcon: Icon(Icons.image_rounded),
                  ),
                ),
                const SizedBox(height: 14),

                TextField(
                  controller: _storeDescCtrl,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    labelText: 'Store Bio / Description',
                    hintText: 'Tell buyers what you sell, return policy, delivery info...',
                  ),
                ),
                const SizedBox(height: 24),

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
                    icon: _isCreatingStore
                        ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black))
                        : const Icon(Icons.rocket_launch_rounded),
                    label: Text(
                      _isCreatingStore ? 'Creating Store...' : 'Create My Store & Start Selling',
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                    ),
                    onPressed: _isCreatingStore ? null : _handleCreateStore,
                  ),
                ),
              ],
            ),
          ),
        ],
      );
    }

    // ── Full Shopify-Style Dashboard When User Has Store (No Create Button!) ──
    final store = widget.appState.myStore;
    final myProducts = widget.appState.products
        .where((p) => p.sellerPin == widget.appState.currentUser?.pin || p.sellerId == widget.appState.currentUser?.id)
        .toList();
    final orders = widget.appState.sellerOrders;
    final coupons = widget.appState.storeCoupons;
    final totalRevenue = orders.fold(0.0, (sum, o) => sum + o.grandTotal);
    final storeViews = myProducts.length * 18 + 42;

    return DefaultTabController(
      length: 5,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: Column(
              children: [
                // Store Header Banner Card
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        AppTheme.primary.withValues(alpha: 0.2),
                        isDark ? AppTheme.darkCard : Colors.grey.shade100,
                      ],
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                    borderRadius: BorderRadius.circular(18),
                    border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3)),
                  ),
                  child: Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: const BoxDecoration(
                          color: AppTheme.primary,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.storefront_rounded, color: Colors.black, size: 24),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Flexible(
                                  child: Text(
                                    store?.storeName ?? 'My Store',
                                    style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                const Icon(Icons.verified_rounded, color: AppTheme.primary, size: 16),
                              ],
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '${store?.category ?? 'Retail'} • PIN: ${widget.appState.currentUser?.pin ?? '8492A1'}',
                              style: const TextStyle(fontSize: 12, color: AppTheme.primary, fontWeight: FontWeight.bold),
                            ),
                          ],
                        ),
                      ),
                      IconButton.filledTonal(
                        style: IconButton.styleFrom(
                          backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
                        ),
                        icon: const Icon(Icons.share_rounded, color: AppTheme.primary, size: 18),
                        tooltip: 'Share Store Link',
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: 'https://gochat.app/store/${widget.appState.currentUser?.pin}'));
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('📋 Store link copied to clipboard!')),
                          );
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 10),

                // Sub Tab Navigation
                TabBar(
                  isScrollable: true,
                  indicatorColor: AppTheme.primary,
                  labelColor: AppTheme.primary,
                  unselectedLabelColor: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                  labelStyle: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                  tabAlignment: TabAlignment.start,
                  tabs: [
                    const Tab(icon: Icon(Icons.analytics_rounded, size: 18), text: 'Overview'),
                    Tab(icon: const Icon(Icons.inventory_2_rounded, size: 18), text: 'Products (${myProducts.length})'),
                    Tab(icon: const Icon(Icons.receipt_long_rounded, size: 18), text: 'Orders (${orders.length})'),
                    Tab(icon: const Icon(Icons.confirmation_number_rounded, size: 18), text: 'Coupons (${coupons.length})'),
                    const Tab(icon: Icon(Icons.settings_rounded, size: 18), text: 'Settings'),
                  ],
                ),
              ],
            ),
          ),
          const Divider(height: 1),

          Expanded(
            child: TabBarView(
              children: [
                // ── Sub-Tab 1: Overview ──────────────────────────────────────
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    const Text('BUSINESS METRICS', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1)),
                    const SizedBox(height: 12),

                    GridView.count(
                      crossAxisCount: 2,
                      crossAxisSpacing: 12,
                      mainAxisSpacing: 12,
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      childAspectRatio: 1.5,
                      children: [
                        _buildMetricCard('GROSS REVENUE', '\$${totalRevenue.toStringAsFixed(2)}', Icons.attach_money_rounded, AppTheme.onlineGreen, isDark),
                        _buildMetricCard('STORE ORDERS', '${orders.length}', Icons.shopping_bag_rounded, Colors.amber, isDark),
                        _buildMetricCard('LISTED PRODUCTS', '${myProducts.length}', Icons.inventory_2_rounded, AppTheme.primary, isDark),
                        _buildMetricCard('STORE VIEWS', '$storeViews', Icons.visibility_rounded, Colors.purpleAccent, isDark),
                      ],
                    ),
                    const SizedBox(height: 20),

                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text('Quick Actions', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: ElevatedButton.icon(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: AppTheme.primary,
                                    foregroundColor: Colors.black,
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                                  ),
                                  icon: const Icon(Icons.add_rounded, size: 18),
                                  label: const Text('+ Add Product', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                                  onPressed: () => _showAddOrEditProductModal(),
                                ),
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: OutlinedButton.icon(
                                  style: OutlinedButton.styleFrom(
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                                  ),
                                  icon: const Icon(Icons.discount_rounded, size: 16),
                                  label: const Text('+ Create Coupon', style: TextStyle(fontSize: 12)),
                                  onPressed: _showCreateCouponModal,
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),

                // ── Sub-Tab 2: Products ──────────────────────────────────────
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('STORE INVENTORY (${myProducts.length})', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1)),
                        ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppTheme.primary,
                            foregroundColor: Colors.black,
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                          ),
                          icon: const Icon(Icons.add_rounded, size: 16),
                          label: const Text('Add Product', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
                          onPressed: () => _showAddOrEditProductModal(),
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),

                    if (myProducts.isEmpty)
                      _buildEmptyTabState(Icons.inventory_2_outlined, 'No products listed yet', 'Add your first product to start receiving customer orders.', isDark)
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
                                    width: 54,
                                    height: 54,
                                    fit: BoxFit.cover,
                                    errorBuilder: (_, __, ___) => Container(
                                      width: 54,
                                      height: 54,
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
                                      Text('\$${p.price.toStringAsFixed(2)} • ${p.category} ${p.hasVariants ? '(${p.variants.length} variations)' : ''}', style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold, fontSize: 13)),
                                    ],
                                  ),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.edit_outlined, size: 20),
                                  tooltip: 'Edit Product',
                                  onPressed: () => _showAddOrEditProductModal(productToEdit: p),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.delete_outline_rounded, color: AppTheme.dangerRed, size: 20),
                                  tooltip: 'Delete Product',
                                  onPressed: () async {
                                    await widget.appState.deleteStoreProduct(p.id);
                                    setState(() {});
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                  ],
                ),

                // ── Sub-Tab 3: Store Orders ──────────────────────────────────
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    const Text('CUSTOMER ORDERS', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1)),
                    const SizedBox(height: 14),

                    if (orders.isEmpty)
                      _buildEmptyTabState(Icons.receipt_long_outlined, 'No store orders yet', 'When buyers purchase items from your store via Escrow, orders will show here.', isDark)
                    else
                      ListView.separated(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: orders.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 12),
                        itemBuilder: (_, index) {
                          final o = orders[index];
                          return Container(
                            padding: const EdgeInsets.all(14),
                            decoration: BoxDecoration(
                              color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                  children: [
                                    Text(o.orderNumber, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                      decoration: BoxDecoration(
                                        color: AppTheme.onlineGreen.withValues(alpha: 0.15),
                                        borderRadius: BorderRadius.circular(8),
                                      ),
                                      child: Text(o.status, style: const TextStyle(color: AppTheme.onlineGreen, fontSize: 11, fontWeight: FontWeight.bold)),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 6),
                                Text('Buyer: ${o.buyerName} (PIN: ${o.buyerPin.isNotEmpty ? o.buyerPin : 'Customer'})', style: TextStyle(fontSize: 12, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                                Text('Amount: \$${o.grandTotal.toStringAsFixed(2)}', style: const TextStyle(fontWeight: FontWeight.bold, color: AppTheme.primary, fontSize: 14)),
                                const SizedBox(height: 10),
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                  children: [
                                    DropdownButton<String>(
                                      value: ['PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED'].contains(o.status) ? o.status : 'PAID',
                                      isDense: true,
                                      underline: const SizedBox(),
                                      items: ['PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED']
                                          .map((s) => DropdownMenuItem(
                                                value: s,
                                                child: Text(s, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                                              ))
                                          .toList(),
                                      onChanged: (newStatus) async {
                                        if (newStatus != null) {
                                          final messenger = ScaffoldMessenger.of(context);
                                          await widget.appState.updateOrderStatus(o.id, newStatus);
                                          setState(() {});
                                          messenger.showSnackBar(
                                            SnackBar(content: Text('Order #${o.orderNumber} status updated to $newStatus')),
                                          );
                                        }
                                      },
                                    ),
                                    OutlinedButton.icon(
                                      style: OutlinedButton.styleFrom(
                                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                      ),
                                      icon: const Icon(Icons.chat_rounded, size: 14),
                                      label: const Text('Message Buyer', style: TextStyle(fontSize: 12)),
                                      onPressed: () => _openChatWithUser(pin: o.buyerPin, fallbackName: o.buyerName),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                  ],
                ),

                // ── Sub-Tab 4: Coupons ───────────────────────────────────────
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('STORE PROMOTIONS', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1)),
                        ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppTheme.primary,
                            foregroundColor: Colors.black,
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                          ),
                          icon: const Icon(Icons.add_rounded, size: 16),
                          label: const Text('New Coupon', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
                          onPressed: _showCreateCouponModal,
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),

                    if (coupons.isEmpty)
                      _buildEmptyTabState(Icons.discount_outlined, 'No promo coupons active', 'Create discount promo codes to boost store sales.', isDark)
                    else
                      ListView.separated(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: coupons.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 10),
                        itemBuilder: (_, index) {
                          final c = coupons[index];
                          return Container(
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Row(
                              children: [
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                                  decoration: BoxDecoration(
                                    color: AppTheme.primary.withValues(alpha: 0.15),
                                    borderRadius: BorderRadius.circular(10),
                                  ),
                                  child: Text(c.code, style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold, fontSize: 13, letterSpacing: 1)),
                                ),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(c.type == 'percentage' ? '${c.value.toStringAsFixed(0)}% OFF' : '\$${c.value.toStringAsFixed(2)} OFF', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                                      Text(c.minSpend > 0 ? 'Min spend \$${c.minSpend.toStringAsFixed(0)}' : 'No min spend required', style: TextStyle(fontSize: 11, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                                    ],
                                  ),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.delete_outline_rounded, color: AppTheme.dangerRed, size: 20),
                                  onPressed: () {
                                    widget.appState.deleteStoreCoupon(c.id);
                                    setState(() {});
                                  },
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                  ],
                ),

                // ── Sub-Tab 5: Settings ──────────────────────────────────────
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    const Text('MERCHANT PROFILE SETTINGS', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1)),
                    const SizedBox(height: 14),

                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Store Name: ${store?.storeName ?? 'My Store'}', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                          const SizedBox(height: 6),
                          Text('Category: ${store?.category ?? 'General Retail'}', style: TextStyle(fontSize: 13, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                          Text('Location: ${store?.address ?? 'Lagos, Nigeria'}', style: TextStyle(fontSize: 13, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                          Text('Contact Phone: ${store?.phone ?? 'Not set'}', style: TextStyle(fontSize: 13, color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                          const SizedBox(height: 16),
                          SizedBox(
                            width: double.infinity,
                            child: ElevatedButton.icon(
                              style: ElevatedButton.styleFrom(
                                backgroundColor: AppTheme.primary,
                                foregroundColor: Colors.black,
                                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                              ),
                              icon: const Icon(Icons.edit_rounded, size: 18),
                              label: const Text('Edit Store Profile Details'),
                              onPressed: () {
                                _storeNameCtrl.text = store?.storeName ?? '';
                                _storeLocationCtrl.text = store?.address ?? '';
                                _storePhoneCtrl.text = store?.phone ?? '';
                                _storeDescCtrl.text = store?.description ?? '';
                                _storeLogoCtrl.text = store?.logoUrl ?? '';
                                _storeCategory = store?.category ?? 'Electronics';
                                _mainTabController.animateTo(1);
                              },
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStepIndicator(String number, String label, bool isActive) {
    return Column(
      children: [
        Container(
          width: 32,
          height: 32,
          decoration: BoxDecoration(
            color: isActive ? AppTheme.primary : Colors.grey.withValues(alpha: 0.2),
            shape: BoxShape.circle,
          ),
          child: Center(
            child: Text(
              number,
              style: TextStyle(
                color: isActive ? Colors.black : Colors.white60,
                fontWeight: FontWeight.bold,
                fontSize: 14,
              ),
            ),
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
            color: isActive ? AppTheme.primary : AppTheme.textMuted,
          ),
        ),
      ],
    );
  }

  Widget _buildStepLine(bool isCompleted) {
    return Expanded(
      child: Container(
        height: 2,
        margin: const EdgeInsets.only(bottom: 16, left: 4, right: 4),
        color: isCompleted ? AppTheme.primary : Colors.grey.withValues(alpha: 0.2),
      ),
    );
  }

  Widget _buildMetricCard(String label, String value, IconData icon, Color color, bool isDark) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: color.withValues(alpha: 0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(label, style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 0.5)),
              Icon(icon, color: color, size: 18),
            ],
          ),
          Text(value, style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: color)),
        ],
      ),
    );
  }

  Widget _buildEmptyTabState(IconData icon, String title, String subtitle, bool isDark) {
    return Container(
      padding: const EdgeInsets.all(28),
      decoration: BoxDecoration(
        color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Icon(icon, size: 48, color: AppTheme.textMuted),
          const SizedBox(height: 12),
          Text(title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
          const SizedBox(height: 4),
          Text(subtitle, style: const TextStyle(fontSize: 12, color: AppTheme.textMuted), textAlign: TextAlign.center),
        ],
      ),
    );
  }

  // ── Tab 1: Marketplace Explore Feed ─────────────────────────────────────────
  Widget _buildMarketplaceExploreFeed(bool isDark) {
    final products = _filteredProducts;

    return CustomScrollView(
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
                      'No items listed yet. Be the first merchant to set up your store and list products!',
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
                      icon: Icon(widget.appState.hasStore ? Icons.add_rounded : Icons.rocket_launch_rounded, size: 20),
                      label: Text(
                        widget.appState.hasStore ? 'Add Products to ${widget.appState.myStore?.storeName}' : 'Open My Store Now',
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                      ),
                      onPressed: () {
                        _mainTabController.animateTo(1);
                      },
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

  // ── Product Detail Bottom Sheet with Interactive Variant Selector ───────────
  void _showProductDetail(Product product) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    ProductVariant? selectedVariant = product.hasVariants ? product.variants.first : null;
    double currentPrice = selectedVariant?.priceOverride ?? product.price;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (detailCtx, setDetailState) {
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
                          selectedVariant?.imageUrl.isNotEmpty == true ? selectedVariant!.imageUrl : product.imageUrl,
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
                          '\$${currentPrice.toStringAsFixed(2)}',
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

                    // ── INTERACTIVE PRODUCT VARIANT SELECTOR ─────────────────
                    if (product.hasVariants) ...[
                      const SizedBox(height: 14),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey.shade100,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3)),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                const Row(
                                  children: [
                                    Icon(Icons.style_rounded, size: 16, color: AppTheme.primary),
                                    SizedBox(width: 6),
                                    Text('Choose Option / Variation:', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                                  ],
                                ),
                                if (selectedVariant != null)
                                  Text(
                                    'Stock: ${selectedVariant!.stockQuantity} left',
                                    style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: AppTheme.onlineGreen),
                                  ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            Wrap(
                              spacing: 8,
                              runSpacing: 8,
                              children: product.variants.map((v) {
                                final isSelected = selectedVariant?.id == v.id;
                                final vPrice = v.priceOverride != null ? ' (\$$v.priceOverride)' : '';
                                return ChoiceChip(
                                  avatar: const Icon(Icons.check_circle_rounded, size: 14, color: AppTheme.primary),
                                  label: Text('${v.title}$vPrice', style: TextStyle(fontSize: 12, fontWeight: isSelected ? FontWeight.bold : FontWeight.normal)),
                                  selected: isSelected,
                                  selectedColor: AppTheme.primary,
                                  backgroundColor: isDark ? AppTheme.darkCard : Colors.white,
                                  onSelected: (_) {
                                    setDetailState(() {
                                      selectedVariant = v;
                                      currentPrice = v.priceOverride ?? product.price;
                                    });
                                  },
                                );
                              }).toList(),
                            ),
                          ],
                        ),
                      ),
                    ],

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
                    const SizedBox(height: 16),

                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            style: OutlinedButton.styleFrom(
                              padding: const EdgeInsets.symmetric(vertical: 10),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                            ),
                            icon: const Icon(Icons.rate_review_outlined, size: 16),
                            label: const Text('Write Review', style: TextStyle(fontSize: 12)),
                            onPressed: () {
                              _showReviewDialog(product);
                            },
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: OutlinedButton.icon(
                            style: OutlinedButton.styleFrom(
                              padding: const EdgeInsets.symmetric(vertical: 10),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                            ),
                            icon: const Icon(Icons.help_outline_rounded, size: 16),
                            label: const Text('Ask Question', style: TextStyle(fontSize: 12)),
                            onPressed: () {
                              _showAskQuestionDialog(product);
                            },
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),

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
                            label: Text(
                              selectedVariant != null ? 'Add ${selectedVariant!.title} to Cart' : 'Add to Cart',
                              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                            ),
                            onPressed: () {
                              final itemToAdd = selectedVariant != null
                                  ? product.copyWith(price: currentPrice, title: '${product.title} (${selectedVariant!.title})')
                                  : product;

                              widget.appState.addToCart(itemToAdd);
                              Navigator.pop(ctx);
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(
                                  content: Text('Added "${itemToAdd.title}" to cart!'),
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
      },
    );
  }

  void _showReviewDialog(Product product) {
    double rating = 5.0;
    final commentCtrl = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (dialogCtx, setDialogState) => AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          title: Text('Review "${product.title}"', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(5, (index) {
                  final starVal = index + 1.0;
                  return IconButton(
                    icon: Icon(
                      starVal <= rating ? Icons.star_rounded : Icons.star_border_rounded,
                      color: Colors.amber,
                      size: 32,
                    ),
                    onPressed: () => setDialogState(() => rating = starVal),
                  );
                }),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: commentCtrl,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: 'Your Experience / Feedback',
                  hintText: 'Great quality, fast delivery, as described...',
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary, foregroundColor: Colors.black),
              onPressed: () async {
                final nav = Navigator.of(ctx);
                final messenger = ScaffoldMessenger.of(context);
                await widget.appState.addProductReview(product.id, rating, commentCtrl.text.trim());
                nav.pop();
                messenger.showSnackBar(
                  const SnackBar(content: Text('🎉 Thank you for your review!')),
                );
              },
              child: const Text('Submit Review'),
            ),
          ],
        ),
      ),
    );
  }

  void _showAskQuestionDialog(Product product) {
    final questionCtrl = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: Text('Ask Seller About "${product.title}"', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
        content: TextField(
          controller: questionCtrl,
          maxLines: 3,
          decoration: const InputDecoration(
            labelText: 'Your Question',
            hintText: 'Is this available in other colors? How long is shipping?',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary, foregroundColor: Colors.black),
            onPressed: () async {
              if (questionCtrl.text.trim().isNotEmpty) {
                final nav = Navigator.of(ctx);
                final messenger = ScaffoldMessenger.of(context);
                await widget.appState.askProductQuestion(product.id, questionCtrl.text.trim());
                nav.pop();
                messenger.showSnackBar(
                  const SnackBar(content: Text('📬 Question sent to seller!')),
                );
              }
            },
            child: const Text('Send Question'),
          ),
        ],
      ),
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
                                  final matchingCoupon = widget.appState.storeCoupons.firstWhere(
                                    (c) => c.code.toUpperCase() == promoCode,
                                    orElse: () => StoreCoupon(id: '', code: '', value: 0),
                                  );

                                  if (matchingCoupon.code.isNotEmpty) {
                                    setSheetState(() {
                                      discount = matchingCoupon.type == 'percentage'
                                          ? (subtotal * (matchingCoupon.value / 100))
                                          : matchingCoupon.value;
                                    });
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      SnackBar(content: Text('🎉 Coupon "${matchingCoupon.code}" applied! \$${discount.toStringAsFixed(2)} saved.')),
                                    );
                                  } else if (promoCode == 'GOCHAT10' || promoCode == 'SAVE10') {
                                    setSheetState(() {
                                      discount = subtotal * 0.10;
                                    });
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      const SnackBar(content: Text('🎉 Coupon applied! 10% Discount saved.')),
                                    );
                                  } else {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      const SnackBar(content: Text('Invalid coupon code.')),
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
    final cartCount = widget.appState.cart.length;
    final hasStore = widget.appState.hasStore;
    final storeName = widget.appState.myStore?.storeName ?? 'My Store';

    return Scaffold(
      backgroundColor: isDark ? AppTheme.darkBg : AppTheme.lightBg,
      appBar: AppBar(
        title: const Text('Marketplace', style: TextStyle(fontWeight: FontWeight.bold)),
        bottom: TabBar(
          controller: _mainTabController,
          indicatorColor: AppTheme.primary,
          labelColor: AppTheme.primary,
          unselectedLabelColor: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
          labelStyle: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
          indicatorWeight: 3,
          tabs: [
            const Tab(
              icon: Icon(Icons.shopping_bag_outlined),
              text: 'Marketplace',
            ),
            Tab(
              icon: Icon(hasStore ? Icons.storefront_rounded : Icons.add_business_rounded),
              text: hasStore ? storeName : 'Open Store',
            ),
          ],
        ),
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
          const SizedBox(width: 8),
        ],
      ),
      body: TabBarView(
        controller: _mainTabController,
        children: [
          // Tab 1: Explore Products & Feed
          _buildMarketplaceExploreFeed(isDark),

          // Tab 2: Store Setup (if no store) OR Complete Seller Hub (if store exists)
          _buildSellerHubTab(isDark),
        ],
      ),
    );
  }
}
