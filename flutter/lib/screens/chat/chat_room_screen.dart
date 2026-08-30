import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/chat_theme.dart';
import '../../core/models/models.dart';
import '../../core/services/api_service.dart';
import '../../core/services/chat_theme_service.dart';
import '../../core/services/voice_recorder_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/chat_bubble.dart';
import '../../widgets/chat_doodle_painter.dart';
import '../../widgets/custom_avatar.dart';
import '../../widgets/mini_app_modal.dart';
import '../../widgets/primary_button.dart';
import '../calls/active_call_screen.dart';
import 'chat_attachment_sheet.dart';
import 'chat_input_bar.dart';
import 'chat_theme_customizer_sheet.dart';
import 'contact_profile_screen.dart';
import 'create_poll_dialog.dart';
import '../stories/story_viewer_screen.dart';

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

class _ChatRoomScreenState extends State<ChatRoomScreen>
    with SingleTickerProviderStateMixin {
  final TextEditingController _inputController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  bool _isTyping = false;
  Message? _replyingTo;
  Timer? _typingDebounceTimer;
  StreamSubscription<String>? _pingSubscription;
  ChatTheme _activeTheme = ChatTheme.defaultEmerald;

  // Screen Shake Animation for BBM PING!
  late AnimationController _shakeController;
  late Animation<double> _shakeAnimation;

  @override
  void initState() {
    super.initState();
    widget.appState.addListener(_onStateChange);

    // Load active custom wallpaper and theme for this chat
    ChatThemeService().getThemeForConversation(widget.conversation.id).then((t) {
      if (mounted) setState(() => _activeTheme = t);
    });

    _shakeController = AnimationController(
      duration: const Duration(milliseconds: 600),
      vsync: this,
    );
    _shakeAnimation = Tween<double>(
      begin: 0.0,
      end: 12.0,
    ).chain(CurveTween(curve: Curves.elasticIn)).animate(_shakeController);

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

  Future<void> _handleSendVoiceNote(VoiceRecordingResult result) async {
    // Stop the live recording indicator
    widget.appState.sendRecordingAudioEvent(widget.conversation.id, false);

    final messenger = ScaffoldMessenger.of(context);

    // Upload the recorded file to /api/v1/media/upload
    String? mediaUrl;
    try {
      mediaUrl = await ApiService.uploadMedia(
        result.filePath,
        mimeType: 'audio/mp4',
      );
    } catch (_) {}

    // Fallback to local file path for zero-delay playback
    mediaUrl ??= result.filePath;

    // Send the voice message with real URL and duration
    await widget.appState.sendMessage(
      widget.conversation.id,
      '🎙️ Voice Note (${VoiceRecorderService.formatDuration(result.durationSeconds)})',
      type: MessageType.voice,
      mediaUrl: mediaUrl,
      mediaDuration: result.durationSeconds,
    );

    messenger.showSnackBar(
      SnackBar(
        content: Text('🎙️ Voice note sent (${VoiceRecorderService.formatDuration(result.durationSeconds)})'),
        backgroundColor: AppTheme.primary,
        duration: const Duration(seconds: 2),
      ),
    );
    _scrollToBottom();
  }

  void _handleSendPing() {
    _triggerScreenShake();
    widget.appState.sendPing(widget.conversation.id);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('💥 GOCHAT PING! Nudge Sent'),
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
      backgroundColor: Theme.of(context).brightness == Brightness.dark
          ? AppTheme.darkSurface
          : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
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
                  child: Center(
                    child: Text('No products available in marketplace'),
                  ),
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
                            errorBuilder: (_, __, ___) =>
                                const Icon(Icons.shopping_bag),
                          ),
                        ),
                        title: Text(
                          prod.title,
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 14,
                          ),
                        ),
                        subtitle: Text(
                          '\$${prod.price.toStringAsFixed(2)}',
                          style: const TextStyle(
                            color: AppTheme.primary,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        trailing: const Icon(
                          Icons.send_rounded,
                          color: AppTheme.primary,
                          size: 20,
                        ),
                        onTap: () {
                          Navigator.pop(ctx);
                          widget.appState.sendProductCard(
                            widget.conversation.id,
                            prod,
                          );
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
      backgroundColor: Theme.of(context).brightness == Brightness.dark
          ? AppTheme.darkSurface
          : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
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
                      errorBuilder: (_, __, ___) =>
                          const Icon(Icons.shopping_bag, size: 40),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          product['title'] ?? 'Product',
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '\$${(product['price'] ?? 0).toString()}',
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: AppTheme.primary,
                          ),
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
          mediaUrl:
              'https://images.unsplash.com/photo-1518770660439-4636190af475?w=600',
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

  void _openThemeCustomizer() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => ChatThemeCustomizerSheet(
        conversationId: widget.conversation.id,
        conversationName: widget.conversation.title,
        currentTheme: _activeTheme,
        onThemeChanged: (newTheme) {
          setState(() => _activeTheme = newTheme);
        },
      ),
    );
  }

  Future<void> _startCall(CallType type) async {
    final recipientId = widget.conversation.memberIds.firstWhere(
      (id) => id.isNotEmpty && id != widget.appState.currentUser?.id,
      orElse: () => widget.conversation.id,
    );

    final call = await widget.appState.startCall(
      receiverId: recipientId,
      receiverName: widget.conversation.title,
      receiverAvatar: widget.conversation.avatarUrl,
      type: type,
    );

    if (!mounted) return;
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) =>
            ActiveCallScreen(callRecord: call, appState: widget.appState),
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

    final contactStories = widget.appState.getStoriesForConversation(widget.conversation);
    final hasStory = contactStories != null && contactStories.stories.isNotEmpty;
    final isRecordingAudio = widget.appState.isUserRecordingAudio(widget.conversation.id);
    final recordingText = widget.appState.getRecordingAudioText(widget.conversation.id);

    return AnimatedBuilder(
      animation: _shakeAnimation,
      builder: (context, child) {
        final dx = sin(_shakeController.value * pi * 8) * _shakeAnimation.value;
        return Transform.translate(offset: Offset(dx, 0), child: child);
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
                hasStory: hasStory,
                onTap: hasStory
                    ? () {
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => StoryViewerScreen(userStories: contactStories),
                          ),
                        );
                      }
                    : () {
                        ContactProfileScreen.open(
                          context,
                          conversation: widget.conversation,
                          appState: widget.appState,
                        );
                      },
              ),
              const SizedBox(width: 10),
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () {
                    ContactProfileScreen.open(
                      context,
                      conversation: widget.conversation,
                      appState: widget.appState,
                    );
                  },
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.conversation.title,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        isRecordingAudio
                            ? recordingText
                            : (isTypingLive
                                  ? typingText
                                  : (widget.conversation.isOnline
                                        ? (hasStory ? '🟢 Online · Has Status' : 'Online')
                                        : (hasStory ? '📸 Tap photo to view status' : 'tap here for info'))),
                        style: TextStyle(
                          fontSize: 11,
                          color: isRecordingAudio
                              ? AppTheme.primary
                              : (isTypingLive
                                    ? AppTheme.primary
                                    : (widget.conversation.isOnline
                                          ? AppTheme.onlineGreen
                                          : (hasStory ? AppTheme.primary : AppTheme.textMuted))),
                          fontWeight:
                              (isRecordingAudio || isTypingLive || widget.conversation.isOnline || hasStory)
                              ? FontWeight.bold
                              : FontWeight.normal,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
          actions: [
            IconButton(
              icon: const Icon(Icons.palette_outlined),
              tooltip: 'Wallpaper & Theme',
              onPressed: _openThemeCustomizer,
            ),
            IconButton(
              icon: const Icon(Icons.vibration_rounded, color: Colors.amber),
              tooltip: 'GOCHAT PING!',
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
                if (val == 'theme') {
                  _openThemeCustomizer();
                } else if (val == 'canvas') {
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
                const PopupMenuItem(
                  value: 'theme',
                  child: Row(
                    children: [
                      Icon(Icons.palette_outlined, size: 18, color: AppTheme.primary),
                      SizedBox(width: 10),
                      Text('Wallpaper & Theme'),
                    ],
                  ),
                ),
                const PopupMenuItem(
                  value: 'ping',
                  child: Text('💥 Send GOCHAT PING!'),
                ),
                const PopupMenuItem(
                  value: 'product',
                  child: Text('🛍️ Share Product'),
                ),
                const PopupMenuItem(
                  value: 'canvas',
                  child: Text('🎨 Open Shared Canvas'),
                ),
                const PopupMenuItem(
                  value: 'poll',
                  child: Text('📊 Create Live Poll'),
                ),
              ],
            ),
          ],
        ),
        body: Stack(
          children: [
            // ── Background Wallpaper / Gradient ──────────────────────────────
            if (_activeTheme.wallpaperImageUrl != null && _activeTheme.wallpaperImageUrl!.isNotEmpty)
              Positioned.fill(
                child: Image.network(
                  _activeTheme.wallpaperImageUrl!,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: _activeTheme.bgGradient,
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                    ),
                  ),
                ),
              )
            else
              Positioned.fill(
                child: Container(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: _activeTheme.bgGradient,
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                  ),
                ),
              ),

            // ── Doodle Pattern Overlay ────────────────────────────────────────
            if (_activeTheme.showDoodlePattern)
              Positioned.fill(
                child: CustomPaint(
                  painter: ChatDoodlePainter(
                    color: Colors.white,
                    opacity: _activeTheme.doodleOpacity,
                  ),
                ),
              ),

            // ── Foreground Chat Messages & Input ──────────────────────────────
            Column(
              children: [
                // ── Messages Stream ─────────────────────────────────────────
                Expanded(
                  child: messages.isEmpty
                      ? Center(
                          child: Text(
                            'Send a message to start the conversation',
                            style: TextStyle(
                              color: isDark
                                  ? AppTheme.textMuted
                                  : AppTheme.textMutedLight,
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
                              chatTheme: _activeTheme,
                              onVotePoll: (optId) => widget.appState.votePoll(
                                widget.conversation.id,
                                msg.id,
                                optId,
                              ),
                              onReact: (emoji) => widget.appState.toggleReaction(
                                widget.conversation.id,
                                msg.id,
                                emoji,
                              ),
                              onReply: () {
                                setState(() => _replyingTo = msg);
                              },
                              onOpenCanvas: _openMiniAppModal,
                              onBuyProduct: _showBuyProductSheet,
                            );
                          },
                        ),
                ),

              // ── Dynamic Input / Invitation Approval Bar ──────────────────
              Builder(
                builder: (context) {
                  final liveConv = widget.appState.conversations.firstWhere(
                    (c) => c.id == widget.conversation.id,
                    orElse: () => widget.conversation,
                  );

                  // 1. Sender awaiting recipient's acceptance
                  if (liveConv.invitationStatus ==
                      InvitationStatus.pendingOutgoing) {
                    return Container(
                      width: double.infinity,
                      margin: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 14,
                      ),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : Colors.white,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: AppTheme.primary.withValues(alpha: 0.35),
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: AppTheme.primary.withValues(alpha: 0.08),
                            blurRadius: 10,
                            offset: const Offset(0, 4),
                          ),
                        ],
                      ),
                      child: Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.all(8),
                            decoration: BoxDecoration(
                              color: AppTheme.primary.withValues(alpha: 0.15),
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(
                              Icons.hourglass_top_rounded,
                              color: AppTheme.primary,
                              size: 20,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Text(
                                  'Invitation Pending',
                                  style: TextStyle(
                                    fontWeight: FontWeight.bold,
                                    fontSize: 13,
                                    color: AppTheme.primary,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  'Waiting for ${liveConv.title} to accept your request before sending more messages.',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: isDark
                                        ? AppTheme.textMuted
                                        : AppTheme.textMutedLight,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    );
                  }

                  // 2. Recipient needs to accept/decline contact request
                  if (liveConv.invitationStatus ==
                      InvitationStatus.pendingIncoming) {
                    return Container(
                      width: double.infinity,
                      margin: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : Colors.white,
                        borderRadius: BorderRadius.circular(18),
                        border: Border.all(
                          color: Colors.amber.withValues(alpha: 0.5),
                          width: 1.5,
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.amber.withValues(alpha: 0.12),
                            blurRadius: 12,
                            offset: const Offset(0, 4),
                          ),
                        ],
                      ),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Row(
                            children: [
                              CustomAvatar(
                                imageUrl: liveConv.avatarUrl,
                                name: liveConv.title,
                                radius: 20,
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      liveConv.title,
                                      style: const TextStyle(
                                        fontWeight: FontWeight.bold,
                                        fontSize: 14,
                                      ),
                                    ),
                                    Text(
                                      'wants to connect with you via GOCHAT PIN',
                                      style: TextStyle(
                                        fontSize: 11,
                                        color: isDark
                                            ? AppTheme.textMuted
                                            : AppTheme.textMutedLight,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 14),
                          Row(
                            children: [
                              Expanded(
                                child: OutlinedButton(
                                  style: OutlinedButton.styleFrom(
                                    foregroundColor: Colors.redAccent,
                                    side: const BorderSide(
                                      color: Colors.redAccent,
                                      width: 1.2,
                                    ),
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(10),
                                    ),
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 10,
                                    ),
                                  ),
                                  onPressed: () {
                                    widget.appState.declineInvitation(
                                      liveConv.id,
                                    );
                                    Navigator.pop(context);
                                  },
                                  child: const Text('Decline'),
                                ),
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: ElevatedButton(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: AppTheme.primary,
                                    foregroundColor: Colors.white,
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(10),
                                    ),
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 10,
                                    ),
                                  ),
                                  onPressed: () {
                                    widget.appState.acceptInvitation(
                                      liveConv.id,
                                    );
                                  },
                                  child: const Text(
                                    'Accept & Chat',
                                    style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    );
                  }

                  // 3. Accepted Contact: Normal Chat Input Bar
                  return ChatInputBar(
                    inputController: _inputController,
                    isTyping: _isTyping,
                    replyingTo: _replyingTo,
                    onCancelReply: () => setState(() => _replyingTo = null),
                    onAttachmentPressed: _showAttachmentSheet,
                    onVoiceNoteRecorded: _handleSendVoiceNote,
                    onRecordingStateChanged: (isRec) {
                      widget.appState.sendRecordingAudioEvent(
                        widget.conversation.id,
                        isRec,
                      );
                    },
                    onPingPressed: _handleSendPing,
                    onSendPressed: _handleSend,
                    onChanged: _handleTypingChanged,
                  );
                },
              ),
            ],
          ),
        ],
      ),
    ),
  );
}
}
