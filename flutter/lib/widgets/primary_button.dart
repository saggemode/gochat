import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';

enum ButtonVariant { filled, outlined, ghost }

class PrimaryButton extends StatelessWidget {
  final String label;
  final VoidCallback? onPressed;
  final bool isLoading;
  final bool isFullWidth;
  final double height;
  final IconData? icon;
  final IconData? trailingIcon;
  final ButtonVariant variant;
  final Color? color;

  const PrimaryButton({
    super.key,
    required this.label,
    this.onPressed,
    this.isLoading = false,
    this.isFullWidth = true,
    this.height = 48,
    this.icon,
    this.trailingIcon,
    this.variant = ButtonVariant.filled,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    final primaryColor = color ?? AppTheme.primary;

    Widget child;
    if (isLoading) {
      child = SizedBox(
        width: 20,
        height: 20,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          color: variant == ButtonVariant.filled ? Colors.black : primaryColor,
        ),
      );
    } else {
      child = Row(
        mainAxisSize: isFullWidth ? MainAxisSize.max : MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 18),
            const SizedBox(width: 8),
          ],
          Text(
            label,
            style: TextStyle(
              fontSize: 14.5,
              fontWeight: FontWeight.bold,
              letterSpacing: 0.2,
              color: variant == ButtonVariant.filled
                  ? Colors.black
                  : primaryColor,
            ),
          ),
          if (trailingIcon != null) ...[
            const SizedBox(width: 8),
            Icon(trailingIcon, size: 18),
          ],
        ],
      );
    }

    Widget button;
    if (variant == ButtonVariant.outlined) {
      button = OutlinedButton(
        style: OutlinedButton.styleFrom(
          side: BorderSide(color: primaryColor, width: 1.5),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(horizontal: 18),
        ),
        onPressed: isLoading ? null : onPressed,
        child: child,
      );
    } else if (variant == ButtonVariant.ghost) {
      button = TextButton(
        style: TextButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(horizontal: 18),
        ),
        onPressed: isLoading ? null : onPressed,
        child: child,
      );
    } else {
      button = ElevatedButton(
        style: ElevatedButton.styleFrom(
          backgroundColor: primaryColor,
          foregroundColor: Colors.black,
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(horizontal: 18),
        ),
        onPressed: isLoading ? null : onPressed,
        child: child,
      );
    }

    return SizedBox(
      width: isFullWidth ? double.infinity : null,
      height: height,
      child: button,
    );
  }
}
