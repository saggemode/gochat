import 'dart:math';
import 'package:flutter/material.dart';

class ChatDoodlePainter extends CustomPainter {
  final Color color;
  final double opacity;

  ChatDoodlePainter({
    required this.color,
    this.opacity = 0.06,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (opacity <= 0.001) return;

    final paint = Paint()
      ..color = color.withValues(alpha: opacity)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2
      ..strokeCap = StrokeCap.round;

    final fillPaint = Paint()
      ..color = color.withValues(alpha: opacity * 0.7)
      ..style = PaintingStyle.fill;

    const double spacing = 64.0;
    final cols = (size.width / spacing).ceil() + 1;
    final rows = (size.height / spacing).ceil() + 1;

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        final x = c * spacing + ((r % 2 == 0) ? 0 : spacing * 0.5);
        final y = r * spacing;
        final iconIndex = (r * 7 + c * 13) % 8;

        canvas.save();
        canvas.translate(x, y);

        // Subtle rotation for organic feel
        final angle = (sin(r + c) * 0.2);
        canvas.rotate(angle);

        switch (iconIndex) {
          case 0:
            // Chat bubble
            final rect = RRect.fromRectAndRadius(
              const Rect.fromLTWH(-10, -8, 20, 16),
              const Radius.circular(5),
            );
            canvas.drawRRect(rect, paint);
            final tail = Path()
              ..moveTo(-4, 8)
              ..lineTo(-8, 12)
              ..lineTo(0, 8)
              ..close();
            canvas.drawPath(tail, fillPaint);
            break;

          case 1:
            // Star
            final star = Path();
            for (int i = 0; i < 5; i++) {
              final a1 = (i * 4 * pi) / 5 - pi / 2;
              final px = cos(a1) * 8;
              final py = sin(a1) * 8;
              if (i == 0) {
                star.moveTo(px, py);
              } else {
                star.lineTo(px, py);
              }
            }
            star.close();
            canvas.drawPath(star, paint);
            break;

          case 2:
            // Heart
            final heart = Path()
              ..moveTo(0, 3)
              ..cubicTo(-6, -4, -10, 2, 0, 9)
              ..cubicTo(10, 2, 6, -4, 0, 3)
              ..close();
            canvas.drawPath(heart, fillPaint);
            break;

          case 3:
            // Music note
            canvas.drawCircle(const Offset(-4, 4), 3, fillPaint);
            canvas.drawCircle(const Offset(4, 2), 3, fillPaint);
            canvas.drawLine(const Offset(-1, 4), const Offset(-1, -6), paint);
            canvas.drawLine(const Offset(7, 2), const Offset(7, -8), paint);
            canvas.drawLine(const Offset(-1, -6), const Offset(7, -8), paint);
            break;

          case 4:
            // Smile circle
            canvas.drawCircle(Offset.zero, 8, paint);
            canvas.drawCircle(const Offset(-3, -2), 1.2, fillPaint);
            canvas.drawCircle(const Offset(3, -2), 1.2, fillPaint);
            final smile = Path()
              ..moveTo(-4, 2)
              ..quadraticBezierTo(0, 6, 4, 2);
            canvas.drawPath(smile, paint);
            break;

          case 5:
            // Diamond / sparkle
            final spark = Path()
              ..moveTo(0, -8)
              ..quadraticBezierTo(0, 0, 8, 0)
              ..quadraticBezierTo(0, 0, 0, 8)
              ..quadraticBezierTo(0, 0, -8, 0)
              ..quadraticBezierTo(0, 0, 0, -8)
              ..close();
            canvas.drawPath(spark, paint);
            break;

          case 6:
            // Location Pin
            canvas.drawCircle(const Offset(0, -3), 4, paint);
            final pinTail = Path()
              ..moveTo(-3, -1)
              ..lineTo(0, 6)
              ..lineTo(3, -1);
            canvas.drawPath(pinTail, paint);
            break;

          case 7:
            // Concentric rings / wave
            canvas.drawCircle(Offset.zero, 4, paint);
            canvas.drawCircle(Offset.zero, 8, paint);
            break;
        }

        canvas.restore();
      }
    }
  }

  @override
  bool shouldRepaint(covariant ChatDoodlePainter oldDelegate) {
    return oldDelegate.color != color || oldDelegate.opacity != opacity;
  }
}
