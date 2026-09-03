import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import 'chat_room_screen.dart';
import 'new_chat_by_pin_dialog.dart';
import 'select_contact_screen.dart';
import '../qr/qr_scanner_screen.dart';
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

  @override
  void initState() {
    super.initState();
    widget.appState.addListener(_onStateChange);
  }

  @override
  void didUpdateWidget(covariant ChatListScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.appState != widget.appState) {
      oldWidget.appState.removeListener(_onStateChange);
      widget.appState.addListener(_onStateChange);
    }
  }

  @override
  void dispose() {
    widget.appState.removeListener(_onStateChange);
    _searchController.dispose();
    super.dispose();
  }

  void _onStateChange() {
    if (mounted) setState(() {});
  }

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
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return GestureDetector(
      onTap: () => setState(() => _selectedFilter = label),
      child: Container(
        margin: const EdgeInsets.only(right: 8),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          color: isSelected
              ? AppTheme.primary.withValues(alpha: 0.18)
              : (isDark ? AppTheme.darkCard : Colors.white),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: isSelected ? AppTheme.primary : (isDark ? AppTheme.darkBorder : AppTheme.lightBorder),
            width: 1,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 13,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
            color: isSelected ? AppTheme.primary : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final convs = _filterConversations(widget.appState.conversations);
    final stories = widget.appState.stories;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: Icon(Icons.menu_rounded, color: isDark ? AppTheme.textLight : AppTheme.textDark, size: 26),
          tooltip: 'Main Menu',
          onPressed: widget.onOpenDrawer ?? () => Scaffold.of(context).openDrawer(),
        ),
        title: _isSearching
            ? SearchField(
                controller: _searchController,
                hintText: 'Search chats, contacts, or messages...',
                autofocus: true,
                onChanged: (_) => setState(() {}),
                onClear: () => setState(() => _searchController.clear()),
              )
            : Text(
                'GoChat',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  letterSpacing: -0.5,
                  color: isDark ? AppTheme.textLight : AppTheme.textDark,
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
            tooltip: 'Scan GOCHAT QR Code',
            onPressed: () => QrScannerScreen.open(context, widget.appState),
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
                                      builder: (_) => StoryViewerScreen(
                                        userStories: item,
                                        appState: widget.appState,
                                      ),
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
                              style: TextStyle(
                                fontSize: 11,
                                color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                              ),
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

            // Filter Chips + Quick PIN action
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      ActionChip(
                        avatar: const Icon(Icons.vpn_key_rounded, size: 14, color: Colors.black),
                        label: const Text(
                          '+ Chat by PIN',
                          style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: Colors.black),
                        ),
                        backgroundColor: AppTheme.primary,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                        onPressed: () => NewChatByPinDialog.show(context, widget.appState),
                      ),
                      const SizedBox(width: 8),
                      _buildFilterChip('All'),
                      _buildFilterChip('Unread'),
                      _buildFilterChip('Groups'),
                      _buildFilterChip('Channels'),
                    ],
                  ),
                ),
              ),
            ),

            // Pending Contact Invitations Banner
            Builder(
              builder: (context) {
                final pendingIncoming = widget.appState.conversations
                    .where((c) => c.invitationStatus == InvitationStatus.pendingIncoming)
                    .toList();
                if (pendingIncoming.isEmpty) return const SliverToBoxAdapter(child: SizedBox.shrink());

                return SliverToBoxAdapter(
                  child: Container(
                    margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    decoration: BoxDecoration(
                      color: Colors.amber.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: Colors.amber.withValues(alpha: 0.4)),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.mark_email_unread_rounded, color: Colors.amber, size: 20),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            '${pendingIncoming.length} new contact invitation request${pendingIncoming.length > 1 ? 's' : ''}',
                            style: const TextStyle(
                              color: Colors.amber,
                              fontWeight: FontWeight.bold,
                              fontSize: 13,
                            ),
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.amber,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: const Text(
                            'Review',
                            style: TextStyle(color: Colors.black, fontSize: 11, fontWeight: FontWeight.bold),
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),

            // Conversation Threads List with ConversationTile
            if (convs.isEmpty)
              SliverFillRemaining(
                child: EmptyStateView(
                  icon: Icons.chat_bubble_outline_rounded,
                  title: 'No Conversations',
                  description: _isSearching
                      ? 'No messages match "${_searchController.text}"'
                      : 'Start chatting with your contacts using their GOCHAT PIN or phone number.',
                  actionLabel: _isSearching ? 'Clear Search' : 'Select Contact',
                  onAction: () {
                    if (_isSearching) {
                      setState(() {
                        _searchController.clear();
                        _isSearching = false;
                      });
                    } else {
                      SelectContactScreen.open(context, widget.appState);
                    }
                  },
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
                    final isRecording = widget.appState.isUserRecordingAudio(c.id);
                    final isTyping = widget.appState.isUserTyping(c.id);

                    String msgPreview = 'No messages yet';
                    if (isRecording) {
                      msgPreview = '🎙️ recording audio...';
                    } else if (isTyping) {
                      msgPreview = 'typing...';
                    } else if (c.invitationStatus == InvitationStatus.pendingIncoming) {
                      msgPreview = '🤝 Incoming invitation · Tap to accept';
                    } else if (c.invitationStatus == InvitationStatus.pendingOutgoing) {
                      msgPreview = '⏳ Invitation sent · Waiting for acceptance';
                    } else if (lastMsg != null) {
                      if (lastMsg.isPing) {
                        msgPreview = '💥 PING!!!';
                      } else if (lastMsg.type == MessageType.voice || lastMsg.type == MessageType.audio) {
                        msgPreview = '🎙️ Voice Note';
                      } else if (lastMsg.type == MessageType.poll) {
                        msgPreview = '📊 Poll';
                      } else if (lastMsg.type == MessageType.product) {
                        msgPreview = '🛍️ Product';
                      } else if (lastMsg.type == MessageType.canvas) {
                        msgPreview = '🎨 Canvas';
                      } else {
                        msgPreview = lastMsg.content;
                      }
                    }

                    final convStories = widget.appState.getStoriesForConversation(c);
                    final hasStory = convStories != null && convStories.stories.isNotEmpty;

                    return ConversationTile(
                      name: c.title,
                      avatarUrl: c.avatarUrl,
                      lastMessage: msgPreview,
                      time: timeStr,
                      unreadCount: c.unreadCount,
                      isOnline: c.isOnline,
                      isMuted: c.isMuted,
                      isPinned: c.isPinned,
                      isTyping: isTyping,
                      isGroup: c.type == ConversationType.group,
                      hasStory: hasStory,
                      onAvatarTap: hasStory
                          ? () {
                              Navigator.push(
                                context,
                                MaterialPageRoute(
                                  builder: (_) => StoryViewerScreen(
                                    userStories: convStories,
                                    appState: widget.appState,
                                  ),
                                ),
                              );
                            }
                          : null,
                      onTap: () {
                        widget.appState.markConversationAsRead(c.id);
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => ChatRoomScreen(
                              conversation: c.copyWith(unreadCount: 0),
                              appState: widget.appState,
                            ),
                          ),
                        ).then((_) {
                          if (mounted) {
                            widget.appState.markConversationAsRead(c.id);
                          }
                        });
                      },
                      onLongPress: () {
                        ConfirmDialog.show(
                          context,
                          title: c.title,
                          message: 'Choose an action for this conversation.',
                          confirmText: 'Mute',
                          cancelText: 'Close',
                        );
                      },
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
        tooltip: 'New Chat',
        onPressed: () => SelectContactScreen.open(context, widget.appState),
        child: const Icon(Icons.message_rounded),
      ),
    );
  }
}
