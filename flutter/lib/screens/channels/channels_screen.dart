import 'package:flutter/material.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';

class ChannelsScreen extends StatefulWidget {
  final AppState appState;

  const ChannelsScreen({super.key, required this.appState});

  @override
  State<ChannelsScreen> createState() => _ChannelsScreenState();
}

class _ChannelsScreenState extends State<ChannelsScreen> {
  final TextEditingController _searchController = TextEditingController();
  bool _isSearching = false;

  List<Channel> get _filteredChannels {
    var list = widget.appState.channels;
    final q = _searchController.text.trim().toLowerCase();
    if (q.isNotEmpty) {
      list = list.where((c) => c.name.toLowerCase().contains(q) || c.description.toLowerCase().contains(q)).toList();
    }
    return list;
  }

  @override
  Widget build(BuildContext context) {
    final channels = _filteredChannels;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: _isSearching
            ? SearchField(
                controller: _searchController,
                hintText: 'Search channels & broadcasts...',
                autofocus: true,
                onChanged: (_) => setState(() {}),
                onClear: () => setState(() => _searchController.clear()),
              )
            : Text(
                'Channels',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: isDark ? AppTheme.textLight : AppTheme.textDark,
                ),
              ),
        actions: [
          IconButton(
            icon: Icon(_isSearching ? Icons.close : Icons.search),
            onPressed: () {
              setState(() {
                if (_isSearching) _searchController.clear();
                _isSearching = !_isSearching;
              });
            },
          ),
          IconButton(
            icon: const Icon(Icons.add_circle_outline_rounded),
            tooltip: 'Create Channel',
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('📢 Create Channel modal')),
              );
            },
          ),
        ],
      ),
      body: channels.isEmpty
          ? EmptyStateView(
              icon: Icons.campaign_rounded,
              title: 'No Channels Found',
              description: _isSearching
                  ? 'No channels match "${_searchController.text}".'
                  : 'Follow channels to receive updates from your favorite creators and organizations.',
              actionLabel: _isSearching ? 'Clear Search' : 'Discover Channels',
              onAction: () {
                setState(() {
                  _searchController.clear();
                  _isSearching = false;
                });
              },
            )
          : ListView(
              padding: const EdgeInsets.symmetric(vertical: 8),
              children: [
                const SectionHeader(
                  title: 'STAY UPDATED ON TOPICS YOU CARE ABOUT',
                ),

                // Channels List
                ...channels.map((ch) {
                  final post = ch.recentPosts.isNotEmpty ? ch.recentPosts.first : null;

                  return Container(
                    margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: isDark ? AppTheme.darkCard : Colors.white,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(
                        color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                        width: 0.5,
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            CustomAvatar(
                              imageUrl: ch.avatarUrl,
                              name: ch.name,
                              radius: 22,
                              isVerified: ch.isVerified,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    ch.name,
                                    style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      fontSize: 15,
                                      color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                    ),
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    '${(ch.followersCount / 1000).toStringAsFixed(1)}K followers',
                                    style: TextStyle(
                                      fontSize: 11.5,
                                      color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            OutlinedButton(
                              style: OutlinedButton.styleFrom(
                                foregroundColor: ch.isFollowing
                                    ? (isDark ? AppTheme.textMuted : AppTheme.textMutedLight)
                                    : AppTheme.primary,
                                side: BorderSide(
                                  color: ch.isFollowing
                                      ? (isDark ? AppTheme.darkBorder : AppTheme.lightBorder)
                                      : AppTheme.primary,
                                ),
                                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
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

                        // Channel Latest Post Snippet
                        if (post != null) ...[
                          const SizedBox(height: 12),
                          Container(
                            padding: const EdgeInsets.all(10),
                            decoration: BoxDecoration(
                              color: isDark ? AppTheme.darkSurface : const Color(0xFFF0F2F5),
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  post.content,
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                  ),
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 6),
                                Row(
                                  children: [
                                    const Icon(Icons.favorite_border_rounded, size: 14, color: AppTheme.primary),
                                    const SizedBox(width: 4),
                                    Text(
                                      '${post.likesCount}',
                                      style: TextStyle(
                                        fontSize: 11,
                                        color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    const Icon(Icons.share_rounded, size: 14, color: AppTheme.primary),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                  );
                }),
              ],
            ),
    );
  }
}
