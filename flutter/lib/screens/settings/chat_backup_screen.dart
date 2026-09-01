import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import '../../core/services/backup_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';

class ChatBackupScreen extends StatefulWidget {
  final AppState appState;

  const ChatBackupScreen({super.key, required this.appState});

  static void open(BuildContext context, {required AppState appState}) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChatBackupScreen(appState: appState),
      ),
    );
  }

  @override
  State<ChatBackupScreen> createState() => _ChatBackupScreenState();
}

class _ChatBackupScreenState extends State<ChatBackupScreen> {
  final _backupService = BackupService();
  BackupMetadata? _lastBackup;
  bool _isLoading = false;
  double _progress = 0.0;
  String _statusMessage = '';
  bool _includeMedia = true;
  String _autoBackupFrequency = 'Daily';

  @override
  void initState() {
    super.initState();
    _loadLastBackup();
  }

  Future<void> _loadLastBackup() async {
    final meta = await _backupService.getLastBackupInfo();
    if (mounted) setState(() => _lastBackup = meta);
  }

  void _startBackupFlow() {
    final passwordController = TextEditingController();
    final confirmController = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) {
          return AlertDialog(
            backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
            title: const Row(
              children: [
                Icon(Icons.enhanced_encryption_rounded, color: AppTheme.primary),
                SizedBox(width: 8),
                Text('Encrypted Backup Password'),
              ],
            ),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Set a password to protect your chat backup. You will need this password to restore your chats on another device.',
                  style: TextStyle(fontSize: 13, color: AppTheme.textMuted),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: passwordController,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Backup Password',
                    prefixIcon: Icon(Icons.lock_outline),
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: confirmController,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Confirm Password',
                    prefixIcon: Icon(Icons.lock_reset),
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
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  foregroundColor: Colors.white,
                ),
                onPressed: () {
                  final pass = passwordController.text.trim();
                  final confirm = confirmController.text.trim();
                  if (pass.length < 4) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Password must be at least 4 characters')),
                    );
                    return;
                  }
                  if (pass != confirm) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Passwords do not match')),
                    );
                    return;
                  }
                  Navigator.pop(ctx);
                  _executeBackup(pass);
                },
                child: const Text('Create Backup'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _executeBackup(String password) async {
    setState(() {
      _isLoading = true;
      _progress = 0.05;
      _statusMessage = 'Starting encrypted backup...';
    });

    try {
      final meta = await _backupService.createEncryptedBackup(
        password: password,
        includeMedia: _includeMedia,
        onProgress: (p, msg) {
          if (mounted) {
            setState(() {
              _progress = p;
              _statusMessage = msg;
            });
          }
        },
      );

      setState(() {
        _lastBackup = meta;
        _isLoading = false;
      });

      HapticFeedback.heavyImpact();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('🎉 Backup created successfully! (${meta.formattedSize}, ${meta.messageCount} messages)'),
            backgroundColor: AppTheme.primary,
          ),
        );
      }
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Backup failed: $e'), backgroundColor: Colors.redAccent),
        );
      }
    }
  }

  void _startRestoreFlow() async {
    // Check available backups in app directory
    final docDir = await getApplicationDocumentsDirectory();
    final backupFiles = docDir
        .listSync()
        .whereType<File>()
        .where((f) => f.path.endsWith('.gcbackup'))
        .toList();

    if (backupFiles.isEmpty) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No local .gcbackup file found. Create a backup first.')),
        );
      }
      return;
    }

    // Pick the most recent backup
    backupFiles.sort((a, b) => b.lastModifiedSync().compareTo(a.lastModifiedSync()));
    final targetBackup = backupFiles.first;
    final fileName = targetBackup.path.split(Platform.pathSeparator).last;

    final passwordController = TextEditingController();

    if (!mounted) return;
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
        title: const Row(
          children: [
            Icon(Icons.restore_page_rounded, color: AppTheme.primary),
            SizedBox(width: 8),
            Text('Restore Chats & Media'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Found backup file:\n$fileName',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
            ),
            const SizedBox(height: 12),
            const Text(
              'Enter the password used when creating this backup:',
              style: TextStyle(fontSize: 12, color: AppTheme.textMuted),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: passwordController,
              obscureText: true,
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Backup Password',
                prefixIcon: Icon(Icons.key),
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
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
            ),
            onPressed: () {
              final pass = passwordController.text.trim();
              if (pass.isEmpty) return;
              Navigator.pop(ctx);
              _executeRestore(targetBackup.path, pass);
            },
            child: const Text('Restore Now'),
          ),
        ],
      ),
    );
  }

  Future<void> _executeRestore(String filePath, String password) async {
    setState(() {
      _isLoading = true;
      _progress = 0.05;
      _statusMessage = 'Verifying and decrypting...';
    });

    try {
      final meta = await _backupService.restoreEncryptedBackup(
        filePath: filePath,
        password: password,
        onProgress: (p, msg) {
          if (mounted) {
            setState(() {
              _progress = p;
              _statusMessage = msg;
            });
          }
        },
      );

      // Refresh in-memory state
      await widget.appState.refreshData();
      setState(() => _isLoading = false);

      HapticFeedback.heavyImpact();
      if (mounted) {
        showDialog(
          context: context,
          builder: (ctx) => AlertDialog(
            backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
            title: const Row(
              children: [
                Icon(Icons.check_circle_rounded, color: AppTheme.primary, size: 28),
                SizedBox(width: 8),
                Text('Restore Complete!'),
              ],
            ),
            content: Text(
              'Successfully restored ${meta.conversationCount} conversations, ${meta.messageCount} messages, and ${meta.mediaCount} media files.',
            ),
            actions: [
              ElevatedButton(
                style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Great!', style: TextStyle(color: Colors.white)),
              ),
            ],
          ),
        );
      }
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e is InvalidPasswordException ? '❌ Incorrect password!' : 'Restore failed: $e'),
            backgroundColor: Colors.redAccent,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final dateStr = _lastBackup != null
        ? DateFormat('MMM dd, yyyy · hh:mm a').format(_lastBackup!.createdAt)
        : 'Never';

    return Scaffold(
      appBar: AppBar(
        title: const Text('Chat Backup & Restore'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // 1. Last Backup Card
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: isDark ? AppTheme.darkBorder : Colors.grey.shade300,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: AppTheme.primary.withValues(alpha: 0.15),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(Icons.cloud_done_rounded, color: AppTheme.primary, size: 28),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Last Encrypted Backup',
                            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            dateStr,
                            style: const TextStyle(fontSize: 13, color: AppTheme.textMuted),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                if (_lastBackup != null) ...[
                  const SizedBox(height: 14),
                  const Divider(),
                  const SizedBox(height: 10),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      _buildMetaItem('Size', _lastBackup!.formattedSize),
                      _buildMetaItem('Chats', '${_lastBackup!.conversationCount}'),
                      _buildMetaItem('Messages', '${_lastBackup!.messageCount}'),
                      _buildMetaItem('Media', '${_lastBackup!.mediaCount} files'),
                    ],
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 20),

          // 2. Active Progress Banner
          if (_isLoading)
            Container(
              margin: const EdgeInsets.only(bottom: 20),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          _statusMessage,
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                        ),
                      ),
                      Text('${(_progress * 100).toInt()}%', style: const TextStyle(fontWeight: FontWeight.bold)),
                    ],
                  ),
                  const SizedBox(height: 10),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: _progress,
                      backgroundColor: Colors.white24,
                      valueColor: const AlwaysStoppedAnimation<Color>(AppTheme.primary),
                    ),
                  ),
                ],
              ),
            ),

          // 3. Primary Actions: Back Up Now & Restore
          ElevatedButton.icon(
            icon: const Icon(Icons.backup_rounded),
            label: const Text('Back Up Now', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            onPressed: _isLoading ? null : _startBackupFlow,
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            icon: const Icon(Icons.settings_backup_restore_rounded, color: AppTheme.primary),
            label: const Text('Restore from Local Backup', style: TextStyle(fontWeight: FontWeight.bold, color: AppTheme.primary)),
            style: OutlinedButton.styleFrom(
              side: const BorderSide(color: AppTheme.primary, width: 1.2),
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            onPressed: _isLoading ? null : _startRestoreFlow,
          ),
          const SizedBox(height: 24),

          // 4. Options & Settings
          const Text('Backup Settings', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15, color: AppTheme.primary)),
          const SizedBox(height: 8),
          SwitchListTile(
            title: const Text('Include Videos & Photos', style: TextStyle(fontWeight: FontWeight.w500)),
            subtitle: const Text('Include voice notes and saved images in backup file', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            value: _includeMedia,
            activeThumbColor: AppTheme.primary,
            onChanged: (val) => setState(() => _includeMedia = val),
          ),
          ListTile(
            title: const Text('Auto-Backup Schedule', style: TextStyle(fontWeight: FontWeight.w500)),
            subtitle: Text('Frequency: $_autoBackupFrequency', style: const TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            trailing: const Icon(Icons.chevron_right),
            onTap: () {
              showDialog(
                context: context,
                builder: (ctx) => AlertDialog(
                  backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
                  title: const Text('Auto-Backup Frequency'),
                  content: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: ['Daily', 'Weekly', 'Monthly', 'Off'].map((f) {
                      return ListTile(
                        title: Text(f),
                        trailing: _autoBackupFrequency == f ? const Icon(Icons.check, color: AppTheme.primary) : null,
                        onTap: () {
                          setState(() => _autoBackupFrequency = f);
                          Navigator.pop(ctx);
                        },
                      );
                    }).toList(),
                  ),
                ),
              );
            },
          ),
          const Divider(),
          const SizedBox(height: 8),

          // 5. Security & Device Transfer Note
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.shield_outlined, color: AppTheme.primary, size: 20),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'End-to-End Encrypted Backups: Your backup is encrypted with your personal password using AES-256 + HMAC-SHA256. Nobody (including server administrators) can read your backup without the password.',
                    style: TextStyle(fontSize: 11.5, color: AppTheme.textMuted, height: 1.4),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMetaItem(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontSize: 11, color: AppTheme.textMuted)),
        const SizedBox(height: 2),
        Text(value, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
      ],
    );
  }
}
