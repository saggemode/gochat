import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';

class ChannelsScreen extends StatefulWidget {
  final AppState appState;

  const ChannelsScreen({super.key, required this.appState});

  @override
  State<ChannelsScreen> createState() => _ChannelsScreenState();
}

class _ChannelsScreenState extends State<ChannelsScreen> {
  @override
  Widget build(BuildContext context) {
    final channels = widget.appState.channels;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Channels', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(icon: const Icon(Icons.search), onPressed: () {}),
          IconButton(icon: const Icon(Icons.add), onPressed: () {}),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: Text(
              'Stay updated on topics you care about. Find channels to follow below.',
              style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
            ),
          ),

          // Followed Channels List
          ...channels.map((ch) {
            final post = ch.recentPosts.isNotEmpty ? ch.recentPosts.first : null;

            return Card(
              margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Padding(
                padding: const EdgeInsets.all(14.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        CircleAvatar(
                          radius: 20,
                          backgroundColor: AppTheme.darkSurface,
                          backgroundImage: ch.avatarUrl.isNotEmpty ? NetworkImage(ch.avatarUrl) : null,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Text(
                                    ch.name,
                                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                                  ),
                                  if (ch.isVerified) ...[
                                    const SizedBox(width: 4),
                                    const Icon(Icons.verified, size: 16, color: AppTheme.readBlue),
                                  ],
                                ],
                              ),
                              Text(
                                '${(ch.followersCount / 1000).round()}K followers',
                                style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                              ),
                            ],
                          ),
                        ),
                        OutlinedButton(
                          style: OutlinedButton.styleFrom(
                            foregroundColor: ch.isFollowing ? AppTheme.textMuted : AppTheme.primary,
                            side: BorderSide(
                              color: ch.isFollowing ? AppTheme.darkBorder : AppTheme.primary,
                            ),
                            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
                          ),
                          onPressed: () {
                            setState(() {
                              final idx = widget.appState.channels.indexWhere((c) => c.id == ch.id);
                              if (idx != -1) {
                                widget.appState.channels[idx] =
                                    ch.copyWith(isFollowing: !ch.isFollowing);
                              }
                            });
                          },
                          child: Text(ch.isFollowing ? 'Following' : 'Follow', style: const TextStyle(fontSize: 12)),
                        ),
                      ],
                    ),

                    if (post != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        post.content,
                        style: const TextStyle(fontSize: 13.5, color: AppTheme.textLight, height: 1.3),
                      ),
                      if (post.mediaUrl != null) ...[
                        const SizedBox(height: 8),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: Image.network(
                            post.mediaUrl!,
                            height: 140,
                            width: double.infinity,
                            fit: BoxFit.cover,
                          ),
                        ),
                      ],
                      const SizedBox(height: 10),
                      Row(
                        children: [
                          ...post.reactions.entries.map((entry) {
                            return Container(
                              margin: const EdgeInsets.only(right: 6),
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                              decoration: BoxDecoration(
                                color: AppTheme.darkSurface,
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Text(
                                '${entry.key} ${entry.value}',
                                style: const TextStyle(fontSize: 11),
                              ),
                            );
                          }),
                          const Spacer(),
                          const Icon(Icons.visibility_outlined, size: 14, color: AppTheme.textMuted),
                          const SizedBox(width: 4),
                          Text('${post.viewsCount}', style: const TextStyle(fontSize: 11, color: AppTheme.textMuted)),
                          const SizedBox(width: 12),
                          const Icon(Icons.share_outlined, size: 14, color: AppTheme.textMuted),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            );
          }),
        ],
      ),
    );
  }
}
