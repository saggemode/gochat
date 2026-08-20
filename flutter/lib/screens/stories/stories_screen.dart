import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import 'story_viewer_screen.dart';

class StoriesScreen extends StatelessWidget {
  final AppState appState;

  const StoriesScreen({super.key, required this.appState});

  @override
  Widget build(BuildContext context) {
    final stories = appState.stories;
    final myStory = stories.firstWhere((s) => s.isMe, orElse: () => stories.first);
    final otherStories = stories.where((s) => !s.isMe).toList();
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          'Updates',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isDark ? AppTheme.textLight : AppTheme.textDark,
          ),
        ),
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
                        child: const Icon(Icons.add, size: 16, color: Colors.black),
                      ),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'My status',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: isDark ? AppTheme.textLight : AppTheme.textDark,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        myStory.stories.isNotEmpty
                            ? '${myStory.stories.length} updates'
                            : 'Tap to add status update',
                        style: TextStyle(
                          fontSize: 13,
                          color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                        ),
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

          const SectionHeader(title: 'RECENT UPDATES'),

          if (otherStories.isEmpty)
            Padding(
              padding: const EdgeInsets.all(32.0),
              child: EmptyStateView(
                icon: Icons.history_toggle_off_rounded,
                title: 'No Recent Updates',
                description: 'Status updates from your contacts will appear here and disappear after 24 hours.',
              ),
            )
          else
            ...otherStories.map((item) {
              return ListTile(
                leading: StoryAvatar(
                  avatarUrl: item.userAvatar,
                  radius: 26,
                  hasUnseenStory: item.hasUnseenStories,
                  storyCount: item.stories.length,
                ),
                title: Text(
                  item.userName,
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: isDark ? AppTheme.textLight : AppTheme.textDark,
                  ),
                ),
                subtitle: Text(
                  item.stories.isNotEmpty ? 'Today, 2:30 PM' : 'No updates',
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                  ),
                ),
                onTap: () {
                  if (item.stories.isNotEmpty) {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => StoryViewerScreen(userStories: item),
                      ),
                    );
                  }
                },
              );
            }),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        heroTag: 'stories_fab',
        onPressed: () {
          appState.addStory(
            'https://images.unsplash.com/photo-1518770660439-4636190af475?w=600',
            'New status story ✨',
          );
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('📸 Status story posted')),
          );
        },
        child: const Icon(Icons.camera_alt_rounded),
      ),
    );
  }
}
