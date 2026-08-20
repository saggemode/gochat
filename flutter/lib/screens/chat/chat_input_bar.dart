import 'package:flutter/material.dart';
import '../../core/models/message.dart';
import '../../core/theme/app_theme.dart';

class ChatInputBar extends StatelessWidget {
  final TextEditingController inputController;
  final bool isTyping;
  final Message? replyingTo;
  final VoidCallback onCancelReply;
  final VoidCallback onAttachmentPressed;
  final VoidCallback onVoiceNotePressed;
  final VoidCallback onPingPressed;
  final VoidCallback onSendPressed;
  final ValueChanged<String> onChanged;

  const ChatInputBar({
    super.key,
    required this.inputController,
    required this.isTyping,
    this.replyingTo,
    required this.onCancelReply,
    required this.onAttachmentPressed,
    required this.onVoiceNotePressed,
    required this.onPingPressed,
    required this.onSendPressed,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      decoration: BoxDecoration(
        color: isDark ? AppTheme.darkSurface : Colors.white,
        border: Border(
          top: BorderSide(
            color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
            width: 0.5,
          ),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // ── Reply Preview Banner ──────────────────────────────────────────
            if (replyingTo != null)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                color: isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5),
                child: Row(
                  children: [
                    Container(
                      width: 3.5,
                      height: 36,
                      decoration: BoxDecoration(
                        color: AppTheme.primary,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Replying to ${replyingTo!.senderName}',
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: AppTheme.primary,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            replyingTo!.content,
                            style: TextStyle(
                              fontSize: 12,
                              color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.close_rounded, size: 18),
                      color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                      onPressed: onCancelReply,
                    ),
                  ],
                ),
              ),

            // ── Input Row ─────────────────────────────────────────────────────
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Row(
                children: [
                  IconButton(
                    icon: Icon(
                      Icons.add_circle_outline_rounded,
                      color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                      size: 24,
                    ),
                    tooltip: 'Attachments & Mini-Apps',
                    onPressed: onAttachmentPressed,
                  ),
                  IconButton(
                    icon: const Icon(Icons.vibration_rounded, color: Colors.amber, size: 22),
                    tooltip: 'BBM PING! Nudge',
                    onPressed: onPingPressed,
                  ),
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5),
                        borderRadius: BorderRadius.circular(24),
                        border: Border.all(
                          color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                          width: 0.5,
                        ),
                      ),
                      child: Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: inputController,
                              style: TextStyle(
                                color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                fontSize: 15,
                              ),
                              decoration: InputDecoration(
                                hintText: replyingTo != null ? 'Type your reply...' : 'Message or @bot...',
                                hintStyle: TextStyle(
                                  color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                  fontSize: 14,
                                ),
                                border: InputBorder.none,
                                enabledBorder: InputBorder.none,
                                focusedBorder: InputBorder.none,
                                contentPadding: const EdgeInsets.symmetric(vertical: 10),
                              ),
                              onChanged: onChanged,
                              onSubmitted: (_) => onSendPressed(),
                            ),
                          ),
                          IconButton(
                            icon: Icon(
                              Icons.mic_none_rounded,
                              color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                              size: 20,
                            ),
                            tooltip: 'Record Voice Note',
                            onPressed: onVoiceNotePressed,
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 6),
                  CircleAvatar(
                    radius: 20,
                    backgroundColor: AppTheme.primary,
                    child: IconButton(
                      icon: const Icon(Icons.send_rounded, color: Colors.black, size: 18),
                      onPressed: isTyping ? onSendPressed : onVoiceNotePressed,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
