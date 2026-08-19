import 'package:flutter/material.dart';
import '../../core/models/conversation.dart';
import '../../core/models/message.dart';
import '../../core/models/call.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/chat_bubble.dart';
import '../../widgets/mini_app_modal.dart';
import '../calls/active_call_screen.dart';

class ChatRoomScreen extends StatefulWidget {
  final Conversation conversation;
  final AppState appState;

  const ChatRoomScreen({
    super.key,
    required this.conversation,
    required this.appState,
  });

  @override
  State<ChatRoomScreen> createState() => _ChatRoomScreenState();
}

class _ChatRoomScreenState extends State<ChatRoomScreen> {
  final TextEditingController _inputController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  bool _isTyping = false;

  @override
  void initState() {
    super.initState();
    widget.appState.addListener(_onStateChange);
  }

  @override
  void dispose() {
    widget.appState.removeListener(_onStateChange);
    _inputController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onStateChange() {
    if (mounted) {
      setState(() {});
      _scrollToBottom();
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _handleSend() {
    final text = _inputController.text.trim();
    if (text.isEmpty) return;

    widget.appState.sendMessage(widget.conversation.id, text);
    _inputController.clear();
    setState(() => _isTyping = false);
    _scrollToBottom();
  }

  void _handleSendVoiceNote() {
    widget.appState.sendMessage(
      widget.conversation.id,
      'Voice Note (0:45)',
      type: MessageType.voice,
      mediaDuration: 45,
    );
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('🎙️ Voice Note sent with wave spectrum')),
    );
    _scrollToBottom();
  }

  void _openCreatePollDialog() {
    final questionController = TextEditingController();
    final opt1Controller = TextEditingController();
    final opt2Controller = TextEditingController();
    final opt3Controller = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          backgroundColor: AppTheme.darkSurface,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Row(
            children: [
              Icon(Icons.poll_rounded, color: AppTheme.primary),
              SizedBox(width: 8),
              Text('Create Live Poll', style: TextStyle(color: AppTheme.textLight)),
            ],
          ),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: questionController,
                  style: const TextStyle(color: AppTheme.textLight),
                  decoration: const InputDecoration(hintText: 'Ask a question...'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: opt1Controller,
                  style: const TextStyle(color: AppTheme.textLight),
                  decoration: const InputDecoration(hintText: 'Option 1'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: opt2Controller,
                  style: const TextStyle(color: AppTheme.textLight),
                  decoration: const InputDecoration(hintText: 'Option 2'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: opt3Controller,
                  style: const TextStyle(color: AppTheme.textLight),
                  decoration: const InputDecoration(hintText: 'Option 3 (Optional)'),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel', style: TextStyle(color: AppTheme.textMuted)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.primary,
                foregroundColor: Colors.white,
              ),
              onPressed: () {
                final q = questionController.text.trim();
                final o1 = opt1Controller.text.trim();
                final o2 = opt2Controller.text.trim();
                final o3 = opt3Controller.text.trim();

                if (q.isNotEmpty && o1.isNotEmpty && o2.isNotEmpty) {
                  final poll = PollData(
                    id: 'poll_${DateTime.now().millisecondsSinceEpoch}',
                    question: q,
                    options: [
                      PollOption(id: 'o1', text: o1),
                      PollOption(id: 'o2', text: o2),
                      if (o3.isNotEmpty) PollOption(id: 'o3', text: o3),
                    ],
                  );

                  widget.appState.sendMessage(
                    widget.conversation.id,
                    '📊 Live Poll: $q',
                    type: MessageType.poll,
                    pollData: poll,
                  );
                  Navigator.pop(ctx);
                  _scrollToBottom();
                }
              },
              child: const Text('Create Poll'),
            ),
          ],
        );
      },
    );
  }

  void _showAttachmentSheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.darkSurface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Colors.white24,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(height: 20),
              Wrap(
                spacing: 24,
                runSpacing: 20,
                alignment: WrapAlignment.center,
                children: [
                  _buildAttachOption(
                    Icons.poll_rounded,
                    'Poll',
                    Colors.amber,
                    () {
                      Navigator.pop(ctx);
                      _openCreatePollDialog();
                    },
                  ),
                  _buildAttachOption(
                    Icons.brush_rounded,
                    'Canvas',
                    Colors.pinkAccent,
                    () {
                      Navigator.pop(ctx);
                      _openMiniAppModal();
                    },
                  ),
                  _buildAttachOption(
                    Icons.image_rounded,
                    'Gallery',
                    Colors.purpleAccent,
                    () {
                      Navigator.pop(ctx);
                      widget.appState.sendMessage(
                        widget.conversation.id,
                        'Shared an image',
                        type: MessageType.image,
                        mediaUrl: 'https://images.unsplash.com/photo-1518770660439-4636190af475?w=600',
                      );
                      _scrollToBottom();
                    },
                  ),
                  _buildAttachOption(
                    Icons.videogame_asset_rounded,
                    'Mini-Game',
                    AppTheme.accent,
                    () {
                      Navigator.pop(ctx);
                      _openMiniAppModal();
                    },
                  ),
                  _buildAttachOption(
                    Icons.insert_drive_file_rounded,
                    'Document',
                    Colors.blueAccent,
                    () {
                      Navigator.pop(ctx);
                      widget.appState.sendMessage(
                        widget.conversation.id,
                        '📄 Architecture_Spec_v2.pdf (1.8 MB)',
                        type: MessageType.file,
                      );
                    },
                  ),
                  _buildAttachOption(
                    Icons.smart_toy_rounded,
                    '@Bot AI',
                    Colors.tealAccent,
                    () {
                      Navigator.pop(ctx);
                      _inputController.text = '@bot summarize conversation';
                      setState(() => _isTyping = true);
                    },
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildAttachOption(IconData icon, String label, Color color, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.15),
              shape: BoxShape.circle,
              border: Border.all(color: color.withValues(alpha: 0.3)),
            ),
            child: Icon(icon, color: color, size: 26),
          ),
          const SizedBox(height: 6),
          Text(
            label,
            style: const TextStyle(fontSize: 12, color: AppTheme.textLight, fontWeight: FontWeight.w500),
          ),
        ],
      ),
    );
  }

  void _openMiniAppModal() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => MiniAppModal(
        onShareToChat: (summary) {
          widget.appState.sendMessage(
            widget.conversation.id,
            '🎨 $summary',
            type: MessageType.canvas,
          );
          _scrollToBottom();
        },
      ),
    );
  }

  void _startCall(CallType type) {
    widget.appState.startCall(
      widget.conversation.title,
      widget.conversation.avatarUrl,
      type,
    );
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ActiveCallScreen(
          callRecord: widget.appState.activeCall!,
          appState: widget.appState,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final messages = widget.appState.getMessagesFor(widget.conversation.id);
    final myId = widget.appState.currentUser?.id ?? 'u_me';

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: Row(
          children: [
            CircleAvatar(
              radius: 18,
              backgroundColor: AppTheme.darkCard,
              backgroundImage: widget.conversation.avatarUrl.isNotEmpty
                  ? NetworkImage(widget.conversation.avatarUrl)
                  : null,
              child: widget.conversation.avatarUrl.isEmpty
                  ? const Icon(Icons.person, size: 20, color: AppTheme.iconColor)
                  : null,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.conversation.title,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    widget.conversation.isOnline ? 'Online' : 'tap here for info',
                    style: TextStyle(
                      fontSize: 11,
                      color: widget.conversation.isOnline ? AppTheme.onlineGreen : AppTheme.textMuted,
                      fontWeight: widget.conversation.isOnline ? FontWeight.bold : FontWeight.normal,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.videocam_rounded),
            onPressed: () => _startCall(CallType.video),
          ),
          IconButton(
            icon: const Icon(Icons.call_rounded),
            onPressed: () => _startCall(CallType.audio),
          ),
          PopupMenuButton<String>(
            color: AppTheme.darkCard,
            iconColor: AppTheme.iconColor,
            onSelected: (val) {
              if (val == 'canvas') {
                _openMiniAppModal();
              } else if (val == 'poll') {
                _openCreatePollDialog();
              }
            },
            itemBuilder: (ctx) => [
              const PopupMenuItem(value: 'canvas', child: Text('Open Shared Canvas')),
              const PopupMenuItem(value: 'poll', child: Text('Create Live Poll')),
              const PopupMenuItem(value: 'clear', child: Text('Clear chat history')),
            ],
          ),
        ],
      ),
      body: Container(
        decoration: const BoxDecoration(
          color: AppTheme.darkBg,
          // Subtle wallpaper gradient
          gradient: RadialGradient(
            center: Alignment.center,
            radius: 1.2,
            colors: [Color(0xFF142129), AppTheme.darkBg],
          ),
        ),
        child: Column(
          children: [
            // Messages List
            Expanded(
              child: messages.isEmpty
                  ? const Center(
                      child: Text(
                        'Send a message to start the conversation',
                        style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
                      ),
                    )
                  : ListView.builder(
                      controller: _scrollController,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      itemCount: messages.length,
                      itemBuilder: (ctx, idx) {
                        final msg = messages[idx];
                        return ChatBubble(
                          message: msg,
                          currentUserId: myId,
                          onVotePoll: (optId) =>
                              widget.appState.votePoll(widget.conversation.id, msg.id, optId),
                          onReact: (emoji) =>
                              widget.appState.addReaction(widget.conversation.id, msg.id, emoji),
                          onOpenCanvas: _openMiniAppModal,
                        );
                      },
                    ),
            ),

            // Bottom Input Bar
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
              color: AppTheme.darkSurface,
              child: SafeArea(
                top: false,
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.add_rounded, color: AppTheme.iconColor, size: 26),
                      onPressed: _showAttachmentSheet,
                    ),
                    Expanded(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        decoration: BoxDecoration(
                          color: AppTheme.darkCard,
                          borderRadius: BorderRadius.circular(24),
                          border: Border.all(color: AppTheme.darkBorder, width: 0.5),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: TextField(
                                controller: _inputController,
                                style: const TextStyle(color: AppTheme.textLight, fontSize: 15),
                                decoration: const InputDecoration(
                                  hintText: 'Message or @bot...',
                                  border: InputBorder.none,
                                  isDense: true,
                                  contentPadding: EdgeInsets.symmetric(vertical: 10),
                                  filled: false,
                                ),
                                onChanged: (val) {
                                  setState(() {
                                    _isTyping = val.trim().isNotEmpty;
                                  });
                                },
                                onSubmitted: (_) => _handleSend(),
                              ),
                            ),
                            IconButton(
                              icon: const Icon(Icons.emoji_emotions_outlined, color: AppTheme.iconColor, size: 22),
                              onPressed: () {
                                _inputController.text += '✨ ';
                              },
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    GestureDetector(
                      onTap: _isTyping ? _handleSend : _handleSendVoiceNote,
                      child: Container(
                        width: 44,
                        height: 44,
                        decoration: const BoxDecoration(
                          color: AppTheme.primary,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          _isTyping ? Icons.send_rounded : Icons.mic_rounded,
                          color: Colors.white,
                          size: 22,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
