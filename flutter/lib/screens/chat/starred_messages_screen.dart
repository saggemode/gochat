import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import '../../core/models/conversation.dart';
import '../../core/models/message.dart';
import '../../core/services/starred_message_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import 'chat_room_screen.dart';
import 'media_lightbox_screen.dart';

class StarredMessagesScreen extends StatefulWidget {
  final Conversation? conversation; // If null, shows all starred messages globally
  final AppState appState;

  const StarredMessagesScreen({
    super.key,
    this.conversation,
    required this.appState,
  });

  static void open(
    BuildContext context, {
    Conversation? conversation,
    required AppState appState,
  }) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => StarredMessagesScreen(
          conversation: conversation,
          appState: appState,
        ),
      ),
    );
  }

  @override
  State<StarredMessagesScreen> createState() => _StarredMessagesScreenState();
}

class _StarredMessagesScreenState extends State<StarredMessagesScreen> {
  final StarredMessageService _starredService = StarredMessageService();
  final TextEditingController _searchController = TextEditingController();

  String _searchQuery = '';
  String _selectedFilter = 'All';

  final List<String> _filterTypes = ['All', 'Text', 'Media', 'Voice / Audio', 'Links'];

  @override
  void initState() {
    super.initState();
    _starredService.init();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<Message> _getFilteredMessages(List<Message> messages) {
    var filtered = messages;

    // Filter by conversation if specified
    if (widget.conversation != null) {
      filtered = filtered.where((m) => m.conversationId == widget.conversation!.id).toList();
    }

    // Filter by search query
    if (_searchQuery.isNotEmpty) {
      final q = _searchQuery.toLowerCase();
      filtered = filtered.where((m) {
        return m.content.toLowerCase().contains(q) ||
            m.senderName.toLowerCase().contains(q);
      }).toList();
    }

    // Filter by category type
    if (_selectedFilter != 'All') {
      switch (_selectedFilter) {
        case 'Text':
          filtered = filtered.where((m) => m.type == MessageType.text).toList();
          break;
        case 'Media':
          filtered = filtered.where((m) =>
              m.type == MessageType.image ||
              m.type == MessageType.video ||
              m.mediaUrl != null).toList();
          break;
        case 'Voice / Audio':
          filtered = filtered.where((m) => m.type == MessageType.audio).toList();
          break;
        case 'Links':
          filtered = filtered.where((m) =>
              m.content.contains('http://') ||
              m.content.contains('https://') ||
              m.content.contains('www.')).toList();
          break;
      }
    }

    return filtered;
  }

  void _onMessageTap(Message message) {
    if (message.mediaUrl != null && message.mediaUrl!.isNotEmpty) {
      MediaLightboxScreen.show(
        context,
        mediaUrl: message.mediaUrl!,
        caption: message.senderName,
        timestamp: message.createdAt,
      );
      return;
    }

    // If global view, find conversation and jump
    if (widget.conversation == null) {
      final conv = widget.appState.conversations.firstWhere(
        (c) => c.id == message.conversationId,
        orElse: () => Conversation(
          id: message.conversationId,
          title: message.senderName,
          updatedAt: message.createdAt,
        ),
      );

      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (_) => ChatRoomScreen(
            conversation: conv,
            appState: widget.appState,
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    final title = widget.conversation != null
        ? 'Starred in ${widget.conversation!.title}'
        : 'Starred Messages';

    return Scaffold(
      appBar: AppBar(
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
      ),
      body: Column(
        children: [
          // Search Bar
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              controller: _searchController,
              style: TextStyle(
                fontSize: 14,
                color: isDark ? AppTheme.textLight : AppTheme.textDark,
              ),
              decoration: InputDecoration(
                hintText: 'Search starred messages...',
                hintStyle: TextStyle(
                  fontSize: 13,
                  color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                ),
                prefixIcon: const Icon(Icons.search_rounded, size: 20),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear_rounded, size: 18),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _searchQuery = '');
                        },
                      )
                    : null,
                contentPadding: const EdgeInsets.symmetric(vertical: 8, horizontal: 14),
                filled: true,
                fillColor: isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(20),
                  borderSide: BorderSide.none,
                ),
              ),
              onChanged: (val) => setState(() => _searchQuery = val.trim()),
            ),
          ),

          // Filter Chips
          SizedBox(
            height: 38,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              itemCount: _filterTypes.length,
              itemBuilder: (context, idx) {
                final filter = _filterTypes[idx];
                final isSelected = filter == _selectedFilter;
                return Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: ChoiceChip(
                    label: Text(filter),
                    selected: isSelected,
                    selectedColor: AppTheme.primary.withValues(alpha: 0.25),
                    labelStyle: TextStyle(
                      fontSize: 12,
                      fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                      color: isSelected
                          ? AppTheme.primary
                          : (isDark ? AppTheme.textLight : AppTheme.textDark),
                    ),
                    onSelected: (_) => setState(() => _selectedFilter = filter),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 6),
          const Divider(height: 1),

          // Starred Messages List
          Expanded(
            child: ValueListenableBuilder<List<Message>>(
              valueListenable: _starredService.starredMessagesNotifier,
              builder: (context, allStarred, _) {
                final filtered = _getFilteredMessages(allStarred);

                if (filtered.isEmpty) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(32),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Container(
                            padding: const EdgeInsets.all(20),
                            decoration: BoxDecoration(
                              color: Colors.amber.withValues(alpha: 0.15),
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(
                              Icons.star_outline_rounded,
                              size: 48,
                              color: Colors.amber,
                            ),
                          ),
                          const SizedBox(height: 16),
                          Text(
                            _searchQuery.isNotEmpty
                                ? 'No matching starred messages'
                                : 'No starred messages yet',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                              color: isDark ? AppTheme.textLight : AppTheme.textDark,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            _searchQuery.isNotEmpty
                                ? 'Try searching for something else'
                                : 'Long-press any message in a chat and tap "Star Message" to bookmark it here for quick reference.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: 13,
                              color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  itemCount: filtered.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, idx) {
                    final message = filtered[idx];
                    final dateStr = DateFormat('MMM d, yyyy · hh:mm a').format(message.createdAt);

                    return InkWell(
                      onTap: () => _onMessageTap(message),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Icon / Avatar preview
                            CircleAvatar(
                              radius: 20,
                              backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
                              child: _buildMessageLeadingIcon(message),
                            ),
                            const SizedBox(width: 12),

                            // Main Content
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      Expanded(
                                        child: Text(
                                          message.senderName.isNotEmpty ? message.senderName : 'Contact',
                                          style: TextStyle(
                                            fontWeight: FontWeight.bold,
                                            fontSize: 14,
                                            color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                          ),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                      const Icon(Icons.star_rounded, size: 16, color: Colors.amber),
                                      const SizedBox(width: 4),
                                      Text(
                                        dateStr,
                                        style: TextStyle(
                                          fontSize: 11,
                                          color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 4),

                                  // Message content snippet
                                  if (message.mediaUrl != null && message.mediaUrl!.isNotEmpty) ...[
                                    Container(
                                      margin: const EdgeInsets.only(top: 4, bottom: 4),
                                      height: 120,
                                      width: 160,
                                      child: ClipRRect(
                                        borderRadius: BorderRadius.circular(8),
                                        child: Image.network(
                                          message.mediaUrl!,
                                          fit: BoxFit.cover,
                                          errorBuilder: (_, _, _) => Container(
                                            color: isDark ? AppTheme.darkCard : Colors.grey[300],
                                            child: const Icon(Icons.broken_image_rounded, color: AppTheme.textMuted),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],

                                  Text(
                                    message.content.isNotEmpty
                                        ? message.content
                                        : (message.mediaUrl != null ? '📷 Media Attachment' : 'Starred Item'),
                                    style: TextStyle(
                                      fontSize: 13,
                                      color: isDark ? AppTheme.textLight : AppTheme.textDark,
                                    ),
                                    maxLines: 3,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ],
                              ),
                            ),

                            // Action Menu (Unstar / Copy)
                            PopupMenuButton<String>(
                              icon: Icon(
                                Icons.more_vert_rounded,
                                size: 18,
                                color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                              ),
                              onSelected: (val) async {
                                if (val == 'unstar') {
                                  await _starredService.unstarMessage(message.id);
                                  if (context.mounted) {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      SnackBar(
                                        content: const Text('Message unstarred'),
                                        action: SnackBarAction(
                                          label: 'Undo',
                                          onPressed: () => _starredService.starMessage(message),
                                        ),
                                      ),
                                    );
                                  }
                                } else if (val == 'copy') {
                                  Clipboard.setData(ClipboardData(text: message.content));
                                  if (context.mounted) {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      const SnackBar(content: Text('Message copied to clipboard')),
                                    );
                                  }
                                }
                              },
                              itemBuilder: (ctx) => [
                                const PopupMenuItem(
                                  value: 'copy',
                                  child: Row(
                                    children: [
                                      Icon(Icons.copy_rounded, size: 18),
                                      SizedBox(width: 8),
                                      Text('Copy'),
                                    ],
                                  ),
                                ),
                                const PopupMenuItem(
                                  value: 'unstar',
                                  child: Row(
                                    children: [
                                      Icon(Icons.star_outline_rounded, size: 18, color: Colors.amber),
                                      SizedBox(width: 8),
                                      Text('Unstar'),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMessageLeadingIcon(Message message) {
    if (message.type == MessageType.image || (message.mediaUrl != null && message.mediaUrl!.isNotEmpty)) {
      return const Icon(Icons.image_rounded, color: AppTheme.primary, size: 20);
    } else if (message.type == MessageType.audio) {
      return const Icon(Icons.mic_rounded, color: AppTheme.primary, size: 20);
    } else if (message.type == MessageType.poll) {
      return const Icon(Icons.poll_rounded, color: AppTheme.primary, size: 20);
    } else {
      return const Icon(Icons.chat_bubble_outline_rounded, color: AppTheme.primary, size: 18);
    }
  }
}
