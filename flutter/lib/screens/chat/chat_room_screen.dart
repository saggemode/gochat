import 'package:flutter/material.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/chat_bubble.dart';
import '../../widgets/mini_app_modal.dart';
import '../calls/active_call_screen.dart';
import 'chat_attachment_sheet.dart';
import 'chat_input_bar.dart';
import 'create_poll_dialog.dart';

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
    CreatePollDialog.show(
      context,
      onCreatePoll: (poll) {
        widget.appState.sendMessage(
          widget.conversation.id,
          '📊 Live Poll: ${poll.question}',
          type: MessageType.poll,
          pollData: poll,
        );
        _scrollToBottom();
      },
    );
  }

  void _showAttachmentSheet() {
    ChatAttachmentSheet.show(
      context,
      onOpenPoll: _openCreatePollDialog,
      onOpenCanvas: _openMiniAppModal,
      onOpenMiniGame: _openMiniAppModal,
      onShareImage: () {
        widget.appState.sendMessage(
          widget.conversation.id,
          'Shared an image',
          type: MessageType.image,
          mediaUrl: 'https://images.unsplash.com/photo-1518770660439-4636190af475?w=600',
        );
        _scrollToBottom();
      },
      onShareDocument: () {
        widget.appState.sendMessage(
          widget.conversation.id,
          '📄 Architecture_Spec_v2.pdf (1.8 MB)',
          type: MessageType.file,
        );
        _scrollToBottom();
      },
      onAskBot: () {
        _inputController.text = '@bot summarize conversation';
        setState(() => _isTyping = true);
      },
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
            ChatInputBar(
              inputController: _inputController,
              isTyping: _isTyping,
              onAttachmentPressed: _showAttachmentSheet,
              onVoiceNotePressed: _handleSendVoiceNote,
              onSendPressed: _handleSend,
              onChanged: (val) {
                final typing = val.trim().isNotEmpty;
                if (typing != _isTyping) {
                  setState(() => _isTyping = typing);
                }
              },
            ),
          ],
        ),
      ),
    );
  }
}
