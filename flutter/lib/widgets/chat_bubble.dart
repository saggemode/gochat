import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../core/models/message.dart';
import '../core/theme/app_theme.dart';
import 'audio_player_bubble.dart';
import 'poll_bubble.dart';

class ChatBubble extends StatelessWidget {
  final Message message;
  final String currentUserId;
  final Function(String optionId)? onVotePoll;
  final Function(String emoji)? onReact;
  final VoidCallback? onReply;
  final VoidCallback? onOpenCanvas;

  const ChatBubble({
    super.key,
    required this.message,
    required this.currentUserId,
    this.onVotePoll,
    this.onReact,
    this.onReply,
    this.onOpenCanvas,
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
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (ctx) {
        return Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: AppTheme.darkSurface,
            borderRadius: BorderRadius.circular(30),
            boxShadow: const [
              BoxShadow(
                color: Colors.black45,
                blurRadius: 15,
                offset: Offset(0, 5),
              ),
            ],
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: ['👍', '❤️', '😂', '😮', '😢', '🙏', '🔥'].map((emoji) {
              return GestureDetector(
                onTap: () {
                  Navigator.pop(ctx);
                  onReact?.call(emoji);
                },
                child: Text(
                  emoji,
                  style: const TextStyle(fontSize: 28),
                ),
              );
            }).toList(),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isMe = message.isMe;
    final timeStr = DateFormat('hh:mm a').format(message.createdAt);

    return Align(
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
            color: isMe ? AppTheme.senderBubbleDark : AppTheme.receiverBubbleDark,
            borderRadius: BorderRadius.only(
              topLeft: const Radius.circular(16),
              topRight: const Radius.circular(16),
              bottomLeft: isMe ? const Radius.circular(16) : const Radius.circular(4),
              bottomRight: isMe ? const Radius.circular(4) : const Radius.circular(16),
            ),
            boxShadow: const [
              BoxShadow(
                color: Colors.black12,
                blurRadius: 3,
                offset: Offset(0, 1),
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

              // Reply preview bubble
              if (message.replyToText != null)
                Container(
                  margin: const EdgeInsets.only(bottom: 6),
                  padding: const EdgeInsets.all(6),
                  decoration: BoxDecoration(
                    color: Colors.black26,
                    borderRadius: BorderRadius.circular(6),
                    border: const Border(
                      left: BorderSide(color: AppTheme.primary, width: 3),
                    ),
                  ),
                  child: Text(
                    message.replyToText!,
                    style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),

              // Content based on message type
              if (message.type == MessageType.voice || message.type == MessageType.audio)
                AudioPlayerBubble(
                  durationSeconds: message.mediaDuration ?? 30,
                  isMe: isMe,
                  audioUrl: message.mediaUrl,
                )
              else if (message.type == MessageType.poll && message.pollData != null)
                PollBubble(
                  pollData: message.pollData!,
                  currentUserId: currentUserId,
                  isMe: isMe,
                  onVote: (optId) => onVotePoll?.call(optId),
                )
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
              else if (message.type == MessageType.image && message.mediaUrl != null)
                ClipRRect(
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
                )
              else
                Text(
                  message.content,
                  style: const TextStyle(
                    fontSize: 14.5,
                    color: AppTheme.textLight,
                    height: 1.35,
                  ),
                ),

              const SizedBox(height: 3),

              // Timestamp & Status Ticks
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    timeStr,
                    style: TextStyle(
                      fontSize: 10,
                      color: isMe ? Colors.white60 : AppTheme.textMuted,
                    ),
                  ),
                  if (isMe) ...[
                    const SizedBox(width: 4),
                    _buildStatusTicks(message.status),
                  ],
                ],
              ),

              // Reaction pills
              if (message.reactions.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 4.0),
                  child: Wrap(
                    spacing: 4,
                    children: message.reactions.entries.map((entry) {
                      return Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: AppTheme.darkSurface,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: Colors.white12, width: 0.5),
                        ),
                        child: Text(
                          '${entry.key} ${entry.value.length}',
                          style: const TextStyle(fontSize: 11),
                        ),
                      );
                    }).toList(),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
