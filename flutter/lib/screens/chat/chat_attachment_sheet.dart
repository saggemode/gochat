import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

class ChatAttachmentSheet extends StatelessWidget {
  final VoidCallback onOpenPoll;
  final VoidCallback onOpenCanvas;
  final VoidCallback onOpenMiniGame;
  final VoidCallback onShareImage;
  final VoidCallback onShareDocument;
  final VoidCallback onAskBot;

  const ChatAttachmentSheet({
    super.key,
    required this.onOpenPoll,
    required this.onOpenCanvas,
    required this.onOpenMiniGame,
    required this.onShareImage,
    required this.onShareDocument,
    required this.onAskBot,
  });

  static Future<void> show(
    BuildContext context, {
    required VoidCallback onOpenPoll,
    required VoidCallback onOpenCanvas,
    required VoidCallback onOpenMiniGame,
    required VoidCallback onShareImage,
    required VoidCallback onShareDocument,
    required VoidCallback onAskBot,
  }) {
    return showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.darkSurface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => ChatAttachmentSheet(
        onOpenPoll: onOpenPoll,
        onOpenCanvas: onOpenCanvas,
        onOpenMiniGame: onOpenMiniGame,
        onShareImage: onShareImage,
        onShareDocument: onShareDocument,
        onAskBot: onAskBot,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: Colors.white24,
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
                Icons.poll_rounded,
                'Poll',
                Colors.amber,
                () {
                  Navigator.pop(context);
                  onOpenPoll();
                },
              ),
              _buildOption(
                Icons.brush_rounded,
                'Canvas',
                Colors.pinkAccent,
                () {
                  Navigator.pop(context);
                  onOpenCanvas();
                },
              ),
              _buildOption(
                Icons.image_rounded,
                'Gallery',
                Colors.purpleAccent,
                () {
                  Navigator.pop(context);
                  onShareImage();
                },
              ),
              _buildOption(
                Icons.videogame_asset_rounded,
                'Mini-Game',
                AppTheme.accent,
                () {
                  Navigator.pop(context);
                  onOpenMiniGame();
                },
              ),
              _buildOption(
                Icons.insert_drive_file_rounded,
                'Document',
                Colors.blueAccent,
                () {
                  Navigator.pop(context);
                  onShareDocument();
                },
              ),
              _buildOption(
                Icons.smart_toy_rounded,
                '@Bot AI',
                Colors.tealAccent,
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

  Widget _buildOption(IconData icon, String label, Color color, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.15),
              shape: BoxShape.circle,
              border: Border.all(color: color.withValues(alpha: 0.3)),
            ),
            child: Icon(icon, color: color, size: 26),
          ),
          const SizedBox(height: 6),
          Text(
            label,
            style: const TextStyle(fontSize: 12, color: AppTheme.textLight, fontWeight: FontWeight.w500),
          ),
        ],
      ),
    );
  }
}
