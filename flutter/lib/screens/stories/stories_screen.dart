import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/story_avatar.dart';
import 'story_viewer_screen.dart';

class StoriesScreen extends StatelessWidget {
  final AppState appState;

  const StoriesScreen({super.key, required this.appState});

  @override
  Widget build(BuildContext context) {
    final stories = appState.stories;
    final myStory = stories.firstWhere((s) => s.isMe, orElse: () => stories.first);
    final otherStories = stories.where((s) => !s.isMe).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Updates', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(icon: const Icon(Icons.search), onPressed: () {}),
          IconButton(icon: const Icon(Icons.more_vert), onPressed: () {}),
        ],
      ),
      body: ListView(
        children: [
          // My Status Tile
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                Stack(
                  children: [
                    StoryAvatar(
                      avatarUrl: myStory.userAvatar,
                      radius: 28,
                      hasUnseenStory: myStory.hasUnseenStories,
                      storyCount: myStory.stories.length,
                      onTap: () {
                        if (myStory.stories.isNotEmpty) {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => StoryViewerScreen(userStories: myStory),
                            ),
                          );
                        }
                      },
                    ),
                    Positioned(
                      bottom: 0,
                      right: 0,
                      child: Container(
                        padding: const EdgeInsets.all(3),
                        decoration: const BoxDecoration(
                          color: AppTheme.primary,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.add, size: 16, color: Colors.white),
                      ),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'My status',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: AppTheme.textLight,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        myStory.stories.isNotEmpty
                            ? '${myStory.stories.length} updates'
                            : 'Tap to add status update',
                        style: const TextStyle(fontSize: 13, color: AppTheme.textMuted),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.camera_alt_rounded, color: AppTheme.primary),
                  onPressed: () {
                    appState.addStory(
                      'https://images.unsplash.com/photo-1518770660439-4636190af475?w=600',
                      'New status update from Flutter app ✨',
                    );
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('🎉 Status update published!')),
                    );
                  },
                ),
              ],
            ),
          ),

          const Divider(),

          // Recent updates section
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Text(
              'Recent updates',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.bold,
                color: AppTheme.textMuted,
              ),
            ),
          ),

          ...otherStories.map((item) {
            return ListTile(
              leading: StoryAvatar(
                avatarUrl: item.userAvatar,
                radius: 26,
                hasUnseenStory: item.hasUnseenStories,
                storyCount: item.stories.length,
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => StoryViewerScreen(userStories: item),
                    ),
                  );
                },
              ),
              title: Text(
                item.userName,
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                  color: AppTheme.textLight,
                ),
              ),
              subtitle: const Text(
                'Today, 2:45 PM',
                style: TextStyle(fontSize: 13, color: AppTheme.textMuted),
              ),
              onTap: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => StoryViewerScreen(userStories: item),
                  ),
                );
              },
            );
          }),
        ],
      ),
      floatingActionButton: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          FloatingActionButton.small(
            heroTag: 'story_edit_fab',
            backgroundColor: AppTheme.darkCard,
            foregroundColor: AppTheme.textLight,
            onPressed: () {
              appState.addStory(
                'https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=600',
                'Feeling energized & grateful 💫',
              );
            },
            child: const Icon(Icons.edit),
          ),
          const SizedBox(height: 12),
          FloatingActionButton(
            heroTag: 'story_camera_fab',
            backgroundColor: AppTheme.primary,
            onPressed: () {
              appState.addStory(
                'https://images.unsplash.com/photo-1526778548025-fa2f459cd5c1?w=600',
                'Adventure awaits! 🏔️',
              );
            },
            child: const Icon(Icons.camera_alt_rounded),
          ),
        ],
      ),
    );
  }
}
