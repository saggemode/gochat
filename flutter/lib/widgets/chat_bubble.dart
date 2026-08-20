import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import '../core/models/message.dart';
import '../core/theme/app_theme.dart';
import '../screens/chat/media_lightbox_screen.dart';
import 'audio_player_bubble.dart';
import 'poll_bubble.dart';

class ChatBubble extends StatelessWidget {
  final Message message;
  final String currentUserId;
  final Function(String optionId)? onVotePoll;
  final Function(String emoji)? onReact;
  final VoidCallback? onReply;
  final VoidCallback? onOpenCanvas;
  final Function(Map<String, dynamic> product)? onBuyProduct;

  const ChatBubble({
    super.key,
    required this.message,
    required this.currentUserId,
    this.onVotePoll,
    this.onReact,
    this.onReply,
    this.onOpenCanvas,
    this.onBuyProduct,
  });

  Widget _buildStatusTicks(MessageStatus status) {
    switch (status) {
      case MessageStatus.pending:
        return const Icon(Icons.access_time_rounded, size: 13, color: AppTheme.textMuted);
      case MessageStatus.sent:
        return const Icon(Icons.check, size: 14, color: AppTheme.textMuted);
      case MessageStatus.delivered:
        return const Icon(Icons.done_all, size: 14, color: AppTheme.textMuted);
      case MessageStatus.read:
        return const Icon(Icons.done_all, size: 14, color: AppTheme.readBlue);
      case MessageStatus.failed:
        return const Icon(Icons.error_outline, size: 13, color: AppTheme.dangerRed);
    }
  }

  void _showReactionMenu(BuildContext context) {
    HapticFeedback.mediumImpact();
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (ctx) {
        final isDark = Theme.of(context).brightness == Brightness.dark;

        return Container(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            color: isDark ? AppTheme.darkSurface : Colors.white,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder, width: 0.5),
            boxShadow: const [
              BoxShadow(
                color: Colors.black45,
                blurRadius: 20,
                offset: Offset(0, 8),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // Emoji Row
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: ['👍', '❤️', '😂', '😮', '😢', '🙏', '🔥', '🎉'].map((emoji) {
                  return GestureDetector(
                    onTap: () {
                      Navigator.pop(ctx);
                      onReact?.call(emoji);
                    },
                    child: Container(
                      padding: const EdgeInsets.all(4),
                      child: Text(
                        emoji,
                        style: const TextStyle(fontSize: 26),
                      ),
                    ),
                  );
                }).toList(),
              ),
              const SizedBox(height: 12),
              const Divider(height: 1),
              const SizedBox(height: 6),

              // Action Options
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  TextButton.icon(
                    onPressed: () {
                      Navigator.pop(ctx);
                      onReply?.call();
                    },
                    icon: const Icon(Icons.reply_rounded, size: 18, color: AppTheme.primary),
                    label: const Text('Reply', style: TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold)),
                  ),
                  TextButton.icon(
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: message.content));
                      Navigator.pop(ctx);
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Message copied to clipboard'), duration: Duration(seconds: 1)),
                      );
                    },
                    icon: Icon(Icons.copy_rounded, size: 18, color: isDark ? AppTheme.textLight : AppTheme.textDark),
                    label: Text('Copy', style: TextStyle(color: isDark ? AppTheme.textLight : AppTheme.textDark)),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isMe = message.isMe;
    final timeStr = DateFormat('hh:mm a').format(message.createdAt);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    // Background color based on theme and ping type
    Color bubbleColor;
    if (message.isPing) {
      bubbleColor = isMe
          ? const Color(0xFF7F1D1D) // Dark red for sender
          : const Color(0xFF991B1B); // Vivid red for receiver
    } else if (isMe) {
      bubbleColor = isDark ? AppTheme.senderBubbleDark : AppTheme.senderBubbleLight;
    } else {
      bubbleColor = isDark ? AppTheme.receiverBubbleDark : AppTheme.receiverBubbleLight;
    }

    final textColor = isMe
        ? (isDark ? AppTheme.textLight : AppTheme.textDark)
        : (isDark ? AppTheme.textLight : AppTheme.textDark);

    return Dismissible(
      key: ValueKey('msg_${message.id}_${message.createdAt.microsecondsSinceEpoch}'),
      direction: DismissDirection.startToEnd,
      confirmDismiss: (dir) async {
        HapticFeedback.lightImpact();
        onReply?.call();
        return false;
      },
      background: Container(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.only(left: 20),
        child: const Icon(Icons.reply_rounded, color: AppTheme.primary, size: 24),
      ),
      child: Align(
        alignment: isMe ? Alignment.centerRight : Alignment.centerLeft,
        child: GestureDetector(
          onLongPress: () => _showReactionMenu(context),
          child: Container(
            margin: EdgeInsets.only(
              top: 3,
              bottom: 3,
              left: isMe ? 48 : 8,
              right: isMe ? 8 : 48,
            ),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: bubbleColor,
              borderRadius: BorderRadius.only(
                topLeft: const Radius.circular(16),
                topRight: const Radius.circular(16),
                bottomLeft: isMe ? const Radius.circular(16) : const Radius.circular(4),
                bottomRight: isMe ? const Radius.circular(4) : const Radius.circular(16),
              ),
              border: message.isPing
                  ? Border.all(color: Colors.redAccent, width: 1.5)
                  : null,
              boxShadow: [
                BoxShadow(
                  color: message.isPing
                      ? Colors.red.withValues(alpha: 0.3)
                      : (isDark ? Colors.black26 : Colors.black12),
                  blurRadius: message.isPing ? 8 : 3,
                  offset: const Offset(0, 1),
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment:
                  isMe ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                // Sender Name if Group chat and not me
                if (!isMe && message.senderName.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4.0),
                    child: Text(
                      message.senderName,
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: AppTheme.accent,
                      ),
                    ),
                  ),

                // ── Quoted Reply Preview ──────────────────────────────────────
                if (message.replyToText != null)
                  Container(
                    margin: const EdgeInsets.only(bottom: 6),
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(8),
                      border: const Border(
                        left: BorderSide(color: AppTheme.primary, width: 3.5),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (message.replyToSenderName != null)
                          Text(
                            message.replyToSenderName!,
                            style: const TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                              color: AppTheme.primary,
                            ),
                          ),
                        Text(
                          message.replyToText!,
                          style: TextStyle(
                            fontSize: 11.5,
                            color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),

                // ── BBM PING! Display ─────────────────────────────────────────
                if (message.isPing)
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.vibration_rounded, color: Colors.amberAccent, size: 20),
                      const SizedBox(width: 6),
                      Text(
                        '💥 PING!!!',
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w900,
                          color: Colors.white,
                          letterSpacing: 1.5,
                        ),
                      ),
                    ],
                  )
                // ── In-Chat Product Card ──────────────────────────────────────
                else if (message.type == MessageType.product && message.productData != null)
                  Container(
                    width: 250,
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: isDark ? AppTheme.darkCard : Colors.white,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (message.productData!['image_url'] != null)
                          ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: Image.network(
                              message.productData!['image_url'],
                              height: 120,
                              width: double.infinity,
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) => const SizedBox(),
                            ),
                          ),
                        const SizedBox(height: 8),
                        Text(
                          message.productData!['title'] ?? 'Product',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                            color: isDark ? AppTheme.textLight : AppTheme.textDark,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              '\$${(message.productData!['price'] ?? 0).toString()}',
                              style: const TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.bold,
                                color: AppTheme.primary,
                              ),
                            ),
                            ElevatedButton(
                              style: ElevatedButton.styleFrom(
                                backgroundColor: AppTheme.primary,
                                foregroundColor: Colors.black,
                                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                              ),
                              onPressed: () => onBuyProduct?.call(message.productData!),
                              child: const Text('Buy Now', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
                            ),
                          ],
                        ),
                      ],
                    ),
                  )
                // ── Audio / Voice Note ────────────────────────────────────────
                else if (message.type == MessageType.voice || message.type == MessageType.audio)
                  AudioPlayerBubble(
                    durationSeconds: message.mediaDuration ?? 30,
                    isMe: isMe,
                    audioUrl: message.mediaUrl,
                  )
                // ── Poll Bubble ───────────────────────────────────────────────
                else if (message.type == MessageType.poll && message.pollData != null)
                  PollBubble(
                    pollData: message.pollData!,
                    currentUserId: currentUserId,
                    isMe: isMe,
                    onVote: (optId) => onVotePoll?.call(optId),
                  )
                // ── Live Canvas Mini-App ──────────────────────────────────────
                else if (message.type == MessageType.canvas)
                  GestureDetector(
                    onTap: onOpenCanvas,
                    child: Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: Colors.black26,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: AppTheme.accent.withValues(alpha: 0.3)),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.brush_rounded, color: AppTheme.accent, size: 24),
                          const SizedBox(width: 8),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'Shared Live Canvas',
                                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                              ),
                              Text(
                                'Tap to collaborate & draw together',
                                style: TextStyle(color: isMe ? Colors.white70 : AppTheme.textMuted, fontSize: 11),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  )
                // ── Image Attachment (Tap to Lightbox) ────────────────────────
                else if (message.type == MessageType.image && message.mediaUrl != null)
                  GestureDetector(
                    onTap: () {
                      MediaLightboxScreen.show(
                        context,
                        mediaUrl: message.mediaUrl!,
                        title: message.senderName,
                        caption: message.content.isNotEmpty && message.content != 'Shared an image' ? message.content : null,
                        timestamp: message.createdAt,
                        heroTag: 'img_${message.id}',
                      );
                    },
                    child: Hero(
                      tag: 'img_${message.id}',
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(10),
                        child: Image.network(
                          message.mediaUrl!,
                          width: 240,
                          height: 180,
                          fit: BoxFit.cover,
                          errorBuilder: (ctx, _, __) => Container(
                            width: 240,
                            height: 140,
                            color: Colors.black26,
                            child: const Icon(Icons.broken_image, color: AppTheme.iconColor),
                          ),
                        ),
                      ),
                    ),
                  )
                // ── Text Content ──────────────────────────────────────────────
                else
                  Text(
                    message.content,
                    style: TextStyle(
                      fontSize: 14.5,
                      color: textColor,
                      height: 1.35,
                    ),
                  ),

                const SizedBox(height: 3),

                // ── Timestamp & Status Ticks ──────────────────────────────────
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      timeStr,
                      style: TextStyle(
                        fontSize: 10,
                        color: isMe ? (isDark ? Colors.white60 : Colors.black54) : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                      ),
                    ),
                    if (isMe) ...[
                      const SizedBox(width: 4),
                      _buildStatusTicks(message.status),
                    ],
                  ],
                ),

                // ── Reaction Pills ────────────────────────────────────────────
                if (message.reactions.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4.0),
                    child: Wrap(
                      spacing: 4,
                      children: message.reactions.entries.map((entry) {
                        return GestureDetector(
                          onTap: () => onReact?.call(entry.key),
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: isDark ? AppTheme.darkSurface : Colors.white,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(
                                color: entry.value.contains(currentUserId)
                                    ? AppTheme.primary
                                    : (isDark ? Colors.white12 : Colors.black12),
                                width: 0.8,
                              ),
                            ),
                            child: Text(
                              '${entry.key} ${entry.value.length}',
                              style: const TextStyle(fontSize: 11),
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
