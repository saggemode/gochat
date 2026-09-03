import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import '../../core/models/conversation.dart';
import '../../core/models/story.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';

class StoryViewerScreen extends StatefulWidget {
  final UserStories userStories;
  final AppState? appState;

  const StoryViewerScreen({
    super.key,
    required this.userStories,
    this.appState,
  });

  @override
  State<StoryViewerScreen> createState() => _StoryViewerScreenState();
}

class _StoryViewerScreenState extends State<StoryViewerScreen> {
  int _currentIndex = 0;
  double _progress = 0.0;
  Timer? _timer;
  final TextEditingController _replyController = TextEditingController();
  final FocusNode _replyFocusNode = FocusNode();

  bool get _isMyStory {
    if (widget.userStories.isMe) return true;
    final currentUserId = widget.appState?.currentUser?.id;
    if (currentUserId != null &&
        currentUserId.isNotEmpty &&
        widget.userStories.userId == currentUserId) {
      return true;
    }
    return false;
  }

  @override
  void initState() {
    super.initState();
    _startStoryTimer();

    _replyFocusNode.addListener(() {
      if (_replyFocusNode.hasFocus) {
        _timer?.cancel();
      } else {
        _startStoryTimer();
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _replyFocusNode.dispose();
    _replyController.dispose();
    super.dispose();
  }

  void _startStoryTimer() {
    _timer?.cancel();
    _progress = 0.0;
    _timer = Timer.periodic(const Duration(milliseconds: 50), (timer) {
      if (!mounted) return;
      setState(() {
        _progress += 0.01;
        if (_progress >= 1.0) {
          _timer?.cancel();
          _nextStory();
        }
      });
    });
  }

  void _nextStory() {
    if (_currentIndex < widget.userStories.stories.length - 1) {
      setState(() {
        _currentIndex++;
      });
      _startStoryTimer();
    } else {
      Navigator.pop(context);
    }
  }

  void _prevStory() {
    if (_currentIndex > 0) {
      setState(() {
        _currentIndex--;
      });
      _startStoryTimer();
    }
  }

  Color _parseBgColor(String? hex) {
    if (hex == null || hex.isEmpty) return const Color(0xFF6C63FF);
    try {
      final clean = hex.replaceAll('#', '');
      return Color(int.parse(clean, radix: 16));
    } catch (_) {
      return const Color(0xFF6C63FF);
    }
  }

  String _formatStoryTime(DateTime time) {
    final diff = DateTime.now().difference(time);
    if (diff.inMinutes < 1) return 'Just now';
    if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
    if (diff.inHours < 24) return '${diff.inHours}h ago';
    return '${diff.inDays}d ago';
  }

  void _sendReply() async {
    final text = _replyController.text.trim();
    if (text.isEmpty) return;

    final partnerId = widget.userStories.userId;
    if (widget.appState != null &&
        partnerId.isNotEmpty &&
        partnerId != widget.appState?.currentUser?.id) {
      final convs = widget.appState!.conversations;
      final matchIdx = convs.indexWhere(
        (c) =>
            c.type == ConversationType.direct &&
            (c.memberIds.contains(partnerId) || c.id == partnerId),
      );

      String convId;
      if (matchIdx != -1) {
        convId = convs[matchIdx].id;
      } else {
        final newConv = await widget.appState!.createConversation(
          widget.userStories.userName,
          [partnerId],
        );
        convId = newConv.id;
      }

      await widget.appState!.sendMessage(convId, 'Replying to story:\n$text');
    }

    if (mounted) {
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Reply sent to ${widget.userStories.userName}'),
          backgroundColor: AppTheme.primary,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.userStories.stories.isEmpty) {
      return const Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Text('No stories found', style: TextStyle(color: Colors.white)),
        ),
      );
    }

    final story = widget.userStories.stories[_currentIndex];
    final isTextStory = story.mediaType == 'text' || story.mediaUrl.isEmpty;

    return Scaffold(
      backgroundColor: Colors.black,
      body: GestureDetector(
        onTapDown: (details) {
          final width = MediaQuery.of(context).size.width;
          if (details.globalPosition.dx < width * 0.3) {
            _prevStory();
          } else {
            _nextStory();
          }
        },
        child: Stack(
          children: [
            // ── Background Story Content (Text vs Image vs Video) ──
            Positioned.fill(
              child: isTextStory
                  ? Container(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            _parseBgColor(story.backgroundColor),
                            _parseBgColor(story.backgroundColor).withValues(alpha: 0.7),
                          ],
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                        ),
                      ),
                      child: Center(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 36.0),
                          child: Text(
                            story.caption,
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 28,
                              fontWeight: FontWeight.bold,
                              height: 1.4,
                            ),
                          ),
                        ),
                      ),
                    )
                  : (story.mediaUrl.startsWith('http')
                      ? Image.network(
                          story.mediaUrl,
                          fit: BoxFit.cover,
                          errorBuilder: (ctx, _, _) => Container(
                            color: AppTheme.darkSurface,
                            child: const Center(
                              child: Icon(Icons.broken_image, size: 48, color: AppTheme.iconColor),
                            ),
                          ),
                        )
                      : Image.file(
                          File(story.mediaUrl),
                          fit: BoxFit.cover,
                          errorBuilder: (ctx, _, _) => Container(
                            color: AppTheme.darkSurface,
                            child: const Center(
                              child: Icon(Icons.broken_image, size: 48, color: AppTheme.iconColor),
                            ),
                          ),
                        )),
            ),

            // Top Gradient Overlay for Header Legibility
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: 120,
              child: Container(
                decoration: const BoxDecoration(
                  gradient: LinearGradient(
                    colors: [Colors.black87, Colors.transparent],
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                  ),
                ),
              ),
            ),

            // Top Multi-Segment Progression Bar
            Positioned(
              top: 48,
              left: 12,
              right: 12,
              child: Row(
                children: List.generate(
                  widget.userStories.stories.length,
                  (index) => Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(2),
                        child: LinearProgressIndicator(
                          value: index == _currentIndex
                              ? _progress
                              : (index < _currentIndex ? 1.0 : 0.0),
                          backgroundColor: Colors.white24,
                          valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
                          minHeight: 2.5,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),

            // Header (Avatar, Name, Time, Close)
            Positioned(
              top: 60,
              left: 16,
              right: 16,
              child: Row(
                children: [
                  CustomAvatar(
                    imageUrl: widget.userStories.userAvatar,
                    name: widget.userStories.userName,
                    radius: 18,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _isMyStory ? 'My status' : widget.userStories.userName,
                          style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                            fontSize: 14,
                          ),
                        ),
                        Text(
                          _formatStoryTime(story.createdAt),
                          style: const TextStyle(color: Colors.white70, fontSize: 11),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, color: Colors.white, size: 24),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),

            // Caption at bottom for media stories
            if (story.caption.isNotEmpty && !isTextStory)
              Positioned(
                bottom: 80,
                left: 20,
                right: 20,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  decoration: BoxDecoration(
                    color: Colors.black54,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Text(
                    story.caption,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 15,
                      fontWeight: FontWeight.w500,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),

            // ── Bottom Action Bar ──────────────────────────────────────────
            // For own story: Show "Your status" pill (User cannot reply to their own status!)
            // For friend story: Show reply input and like heart
            if (_isMyStory)
              Positioned(
                bottom: 24,
                left: 0,
                right: 0,
                child: Center(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                    decoration: BoxDecoration(
                      color: Colors.black54,
                      borderRadius: BorderRadius.circular(24),
                      border: Border.all(color: Colors.white24),
                    ),
                    child: const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.remove_red_eye_outlined, color: Colors.white70, size: 18),
                        SizedBox(width: 8),
                        Text(
                          'Your status',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              )
            else
              // Bottom Quick Reply Bar (Only for other users' stories)
              Positioned(
                bottom: 16,
                left: 16,
                right: 16,
                child: Row(
                  children: [
                    Expanded(
                      child: Container(
                        height: 44,
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        decoration: BoxDecoration(
                          color: Colors.black54,
                          borderRadius: BorderRadius.circular(24),
                          border: Border.all(color: Colors.white24),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: TextField(
                                controller: _replyController,
                                focusNode: _replyFocusNode,
                                style: const TextStyle(color: Colors.white, fontSize: 13),
                                decoration: const InputDecoration(
                                  hintText: 'Reply to story...',
                                  hintStyle: TextStyle(color: Colors.white54),
                                  border: InputBorder.none,
                                  isDense: true,
                                ),
                                onSubmitted: (_) => _sendReply(),
                              ),
                            ),
                            IconButton(
                              icon: const Icon(Icons.send_rounded, color: AppTheme.primary, size: 20),
                              onPressed: _sendReply,
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.white24),
                      ),
                      child: IconButton(
                        icon: const Icon(Icons.favorite_border_rounded, color: Colors.pinkAccent),
                        onPressed: () {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('❤️ Liked Story!')),
                          );
                        },
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
