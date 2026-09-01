import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import '../core/models/chat_theme.dart';
import '../core/models/game_data.dart';
import '../core/models/message.dart';
import '../core/services/starred_message_service.dart';
import '../core/theme/app_theme.dart';
import 'audio_player_bubble.dart';
import 'game_bubble.dart';
import 'media_bubble.dart';
import 'poll_bubble.dart';

class ChatBubble extends StatelessWidget {
  final Message message;
  final String currentUserId;
  final ChatTheme? chatTheme;
  final Function(String optionId)? onVotePoll;
  final Function(String emoji)? onReact;
  final VoidCallback? onReply;
  final VoidCallback? onOpenCanvas;
  final VoidCallback? onOpenViewOnce;
  final Function(GameData updatedGame)? onUpdateGame;
  final Function(Map<String, dynamic> product)? onBuyProduct;

  const ChatBubble({
    super.key,
    required this.message,
    required this.currentUserId,
    this.chatTheme,
    this.onVotePoll,
    this.onReact,
    this.onReply,
    this.onOpenCanvas,
    this.onOpenViewOnce,
    this.onUpdateGame,
    this.onBuyProduct,
  });

  Widget _buildStatusTicks(MessageStatus status, {bool isMe = false, bool isDark = true}) {
    final subtleTickColor = isMe
        ? (isDark ? Colors.white60 : Colors.black54)
        : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight);

    switch (status) {
      case MessageStatus.pending:
        return Icon(Icons.access_time_rounded, size: 12, color: subtleTickColor);
      case MessageStatus.sent:
        // Single Good Sign (Grey Check)
        return Icon(Icons.check_rounded, size: 15, color: subtleTickColor);
      case MessageStatus.delivered:
        // Double Good Sign (Grey Double Check)
        return Icon(Icons.done_all_rounded, size: 15, color: subtleTickColor);
      case MessageStatus.read:
        // Double Good Sign in Green (Read Double Check)
        return const Icon(Icons.done_all_rounded, size: 15, color: Color(0xFF25D366));
      case MessageStatus.failed:
        return const Icon(Icons.error_outline_rounded, size: 13, color: AppTheme.dangerRed);
    }
  }

  void _showReactionMenu(BuildContext context) {
    HapticFeedback.mediumImpact();
    final starredService = StarredMessageService();
    final isStarred = starredService.isStarred(message.id);

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (ctx) {
        final isDark = Theme.of(context).brightness == Brightness.dark;

        return Container(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          decoration: BoxDecoration(
            color: isDark ? AppTheme.darkSurface : Colors.white,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder, width: 0.5),
            boxShadow: const [
              BoxShadow(color: Colors.black38, blurRadius: 16, offset: Offset(0, 4)),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // ── Quick Reactions Row ──────────────────────────────────────────
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: ['❤️', '👍', '😂', '😮', '😢', '🙏', '🔥', '🎉'].map((emoji) {
                    return GestureDetector(
                      onTap: () {
                        Navigator.pop(ctx);
                        onReact?.call(emoji);
                      },
                      child: Text(emoji, style: const TextStyle(fontSize: 28)),
                    );
                  }).toList(),
                ),
              ),
              const Divider(height: 1),

              // ── Star / Unstar Action ─────────────────────────────────────────
              ListTile(
                leading: Icon(
                  isStarred ? Icons.star_rounded : Icons.star_outline_rounded,
                  color: Colors.amber,
                ),
                title: Text(isStarred ? 'Unstar Message' : 'Star Message'),
                subtitle: Text(
                  isStarred ? 'Remove from saved bookmarks' : 'Bookmark this message for quick access',
                  style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                ),
                onTap: () async {
                  Navigator.pop(ctx);
                  await starredService.toggleStar(message);
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        content: Text(isStarred ? 'Message unstarred' : '⭐ Message starred! Saved to Bookmarks'),
                        duration: const Duration(seconds: 2),
                      ),
                    );
                  }
                },
              ),

              // ── Reply Action ─────────────────────────────────────────────────
              if (onReply != null)
                ListTile(
                  leading: const Icon(Icons.reply_rounded, color: AppTheme.primary),
                  title: const Text('Reply'),
                  onTap: () {
                    Navigator.pop(ctx);
                    onReply?.call();
                  },
                ),

              // ── Copy Text Action ─────────────────────────────────────────────
              if (message.content.isNotEmpty)
                ListTile(
                  leading: Icon(
                    Icons.copy_rounded,
                    color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                  ),
                  title: const Text('Copy Text'),
                  onTap: () {
                    Navigator.pop(ctx);
                    Clipboard.setData(ClipboardData(text: message.content));
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Message copied to clipboard')),
                      );
                    }
                  },
                ),
              const SizedBox(height: 6),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isMe = message.isMe || (currentUserId.isNotEmpty && message.senderId == currentUserId);
    final timeStr = DateFormat('hh:mm a').format(message.createdAt);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    final shape = chatTheme?.bubbleShape ?? BubbleShape.classic;

    // Determine Bubble Color & Gradient
    Gradient? bubbleGradient;
    Color bubbleColor;
    Color textColor;

    if (isMe) {
      if (chatTheme != null) {
        bubbleGradient = LinearGradient(
          colors: chatTheme!.senderGradient,
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        );
        bubbleColor = chatTheme!.senderGradient.first;
        textColor = chatTheme!.senderTextColor;
      } else {
        bubbleColor = isDark ? AppTheme.senderBubbleDark : AppTheme.senderBubbleLight;
        textColor = isDark ? AppTheme.textLight : AppTheme.textDark;
      }
    } else {
      if (chatTheme != null) {
        bubbleColor = chatTheme!.receiverColor;
        textColor = chatTheme!.receiverTextColor;
      } else {
        bubbleColor = isDark ? AppTheme.receiverBubbleDark : AppTheme.receiverBubbleLight;
        textColor = isDark ? AppTheme.textLight : AppTheme.textDark;
      }
    }

    // Determine Border Radius based on shape
    BorderRadius borderRadius;
    switch (shape) {
      case BubbleShape.glassmorphism:
        borderRadius = BorderRadius.circular(18);
        break;
      case BubbleShape.minimalFlat:
        borderRadius = BorderRadius.circular(8);
        break;
      case BubbleShape.neonGlow:
        borderRadius = BorderRadius.circular(16);
        break;
      case BubbleShape.classic:
        borderRadius = BorderRadius.only(
          topLeft: const Radius.circular(16),
          topRight: const Radius.circular(16),
          bottomLeft: isMe ? const Radius.circular(16) : const Radius.circular(4),
          bottomRight: isMe ? const Radius.circular(4) : const Radius.circular(16),
        );
        break;
    }

    // Determine Border & Glow
    Border? border;
    List<BoxShadow> shadows = [];

    if (message.isPing) {
      border = Border.all(color: Colors.redAccent, width: 1.5);
      shadows.add(BoxShadow(color: Colors.red.withValues(alpha: 0.3), blurRadius: 8));
    } else if (shape == BubbleShape.neonGlow && chatTheme?.neonGlowColor != null) {
      border = Border.all(
        color: isMe ? chatTheme!.neonGlowColor! : chatTheme!.neonGlowColor!.withValues(alpha: 0.4),
        width: 1.2,
      );
      if (isMe) {
        shadows.add(BoxShadow(
          color: chatTheme!.neonGlowColor!.withValues(alpha: 0.35),
          blurRadius: 8,
        ));
      }
    } else if (shape == BubbleShape.glassmorphism) {
      border = Border.all(color: Colors.white.withValues(alpha: isMe ? 0.25 : 0.12), width: 1);
      shadows.add(BoxShadow(color: Colors.black.withValues(alpha: 0.15), blurRadius: 6));
    } else {
      shadows.add(BoxShadow(
        color: isDark ? Colors.black26 : Colors.black12,
        blurRadius: 3,
        offset: const Offset(0, 1),
      ));
    }

    return Dismissible(
      key: Key('reply_${message.id}'),
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
              gradient: bubbleGradient,
              color: bubbleGradient == null ? bubbleColor : null,
              borderRadius: borderRadius,
              border: border,
              boxShadow: shadows,
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
                              errorBuilder: (_, _, _) => const SizedBox(),
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
                // ── Interactive Mini-Game Bubble ─────────────────────────────
                else if (message.type == MessageType.game)
                  GameBubble(
                    message: message,
                    currentUserId: currentUserId,
                    isMe: isMe,
                    onUpdateGame: (updated) => onUpdateGame?.call(updated),
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
                // ── View-Once Message (Photo or Video) ────────────────────────
                else if (message.isViewOnce)
                  if (message.isOpened)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4.0),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            padding: const EdgeInsets.all(5),
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: Colors.white.withValues(alpha: 0.1),
                              border: Border.all(color: Colors.white24, width: 1),
                            ),
                            child: const Text(
                              '①',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.bold,
                                color: AppTheme.textMuted,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'Opened',
                            style: TextStyle(
                              fontSize: 13.5,
                              fontStyle: FontStyle.italic,
                              fontWeight: FontWeight.w500,
                              color: isMe ? Colors.white60 : AppTheme.textMuted,
                            ),
                          ),
                        ],
                      ),
                    )
                  else if (isMe)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4.0),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            padding: const EdgeInsets.all(5),
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: AppTheme.primary.withValues(alpha: 0.2),
                              border: Border.all(color: AppTheme.primary, width: 1.2),
                            ),
                            child: const Text(
                              '①',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.bold,
                                color: AppTheme.primary,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                message.type == MessageType.video
                                    ? 'Video (View Once)'
                                    : 'Photo (View Once)',
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                  fontSize: 13.5,
                                ),
                              ),
                              const Text(
                                'Sent • Self-destructing',
                                style: TextStyle(fontSize: 10.5, color: Colors.white70),
                              ),
                            ],
                          ),
                        ],
                      ),
                    )
                  else
                    InkWell(
                      onTap: onOpenViewOnce,
                      borderRadius: BorderRadius.circular(10),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.2),
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(
                            color: AppTheme.primary.withValues(alpha: 0.4),
                            width: 1,
                          ),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              width: 34,
                              height: 34,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: AppTheme.primary.withValues(alpha: 0.2),
                                border: Border.all(color: AppTheme.primary, width: 1.5),
                              ),
                              child: const Center(
                                child: Text(
                                  '①',
                                  style: TextStyle(
                                    fontSize: 15,
                                    fontWeight: FontWeight.bold,
                                    color: AppTheme.primary,
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(width: 10),
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  message.type == MessageType.video
                                      ? 'View Once Video'
                                      : 'View Once Photo',
                                  style: const TextStyle(
                                    fontWeight: FontWeight.bold,
                                    fontSize: 13,
                                  ),
                                ),
                                const Row(
                                  children: [
                                    Icon(
                                      Icons.touch_app_rounded,
                                      size: 11,
                                      color: AppTheme.primary,
                                    ),
                                    SizedBox(width: 3),
                                    Text(
                                      'Tap to view',
                                      style: TextStyle(
                                        fontSize: 11,
                                        color: AppTheme.primary,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    )
                // ── Image Attachment ──────────────────────────────────────────
                else if (message.type == MessageType.image && message.mediaUrl != null)
                  MediaBubble(
                    message: message,
                    isMe: isMe,
                    heroTag: 'img_${message.id}',
                  )
                // ── Video Attachment ──────────────────────────────────────────
                else if (message.type == MessageType.video && message.mediaUrl != null)
                  MediaBubble(
                    message: message,
                    isMe: isMe,
                    heroTag: 'vid_${message.id}',
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
                    if (message.disappearingDurationSeconds != null || message.expiresAt != null) ...[
                      const Icon(
                        Icons.local_fire_department_rounded,
                        size: 12,
                        color: Colors.deepOrangeAccent,
                      ),
                      const SizedBox(width: 3),
                    ],
                    Text(
                      timeStr,
                      style: TextStyle(
                        fontSize: 10,
                        color: isMe ? (isDark ? Colors.white60 : Colors.black54) : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                      ),
                    ),
                    ValueListenableBuilder<Set<String>>(
                      valueListenable: StarredMessageService().starredIdsNotifier,
                      builder: (context, starredIds, _) {
                        if (!starredIds.contains(message.id)) return const SizedBox.shrink();
                        return const Padding(
                          padding: EdgeInsets.only(left: 4),
                          child: Icon(Icons.star_rounded, size: 12, color: Colors.amber),
                        );
                      },
                    ),
                    if (isMe) ...[
                      const SizedBox(width: 4),
                      _buildStatusTicks(message.status, isMe: isMe, isDark: isDark),
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
