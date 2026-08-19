import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';
import '../models/user.dart';
import '../models/conversation.dart';
import '../models/message.dart';
import '../models/story.dart';
import '../models/call.dart';
import '../models/channel.dart';
import '../models/product.dart';
import '../services/api_service.dart';
import '../services/storage_service.dart';
import '../services/websocket_service.dart';

class AppState extends ChangeNotifier {
  final WebSocketService wsService = WebSocketService();
  final Uuid _uuid = const Uuid();

  User? _currentUser;
  bool _isLoading = true;
  List<Conversation> _conversations = [];
  final Map<String, List<Message>> _messages = {};
  List<UserStories> _stories = [];
  List<CallRecord> _calls = [];
  List<Channel> _channels = [];
  List<Product> _products = [];
  final List<Product> _cart = [];
  CallRecord? _activeCall;

  User? get currentUser => _currentUser;
  bool get isLoading => _isLoading;
  bool get isAuthenticated => _currentUser != null;
  List<Conversation> get conversations => _conversations;
  List<UserStories> get stories => _stories;
  List<CallRecord> get calls => _calls;
  List<Channel> get channels => _channels;
  List<Product> get products => _products;
  List<Product> get cart => _cart;
  CallRecord? get activeCall => _activeCall;

  List<Message> getMessagesFor(String convId) {
    return _messages[convId] ?? [];
  }

  Future<void> init() async {
    _isLoading = true;
    notifyListeners();

    // Check cached auth
    _currentUser = await StorageService.getUser();
    if (_currentUser == null) {
      // Default to demo user for instant smooth preview
      _currentUser = User(
        id: 'u_me',
        displayName: 'Alexandre Sterling',
        email: 'alexandre@gochat.io',
        phone: '+1 (555) 234-5678',
        avatarUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
        statusText: 'Building microservices in Go & Flutter 🚀',
        isOnline: true,
      );
      await StorageService.saveUser(_currentUser!);
    }

    // Connect WebSocket
    wsService.addListener(_handleIncomingWebSocket);
    wsService.connect();

    // Load initial data
    await refreshData();

    _isLoading = false;
    notifyListeners();
  }

  Future<void> refreshData() async {
    _conversations = await ApiService.getConversations();
    _products = await ApiService.getProducts();
    _loadSampleStories();
    _loadSampleCalls();
    _loadSampleChannels();

    // Preload sample messages for conversations
    for (final c in _conversations) {
      _messages[c.id] = await ApiService.getMessages(c.id);
    }

    notifyListeners();
  }

  void _handleIncomingWebSocket(Map<String, dynamic> data) {
    final type = data['type'];
    if (type == 'chat_message' && data['payload'] != null) {
      final msg = Message.fromJson(data['payload'], currentUserId: _currentUser?.id ?? '');
      if (!_messages.containsKey(msg.conversationId)) {
        _messages[msg.conversationId] = [];
      }
      _messages[msg.conversationId]!.add(msg);
      notifyListeners();
    }
  }

  Future<void> login(String email, String password) async {
    _isLoading = true;
    notifyListeners();

    final res = await ApiService.login(email: email, password: password);
    if (res['token'] != null) {
      await StorageService.saveToken(res['token']);
    }
    if (res['user'] != null) {
      _currentUser = User.fromJson(res['user']);
      await StorageService.saveUser(_currentUser!);
    }

    await refreshData();
    _isLoading = false;
    notifyListeners();
  }

  Future<void> logout() async {
    await StorageService.clearAuth();
    _currentUser = null;
    wsService.disconnect();
    notifyListeners();
  }

  // Send Message
  void sendMessage(
    String convId,
    String content, {
    MessageType type = MessageType.text,
    PollData? pollData,
    String? mediaUrl,
    int? mediaDuration,
    String? replyToId,
    String? replyToText,
  }) {
    final msg = Message(
      id: _uuid.v4(),
      conversationId: convId,
      senderId: _currentUser?.id ?? 'u_me',
      senderName: _currentUser?.displayName ?? 'Me',
      content: content,
      type: type,
      status: MessageStatus.sent,
      pollData: pollData,
      mediaUrl: mediaUrl,
      mediaDuration: mediaDuration,
      replyToId: replyToId,
      replyToText: replyToText,
      createdAt: DateTime.now(),
      isMe: true,
    );

    if (!_messages.containsKey(convId)) {
      _messages[convId] = [];
    }
    _messages[convId]!.add(msg);

    // Update conversation last message
    final index = _conversations.indexWhere((c) => c.id == convId);
    if (index != -1) {
      _conversations[index] = _conversations[index].copyWith(
        lastMessage: msg,
        updatedAt: DateTime.now(),
      );
      // Move to top
      final updated = _conversations.removeAt(index);
      _conversations.insert(0, updated);
    }

    notifyListeners();

    // Broadcast over WebSocket if connected
    wsService.send({
      'type': 'send_message',
      'conversation_id': convId,
      'content': content,
      'message_type': type.name,
      if (pollData != null) 'poll_data': pollData.toJson(),
    });

    // Simulate smart bot/peer reply if chatting in a test thread
    _simulateAutoReply(convId, content);
  }

  void _simulateAutoReply(String convId, String userMessage) {
    if (userMessage.startsWith('@bot') || userMessage.toLowerCase().contains('hello') || userMessage.toLowerCase().contains('poll')) {
      Future.delayed(const Duration(milliseconds: 1200), () {
        final reply = Message(
          id: _uuid.v4(),
          conversationId: convId,
          senderId: 'bot_assistant',
          senderName: 'GoChat AI Assistant 🤖',
          content: '⚡ I received your message: "$userMessage". Live microservices & WebSocket signaling are running smoothly on Render!',
          type: MessageType.text,
          status: MessageStatus.read,
          createdAt: DateTime.now(),
          isMe: false,
        );
        _messages[convId]?.add(reply);
        notifyListeners();
      });
    }
  }

  // Poll Voting
  void votePoll(String convId, String messageId, String optionId) {
    final list = _messages[convId];
    if (list == null) return;

    final msgIdx = list.indexWhere((m) => m.id == messageId);
    if (msgIdx == -1 || list[msgIdx].pollData == null) return;

    final poll = list[msgIdx].pollData!;
    final myId = _currentUser?.id ?? 'u_me';

    final updatedOptions = poll.options.map((opt) {
      final hasVoted = opt.voterIds.contains(myId);
      if (opt.id == optionId) {
        if (!hasVoted) {
          return opt.copyWith(
            votes: opt.votes + 1,
            voterIds: [...opt.voterIds, myId],
          );
        }
      } else if (!poll.allowMultiple && hasVoted) {
        return opt.copyWith(
          votes: (opt.votes > 0) ? opt.votes - 1 : 0,
          voterIds: opt.voterIds.where((id) => id != myId).toList(),
        );
      }
      return opt;
    }).toList();

    final updatedPoll = PollData(
      id: poll.id,
      question: poll.question,
      options: updatedOptions,
      allowMultiple: poll.allowMultiple,
      isAnonymous: poll.isAnonymous,
      closesAt: poll.closesAt,
    );

    list[msgIdx] = list[msgIdx].copyWith(pollData: updatedPoll);
    notifyListeners();
  }

  // Reactions
  void addReaction(String convId, String messageId, String emoji) {
    final list = _messages[convId];
    if (list == null) return;

    final msgIdx = list.indexWhere((m) => m.id == messageId);
    if (msgIdx == -1) return;

    final myId = _currentUser?.id ?? 'u_me';
    final existing = Map<String, List<String>>.from(list[msgIdx].reactions);

    if (existing[emoji]?.contains(myId) == true) {
      existing[emoji]?.remove(myId);
      if (existing[emoji]?.isEmpty == true) {
        existing.remove(emoji);
      }
    } else {
      existing[emoji] = [...(existing[emoji] ?? []), myId];
    }

    list[msgIdx] = list[msgIdx].copyWith(reactions: existing);
    notifyListeners();
  }

  // Status Story addition
  void addStory(String mediaUrl, String caption, {String? backgroundColor}) {
    final newStory = StoryItem(
      id: _uuid.v4(),
      mediaUrl: mediaUrl,
      caption: caption,
      backgroundColor: backgroundColor,
      createdAt: DateTime.now(),
    );

    final meIdx = _stories.indexWhere((s) => s.isMe);
    if (meIdx != -1) {
      _stories[meIdx].stories.add(newStory);
    } else {
      _stories.insert(
        0,
        UserStories(
          userId: _currentUser?.id ?? 'u_me',
          userName: 'My Status',
          userAvatar: _currentUser?.avatarUrl ?? '',
          stories: [newStory],
          isMe: true,
        ),
      );
    }
    notifyListeners();
  }

  // Active Calls
  void startCall(String contactName, String avatar, CallType type) {
    _activeCall = CallRecord(
      id: _uuid.v4(),
      callerId: 'c_target',
      callerName: contactName,
      callerAvatar: avatar,
      type: type,
      direction: CallDirection.outgoing,
      timestamp: DateTime.now(),
    );
    notifyListeners();
  }

  void endCall() {
    if (_activeCall != null) {
      _calls.insert(0, _activeCall!);
      _activeCall = null;
      notifyListeners();
    }
  }

  // Cart
  void addToCart(Product p) {
    _cart.add(p);
    notifyListeners();
  }

  void removeFromCart(Product p) {
    _cart.removeWhere((item) => item.id == p.id);
    notifyListeners();
  }

  // Sample data setups
  void _loadSampleStories() {
    _stories = [
      UserStories(
        userId: 'u_me',
        userName: 'My Status',
        userAvatar: _currentUser?.avatarUrl ?? 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
        stories: [],
        isMe: true,
      ),
      UserStories(
        userId: 'u_sarah',
        userName: 'Sarah Jenkins',
        userAvatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=150',
        stories: [
          StoryItem(
            id: 's1',
            mediaUrl: 'https://images.unsplash.com/photo-1517841905240-472988babdf9?w=600',
            caption: 'Sunset in Santorini 🌅',
            createdAt: DateTime.now().subtract(const Duration(hours: 2)),
          ),
          StoryItem(
            id: 's2',
            mediaUrl: 'https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=600',
            caption: 'Crystal blue waters 🌊',
            createdAt: DateTime.now().subtract(const Duration(hours: 1)),
          ),
        ],
      ),
      UserStories(
        userId: 'u_marcus',
        userName: 'Marcus Vance',
        userAvatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=150',
        stories: [
          StoryItem(
            id: 's3',
            mediaUrl: 'https://images.unsplash.com/photo-1526778548025-fa2f459cd5c1?w=600',
            caption: 'Mountain trekking adventure! 🏔️',
            createdAt: DateTime.now().subtract(const Duration(hours: 5)),
          ),
        ],
      ),
    ];
  }

  void _loadSampleCalls() {
    _calls = [
      CallRecord(
        id: 'cl1',
        callerId: 'u2',
        callerName: 'Sarah Jenkins',
        callerAvatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=150',
        type: CallType.video,
        direction: CallDirection.incoming,
        timestamp: DateTime.now().subtract(const Duration(hours: 3)),
        durationSeconds: 342,
      ),
      CallRecord(
        id: 'cl2',
        callerId: 'u3',
        callerName: 'Alex Rivera',
        callerAvatar: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=150',
        type: CallType.audio,
        direction: CallDirection.missed,
        timestamp: DateTime.now().subtract(const Duration(hours: 8)),
      ),
      CallRecord(
        id: 'cl3',
        callerId: 'u5',
        callerName: 'Marcus Vance',
        callerAvatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=150',
        type: CallType.audio,
        direction: CallDirection.outgoing,
        timestamp: DateTime.now().subtract(const Duration(days: 1)),
        durationSeconds: 125,
      ),
    ];
  }

  void _loadSampleChannels() {
    _channels = [
      Channel(
        id: 'ch1',
        name: 'TechCrunch & AI Pulse',
        description: 'Daily breakthrough updates in AI, Microservices, and Cloud Native architecture.',
        avatarUrl: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=150',
        followersCount: 842000,
        isVerified: true,
        isFollowing: true,
        recentPosts: [
          ChannelPost(
            id: 'cp1',
            channelId: 'ch1',
            content: '🚀 Google & Open Source community release revolutionary on-device neural audio compression with 10x bandwidth reduction!',
            mediaUrl: 'https://images.unsplash.com/photo-1518770660439-4636190af475?w=600',
            viewsCount: 45200,
            forwardsCount: 1820,
            reactions: {'🔥': 1420, '👏': 890, '❤️': 610},
          ),
        ],
      ),
      Channel(
        id: 'ch2',
        name: 'Flutter & Dart Global Community',
        description: 'Official announcements, widget libraries, and design inspiration for mobile engineers.',
        avatarUrl: 'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=150',
        followersCount: 320000,
        isVerified: true,
        isFollowing: true,
      ),
      Channel(
        id: 'ch3',
        name: 'Bloomberg Markets Daily',
        description: 'Real-time financial markets, commodities, and currency movements.',
        avatarUrl: 'https://images.unsplash.com/photo-1590283603385-17ffb3a7f29f?w=150',
        followersCount: 1200000,
        isVerified: true,
        isFollowing: false,
      ),
    ];
  }
}
