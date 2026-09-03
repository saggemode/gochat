import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/models/conversation.dart';
import '../../core/models/story.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/media_image_helper.dart';
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
    _recordCurrentStoryView();
    _fetchCurrentStoryViewers();

    _replyFocusNode.addListener(() {
      if (_replyFocusNode.hasFocus) {
        _timer?.cancel();
      } else {
        _startStoryTimer();
      }
    });
  }

  void _recordCurrentStoryView() {
    if (!_isMyStory && _currentIndex < widget.userStories.stories.length) {
      final currentStory = widget.userStories.stories[_currentIndex];
      widget.appState?.recordStoryView(
        currentStory.id,
        storyOwnerId: widget.userStories.userId,
      );
    }
  }

  void _fetchCurrentStoryViewers() {
    if (_isMyStory && _currentIndex < widget.userStories.stories.length) {
      final currentStory = widget.userStories.stories[_currentIndex];
      widget.appState?.fetchStoryViewers(currentStory.id);
    }
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
      _recordCurrentStoryView();
      _fetchCurrentStoryViewers();
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
      _recordCurrentStoryView();
      _fetchCurrentStoryViewers();
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

      final currentStory = widget.userStories.stories[_currentIndex];
      final storyCaption = currentStory.caption.trim();
      final snippet = storyCaption.isNotEmpty
          ? storyCaption
          : (currentStory.mediaType == 'image'
              ? '📷 Status Photo'
              : (currentStory.mediaType == 'video'
                  ? '🎬 Status Video'
                  : 'Status Update'));

      await widget.appState!.sendMessage(
        convId,
        text,
        replyToId: currentStory.id,
        replyToText: 'Story: $snippet',
        replyToSenderName: widget.userStories.userName,
        mediaThumbnail: currentStory.mediaUrl.isNotEmpty ? currentStory.mediaUrl : null,
      );
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

  void _showViewersSheet(BuildContext context, StoryItem story) {
    _timer?.cancel();
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      isScrollControlled: true,
      builder: (ctx) {
        return FutureBuilder<List<StoryViewer>>(
          future: widget.appState?.fetchStoryViewers(story.id),
          initialData: story.viewers,
          builder: (ctx, snapshot) {
            final viewers = snapshot.data ?? story.viewers;

            return Container(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.of(context).size.height * 0.65,
              ),
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: Colors.grey.shade400,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: Row(
                      children: [
                        const Icon(Icons.remove_red_eye_rounded, color: AppTheme.primary, size: 22),
                        const SizedBox(width: 8),
                        Text(
                          'Viewed by (${viewers.length})',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: isDark ? AppTheme.textLight : AppTheme.textDark,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Divider(height: 1),
                  if (viewers.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(40),
                      child: Center(
                        child: Column(
                          children: [
                            Icon(
                              Icons.visibility_off_outlined,
                              size: 48,
                              color: isDark ? Colors.white24 : Colors.black26,
                            ),
                            const SizedBox(height: 12),
                            Text(
                              'No views yet',
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                                color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'Views from your contacts will appear here',
                              style: TextStyle(
                                fontSize: 12,
                                color: isDark ? Colors.white38 : Colors.black38,
                              ),
                            ),
                          ],
                        ),
                      ),
                    )
                  else
                    Expanded(
                      child: ListView.separated(
                        itemCount: viewers.length,
                        separatorBuilder: (context, index) => const Divider(height: 1, indent: 68),
                        itemBuilder: (ctx, idx) {
                          final v = viewers[idx];
                          return ListTile(
                            leading: CircleAvatar(
                              radius: 22,
                              backgroundColor: AppTheme.primary.withValues(alpha: 0.15),
                              backgroundImage: v.avatarUrl.isNotEmpty
                                  ? NetworkImage(v.avatarUrl)
                                  : null,
                              child: v.avatarUrl.isEmpty
                                  ? Text(
                                      v.displayName.isNotEmpty
                                          ? v.displayName[0].toUpperCase()
                                          : '?',
                                      style: const TextStyle(
                                        color: AppTheme.primary,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    )
                                  : null,
                            ),
                            title: Text(
                              v.displayName,
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: isDark ? AppTheme.textLight : AppTheme.textDark,
                              ),
                            ),
                            subtitle: Text(
                              _formatStoryTime(v.viewedAt),
                              style: TextStyle(
                                fontSize: 12,
                                color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                              ),
                            ),
                            trailing: const Icon(
                              Icons.check_circle_outline_rounded,
                              color: AppTheme.primary,
                              size: 18,
                            ),
                          );
                        },
                      ),
                    ),
                ],
              ),
            );
          },
        );
      },
    ).then((_) {
      if (mounted) _startStoryTimer();
    });
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
        onLongPressStart: (_) => _timer?.cancel(),
        onLongPressEnd: (_) => _startStoryTimer(),
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
                  : Container(
                      color: Colors.black,
                      child: Center(
                        child: MediaImageHelper.buildSafeImage(
                          story.mediaUrl,
                          fit: BoxFit.contain,
                          errorWidget: Container(
                            color: Colors.black,
                            child: const Center(
                              child: Icon(Icons.broken_image_rounded, size: 54, color: Colors.white24),
                            ),
                          ),
                        ),
                      ),
                    ),
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
                  child: GestureDetector(
                    onTap: () => _showViewersSheet(context, story),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.65),
                        borderRadius: BorderRadius.circular(24),
                        border: Border.all(color: Colors.white24),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.3),
                            blurRadius: 8,
                            offset: const Offset(0, 2),
                          ),
                        ],
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.remove_red_eye_rounded, color: Colors.white, size: 18),
                          const SizedBox(width: 8),
                          Text(
                            story.viewCount > 0
                                ? '${story.viewCount} ${story.viewCount == 1 ? 'view' : 'views'}'
                                : 'No views yet',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(width: 4),
                          const Icon(Icons.keyboard_arrow_up_rounded, color: Colors.white70, size: 18),
                        ],
                      ),
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
