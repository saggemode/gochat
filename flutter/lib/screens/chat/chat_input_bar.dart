import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

class ChatInputBar extends StatelessWidget {
  final TextEditingController inputController;
  final bool isTyping;
  final VoidCallback onAttachmentPressed;
  final VoidCallback onVoiceNotePressed;
  final VoidCallback onSendPressed;
  final ValueChanged<String> onChanged;

  const ChatInputBar({
    super.key,
    required this.inputController,
    required this.isTyping,
    required this.onAttachmentPressed,
    required this.onVoiceNotePressed,
    required this.onSendPressed,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
      color: AppTheme.darkSurface,
      child: SafeArea(
        top: false,
        child: Row(
          children: [
            IconButton(
              icon: const Icon(Icons.add_rounded, color: AppTheme.iconColor, size: 26),
              onPressed: onAttachmentPressed,
            ),
            Expanded(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14),
                decoration: BoxDecoration(
                  color: AppTheme.darkCard,
                  borderRadius: BorderRadius.circular(24),
                  border: Border.all(color: AppTheme.darkBorder, width: 0.5),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: inputController,
                        style: const TextStyle(color: AppTheme.textLight, fontSize: 15),
                        decoration: const InputDecoration(
                          hintText: 'Message or @bot...',
                          border: InputBorder.none,
                          enabledBorder: InputBorder.none,
                          focusedBorder: InputBorder.none,
                          contentPadding: EdgeInsets.symmetric(vertical: 10),
                        ),
                        onChanged: onChanged,
                        onSubmitted: (_) => onSendPressed(),
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.mic_none_rounded, color: AppTheme.iconColor, size: 22),
                      tooltip: 'Record Voice Note',
                      onPressed: onVoiceNotePressed,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 6),
            CircleAvatar(
              radius: 22,
              backgroundColor: AppTheme.primary,
              child: IconButton(
                icon: const Icon(Icons.send_rounded, color: Colors.black, size: 20),
                onPressed: isTyping ? onSendPressed : onVoiceNotePressed,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
