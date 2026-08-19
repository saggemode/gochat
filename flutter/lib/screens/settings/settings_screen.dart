import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../auth/login_screen.dart';

class SettingsScreen extends StatelessWidget {
  final AppState appState;

  const SettingsScreen({super.key, required this.appState});

  @override
  Widget build(BuildContext context) {
    final user = appState.currentUser;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: ListView(
        children: [
          // Profile Tile
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 32,
                  backgroundColor: AppTheme.darkCard,
                  backgroundImage: user?.avatarUrl.isNotEmpty == true
                      ? NetworkImage(user!.avatarUrl)
                      : null,
                  child: user?.avatarUrl.isEmpty != false
                      ? const Icon(Icons.person, size: 36, color: AppTheme.iconColor)
                      : null,
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        user?.displayName ?? 'Alexandre Sterling',
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: AppTheme.textLight,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        user?.statusText ?? 'Building microservices in Go & Flutter',
                        style: const TextStyle(fontSize: 13, color: AppTheme.textMuted),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.qr_code, color: AppTheme.primary),
                  onPressed: () {
                    showDialog(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        backgroundColor: AppTheme.darkSurface,
                        title: const Text('My QR Code', style: TextStyle(color: AppTheme.textLight)),
                        content: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              padding: const EdgeInsets.all(16),
                              color: Colors.white,
                              child: const Icon(Icons.qr_code_2_rounded, size: 160, color: Colors.black),
                            ),
                            const SizedBox(height: 12),
                            Text(
                              user?.phone ?? '+1 (555) 234-5678',
                              style: const TextStyle(fontWeight: FontWeight.bold, color: AppTheme.textLight),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ],
            ),
          ),

          const Divider(),

          // Settings Items
          _buildSettingsTile(
            Icons.key_rounded,
            'Account & Security',
            'Security notifications, two-step verification',
            onTap: () {},
          ),
          _buildSettingsTile(
            Icons.lock_outline_rounded,
            'Privacy',
            'Block contacts, disappearing messages, read receipts',
            onTap: () {},
          ),
          _buildSettingsTile(
            Icons.palette_outlined,
            'Appearance & Theme',
            'Dark Emerald (Active), Wallpapers, Font size',
            onTap: () {},
          ),
          _buildSettingsTile(
            Icons.smart_toy_outlined,
            'Custom Bot Webhooks',
            'Manage bot developer credentials and triggers',
            onTap: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('🤖 Bot Developer Portal enabled')),
              );
            },
          ),
          _buildSettingsTile(
            Icons.notifications_none_rounded,
            'Notifications',
            'Message tones, group alerts, in-app vibration',
            onTap: () {},
          ),
          _buildSettingsTile(
            Icons.data_usage_rounded,
            'Storage and Data',
            'Network usage, auto-download, proxy settings',
            onTap: () {},
          ),
          _buildSettingsTile(
            Icons.help_outline_rounded,
            'Help & Server Info',
            'Connected to Render Gateway (v1.0.0 Live)',
            onTap: () {},
          ),

          const Divider(),

          // Logout button
          ListTile(
            leading: const Icon(Icons.logout, color: AppTheme.dangerRed),
            title: const Text('Log out', style: TextStyle(color: AppTheme.dangerRed, fontWeight: FontWeight.bold)),
            onTap: () async {
              await appState.logout();
              if (context.mounted) {
                Navigator.pushReplacement(
                  context,
                  MaterialPageRoute(builder: (_) => LoginScreen(appState: appState)),
                );
              }
            },
          ),
        ],
      ),
    );
  }

  Widget _buildSettingsTile(IconData icon, String title, String subtitle, {VoidCallback? onTap}) {
    return ListTile(
      leading: Icon(icon, color: AppTheme.iconColor),
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600, color: AppTheme.textLight, fontSize: 15)),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 12.5, color: AppTheme.textMuted)),
      onTap: onTap,
    );
  }
}
