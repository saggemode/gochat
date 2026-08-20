import 'package:flutter/material.dart';
import '../core/state/app_state.dart';
import '../core/theme/app_theme.dart';
import 'app_drawer_nav_item.dart';
import 'app_drawer_profile_header.dart';
import 'mini_app_modal.dart';

class AppDrawer extends StatelessWidget {
  final AppState appState;
  final int currentIndex;
  final Function(int index) onSelectTab;

  const AppDrawer({
    super.key,
    required this.appState,
    required this.currentIndex,
    required this.onSelectTab,
  });

  @override
  Widget build(BuildContext context) {
    final user = appState.currentUser;
    final unreadChats = appState.conversations.fold(0, (sum, c) => sum + c.unreadCount);
    final cartCount = appState.cart.length;

    return Drawer(
      backgroundColor: AppTheme.darkBg,
      child: SafeArea(
        child: Column(
          children: [
            // User Profile Header
            AppDrawerProfileHeader(user: user),

            // Navigation Items List
            Expanded(
              child: ListView(
                padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 8),
                children: [
                  _buildSectionHeader('COMMUNICATION'),
                  AppDrawerNavItem(
                    icon: Icons.chat_bubble_outline_rounded,
                    activeIcon: Icons.chat_bubble_rounded,
                    title: 'All Chats',
                    badgeCount: unreadChats,
                    isSelected: currentIndex == 0,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(0);
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.auto_stories_outlined,
                    activeIcon: Icons.auto_stories,
                    title: 'Updates & Stories',
                    isSelected: currentIndex == 1,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(1);
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.groups_outlined,
                    activeIcon: Icons.groups_rounded,
                    title: 'Channels & Broadcasts',
                    isSelected: currentIndex == 2,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(2);
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.call_outlined,
                    activeIcon: Icons.call_rounded,
                    title: 'Calls & Audio Rooms',
                    isSelected: currentIndex == 3,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(3);
                    },
                  ),

                  const SizedBox(height: 8),
                  _buildSectionHeader('COMMERCE & ECOSYSTEM'),
                  AppDrawerNavItem(
                    icon: Icons.storefront_outlined,
                    activeIcon: Icons.storefront,
                    title: 'Marketplace & Store',
                    badgeCount: cartCount,
                    isSelected: currentIndex == 4,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(4);
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.apps_rounded,
                    activeIcon: Icons.apps_rounded,
                    title: 'Mini-Apps & Bots',
                    isSelected: false,
                    onTap: () {
                      Navigator.pop(context);
                      showModalBottomSheet(
                        context: context,
                        isScrollControlled: true,
                        backgroundColor: Colors.transparent,
                        builder: (_) => const MiniAppModal(),
                      );
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.auto_awesome_rounded,
                    activeIcon: Icons.auto_awesome,
                    title: 'AI Smart Assistant',
                    isSelected: false,
                    badgeText: 'GPT/Gemini',
                    onTap: () {
                      Navigator.pop(context);
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('AI Smart Assistant is active in your chat stream.'),
                          backgroundColor: AppTheme.primary,
                        ),
                      );
                    },
                  ),

                  const SizedBox(height: 8),
                  _buildSectionHeader('PRODUCTIVITY'),
                  AppDrawerNavItem(
                    icon: Icons.sticky_note_2_outlined,
                    title: 'Quick Notes',
                    onTap: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'Quick Notes', 'Take encrypted notes and sync across devices.');
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.calendar_today_outlined,
                    title: 'Calendar & Events',
                    onTap: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'Calendar & Events', 'Schedule and track upcoming group events.');
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.alarm_outlined,
                    title: 'Smart Reminders',
                    onTap: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'Smart Reminders', 'Set automated chat and message reminders.');
                    },
                  ),

                  const SizedBox(height: 8),
                  _buildSectionHeader('PREFERENCES'),
                  AppDrawerNavItem(
                    icon: Icons.settings_outlined,
                    activeIcon: Icons.settings,
                    title: 'Settings & Security',
                    isSelected: currentIndex == 5,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(5);
                    },
                  ),
                  AppDrawerNavItem(
                    icon: Icons.verified_user_outlined,
                    title: 'BBM PIN Lookup',
                    badgeText: 'PIN',
                    onTap: () {
                      Navigator.pop(context);
                      _showPinLookupDialog(context);
                    },
                  ),
                ],
              ),
            ),

            // Drawer Footer
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: AppTheme.darkBorder, width: 0.5)),
              ),
              child: Row(
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(
                      color: AppTheme.onlineGreen,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      'GoChat v1.0.0 • Cloud Live',
                      style: TextStyle(
                        color: AppTheme.textMuted,
                        fontSize: 11,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.help_outline_rounded, color: AppTheme.iconColor, size: 18),
                    tooltip: 'Help & Docs',
                    onPressed: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'GoChat Docs', 'Comprehensive Next.js + Flutter + Go Microservices.');
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 14, top: 12, bottom: 4),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.0,
          color: AppTheme.textMuted,
        ),
      ),
    );
  }

  void _showInfoSheet(BuildContext context, String title, String description) {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.darkCard,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.check_circle_outline, color: AppTheme.primary, size: 28),
                const SizedBox(width: 12),
                Text(
                  title,
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: AppTheme.textLight),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(description, style: const TextStyle(color: AppTheme.textMuted, fontSize: 14)),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  foregroundColor: Colors.black,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Got it', style: TextStyle(fontWeight: FontWeight.bold)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showPinLookupDialog(BuildContext context) {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.darkSurface,
        title: const Row(
          children: [
            Icon(Icons.tag_rounded, color: AppTheme.primary),
            SizedBox(width: 8),
            Text('Lookup User by PIN', style: TextStyle(color: AppTheme.textLight)),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Enter a 6-character BBM PIN to instantly start a secure direct conversation.',
              style: TextStyle(color: AppTheme.textMuted, fontSize: 12.5),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: controller,
              maxLength: 6,
              textCapitalization: TextCapitalization.characters,
              style: const TextStyle(
                color: Colors.white,
                fontFamily: 'monospace',
                fontSize: 18,
                letterSpacing: 4,
                fontWeight: FontWeight.bold,
              ),
              decoration: InputDecoration(
                hintText: 'e.g. 8492A1',
                counterText: '',
                prefixIcon: const Icon(Icons.search, color: AppTheme.iconColor),
                filled: true,
                fillColor: AppTheme.darkCard,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel', style: TextStyle(color: AppTheme.textMuted)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
            onPressed: () {
              final pin = controller.text.trim().toUpperCase();
              if (pin.isNotEmpty) {
                Navigator.pop(ctx);
                appState.createConversation('Contact ($pin)', []);
                onSelectTab(0);
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text('Connected with PIN $pin')),
                );
              }
            },
            child: const Text('Connect', style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }
}
