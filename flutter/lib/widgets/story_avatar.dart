import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';

class StoryAvatar extends StatelessWidget {
  final String avatarUrl;
  final double radius;
  final bool hasUnseenStory;
  final int storyCount;
  final VoidCallback? onTap;

  const StoryAvatar({
    super.key,
    required this.avatarUrl,
    this.radius = 26,
    this.hasUnseenStory = false,
    this.storyCount = 0,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    Widget avatar = CircleAvatar(
      radius: radius,
      backgroundColor: AppTheme.darkCard,
      backgroundImage: avatarUrl.isNotEmpty ? NetworkImage(avatarUrl) : null,
      child: avatarUrl.isEmpty
          ? const Icon(Icons.person, color: AppTheme.iconColor)
          : null,
    );

    if (!hasUnseenStory && storyCount == 0) {
      return GestureDetector(onTap: onTap, child: avatar);
    }

    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(2.5),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: hasUnseenStory
              ? const LinearGradient(
                  colors: [AppTheme.primary, AppTheme.accent, Colors.tealAccent],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                )
              : const LinearGradient(
                  colors: [AppTheme.textMuted, AppTheme.darkBorder],
                ),
        ),
        child: Container(
          padding: const EdgeInsets.all(2),
          decoration: const BoxDecoration(
            color: AppTheme.darkBg,
            shape: BoxShape.circle,
          ),
          child: avatar,
        ),
      ),
    );
  }
}
