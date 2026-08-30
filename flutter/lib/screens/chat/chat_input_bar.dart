import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/models/message.dart';
import '../../core/services/voice_recorder_service.dart';
import '../../core/theme/app_theme.dart';

class ChatInputBar extends StatefulWidget {
  final TextEditingController inputController;
  final bool isTyping;
  final Message? replyingTo;
  final VoidCallback onCancelReply;
  final VoidCallback onAttachmentPressed;
  final Future<void> Function(VoiceRecordingResult result)? onVoiceNoteRecorded;
  final Future<void> Function()? onVoiceNotePressed;
  final ValueChanged<bool>? onRecordingStateChanged;
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
    this.onVoiceNoteRecorded,
    this.onVoiceNotePressed,
    this.onRecordingStateChanged,
    required this.onPingPressed,
    required this.onSendPressed,
    required this.onChanged,
  });

  @override
  State<ChatInputBar> createState() => _ChatInputBarState();
}

class _ChatInputBarState extends State<ChatInputBar> with SingleTickerProviderStateMixin {
  bool _isRecording = false;
  int _recordingDuration = 0;
  StreamSubscription<int>? _durationSub;
  final VoiceRecorderService _recorder = VoiceRecorderService();

  // Slide to cancel tracking
  double _slideOffset = 0.0;
  bool _isCancelling = false;

  // Pulse animation
  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.35).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _durationSub?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  Future<void> _startRecording() async {
    final started = await _recorder.startRecording();
    if (!started) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('🎙️ Microphone permission required')),
        );
      }
      return;
    }

    widget.onRecordingStateChanged?.call(true);

    _durationSub?.cancel();
    _durationSub = _recorder.durationStream.listen((secs) {
      if (mounted) {
        setState(() => _recordingDuration = secs);
      }
      // Auto-stop at 5 minutes
      if (secs >= 300) {
        _stopAndSend();
      }
    });

    _pulseController.repeat(reverse: true);

    setState(() {
      _isRecording = true;
      _recordingDuration = 0;
      _slideOffset = 0.0;
      _isCancelling = false;
    });
  }

  Future<void> _stopAndSend() async {
    _pulseController.stop();
    _pulseController.reset();
    _durationSub?.cancel();
    widget.onRecordingStateChanged?.call(false);

    if (_isCancelling) {
      await _recorder.cancelRecording();
      setState(() {
        _isRecording = false;
        _recordingDuration = 0;
        _isCancelling = false;
      });
      return;
    }

    setState(() => _isRecording = false);

    // Stop and retrieve the recording result
    final result = await _recorder.stopRecording();
    if (result != null) {
      if (widget.onVoiceNoteRecorded != null) {
        await widget.onVoiceNoteRecorded!(result);
      } else if (widget.onVoiceNotePressed != null) {
        await widget.onVoiceNotePressed!();
      }
    }
  }

  Future<void> _cancelRecording() async {
    _pulseController.stop();
    _pulseController.reset();
    _durationSub?.cancel();
    widget.onRecordingStateChanged?.call(false);
    await _recorder.cancelRecording();
    setState(() {
      _isRecording = false;
      _recordingDuration = 0;
      _isCancelling = false;
    });
  }

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
            if (widget.replyingTo != null && !_isRecording)
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
                            'Replying to ${widget.replyingTo!.senderName}',
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: AppTheme.primary,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            widget.replyingTo!.content,
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
                      onPressed: widget.onCancelReply,
                    ),
                  ],
                ),
              ),

            // ── Recording Mode ────────────────────────────────────────────────
            if (_isRecording)
              _buildRecordingBar(isDark)
            else
              _buildNormalInputBar(isDark),
          ],
        ),
      ),
    );
  }

  Widget _buildRecordingBar(bool isDark) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
      child: Row(
        children: [
          // Red recording dot with pulse
          AnimatedBuilder(
            animation: _pulseAnimation,
            builder: (_, __) => Transform.scale(
              scale: _pulseAnimation.value,
              child: Container(
                width: 12,
                height: 12,
                decoration: const BoxDecoration(
                  color: AppTheme.dangerRed,
                  shape: BoxShape.circle,
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),

          // Duration
          Text(
            VoiceRecorderService.formatDuration(_recordingDuration),
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: isDark ? Colors.white : Colors.black87,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(width: 16),

          // Slide to cancel hint
          Expanded(
            child: GestureDetector(
              onHorizontalDragUpdate: (details) {
                setState(() {
                  _slideOffset += details.delta.dx;
                  _isCancelling = _slideOffset < -80;
                });
              },
              onHorizontalDragEnd: (_) {
                if (_isCancelling) {
                  _cancelRecording();
                } else {
                  setState(() => _slideOffset = 0.0);
                }
              },
              child: Transform.translate(
                offset: Offset(_slideOffset.clamp(-120.0, 0.0), 0),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      Icons.chevron_left_rounded,
                      size: 18,
                      color: _isCancelling
                          ? AppTheme.dangerRed
                          : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                    ),
                    Text(
                      _isCancelling ? 'Release to Cancel' : 'Slide to Cancel',
                      style: TextStyle(
                        fontSize: 13,
                        color: _isCancelling
                            ? AppTheme.dangerRed
                            : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                        fontWeight: _isCancelling ? FontWeight.bold : FontWeight.normal,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Stop & Send button
          GestureDetector(
            onTap: _stopAndSend,
            child: Container(
              width: 44,
              height: 44,
              decoration: const BoxDecoration(
                color: AppTheme.primary,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.send_rounded, color: Colors.black, size: 20),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNormalInputBar(bool isDark) {
    return Padding(
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
            onPressed: widget.onAttachmentPressed,
          ),
          IconButton(
            icon: const Icon(Icons.vibration_rounded, color: Colors.amber, size: 22),
            tooltip: 'GOCHAT PING! Nudge',
            onPressed: widget.onPingPressed,
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
                      controller: widget.inputController,
                      style: TextStyle(
                        color: isDark ? AppTheme.textLight : AppTheme.textDark,
                        fontSize: 15,
                      ),
                      decoration: InputDecoration(
                        hintText: widget.replyingTo != null ? 'Type your reply...' : 'Message or @bot...',
                        hintStyle: TextStyle(
                          color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                          fontSize: 14,
                        ),
                        border: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        contentPadding: const EdgeInsets.symmetric(vertical: 10),
                      ),
                      onChanged: widget.onChanged,
                      onSubmitted: (_) => widget.onSendPressed(),
                    ),
                  ),
                  // Mic button inside text field (tap to start recording)
                  GestureDetector(
                    onTap: _startRecording,
                    child: Padding(
                      padding: const EdgeInsets.all(4.0),
                      child: Icon(
                        Icons.mic_none_rounded,
                        color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                        size: 20,
                      ),
                    ),
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
              onPressed: widget.isTyping ? widget.onSendPressed : _startRecording,
            ),
          ),
        ],
      ),
    );
  }
}
