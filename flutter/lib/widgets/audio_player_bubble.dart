import 'dart:math';
import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';

class AudioPlayerBubble extends StatefulWidget {
  final int durationSeconds;
  final bool isMe;
  final String? audioUrl;

  const AudioPlayerBubble({
    super.key,
    required this.durationSeconds,
    required this.isMe,
    this.audioUrl,
  });

  @override
  State<AudioPlayerBubble> createState() => _AudioPlayerBubbleState();
}

class _AudioPlayerBubbleState extends State<AudioPlayerBubble>
    with SingleTickerProviderStateMixin {
  bool _isPlaying = false;
  double _currentProgress = 0.35; // 0.0 to 1.0
  double _speed = 1.0; // 1.0, 1.5, 2.0
  late List<double> _waveformAmplitudes;

  @override
  void initState() {
    super.initState();
    // Generate organic voice note amplitude bars
    final rnd = Random(widget.durationSeconds);
    _waveformAmplitudes = List.generate(28, (index) {
      final base = sin(index / 4.0).abs() * 0.7 + 0.3;
      return (base * rnd.nextDouble() * 0.8 + 0.2).clamp(0.15, 1.0);
    });
  }

  void _togglePlay() {
    setState(() {
      _isPlaying = !_isPlaying;
    });
  }

  void _toggleSpeed() {
    setState(() {
      if (_speed == 1.0) {
        _speed = 1.5;
      } else if (_speed == 1.5) {
        _speed = 2.0;
      } else {
        _speed = 1.0;
      }
    });
  }

  String _formatDuration(int totalSeconds, double progress) {
    final remainingSeconds = (totalSeconds * (1.0 - progress)).round();
    final mins = remainingSeconds ~/ 60;
    final secs = remainingSeconds % 60;
    return '${mins.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final playedColor = widget.isMe ? AppTheme.accent : AppTheme.primary;
    final unplayedColor = widget.isMe
        ? Colors.white.withValues(alpha: 0.35)
        : AppTheme.textMuted.withValues(alpha: 0.5);

    return Container(
      constraints: const BoxConstraints(maxWidth: 280),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Play / Pause Circle
          GestureDetector(
            onTap: _togglePlay,
            child: Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: widget.isMe
                    ? AppTheme.accent.withValues(alpha: 0.25)
                    : AppTheme.primary.withValues(alpha: 0.2),
              ),
              child: Icon(
                _isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
                color: widget.isMe ? Colors.white : AppTheme.primary,
                size: 28,
              ),
            ),
          ),
          const SizedBox(width: 8),

          // Waveform Canvas & Time
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                // Interactive Waveform
                GestureDetector(
                  onHorizontalDragUpdate: (details) {
                    final box = context.findRenderObject() as RenderBox?;
                    if (box != null) {
                      final local = box.globalToLocal(details.globalPosition);
                      setState(() {
                        _currentProgress = (local.dx / 180).clamp(0.0, 1.0);
                      });
                    }
                  },
                  child: SizedBox(
                    height: 28,
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: List.generate(_waveformAmplitudes.length, (idx) {
                        final barProgress = idx / _waveformAmplitudes.length;
                        final isPlayed = barProgress <= _currentProgress;
                        final height = _waveformAmplitudes[idx] * 24.0;

                        return Container(
                          width: 3,
                          height: height.clamp(4.0, 24.0),
                          decoration: BoxDecoration(
                            color: isPlayed ? playedColor : unplayedColor,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        );
                      }),
                    ),
                  ),
                ),
                const SizedBox(height: 4),

                // Timestamp & Speed toggle
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      _formatDuration(widget.durationSeconds, _currentProgress),
                      style: TextStyle(
                        fontSize: 11,
                        color: widget.isMe
                            ? Colors.white70
                            : AppTheme.textMuted,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    GestureDetector(
                      onTap: _toggleSpeed,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: widget.isMe
                              ? Colors.black26
                              : AppTheme.darkSurface,
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Text(
                          '${_speed}x',
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                            color: widget.isMe ? Colors.white : AppTheme.primary,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
