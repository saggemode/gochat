import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/models/story.dart';
import '../../core/theme/app_theme.dart';

class StoryViewerScreen extends StatefulWidget {
  final UserStories userStories;

  const StoryViewerScreen({super.key, required this.userStories});

  @override
  State<StoryViewerScreen> createState() => _StoryViewerScreenState();
}

class _StoryViewerScreenState extends State<StoryViewerScreen> {
  int _currentIndex = 0;
  double _progress = 0.0;
  Timer? _timer;
  final TextEditingController _replyController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _startStoryTimer();
  }

  @override
  void dispose() {
    _timer?.cancel();
    _replyController.dispose();
    super.dispose();
  }

  void _startStoryTimer() {
    _progress = 0.0;
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 50), (timer) {
      if (mounted) {
        setState(() {
          _progress += 0.01;
          if (_progress >= 1.0) {
            _nextStory();
          }
        });
      }
    });
  }

  void _nextStory() {
    if (_currentIndex < widget.userStories.stories.length - 1) {
      setState(() {
        _currentIndex++;
      });
      _startStoryTimer();
    } else {
      _timer?.cancel();
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

  @override
  Widget build(BuildContext context) {
    if (widget.userStories.stories.isEmpty) {
      return const Scaffold(body: Center(child: Text('No stories found')));
    }

    final story = widget.userStories.stories[_currentIndex];

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
            // Background Story Media Image
            Positioned.fill(
              child: Image.network(
                story.mediaUrl,
                fit: BoxFit.cover,
                errorBuilder: (ctx, _, __) => Container(
                  color: AppTheme.darkSurface,
                  child: const Center(
                    child: Icon(Icons.broken_image, size: 48, color: AppTheme.iconColor),
                  ),
                ),
              ),
            ),

            // Top Gradient & Story Progression Bars
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: Container(
                padding: const EdgeInsets.fromLTRB(16, 44, 16, 20),
                decoration: const BoxDecoration(
                  gradient: LinearGradient(
                    colors: [Colors.black87, Colors.transparent],
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                  ),
                ),
                child: Column(
                  children: [
                    // Multi-Segment Progression Bar
                    Row(
                      children: List.generate(
                        widget.userStories.stories.length,
                        (idx) {
                          double barVal = 0.0;
                          if (idx < _currentIndex) {
                            barVal = 1.0;
                          } else if (idx == _currentIndex) {
                            barVal = _progress;
                          }

                          return Expanded(
                            child: Container(
                              margin: const EdgeInsets.symmetric(horizontal: 2),
                              height: 3,
                              child: LinearProgressIndicator(
                                value: barVal,
                                backgroundColor: Colors.white24,
                                valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                    const SizedBox(height: 12),

                    // User Info Header
                    Row(
                      children: [
                        CircleAvatar(
                          radius: 18,
                          backgroundImage: NetworkImage(widget.userStories.userAvatar),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                widget.userStories.userName,
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 14,
                                ),
                              ),
                              const Text(
                                '2 hours ago',
                                style: TextStyle(color: Colors.white70, fontSize: 11),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.close, color: Colors.white),
                          onPressed: () => Navigator.pop(context),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),

            // Caption at bottom
            if (story.caption.isNotEmpty)
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

            // Bottom Quick Reply Bar
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
                              style: const TextStyle(color: Colors.white, fontSize: 13),
                              decoration: const InputDecoration(
                                hintText: 'Reply to story...',
                                hintStyle: TextStyle(color: Colors.white54),
                                border: InputBorder.none,
                                isDense: true,
                              ),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.send_rounded, color: AppTheme.primary, size: 20),
                            onPressed: () {
                              if (_replyController.text.trim().isNotEmpty) {
                                Navigator.pop(context);
                              }
                            },
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
