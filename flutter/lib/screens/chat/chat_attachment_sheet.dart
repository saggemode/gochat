import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

class ChatAttachmentSheet extends StatelessWidget {
  final VoidCallback onOpenPoll;
  final VoidCallback onOpenCanvas;
  final VoidCallback onOpenMiniGame;
  final VoidCallback onShareProduct;
  final VoidCallback onSendPing;
  final VoidCallback onShareImage;
  final VoidCallback onShareDocument;
  final VoidCallback onAskBot;

  const ChatAttachmentSheet({
    super.key,
    required this.onOpenPoll,
    required this.onOpenCanvas,
    required this.onOpenMiniGame,
    required this.onShareProduct,
    required this.onSendPing,
    required this.onShareImage,
    required this.onShareDocument,
    required this.onAskBot,
  });

  static Future<void> show(
    BuildContext context, {
    required VoidCallback onOpenPoll,
    required VoidCallback onOpenCanvas,
    required VoidCallback onOpenMiniGame,
    required VoidCallback onShareProduct,
    required VoidCallback onSendPing,
    required VoidCallback onShareImage,
    required VoidCallback onShareDocument,
    required VoidCallback onAskBot,
  }) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return showModalBottomSheet(
      context: context,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => ChatAttachmentSheet(
        onOpenPoll: onOpenPoll,
        onOpenCanvas: onOpenCanvas,
        onOpenMiniGame: onOpenMiniGame,
        onShareProduct: onShareProduct,
        onSendPing: onSendPing,
        onShareImage: onShareImage,
        onShareDocument: onShareDocument,
        onAskBot: onAskBot,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: isDark ? Colors.white24 : Colors.black12,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 20),
          Wrap(
            spacing: 24,
            runSpacing: 20,
            alignment: WrapAlignment.center,
            children: [
              _buildOption(
                context,
                Icons.poll_rounded,
                'Live Poll',
                Colors.amber,
                () {
                  Navigator.pop(context);
                  onOpenPoll();
                },
              ),
              _buildOption(
                context,
                Icons.vibration_rounded,
                'GOCHAT PING!',
                Colors.redAccent,
                () {
                  Navigator.pop(context);
                  onSendPing();
                },
              ),
              _buildOption(
                context,
                Icons.storefront_rounded,
                'Store Product',
                Colors.tealAccent,
                () {
                  Navigator.pop(context);
                  onShareProduct();
                },
              ),
              _buildOption(
                context,
                Icons.brush_rounded,
                'Canvas',
                Colors.pinkAccent,
                () {
                  Navigator.pop(context);
                  onOpenCanvas();
                },
              ),
              _buildOption(
                context,
                Icons.image_rounded,
                'Gallery',
                Colors.purpleAccent,
                () {
                  Navigator.pop(context);
                  onShareImage();
                },
              ),
              _buildOption(
                context,
                Icons.videogame_asset_rounded,
                'Mini-Game',
                AppTheme.accent,
                () {
                  Navigator.pop(context);
                  onOpenMiniGame();
                },
              ),
              _buildOption(
                context,
                Icons.insert_drive_file_rounded,
                'Document',
                Colors.blueAccent,
                () {
                  Navigator.pop(context);
                  onShareDocument();
                },
              ),
              _buildOption(
                context,
                Icons.smart_toy_rounded,
                '@bot AI',
                const Color(0xFF10B981),
                () {
                  Navigator.pop(context);
                  onAskBot();
                },
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildOption(
    BuildContext context,
    IconData icon,
    String label,
    Color color,
    VoidCallback onTap,
  ) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: SizedBox(
        width: 68,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 54,
              height: 54,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: color.withValues(alpha: 0.3), width: 1),
              ),
              child: Icon(icon, color: color, size: 26),
            ),
            const SizedBox(height: 8),
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: isDark ? AppTheme.textLight : AppTheme.textDark,
              ),
              textAlign: TextAlign.center,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }
}
