import 'package:flutter/material.dart';
import '../core/state/app_state.dart';
import '../core/theme/app_theme.dart';
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
      backgroundColor: AppTheme.darkBackground,
      child: SafeArea(
        child: Column(
          children: [
            // User Profile Header
            Container(
              padding: const EdgeInsets.all(20),
              decoration: const BoxDecoration(
                color: AppTheme.darkCard,
                border: Border(bottom: BorderSide(color: AppTheme.darkBorder, width: 0.5)),
              ),
              child: Row(
                children: [
                  Stack(
                    children: [
                      CircleAvatar(
                        radius: 28,
                        backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
                        backgroundImage: (user?.avatarUrl != null && user!.avatarUrl.isNotEmpty)
                            ? NetworkImage(user.avatarUrl)
                            : null,
                        child: (user?.avatarUrl == null || user!.avatarUrl.isEmpty)
                            ? Text(
                                (user?.displayName.isNotEmpty == true)
                                    ? user!.displayName.substring(0, 1).toUpperCase()
                                    : 'G',
                                style: const TextStyle(
                                  fontSize: 22,
                                  fontWeight: FontWeight.bold,
                                  color: AppTheme.primary,
                                ),
                              )
                            : null,
                      ),
                      Positioned(
                        bottom: 0,
                        right: 0,
                        child: Container(
                          width: 14,
                          height: 14,
                          decoration: BoxDecoration(
                            color: AppTheme.primary,
                            shape: BoxShape.circle,
                            border: Border.all(color: AppTheme.darkCard, width: 2),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Flexible(
                              child: Text(
                                user?.displayName.isNotEmpty == true
                                    ? user!.displayName
                                    : 'GoChat User',
                                style: const TextStyle(
                                  color: AppTheme.textLight,
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            const SizedBox(width: 4),
                            const Icon(Icons.verified, color: AppTheme.primary, size: 16),
                          ],
                        ),
                        const SizedBox(height: 2),
                        Text(
                          user?.phone.isNotEmpty == true
                              ? user!.phone
                              : (user?.email.isNotEmpty == true ? user!.email : 'Online'),
                          style: const TextStyle(
                            color: AppTheme.textMuted,
                            fontSize: 12,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (user?.pin.isNotEmpty == true) ...[
                          const SizedBox(height: 4),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: AppTheme.primary.withValues(alpha: 0.15),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              'PIN: ${user!.pin}',
                              style: const TextStyle(
                                color: AppTheme.primary,
                                fontSize: 10,
                                fontWeight: FontWeight.bold,
                                letterSpacing: 0.5,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),

            // Navigation Items List
            Expanded(
              child: ListView(
                padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 8),
                children: [
                  _buildSectionHeader('COMMUNICATION'),
                  _buildNavItem(
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
                  _buildNavItem(
                    icon: Icons.auto_stories_outlined,
                    activeIcon: Icons.auto_stories,
                    title: 'Updates & Stories',
                    isSelected: currentIndex == 1,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(1);
                    },
                  ),
                  _buildNavItem(
                    icon: Icons.groups_outlined,
                    activeIcon: Icons.groups_rounded,
                    title: 'Channels & Broadcasts',
                    isSelected: currentIndex == 2,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(2);
                    },
                  ),
                  _buildNavItem(
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
                  _buildNavItem(
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
                  _buildNavItem(
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
                  _buildNavItem(
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
                  _buildNavItem(
                    icon: Icons.sticky_note_2_outlined,
                    title: 'Quick Notes',
                    onTap: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'Quick Notes', 'Take encrypted notes and sync across devices.');
                    },
                  ),
                  _buildNavItem(
                    icon: Icons.calendar_today_outlined,
                    title: 'Calendar & Events',
                    onTap: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'Calendar & Events', 'Schedule and track upcoming group events.');
                    },
                  ),
                  _buildNavItem(
                    icon: Icons.alarm_outlined,
                    title: 'Smart Reminders',
                    onTap: () {
                      Navigator.pop(context);
                      _showInfoSheet(context, 'Smart Reminders', 'Set contextual reminders from chat messages.');
                    },
                  ),

                  const SizedBox(height: 8),
                  _buildSectionHeader('ACCOUNT & SECURITY'),
                  _buildNavItem(
                    icon: Icons.lock_outline_rounded,
                    title: 'Two-Step PIN & Privacy',
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(5);
                    },
                  ),
                  _buildNavItem(
                    icon: Icons.settings_outlined,
                    activeIcon: Icons.settings,
                    title: 'Settings',
                    isSelected: currentIndex == 5,
                    onTap: () {
                      Navigator.pop(context);
                      onSelectTab(5);
                    },
                  ),
                ],
              ),
            ),

            // Logout Footer
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: AppTheme.darkBorder, width: 0.5)),
              ),
              child: ListTile(
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                leading: const Icon(Icons.logout_rounded, color: Colors.redAccent),
                title: const Text(
                  'Log Out',
                  style: TextStyle(
                    color: Colors.redAccent,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                onTap: () async {
                  Navigator.pop(context);
                  await appState.logout();
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 12, top: 8, bottom: 4),
      child: Text(
        title,
        style: const TextStyle(
          color: AppTheme.textMuted,
          fontSize: 10,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.2,
        ),
      ),
    );
  }

  Widget _buildNavItem({
    required IconData icon,
    IconData? activeIcon,
    required String title,
    bool isSelected = false,
    int badgeCount = 0,
    String? badgeText,
    required VoidCallback onTap,
  }) {
    final effectiveIcon = (isSelected && activeIcon != null) ? activeIcon : icon;
    final color = isSelected ? AppTheme.primary : AppTheme.textLight;

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 2),
      decoration: BoxDecoration(
        color: isSelected ? AppTheme.primary.withValues(alpha: 0.15) : Colors.transparent,
        borderRadius: BorderRadius.circular(12),
        border: isSelected
            ? Border.all(color: AppTheme.primary.withValues(alpha: 0.3), width: 1)
            : null,
      ),
      child: ListTile(
        dense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 0),
        leading: Icon(
          effectiveIcon,
          color: isSelected ? AppTheme.primary : AppTheme.textMuted,
          size: 22,
        ),
        title: Text(
          title,
          style: TextStyle(
            color: color,
            fontSize: 13.5,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
          ),
        ),
        trailing: badgeCount > 0
            ? Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                decoration: BoxDecoration(
                  color: AppTheme.accent,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  '$badgeCount',
                  style: const TextStyle(
                    color: Colors.black,
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              )
            : badgeText != null
                ? Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: AppTheme.primary.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      badgeText,
                      style: const TextStyle(
                        color: AppTheme.primary,
                        fontSize: 9.5,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  )
                : null,
        onTap: onTap,
      ),
    );
  }

  void _showInfoSheet(BuildContext context, String title, String description) {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.darkCard,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(24.0),
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
                  style: const TextStyle(
                    color: AppTheme.textLight,
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              description,
              style: const TextStyle(color: AppTheme.textMuted, fontSize: 14),
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  side: const BorderSide(color: AppTheme.primary),
                  backgroundColor: AppTheme.primary,
                  foregroundColor: Colors.black,
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
}
