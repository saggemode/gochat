import 'package:flutter/material.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';

class MarketplaceScreen extends StatefulWidget {
  final AppState appState;

  const MarketplaceScreen({super.key, required this.appState});

  @override
  State<MarketplaceScreen> createState() => _MarketplaceScreenState();
}

class _MarketplaceScreenState extends State<MarketplaceScreen> {
  String _selectedCategory = 'All';
  final TextEditingController _searchController = TextEditingController();

  List<Product> get _filteredProducts {
    var list = widget.appState.products;
    if (_selectedCategory != 'All') {
      list = list.where((p) => p.category.toLowerCase() == _selectedCategory.toLowerCase()).toList();
    }
    final query = _searchController.text.trim().toLowerCase();
    if (query.isNotEmpty) {
      list = list.where((p) => p.title.toLowerCase().contains(query) || p.description.toLowerCase().contains(query)).toList();
    }
    return list;
  }

  void _showCartSheet() {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (context, setSheetState) {
            final cart = widget.appState.cart;
            final total = cart.fold(0.0, (sum, p) => sum + p.price);

            return Padding(
              padding: const EdgeInsets.all(20.0),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 38,
                    height: 4,
                    decoration: BoxDecoration(
                      color: isDark ? Colors.white24 : Colors.black12,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        'Shopping Cart',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: isDark ? AppTheme.textLight : AppTheme.textDark,
                        ),
                      ),
                      Text(
                        '${cart.length} item(s)',
                        style: TextStyle(color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                      ),
                    ],
                  ),
                  const Divider(height: 24),
                  if (cart.isEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 24),
                      child: EmptyStateView(
                        icon: Icons.remove_shopping_cart_rounded,
                        title: 'Cart is Empty',
                        description: 'Browse the GoChat store and add items to your cart.',
                      ),
                    )
                  else
                    Flexible(
                      child: ListView(
                        shrinkWrap: true,
                        children: cart.map((p) => ListTile(
                          contentPadding: EdgeInsets.zero,
                          leading: ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: Image.network(
                              p.imageUrl,
                              width: 44,
                              height: 44,
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) => const Icon(Icons.shopping_bag),
                            ),
                          ),
                          title: Text(
                            p.title,
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.bold,
                              color: isDark ? AppTheme.textLight : AppTheme.textDark,
                            ),
                          ),
                          subtitle: Text(
                            '\$${p.price.toStringAsFixed(2)}',
                            style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold),
                          ),
                          trailing: IconButton(
                            icon: const Icon(Icons.delete_outline, color: AppTheme.dangerRed, size: 20),
                            onPressed: () {
                              widget.appState.removeFromCart(p.id);
                              setSheetState(() {});
                              setState(() {});
                            },
                          ),
                        )).toList(),
                      ),
                    ),
                  if (cart.isNotEmpty) ...[
                    const SizedBox(height: 16),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('Total:', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                        Text(
                          '\$${total.toStringAsFixed(2)}',
                          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: AppTheme.primary),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    PrimaryButton(
                      label: 'Proceed to Checkout (\$${total.toStringAsFixed(2)})',
                      icon: Icons.lock_outline_rounded,
                      onPressed: () {
                        widget.appState.clearCart();
                        Navigator.pop(ctx);
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('🎉 Order placed successfully via GoChat Marketplace!'),
                            backgroundColor: AppTheme.primary,
                          ),
                        );
                      },
                    ),
                  ],
                ],
              ),
            );
          },
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final products = _filteredProducts;
    final cartCount = widget.appState.cart.length;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    final categories = ['All', 'Electronics', 'Fashion', 'Gaming', 'Digital', 'Accessories'];

    return Scaffold(
      appBar: AppBar(
        title: Text(
          'GoChat Marketplace',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.bold,
            color: isDark ? AppTheme.textLight : AppTheme.textDark,
          ),
        ),
        actions: [
          Stack(
            children: [
              IconButton(
                icon: const Icon(Icons.shopping_cart_outlined),
                onPressed: _showCartSheet,
              ),
              if (cartCount > 0)
                Positioned(
                  top: 6,
                  right: 6,
                  child: Container(
                    padding: const EdgeInsets.all(4),
                    decoration: const BoxDecoration(
                      color: AppTheme.primary,
                      shape: BoxShape.circle,
                    ),
                    child: Text(
                      '$cartCount',
                      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black),
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          // Search Bar
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: SearchField(
              controller: _searchController,
              hintText: 'Search products in store...',
              onChanged: (_) => setState(() {}),
              onClear: () => setState(() => _searchController.clear()),
            ),
          ),

          // Category Chips
          SizedBox(
            height: 44,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: categories.length,
              itemBuilder: (ctx, idx) {
                final cat = categories[idx];
                final isSelected = _selectedCategory == cat;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: FilterChip(
                    label: Text(cat),
                    selected: isSelected,
                    selectedColor: AppTheme.primary.withValues(alpha: 0.2),
                    backgroundColor: isDark ? AppTheme.darkCard : Colors.white,
                    labelStyle: TextStyle(
                      fontSize: 12,
                      fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                      color: isSelected ? AppTheme.primary : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                    ),
                    side: BorderSide(
                      color: isSelected ? AppTheme.primary : (isDark ? AppTheme.darkBorder : AppTheme.lightBorder),
                      width: 0.8,
                    ),
                    onSelected: (_) => setState(() => _selectedCategory = cat),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 8),

          // Products Grid
          Expanded(
            child: products.isEmpty
                ? EmptyStateView(
                    icon: Icons.store_mall_directory_outlined,
                    title: 'No Products Found',
                    description: 'Try adjusting your search query or selecting another category.',
                    actionLabel: 'Reset Filters',
                    onAction: () {
                      setState(() {
                        _searchController.clear();
                        _selectedCategory = 'All';
                      });
                    },
                  )
                : GridView.builder(
                    padding: const EdgeInsets.all(16),
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 2,
                      childAspectRatio: 0.72,
                      crossAxisSpacing: 12,
                      mainAxisSpacing: 12,
                    ),
                    itemCount: products.length,
                    itemBuilder: (ctx, idx) {
                      final p = products[idx];
                      return Container(
                        decoration: BoxDecoration(
                          color: isDark ? AppTheme.darkCard : Colors.white,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(
                            color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                            width: 0.5,
                          ),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Product Image
                            Expanded(
                              child: ClipRRect(
                                borderRadius: const BorderRadius.vertical(top: Radius.circular(15)),
                                child: Image.network(
                                  p.imageUrl,
                                  width: double.infinity,
                                  fit: BoxFit.cover,
                                  errorBuilder: (_, __, ___) => Container(
                                    color: isDark ? Colors.black26 : const Color(0xFFF0F2F5),
                                    child: const Icon(Icons.shopping_bag, size: 40, color: AppTheme.iconColor),
                                  ),
                                ),
                              ),
                            ),
                            // Details
                            Padding(
                              padding: const EdgeInsets.all(10),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  StatusBadge(
                                    text: p.category.toUpperCase(),
                                    type: BadgeType.primary,
                                  ),
                                  const SizedBox(height: 6),
                                  Text(
                                    p.title,
                                    style: TextStyle(
                                      fontSize: 13,
                                      fontWeight: FontWeight.bold,
                                      color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                    ),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                  const SizedBox(height: 6),
                                  Row(
                                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                    children: [
                                      Text(
                                        '\$${p.price.toStringAsFixed(2)}',
                                        style: const TextStyle(
                                          fontSize: 14,
                                          fontWeight: FontWeight.bold,
                                          color: AppTheme.primary,
                                        ),
                                      ),
                                      InkWell(
                                        onTap: () {
                                          widget.appState.addToCart(p);
                                          ScaffoldMessenger.of(context).showSnackBar(
                                            SnackBar(
                                              content: Text('Added ${p.title} to cart'),
                                              duration: const Duration(milliseconds: 900),
                                            ),
                                          );
                                        },
                                        borderRadius: BorderRadius.circular(20),
                                        child: Container(
                                          padding: const EdgeInsets.all(6),
                                          decoration: BoxDecoration(
                                            color: AppTheme.primary.withValues(alpha: 0.15),
                                            shape: BoxShape.circle,
                                          ),
                                          child: const Icon(Icons.add_shopping_cart_rounded, size: 16, color: AppTheme.primary),
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
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
