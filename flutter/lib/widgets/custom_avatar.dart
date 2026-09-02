import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';
import '../core/utils/media_image_helper.dart';

class CustomAvatar extends StatelessWidget {
  final String? imageUrl;
  final String name;
  final double radius;
  final bool isOnline;
  final bool showOnlineBadge;
  final bool isVerified;
  final bool hasStory;
  final VoidCallback? onTap;

  const CustomAvatar({
    super.key,
    this.imageUrl,
    this.name = '',
    this.radius = 24,
    this.isOnline = false,
    this.showOnlineBadge = false,
    this.isVerified = false,
    this.hasStory = false,
    this.onTap,
  });

  String get _initials {
    if (name.trim().isEmpty) return 'G';
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.substring(0, name.length >= 2 ? 2 : 1).toUpperCase();
  }

  ImageProvider? _getImageProvider(String? url) {
    return MediaImageHelper.safeImageProvider(url);
  }

  @override
  Widget build(BuildContext context) {
    final imageProvider = _getImageProvider(imageUrl);

    final avatarContent = CircleAvatar(
      radius: radius,
      backgroundColor: AppTheme.primary.withValues(alpha: 0.18),
      backgroundImage: imageProvider,
      child: imageProvider == null
          ? Text(
              _initials,
              style: TextStyle(
                fontSize: radius * 0.75,
                fontWeight: FontWeight.bold,
                color: AppTheme.primary,
              ),
            )
          : null,
    );

    Widget widgetTree = avatarContent;

    // Add story gradient ring if active
    if (hasStory) {
      widgetTree = Container(
        padding: const EdgeInsets.all(2.5),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: const LinearGradient(
            colors: [Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF8B5CF6)],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: avatarContent,
      );
    }

    // Add online status badge or verified indicator
    if (showOnlineBadge || isVerified) {
      widgetTree = Stack(
        clipBehavior: Clip.none,
        children: [
          widgetTree,
          if (showOnlineBadge && isOnline)
            Positioned(
              bottom: 0,
              right: 0,
              child: Container(
                width: radius * 0.55,
                height: radius * 0.55,
                decoration: BoxDecoration(
                  color: AppTheme.onlineGreen,
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: Theme.of(context).scaffoldBackgroundColor,
                    width: 2,
                  ),
                ),
              ),
            ),
          if (isVerified)
            Positioned(
              top: -2,
              right: -2,
              child: Container(
                padding: const EdgeInsets.all(1.5),
                decoration: BoxDecoration(
                  color: AppTheme.primary,
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: Theme.of(context).scaffoldBackgroundColor,
                    width: 1.5,
                  ),
                ),
                child: Icon(
                  Icons.check,
                  size: radius * 0.45,
                  color: Colors.white,
                ),
              ),
            ),
        ],
      );
    }

    if (onTap != null) {
      return GestureDetector(onTap: onTap, child: widgetTree);
    }

    return widgetTree;
  }
}
