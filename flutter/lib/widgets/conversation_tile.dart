import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';
import 'custom_avatar.dart';

class ConversationTile extends StatelessWidget {
  final String name;
  final String? avatarUrl;
  final String lastMessage;
  final String time;
  final int unreadCount;
  final bool isOnline;
  final bool isMuted;
  final bool isPinned;
  final bool isTyping;
  final bool isGroup;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  const ConversationTile({
    super.key,
    required this.name,
    this.avatarUrl,
    this.lastMessage = '',
    this.time = '',
    this.unreadCount = 0,
    this.isOnline = false,
    this.isMuted = false,
    this.isPinned = false,
    this.isTyping = false,
    this.isGroup = false,
    this.onTap,
    this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final hasUnread = unreadCount > 0;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              // Avatar
              CustomAvatar(
                imageUrl: avatarUrl,
                name: name,
                radius: 26,
                isOnline: isOnline,
                showOnlineBadge: !isGroup,
              ),
              const SizedBox(width: 14),

              // Name & Message
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Row(
                            children: [
                              if (isGroup)
                                Padding(
                                  padding: const EdgeInsets.only(right: 4),
                                  child: Icon(
                                    Icons.group_rounded,
                                    size: 16,
                                    color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                                  ),
                                ),
                              Flexible(
                                child: Text(
                                  name,
                                  style: TextStyle(
                                    fontSize: 15.5,
                                    fontWeight: hasUnread ? FontWeight.bold : FontWeight.w600,
                                    color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          time,
                          style: TextStyle(
                            fontSize: 11.5,
                            color: hasUnread
                                ? AppTheme.primary
                                : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                            fontWeight: hasUnread ? FontWeight.bold : FontWeight.normal,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),

                    Row(
                      children: [
                        Expanded(
                          child: isTyping
                              ? Text(
                                  'typing...',
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: AppTheme.primary,
                                    fontStyle: FontStyle.italic,
                                  ),
                                )
                              : Text(
                                  lastMessage,
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                    fontWeight: hasUnread ? FontWeight.w500 : FontWeight.normal,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                        ),

                        // Trailing indicators
                        if (isPinned) ...[
                          const SizedBox(width: 4),
                          Transform.rotate(
                            angle: 0.6,
                            child: Icon(
                              Icons.push_pin_rounded,
                              size: 14,
                              color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                            ),
                          ),
                        ],
                        if (isMuted) ...[
                          const SizedBox(width: 4),
                          Icon(
                            Icons.volume_off_rounded,
                            size: 14,
                            color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                          ),
                        ],
                        if (hasUnread) ...[
                          const SizedBox(width: 6),
                          Container(
                            constraints: const BoxConstraints(minWidth: 22),
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                            decoration: BoxDecoration(
                              color: isMuted ? AppTheme.textMuted : AppTheme.primary,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Text(
                              unreadCount > 99 ? '99+' : '$unreadCount',
                              style: const TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                              textAlign: TextAlign.center,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
