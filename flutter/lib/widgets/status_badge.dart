import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';

enum BadgeType { primary, success, warning, danger, neutral }

class StatusBadge extends StatelessWidget {
  final String text;
  final IconData? icon;
  final BadgeType type;
  final VoidCallback? onTap;

  const StatusBadge({
    super.key,
    required this.text,
    this.icon,
    this.type = BadgeType.primary,
    this.onTap,
  });

  Color _getColor() {
    switch (type) {
      case BadgeType.success:
        return AppTheme.onlineGreen;
      case BadgeType.warning:
        return Colors.amber;
      case BadgeType.danger:
        return AppTheme.dangerRed;
      case BadgeType.neutral:
        return AppTheme.textMuted;
      case BadgeType.primary:
        return AppTheme.primary;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _getColor();

    final badge = Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.3), width: 0.8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 13, color: color),
            const SizedBox(width: 4),
          ],
          Text(
            text,
            style: TextStyle(
              color: color,
              fontSize: 11,
              fontWeight: FontWeight.bold,
              letterSpacing: 0.3,
            ),
          ),
        ],
      ),
    );

    if (onTap != null) {
      return GestureDetector(onTap: onTap, child: badge);
    }
    return badge;
  }
}
