import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/models/user.dart';
import '../core/theme/app_theme.dart';

class AppDrawerProfileHeader extends StatelessWidget {
  final User? user;
  final bool isDarkMode;
  final VoidCallback? onToggleTheme;

  const AppDrawerProfileHeader({
    super.key,
    required this.user,
    this.isDarkMode = true,
    this.onToggleTheme,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
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
                    ? NetworkImage(user!.avatarUrl)
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
                  const SizedBox(height: 6),
                  InkWell(
                    onTap: () {
                      Clipboard.setData(ClipboardData(text: user!.pin));
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(
                          content: Text('Copied PIN ${user!.pin} to clipboard!'),
                          backgroundColor: AppTheme.primary,
                          duration: const Duration(seconds: 2),
                        ),
                      );
                    },
                    borderRadius: BorderRadius.circular(8),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: AppTheme.primary.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3), width: 0.5),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.vpn_key_rounded, color: AppTheme.primary, size: 12),
                          const SizedBox(width: 4),
                          Flexible(
                            child: Text(
                              'PIN: ${user!.pin}',
                              style: const TextStyle(
                                color: AppTheme.primary,
                                fontSize: 11,
                                fontWeight: FontWeight.bold,
                                fontFamily: 'monospace',
                                letterSpacing: 1,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 4),
                          const Icon(Icons.copy_rounded, color: AppTheme.primary, size: 12),
                        ],
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (onToggleTheme != null)
            IconButton(
              icon: Icon(
                isDarkMode ? Icons.light_mode_rounded : Icons.dark_mode_rounded,
                color: isDarkMode ? Colors.amber : AppTheme.primary,
                size: 22,
              ),
              tooltip: isDarkMode ? 'Switch to Light Theme' : 'Switch to Dark Theme',
              onPressed: onToggleTheme,
            ),
        ],
      ),
    );
  }
}
