import 'package:flutter/material.dart';
import '../core/models/message.dart';
import '../core/theme/app_theme.dart';

class PollBubble extends StatelessWidget {
  final PollData pollData;
  final String currentUserId;
  final bool isMe;
  final Function(String optionId) onVote;

  const PollBubble({
    super.key,
    required this.pollData,
    required this.currentUserId,
    required this.isMe,
    required this.onVote,
  });

  int get totalVotes =>
      pollData.options.fold(0, (sum, opt) => sum + opt.votes);

  @override
  Widget build(BuildContext context) {
    final tot = totalVotes;

    return Container(
      constraints: const BoxConstraints(maxWidth: 300),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Question Header
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  color: isMe ? AppTheme.accent.withValues(alpha: 0.2) : AppTheme.primary.withValues(alpha: 0.2),
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  Icons.poll_rounded,
                  size: 16,
                  color: isMe ? Colors.white : AppTheme.primary,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  pollData.question,
                  style: const TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.bold,
                    color: AppTheme.textLight,
                    height: 1.3,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),

          // Options List with Animated Progress
          ...pollData.options.map((opt) {
            final hasVoted = opt.voterIds.contains(currentUserId);
            final double percentage =
                tot > 0 ? (opt.votes / tot) : 0.0;

            return Padding(
              padding: const EdgeInsets.only(bottom: 8.0),
              child: GestureDetector(
                onTap: () => onVote(opt.id),
                child: Stack(
                  children: [
                    // Background & Animated Fill Bar
                    Container(
                      height: 42,
                      decoration: BoxDecoration(
                        color: isMe
                            ? Colors.black26
                            : AppTheme.darkSurface.withValues(alpha: 0.7),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: hasVoted
                              ? (isMe ? AppTheme.accent : AppTheme.primary)
                              : Colors.white10,
                          width: hasVoted ? 1.5 : 0.5,
                        ),
                      ),
                    ),
                    // Progress Fill
                    if (percentage > 0)
                      ClipRRect(
                        borderRadius: BorderRadius.circular(10),
                        child: AnimatedFractionallySizedBox(
                          duration: const Duration(milliseconds: 400),
                          curve: Curves.easeOutCubic,
                          widthFactor: percentage,
                          child: Container(
                            height: 42,
                            color: hasVoted
                                ? (isMe
                                    ? AppTheme.accent.withValues(alpha: 0.35)
                                    : AppTheme.primary.withValues(alpha: 0.35))
                                : Colors.white.withValues(alpha: 0.08),
                          ),
                        ),
                      ),

                    // Content: Option text & votes
                    Container(
                      height: 42,
                      padding: const EdgeInsets.symmetric(horizontal: 10),
                      child: Row(
                        children: [
                          Icon(
                            hasVoted
                                ? Icons.check_circle_rounded
                                : Icons.radio_button_unchecked_rounded,
                            size: 18,
                            color: hasVoted
                                ? (isMe ? Colors.white : AppTheme.primary)
                                : AppTheme.textMuted,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              opt.text,
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: hasVoted
                                    ? FontWeight.bold
                                    : FontWeight.w500,
                                color: AppTheme.textLight,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (tot > 0)
                            Text(
                              '${(percentage * 100).round()}% (${opt.votes})',
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.bold,
                                color: hasVoted
                                    ? (isMe ? Colors.white : AppTheme.primary)
                                    : AppTheme.textMuted,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),

          // Footer info
          const SizedBox(height: 2),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '$tot vote${tot == 1 ? '' : 's'}${pollData.allowMultiple ? ' • Multiple selection' : ''}',
                style: const TextStyle(
                  fontSize: 11,
                  color: AppTheme.textMuted,
                ),
              ),
              if (pollData.isAnonymous)
                const Row(
                  children: [
                    Icon(Icons.visibility_off_outlined, size: 12, color: AppTheme.textMuted),
                    SizedBox(width: 4),
                    Text(
                      'Anonymous',
                      style: TextStyle(fontSize: 10, color: AppTheme.textMuted),
                    ),
                  ],
                ),
            ],
          ),
        ],
      ),
    );
  }
}
