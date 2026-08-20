import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/chat_bubble.dart';
import '../../widgets/custom_avatar.dart';
import '../../widgets/mini_app_modal.dart';
import '../../widgets/primary_button.dart';
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

class _ChatRoomScreenState extends State<ChatRoomScreen> with SingleTickerProviderStateMixin {
  final TextEditingController _inputController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  bool _isTyping = false;
  Message? _replyingTo;
  Timer? _typingDebounceTimer;
  StreamSubscription<String>? _pingSubscription;

  // Screen Shake Animation for BBM PING!
  late AnimationController _shakeController;
  late Animation<double> _shakeAnimation;

  @override
  void initState() {
    super.initState();
    widget.appState.addListener(_onStateChange);

    _shakeController = AnimationController(
      duration: const Duration(milliseconds: 600),
      vsync: this,
    );
    _shakeAnimation = Tween<double>(begin: 0.0, end: 12.0)
        .chain(CurveTween(curve: Curves.elasticIn))
        .animate(_shakeController);

    // Listen for incoming PING! events for this conversation
    _pingSubscription = widget.appState.onPingReceived.listen((convId) {
      if (convId == widget.conversation.id && mounted) {
        _triggerScreenShake();
      }
    });
  }

  @override
  void dispose() {
    _typingDebounceTimer?.cancel();
    _pingSubscription?.cancel();
    _shakeController.dispose();
    widget.appState.removeListener(_onStateChange);
    _inputController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _triggerScreenShake() {
    HapticFeedback.heavyImpact();
    _shakeController.forward(from: 0.0);
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

  void _handleTypingChanged(String val) {
    final typing = val.trim().isNotEmpty;
    if (typing != _isTyping) {
      setState(() => _isTyping = typing);
    }

    // Broadcast live typing event to other users
    widget.appState.sendTypingEvent(widget.conversation.id, true);
    _typingDebounceTimer?.cancel();
    _typingDebounceTimer = Timer(const Duration(seconds: 2), () {
      widget.appState.sendTypingEvent(widget.conversation.id, false);
    });
  }

  void _handleSend() {
    final text = _inputController.text.trim();
    if (text.isEmpty) return;

    widget.appState.sendMessage(
      widget.conversation.id,
      text,
      replyToId: _replyingTo?.id,
      replyToText: _replyingTo?.content,
      replyToSenderName: _replyingTo?.senderName,
    );

    _inputController.clear();
    setState(() {
      _isTyping = false;
      _replyingTo = null;
    });
    widget.appState.sendTypingEvent(widget.conversation.id, false);
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

  void _handleSendPing() {
    _triggerScreenShake();
    widget.appState.sendPing(widget.conversation.id);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('💥 BBM PING! Nudge Sent'),
        duration: Duration(seconds: 1),
      ),
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

  void _openProductPicker() {
    final products = widget.appState.products;
    showModalBottomSheet(
      context: context,
      backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (ctx) {
        return Container(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Share Product to Chat',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              if (products.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(20),
                  child: Center(child: Text('No products available in marketplace')),
                )
              else
                Flexible(
                  child: ListView.separated(
                    shrinkWrap: true,
                    itemCount: products.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (c, idx) {
                      final prod = products[idx];
                      return ListTile(
                        leading: ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: Image.network(
                            prod.imageUrl,
                            width: 44,
                            height: 44,
                            fit: BoxFit.cover,
                            errorBuilder: (_, __, ___) => const Icon(Icons.shopping_bag),
                          ),
                        ),
                        title: Text(prod.title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                        subtitle: Text('\$${prod.price.toStringAsFixed(2)}', style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold)),
                        trailing: const Icon(Icons.send_rounded, color: AppTheme.primary, size: 20),
                        onTap: () {
                          Navigator.pop(ctx);
                          widget.appState.sendProductCard(widget.conversation.id, prod);
                          _scrollToBottom();
                        },
                      );
                    },
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  void _showBuyProductSheet(Map<String, dynamic> product) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (ctx) {
        return Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: Image.network(
                      product['image_url'] ?? '',
                      width: 64,
                      height: 64,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const Icon(Icons.shopping_bag, size: 40),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          product['title'] ?? 'Product',
                          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '\$${(product['price'] ?? 0).toString()}',
                          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppTheme.primary),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              PrimaryButton(
                label: 'Instant 1-Tap Checkout',
                icon: Icons.flash_on_rounded,
                onPressed: () {
                  Navigator.pop(ctx);
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text('🎉 Order placed for ${product['title']}!'),
                      backgroundColor: AppTheme.primary,
                    ),
                  );
                },
              ),
            ],
          ),
        );
      },
    );
  }

  void _showAttachmentSheet() {
    ChatAttachmentSheet.show(
      context,
      onOpenPoll: _openCreatePollDialog,
      onOpenCanvas: _openMiniAppModal,
      onOpenMiniGame: _openMiniAppModal,
      onShareProduct: _openProductPicker,
      onSendPing: _handleSendPing,
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
      CallRecord(
        id: 'call_${DateTime.now().millisecondsSinceEpoch}',
        callerId: widget.conversation.id,
        callerName: widget.conversation.title,
        callerAvatar: widget.conversation.avatarUrl,
        type: type,
        direction: CallDirection.outgoing,
        timestamp: DateTime.now(),
      ),
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
    final isTypingLive = widget.appState.isUserTyping(widget.conversation.id);
    final typingText = widget.appState.getTypingText(widget.conversation.id);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return AnimatedBuilder(
      animation: _shakeAnimation,
      builder: (context, child) {
        final dx = sin(_shakeController.value * pi * 8) * _shakeAnimation.value;
        return Transform.translate(
          offset: Offset(dx, 0),
          child: child,
        );
      },
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: 0,
          title: Row(
            children: [
              CustomAvatar(
                imageUrl: widget.conversation.avatarUrl,
                name: widget.conversation.title,
                radius: 18,
                isOnline: widget.conversation.isOnline,
                showOnlineBadge: true,
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
                      isTypingLive
                          ? typingText
                          : (widget.conversation.isOnline ? 'Online' : 'tap here for info'),
                      style: TextStyle(
                        fontSize: 11,
                        color: isTypingLive
                            ? AppTheme.primary
                            : (widget.conversation.isOnline ? AppTheme.onlineGreen : AppTheme.textMuted),
                        fontWeight: (isTypingLive || widget.conversation.isOnline)
                            ? FontWeight.bold
                            : FontWeight.normal,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          actions: [
            IconButton(
              icon: const Icon(Icons.vibration_rounded, color: Colors.amber),
              tooltip: 'BBM PING!',
              onPressed: _handleSendPing,
            ),
            IconButton(
              icon: const Icon(Icons.videocam_rounded),
              onPressed: () => _startCall(CallType.video),
            ),
            IconButton(
              icon: const Icon(Icons.call_rounded),
              onPressed: () => _startCall(CallType.audio),
            ),
            PopupMenuButton<String>(
              color: isDark ? AppTheme.darkCard : Colors.white,
              iconColor: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
              onSelected: (val) {
                if (val == 'canvas') {
                  _openMiniAppModal();
                } else if (val == 'poll') {
                  _openCreatePollDialog();
                } else if (val == 'product') {
                  _openProductPicker();
                } else if (val == 'ping') {
                  _handleSendPing();
                }
              },
              itemBuilder: (ctx) => [
                const PopupMenuItem(value: 'ping', child: Text('💥 Send BBM PING!')),
                const PopupMenuItem(value: 'product', child: Text('🛍️ Share Product')),
                const PopupMenuItem(value: 'canvas', child: Text('🎨 Open Shared Canvas')),
                const PopupMenuItem(value: 'poll', child: Text('📊 Create Live Poll')),
              ],
            ),
          ],
        ),
        body: Container(
          decoration: BoxDecoration(
            color: isDark ? AppTheme.darkBg : AppTheme.lightBg,
          ),
          child: Column(
            children: [
              // ── Messages Stream ───────────────────────────────────────────
              Expanded(
                child: messages.isEmpty
                    ? Center(
                        child: Text(
                          'Send a message to start the conversation',
                          style: TextStyle(
                            color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                            fontSize: 13,
                          ),
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
                                widget.appState.toggleReaction(widget.conversation.id, msg.id, emoji),
                            onReply: () {
                              setState(() => _replyingTo = msg);
                            },
                            onOpenCanvas: _openMiniAppModal,
                            onBuyProduct: _showBuyProductSheet,
                          );
                        },
                      ),
              ),

              // ── Bottom Input Bar ──────────────────────────────────────────
              ChatInputBar(
                inputController: _inputController,
                isTyping: _isTyping,
                replyingTo: _replyingTo,
                onCancelReply: () => setState(() => _replyingTo = null),
                onAttachmentPressed: _showAttachmentSheet,
                onVoiceNotePressed: _handleSendVoiceNote,
                onPingPressed: _handleSendPing,
                onSendPressed: _handleSend,
                onChanged: _handleTypingChanged,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
