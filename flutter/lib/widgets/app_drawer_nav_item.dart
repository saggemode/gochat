import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';

class AppDrawerNavItem extends StatelessWidget {
  final IconData icon;
  final IconData? activeIcon;
  final String title;
  final bool isSelected;
  final int badgeCount;
  final String? badgeText;
  final VoidCallback onTap;

  const AppDrawerNavItem({
    super.key,
    required this.icon,
    this.activeIcon,
    required this.title,
    this.isSelected = false,
    this.badgeCount = 0,
    this.badgeText,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = isSelected ? AppTheme.primary : AppTheme.textLight;
    final currentIcon = (isSelected && activeIcon != null) ? activeIcon! : icon;

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: isSelected
            ? Border.all(color: AppTheme.primary.withValues(alpha: 0.3), width: 1)
            : null,
      ),
      child: Material(
        color: isSelected ? AppTheme.primary.withValues(alpha: 0.15) : Colors.transparent,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: ListTile(
        dense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 0),
        leading: Icon(currentIcon, color: color, size: 22),
        title: Text(
          title,
          style: TextStyle(
            color: color,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
            fontSize: 14,
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (badgeCount > 0)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppTheme.accent,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  badgeCount.toString(),
                  style: const TextStyle(
                    color: Colors.black,
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            if (badgeText != null)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: AppTheme.primary.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  badgeText!,
                  style: const TextStyle(
                    color: AppTheme.primary,
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            const SizedBox(width: 4),
            Icon(Icons.chevron_right_rounded, color: isSelected ? AppTheme.primary : AppTheme.textMuted, size: 18),
          ],
        ),
        onTap: onTap,
      ),
    ),
  );
  }
}
