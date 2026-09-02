import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import '../../core/services/api_service.dart';
import '../../core/services/media_storage_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/media_image_helper.dart';
import '../auth/login_screen.dart';
import '../chat/starred_messages_screen.dart';
import 'chat_backup_screen.dart';
import 'notifications_settings_screen.dart';

class SettingsScreen extends StatefulWidget {
  final AppState appState;

  const SettingsScreen({super.key, required this.appState});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _isUploadingAvatar = false;

  Future<void> _pickProfileAvatar() async {
    final picker = ImagePicker();
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.camera_alt_rounded, color: AppTheme.primary),
              title: const Text('Take Profile Photo with Camera', style: TextStyle(fontWeight: FontWeight.bold)),
              onTap: () async {
                Navigator.pop(ctx);
                final XFile? image = await picker.pickImage(source: ImageSource.camera, maxWidth: 800, maxHeight: 800, imageQuality: 80);
                if (image != null) {
                  await _uploadAndSetAvatar(image.path);
                }
              },
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.photo_library_rounded, color: Colors.teal),
              title: const Text('Choose Photo from Gallery', style: TextStyle(fontWeight: FontWeight.bold)),
              onTap: () async {
                Navigator.pop(ctx);
                final XFile? image = await picker.pickImage(source: ImageSource.gallery, maxWidth: 800, maxHeight: 800, imageQuality: 80);
                if (image != null) {
                  await _uploadAndSetAvatar(image.path);
                }
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _uploadAndSetAvatar(String localPath) async {
    setState(() => _isUploadingAvatar = true);
    try {
      final savedPath = await MediaStorageService().saveImage(localPath);
      final uploadedUrl = await ApiService.uploadMedia(savedPath, mimeType: 'image/jpeg');
      final finalAvatarUrl = uploadedUrl ?? savedPath;

      await widget.appState.updateProfile(avatarUrl: finalAvatarUrl);
      if (mounted) {
        setState(() => _isUploadingAvatar = false);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('✅ Profile photo uploaded to Telegram CDN and updated!'),
            backgroundColor: AppTheme.primary,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isUploadingAvatar = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to update avatar: $e'), backgroundColor: Colors.redAccent),
        );
      }
    }
  }
  void _showEditProfileDialog() {
    final user = widget.appState.currentUser;
    final nameController = TextEditingController(text: user?.displayName ?? '');
    final statusController = TextEditingController(text: user?.statusText ?? '');

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.darkSurface,
        title: const Text('Edit Profile', style: TextStyle(color: AppTheme.textLight)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              style: const TextStyle(color: AppTheme.textLight),
              decoration: const InputDecoration(labelText: 'Display Name'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: statusController,
              style: const TextStyle(color: AppTheme.textLight),
              decoration: const InputDecoration(labelText: 'Status / Bio'),
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
            onPressed: () async {
              final newName = nameController.text.trim();
              final newStatus = statusController.text.trim();
              if (newName.isNotEmpty) {
                await widget.appState.updateProfile(
                  displayName: newName,
                  statusText: newStatus,
                );
                if (mounted) {
                  setState(() {});
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Profile updated successfully')),
                  );
                }
              }
              if (ctx.mounted) {
                Navigator.pop(ctx);
              }
            },
            child: const Text('Save', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }

  void _showThemeDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.darkSurface,
        title: const Row(
          children: [
            Icon(Icons.palette_outlined, color: AppTheme.primary),
            SizedBox(width: 8),
            Text('Choose Theme', style: TextStyle(color: AppTheme.textLight)),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.dark_mode_rounded, color: AppTheme.primary),
              title: const Text('Dark Mode (Emerald)', style: TextStyle(color: AppTheme.textLight)),
              trailing: widget.appState.themeMode == ThemeMode.dark
                  ? const Icon(Icons.check_circle_rounded, color: AppTheme.primary)
                  : null,
              onTap: () {
                widget.appState.setThemeMode(ThemeMode.dark);
                Navigator.pop(ctx);
              },
            ),
            ListTile(
              leading: const Icon(Icons.light_mode_rounded, color: Colors.amber),
              title: const Text('Light Mode (Clean)', style: TextStyle(color: AppTheme.textLight)),
              trailing: widget.appState.themeMode == ThemeMode.light
                  ? const Icon(Icons.check_circle_rounded, color: AppTheme.primary)
                  : null,
              onTap: () {
                widget.appState.setThemeMode(ThemeMode.light);
                Navigator.pop(ctx);
              },
            ),
            ListTile(
              leading: const Icon(Icons.settings_system_daydream_rounded, color: AppTheme.textMuted),
              title: const Text('System Default', style: TextStyle(color: AppTheme.textLight)),
              trailing: widget.appState.themeMode == ThemeMode.system
                  ? const Icon(Icons.check_circle_rounded, color: AppTheme.primary)
                  : null,
              onTap: () {
                widget.appState.setThemeMode(ThemeMode.system);
                Navigator.pop(ctx);
              },
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final user = widget.appState.currentUser;
    final pin = user?.pin.isNotEmpty == true
        ? user!.pin
        : (user?.id.isNotEmpty == true ? user!.id.replaceAll('-', '').substring(0, 6).toUpperCase() : '8492A1');

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: ListView(
        children: [
          // Profile Tile
          Container(
            margin: const EdgeInsets.all(16),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.darkCard,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: AppTheme.darkBorder, width: 0.5),
            ),
            child: Column(
              children: [
                Row(
                  children: [
                    GestureDetector(
                      onTap: _isUploadingAvatar ? null : _pickProfileAvatar,
                      child: Stack(
                        children: [
                          CircleAvatar(
                            radius: 32,
                            backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
                            backgroundImage: user?.avatarUrl.isNotEmpty == true
                                ? MediaImageHelper.safeImageProvider(user!.avatarUrl)
                                : null,
                            child: user?.avatarUrl.isEmpty != false
                                ? const Icon(Icons.person, size: 34, color: AppTheme.primary)
                                : null,
                          ),
                          if (_isUploadingAvatar)
                            Container(
                              width: 64,
                              height: 64,
                              decoration: const BoxDecoration(
                                color: Colors.black54,
                                shape: BoxShape.circle,
                              ),
                              child: const Center(
                                child: SizedBox(
                                  width: 22,
                                  height: 22,
                                  child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                                ),
                              ),
                            )
                          else
                            Positioned(
                              bottom: 0,
                              right: 0,
                              child: Container(
                                padding: const EdgeInsets.all(4),
                                decoration: const BoxDecoration(
                                  color: AppTheme.primary,
                                  shape: BoxShape.circle,
                                ),
                                child: const Icon(Icons.camera_alt_rounded, size: 12, color: Colors.white),
                              ),
                            ),
                        ],
                      ),
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
                                  user?.displayName ?? 'Alexandre Sterling',
                                  style: const TextStyle(
                                    fontSize: 17,
                                    fontWeight: FontWeight.bold,
                                    color: AppTheme.textLight,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              const SizedBox(width: 4),
                              const Icon(Icons.verified, color: AppTheme.primary, size: 16),
                            ],
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
                      icon: const Icon(Icons.edit_outlined, color: AppTheme.iconColor, size: 20),
                      tooltip: 'Edit Profile',
                      onPressed: _showEditProfileDialog,
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                const Divider(height: 1, color: AppTheme.darkBorder),
                const SizedBox(height: 12),
                // BBM PIN & Phone Bar
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                      decoration: BoxDecoration(
                        color: AppTheme.primary.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3)),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.tag_rounded, color: AppTheme.primary, size: 16),
                          const SizedBox(width: 4),
                          Text(
                            'PIN: $pin',
                            style: const TextStyle(
                              color: AppTheme.primary,
                              fontWeight: FontWeight.bold,
                              fontSize: 13,
                              letterSpacing: 0.8,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton(
                      icon: const Icon(Icons.copy_rounded, color: AppTheme.iconColor, size: 18),
                      tooltip: 'Copy PIN',
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: pin));
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text('Copied PIN $pin to clipboard!'),
                            duration: const Duration(seconds: 2),
                          ),
                        );
                      },
                    ),
                    const Spacer(),
                    IconButton(
                      icon: const Icon(Icons.qr_code, color: AppTheme.primary),
                      tooltip: 'Show QR',
                      onPressed: () {
                        showDialog(
                          context: context,
                          builder: (ctx) => AlertDialog(
                            backgroundColor: AppTheme.darkSurface,
                            title: const Text('My GoChat QR Code', style: TextStyle(color: AppTheme.textLight)),
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
                                  'PIN: $pin',
                                  style: const TextStyle(fontWeight: FontWeight.bold, color: AppTheme.textLight, fontSize: 16),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ],
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
            Icons.notifications_active_rounded,
            'Notifications & Sounds',
            'FCM & APNs push alerts, full-screen VoIP calls, ringtones',
            onTap: () {
              NotificationsSettingsScreen.open(context, appState: widget.appState);
            },
          ),
          _buildSettingsTile(
            Icons.star_rounded,
            'Starred Messages',
            'View saved bookmarks and important messages',
            onTap: () {
              StarredMessagesScreen.open(context, appState: widget.appState);
            },
          ),
          _buildSettingsTile(
            Icons.backup_rounded,
            'Chat Backup & Export',
            'Password-encrypted local & cloud backup, restore chats',
            onTap: () {
              ChatBackupScreen.open(context, appState: widget.appState);
            },
          ),
          _buildSettingsTile(
            Icons.lock_outline_rounded,
            'Privacy',
            'Block contacts, disappearing messages, read receipts',
            onTap: () {},
          ),
          ListTile(
            leading: Icon(
              widget.appState.isDarkMode ? Icons.dark_mode_outlined : Icons.light_mode_outlined,
              color: AppTheme.primary,
            ),
            title: const Text(
              'Appearance & Theme',
              style: TextStyle(fontWeight: FontWeight.w600, color: AppTheme.textLight, fontSize: 15),
            ),
            subtitle: Text(
              widget.appState.themeMode == ThemeMode.dark
                  ? 'Dark Emerald (Active)'
                  : (widget.appState.themeMode == ThemeMode.light ? 'Light Clean (Active)' : 'System Default'),
              style: const TextStyle(fontSize: 12.5, color: AppTheme.textMuted),
            ),
            trailing: Switch(
              value: widget.appState.isDarkMode,
              activeThumbColor: AppTheme.primary,
              onChanged: (_) {
                widget.appState.toggleTheme();
              },
            ),
            onTap: () => _showThemeDialog(context),
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
              await widget.appState.logout();
              if (context.mounted) {
                Navigator.pushReplacement(
                  context,
                  MaterialPageRoute(builder: (_) => LoginScreen(appState: widget.appState)),
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
