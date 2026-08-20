import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../core/models/conversation.dart';
import '../../core/models/message.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/story_avatar.dart';
import 'chat_room_screen.dart';
import '../stories/story_viewer_screen.dart';

class ChatListScreen extends StatefulWidget {
  final AppState appState;
  final VoidCallback? onOpenDrawer;

  const ChatListScreen({super.key, required this.appState, this.onOpenDrawer});

  @override
  State<ChatListScreen> createState() => _ChatListScreenState();
}

class _ChatListScreenState extends State<ChatListScreen> {
  String _selectedFilter = 'All';
  final TextEditingController _searchController = TextEditingController();
  bool _isSearching = false;

  List<Conversation> _filterConversations(List<Conversation> list) {
    var filtered = list;
    if (_selectedFilter == 'Unread') {
      filtered = filtered.where((c) => c.unreadCount > 0).toList();
    } else if (_selectedFilter == 'Groups') {
      filtered = filtered.where((c) => c.type == ConversationType.group).toList();
    } else if (_selectedFilter == 'Channels') {
      filtered = filtered.where((c) => c.type == ConversationType.channel).toList();
    }

    final query = _searchController.text.trim().toLowerCase();
    if (query.isNotEmpty) {
      filtered = filtered.where((c) {
        final matchesTitle = c.title.toLowerCase().contains(query);
        final matchesContent = c.lastMessage?.content.toLowerCase().contains(query) ?? false;
        return matchesTitle || matchesContent;
      }).toList();
    }

    return filtered;
  }

  Widget _buildFilterChip(String label) {
    final isSelected = _selectedFilter == label;
    return GestureDetector(
      onTap: () => setState(() => _selectedFilter = label),
      child: Container(
        margin: const EdgeInsets.only(right: 8),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          color: isSelected ? AppTheme.primary.withValues(alpha: 0.2) : AppTheme.darkCard,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: isSelected ? AppTheme.primary : Colors.transparent,
            width: 1,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 13,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
            color: isSelected ? AppTheme.primary : AppTheme.textMuted,
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final convs = _filterConversations(widget.appState.conversations);
    final stories = widget.appState.stories;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.menu_rounded, color: AppTheme.textLight, size: 26),
          tooltip: 'Main Menu',
          onPressed: widget.onOpenDrawer != null
              ? widget.onOpenDrawer
              : () => Scaffold.of(context).openDrawer(),
        ),
        title: _isSearching
            ? TextField(
                controller: _searchController,
                autofocus: true,
                style: const TextStyle(color: AppTheme.textLight),
                decoration: const InputDecoration(
                  hintText: 'Search chats, messages, or contacts...',
                  border: InputBorder.none,
                  filled: false,
                ),
                onChanged: (_) => setState(() {}),
              )
            : const Text(
                'GoChat',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  letterSpacing: -0.5,
                  color: AppTheme.textLight,
                ),
              ),
        actions: [
          IconButton(
            icon: Icon(_isSearching ? Icons.close : Icons.search),
            onPressed: () {
              setState(() {
                if (_isSearching) {
                  _searchController.clear();
                }
                _isSearching = !_isSearching;
              });
            },
          ),
          IconButton(
            icon: const Icon(Icons.qr_code_scanner_rounded),
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('QR Code scanner ready for web linking')),
              );
            },
          ),
          IconButton(
            icon: const Icon(Icons.more_vert),
            onPressed: () {},
          ),
        ],
      ),
      body: RefreshIndicator(
        color: AppTheme.primary,
        onRefresh: () => widget.appState.refreshData(),
        child: CustomScrollView(
          slivers: [
            // Status Stories Carousel (Horizontal)
            if (!_isSearching && stories.isNotEmpty)
              SliverToBoxAdapter(
                child: Container(
                  height: 104,
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: ListView.builder(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    itemCount: stories.length,
                    itemBuilder: (ctx, idx) {
                      final item = stories[idx];
                      return Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 6),
                        child: Column(
                          children: [
                            StoryAvatar(
                              avatarUrl: item.userAvatar,
                              radius: 28,
                              hasUnseenStory: item.hasUnseenStories,
                              storyCount: item.stories.length,
                              onTap: () {
                                if (item.stories.isNotEmpty) {
                                  Navigator.push(
                                    context,
                                    MaterialPageRoute(
                                      builder: (_) => StoryViewerScreen(userStories: item),
                                    ),
                                  );
                                } else {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    const SnackBar(content: Text('Add to your status')),
                                  );
                                }
                              },
                            ),
                            const SizedBox(height: 4),
                            Text(
                              item.isMe ? 'My status' : item.userName.split(' ').first,
                              style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ),

            // Filter Chips
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      _buildFilterChip('All'),
                      _buildFilterChip('Unread'),
                      _buildFilterChip('Groups'),
                      _buildFilterChip('Channels'),
                    ],
                  ),
                ),
              ),
            ),

            // Conversation Threads List
            if (convs.isEmpty)
              const SliverFillRemaining(
                child: Center(
                  child: Text(
                    'No chats found',
                    style: TextStyle(color: AppTheme.textMuted),
                  ),
                ),
              )
            else
              SliverList(
                delegate: SliverChildBuilderDelegate(
                  (ctx, idx) {
                    final c = convs[idx];
                    final lastMsg = c.lastMessage;
                    final timeStr = lastMsg != null
                        ? DateFormat('hh:mm a').format(lastMsg.createdAt)
                        : '';

                    return InkWell(
                      onTap: () {
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => ChatRoomScreen(
                              conversation: c,
                              appState: widget.appState,
                            ),
                          ),
                        );
                      },
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                        child: Row(
                          children: [
                            // Avatar with online dot
                            Stack(
                              children: [
                                CircleAvatar(
                                  radius: 26,
                                  backgroundColor: AppTheme.darkCard,
                                  backgroundImage: c.avatarUrl.isNotEmpty
                                      ? NetworkImage(c.avatarUrl)
                                      : null,
                                  child: c.avatarUrl.isEmpty
                                      ? Icon(
                                          c.type == ConversationType.group
                                              ? Icons.group
                                              : Icons.person,
                                          color: AppTheme.iconColor,
                                        )
                                      : null,
                                ),
                                if (c.isOnline)
                                  Positioned(
                                    bottom: 0,
                                    right: 0,
                                    child: Container(
                                      width: 13,
                                      height: 13,
                                      decoration: BoxDecoration(
                                        color: AppTheme.onlineGreen,
                                        shape: BoxShape.circle,
                                        border: Border.all(color: AppTheme.darkBg, width: 2),
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                            const SizedBox(width: 14),

                            // Title & Message snippet
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                    children: [
                                      Expanded(
                                        child: Text(
                                          c.title,
                                          style: const TextStyle(
                                            fontSize: 16,
                                            fontWeight: FontWeight.bold,
                                            color: AppTheme.textLight,
                                          ),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                      Text(
                                        timeStr,
                                        style: TextStyle(
                                          fontSize: 11,
                                          color: c.unreadCount > 0
                                              ? AppTheme.accent
                                              : AppTheme.textMuted,
                                          fontWeight: c.unreadCount > 0
                                              ? FontWeight.bold
                                              : FontWeight.normal,
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 4),
                                  Row(
                                    children: [
                                      if (lastMsg?.isMe == true) ...[
                                        Icon(
                                          lastMsg?.status == MessageStatus.read
                                              ? Icons.done_all
                                              : Icons.check,
                                          size: 14,
                                          color: lastMsg?.status == MessageStatus.read
                                              ? AppTheme.readBlue
                                              : AppTheme.textMuted,
                                        ),
                                        const SizedBox(width: 4),
                                      ],
                                      if (lastMsg?.type == MessageType.voice)
                                        const Row(
                                          children: [
                                            Icon(Icons.mic, size: 14, color: AppTheme.primary),
                                            SizedBox(width: 3),
                                            Text(
                                              'Voice message',
                                              style: TextStyle(fontSize: 13, color: AppTheme.textMuted),
                                            ),
                                          ],
                                        )
                                      else if (lastMsg?.type == MessageType.poll)
                                        const Row(
                                          children: [
                                            Icon(Icons.poll_rounded, size: 14, color: AppTheme.accent),
                                            SizedBox(width: 3),
                                            Text(
                                              'Poll',
                                              style: TextStyle(fontSize: 13, color: AppTheme.textMuted),
                                            ),
                                          ],
                                        )
                                      else
                                        Expanded(
                                          child: Text(
                                            lastMsg?.content ?? 'No messages yet',
                                            style: const TextStyle(
                                              fontSize: 13.5,
                                              color: AppTheme.textMuted,
                                            ),
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                        ),
                                      if (c.unreadCount > 0)
                                        Container(
                                          padding: const EdgeInsets.all(6),
                                          decoration: const BoxDecoration(
                                            color: AppTheme.accent,
                                            shape: BoxShape.circle,
                                          ),
                                          child: Text(
                                            '${c.unreadCount}',
                                            style: const TextStyle(
                                              fontSize: 11,
                                              fontWeight: FontWeight.bold,
                                              color: Colors.black,
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
                      ),
                    );
                  },
                  childCount: convs.length,
                ),
              ),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton(
        heroTag: 'chat_list_fab',
        onPressed: () {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Starting new conversation / group')),
          );
        },
        child: const Icon(Icons.message_rounded),
      ),
    );
  }
}
