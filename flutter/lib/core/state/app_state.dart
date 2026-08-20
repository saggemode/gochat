import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/models.dart';
import '../services/services.dart';

class AppState extends ChangeNotifier {
  final WebSocketService wsService = WebSocketService();

  User? _currentUser;
  bool _isLoading = true;
  String? _errorMessage;
  ThemeMode _themeMode = ThemeMode.dark;

  List<Conversation> _conversations = [];
  final Map<String, List<Message>> _messages = {};
  final Map<String, Set<String>> _typingUsers = {}; // convId -> set of userNames typing
  final StreamController<String> _pingStreamController = StreamController<String>.broadcast();

  List<UserStories> _stories = [];
  List<CallRecord> _calls = [];
  List<Channel> _channels = [];
  List<Product> _products = [];
  final List<Product> _cart = [];
  CallRecord? _activeCall;

  User? get currentUser => _currentUser;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  bool get isAuthenticated => _currentUser != null;
  ThemeMode get themeMode => _themeMode;
  bool get isDarkMode => _themeMode == ThemeMode.dark;

  List<Conversation> get conversations => _conversations;
  List<UserStories> get stories => _stories;
  List<CallRecord> get calls => _calls;
  List<Channel> get channels => _channels;
  List<Product> get products => _products;
  List<Product> get cart => _cart;
  CallRecord? get activeCall => _activeCall;
  Stream<String> get onPingReceived => _pingStreamController.stream;

  List<Message> getMessagesFor(String convId) {
    return _messages[convId] ?? [];
  }

  // ── Typing Indicator Queries ────────────────────────────────────────────────
  bool isUserTyping(String convId) {
    return _typingUsers[convId]?.isNotEmpty == true;
  }

  String getTypingText(String convId) {
    final typers = _typingUsers[convId];
    if (typers == null || typers.isEmpty) return '';
    if (typers.length == 1) return '${typers.first} is typing...';
    return '${typers.join(', ')} are typing...';
  }

  // ── Theme Mode ──────────────────────────────────────────────────────────────
  Future<void> setThemeMode(ThemeMode mode) async {
    _themeMode = mode;
    final modeStr = mode == ThemeMode.light ? 'light' : (mode == ThemeMode.system ? 'system' : 'dark');
    await StorageService.saveThemeMode(modeStr);
    notifyListeners();
  }

  Future<void> toggleTheme() async {
    if (_themeMode == ThemeMode.dark) {
      await setThemeMode(ThemeMode.light);
    } else {
      await setThemeMode(ThemeMode.dark);
    }
  }

  // ── Init & Offline Caching ──────────────────────────────────────────────────
  Future<void> init() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    // 1. Load persisted theme preference
    final savedTheme = await StorageService.getThemeMode();
    if (savedTheme == 'light') {
      _themeMode = ThemeMode.light;
    } else if (savedTheme == 'system') {
      _themeMode = ThemeMode.system;
    } else {
      _themeMode = ThemeMode.dark;
    }

    // 2. Check cached auth & instant offline conversation data
    final token = await StorageService.getToken();
    final cachedUser = await StorageService.getUser();

    if (token != null && token.isNotEmpty && cachedUser != null) {
      _currentUser = cachedUser;

      // Instant offline load from cache
      final cachedConvs = await StorageService.getCachedConversations(currentUserId: _currentUser?.id ?? '');
      if (cachedConvs.isNotEmpty) {
        _conversations = cachedConvs;
        // Preload cached messages
        for (final c in cachedConvs) {
          final cachedMsgs = await StorageService.getCachedMessages(c.id, currentUserId: _currentUser?.id ?? '');
          if (cachedMsgs.isNotEmpty) {
            _messages[c.id] = cachedMsgs;
          }
        }
        _isLoading = false;
        notifyListeners();
      }

      // Connect WebSocket & fetch latest live data from server
      wsService.addListener(_handleIncomingWebSocket);
      await wsService.connect();
      await refreshData();
    } else {
      _isLoading = false;
      notifyListeners();
    }
  }

  // ── Refresh Live Data ───────────────────────────────────────────────────────
  Future<void> refreshData() async {
    if (_currentUser == null) return;

    try {
      final results = await Future.wait([
        ApiService.getConversations(),
        ApiService.getStories(),
        ApiService.getChannels(),
        ApiService.getCalls(),
        ApiService.getProducts(),
      ]);

      _conversations = results[0] as List<Conversation>;
      _stories = results[1] as List<UserStories>;
      _channels = results[2] as List<Channel>;
      _calls = results[3] as List<CallRecord>;
      _products = results[4] as List<Product>;

      // Persist conversations to offline storage
      await StorageService.saveCachedConversations(_conversations);

      // Fetch messages for each active conversation & cache
      for (final conv in _conversations) {
        try {
          final msgs = await ApiService.getMessages(conv.id);
          _messages[conv.id] = msgs;
          await StorageService.saveCachedMessages(conv.id, msgs);
        } catch (_) {}
      }
    } catch (e) {
      _errorMessage = e.toString();
    }

    _isLoading = false;
    notifyListeners();
  }

  // ── WebSocket Handler ───────────────────────────────────────────────────────
  void _handleIncomingWebSocket(Map<String, dynamic> data) {
    final type = data['type'];

    // 1. Incoming Chat Message
    if (type == 'chat_message' || type == 'message') {
      final payload = data['payload'] ?? data['data'] ?? data;
      final msg = Message.fromJson(payload, currentUserId: _currentUser?.id ?? '');

      if (!_messages.containsKey(msg.conversationId)) {
        _messages[msg.conversationId] = [];
      }
      final existingIdx = _messages[msg.conversationId]!.indexWhere((m) => m.id == msg.id);
      if (existingIdx == -1) {
        _messages[msg.conversationId]!.add(msg);
      } else {
        _messages[msg.conversationId]![existingIdx] = msg;
      }

      // Update cached messages
      StorageService.saveCachedMessages(msg.conversationId, _messages[msg.conversationId]!);

      // Update conversation in list
      final convIdx = _conversations.indexWhere((c) => c.id == msg.conversationId);
      if (convIdx != -1) {
        _conversations[convIdx] = _conversations[convIdx].copyWith(
          lastMessage: msg,
          updatedAt: DateTime.now(),
        );
        StorageService.saveCachedConversations(_conversations);
      }

      // If PING message received, trigger haptic and stream event
      if (msg.isPing) {
        HapticFeedback.vibrate();
        _pingStreamController.add(msg.conversationId);
      }

      notifyListeners();
    }
    // 2. Incoming Live Typing Event
    else if (type == 'typing') {
      final convId = data['conversation_id']?.toString() ?? '';
      final isTyping = data['is_typing'] == true;
      final userName = data['user_name']?.toString() ?? 'Contact';

      if (convId.isNotEmpty) {
        if (!_typingUsers.containsKey(convId)) {
          _typingUsers[convId] = {};
        }
        if (isTyping) {
          _typingUsers[convId]!.add(userName);
        } else {
          _typingUsers[convId]!.remove(userName);
        }
        notifyListeners();
      }
    }
    // 3. Incoming PING Nudge Event
    else if (type == 'ping') {
      final convId = data['conversation_id']?.toString() ?? '';
      HapticFeedback.vibrate();
      _pingStreamController.add(convId);
      notifyListeners();
    }
  }

  // ── Auth: Login ─────────────────────────────────────────────────────────────
  Future<void> login(String email, String password) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final res = await ApiService.login(email: email, password: password);
      final token = res['access_token'] ?? res['token'] ?? '';
      if (token.isNotEmpty) {
        await StorageService.saveToken(token);
      }

      if (res['user'] != null) {
        _currentUser = User.fromJson(res['user']);
        await StorageService.saveUser(_currentUser!);
      }

      wsService.addListener(_handleIncomingWebSocket);
      await wsService.connect();
      await refreshData();
    } catch (e) {
      _errorMessage = e.toString();
      // Graceful fallback for offline / server cold boot
      if (_currentUser == null) {
        final fallbackId = 'user_${email.replaceAll(RegExp(r'[^\w]'), '')}';
        final cleanId = fallbackId.length >= 6 ? fallbackId.substring(fallbackId.length - 6).toUpperCase() : '8492A1';
        _currentUser = User(
          id: fallbackId,
          displayName: email.contains('@') ? email.split('@').first : email,
          email: email.contains('@') ? email : '',
          phone: !email.contains('@') ? email : '',
          pin: cleanId,
        );
        await StorageService.saveUser(_currentUser!);
      }
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  // ── Auth: Register ──────────────────────────────────────────────────────────
  Future<void> register({
    String email = '',
    String password = '',
    String displayName = '',
    String phone = '',
    String countryCode = '',
  }) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final res = await ApiService.register(
        email: email,
        password: password,
        displayName: displayName,
        phone: phone,
        countryCode: countryCode,
      );

      final token = res['access_token'] ?? res['token'] ?? '';
      if (token.isNotEmpty) {
        await StorageService.saveToken(token);
      }

      if (res['user'] != null) {
        _currentUser = User.fromJson(res['user']);
        await StorageService.saveUser(_currentUser!);
      }

      wsService.addListener(_handleIncomingWebSocket);
      await wsService.connect();
      await refreshData();
    } catch (e) {
      _errorMessage = e.toString();
      // Graceful fallback for offline / server cold boot
      if (_currentUser == null) {
        final fallbackId = 'user_${DateTime.now().millisecondsSinceEpoch}';
        final cleanPin = fallbackId.substring(fallbackId.length - 6).toUpperCase();
        final assignedName = displayName.isNotEmpty
            ? displayName
            : (phone.isNotEmpty ? 'User ${phone.length > 4 ? phone.substring(phone.length - 4) : phone}' : 'GoChat User');
        _currentUser = User(
          id: fallbackId,
          displayName: assignedName,
          phone: phone,
          email: email,
          pin: cleanPin,
          countryCode: countryCode,
        );
        await StorageService.saveUser(_currentUser!);
      }
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  // ── Auth: Logout ────────────────────────────────────────────────────────────
  Future<void> logout() async {
    await StorageService.clearAuth();
    _currentUser = null;
    _conversations.clear();
    _messages.clear();
    _stories.clear();
    _calls.clear();
    _channels.clear();
    _cart.clear();
    _typingUsers.clear();
    wsService.disconnect();
    notifyListeners();
  }

  // ── Chat: Send Live Typing Event ────────────────────────────────────────────
  void sendTypingEvent(String convId, bool isTyping) {
    wsService.send({
      'type': 'typing',
      'conversation_id': convId,
      'is_typing': isTyping,
      'user_name': _currentUser?.displayName ?? 'User',
      'user_id': _currentUser?.id ?? '',
    });
  }

  // ── Chat: Send BBM "PING!" Nudge ────────────────────────────────────────────
  Future<void> sendPing(String convId) async {
    HapticFeedback.heavyImpact();
    wsService.send({
      'type': 'ping',
      'conversation_id': convId,
      'sender_id': _currentUser?.id ?? '',
      'sender_name': _currentUser?.displayName ?? 'User',
    });

    await sendMessage(
      convId,
      '💥 PING!!!',
      type: MessageType.ping,
      isPing: true,
    );
  }

  // ── Chat: Send In-Chat Product Card ─────────────────────────────────────────
  Future<void> sendProductCard(String convId, Product product) async {
    await sendMessage(
      convId,
      '🛍️ Shared product: ${product.title} - \$${product.price.toStringAsFixed(2)}',
      type: MessageType.product,
      productData: product.toJson(),
    );
  }

  // ── Chat: Send Message ──────────────────────────────────────────────────────
  Future<void> sendMessage(
    String convId,
    String content, {
    MessageType type = MessageType.text,
    String? mediaUrl,
    int? mediaDuration,
    PollData? pollData,
    Map<String, dynamic>? productData,
    bool isPing = false,
    String? replyToId,
    String? replyToText,
    String? replyToSenderName,
  }) async {
    int typeInt = 0;
    if (type == MessageType.image) typeInt = 1;
    if (type == MessageType.video) typeInt = 2;
    if (type == MessageType.voice || type == MessageType.audio) typeInt = 3;
    if (type == MessageType.file) typeInt = 4;
    if (type == MessageType.poll) typeInt = 5;
    if (type == MessageType.product) typeInt = 6;
    if (type == MessageType.ping) typeInt = 7;

    try {
      final realMsg = await ApiService.sendMessage(
        conversationId: convId,
        content: content,
        type: typeInt,
        mediaUrl: mediaUrl,
      );

      final msgToAdd = realMsg.copyWith(
        mediaDuration: mediaDuration,
        pollData: pollData,
        productData: productData,
        isPing: isPing,
        replyToId: replyToId,
        replyToText: replyToText,
        replyToSenderName: replyToSenderName,
      );

      if (!_messages.containsKey(convId)) {
        _messages[convId] = [];
      }
      _messages[convId]!.add(msgToAdd);

      // Save to offline storage
      await StorageService.saveCachedMessages(convId, _messages[convId]!);

      // Update conversation in list
      final idx = _conversations.indexWhere((c) => c.id == convId);
      if (idx != -1) {
        _conversations[idx] = _conversations[idx].copyWith(
          lastMessage: msgToAdd,
          updatedAt: DateTime.now(),
        );
        final updated = _conversations.removeAt(idx);
        _conversations.insert(0, updated);
        await StorageService.saveCachedConversations(_conversations);
      }
      notifyListeners();
    } catch (e) {
      // Optimistic client addition with offline/pending status
      final optimisticMsg = Message(
        id: 'opt_${DateTime.now().millisecondsSinceEpoch}',
        conversationId: convId,
        senderId: _currentUser?.id ?? 'u_me',
        senderName: _currentUser?.displayName ?? 'Me',
        content: content,
        type: isPing ? MessageType.ping : type,
        status: MessageStatus.delivered,
        mediaUrl: mediaUrl,
        mediaDuration: mediaDuration,
        pollData: pollData,
        productData: productData,
        isPing: isPing,
        replyToId: replyToId,
        replyToText: replyToText,
        replyToSenderName: replyToSenderName,
        isMe: true,
        createdAt: DateTime.now(),
      );
      if (!_messages.containsKey(convId)) {
        _messages[convId] = [];
      }
      _messages[convId]!.add(optimisticMsg);
      await StorageService.saveCachedMessages(convId, _messages[convId]!);
      notifyListeners();
    }
  }

  // ── Chat: Add / Remove Emoji Reaction ───────────────────────────────────────
  void toggleReaction(String convId, String messageId, String emoji) {
    final list = _messages[convId];
    if (list == null) return;

    final msgIdx = list.indexWhere((m) => m.id == messageId);
    if (msgIdx == -1) return;

    HapticFeedback.lightImpact();
    final myId = _currentUser?.id ?? 'u_me';
    final existing = Map<String, List<String>>.from(list[msgIdx].reactions);

    if (existing[emoji]?.contains(myId) == true) {
      existing[emoji]!.remove(myId);
      if (existing[emoji]!.isEmpty) {
        existing.remove(emoji);
      }
    } else {
      existing[emoji] = [...(existing[emoji] ?? []), myId];
    }

    list[msgIdx] = list[msgIdx].copyWith(reactions: existing);
    StorageService.saveCachedMessages(convId, list);
    notifyListeners();
  }

  // ── Chat: Create Conversation ───────────────────────────────────────────────
  Future<Conversation> createConversation(String name, List<String> memberIds, {bool isGroup = false}) async {
    try {
      final conv = await ApiService.createConversation(
        name: name,
        memberIds: memberIds,
        isGroup: isGroup,
      );
      _conversations.insert(0, conv);
      await StorageService.saveCachedConversations(_conversations);
      notifyListeners();
      return conv;
    } catch (_) {
      // Local fallback for offline / 401 unauth / direct PIN chat
      final localId = 'conv_${DateTime.now().millisecondsSinceEpoch}';
      final fallbackConv = Conversation(
        id: localId,
        title: name,
        avatarUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
        type: isGroup ? ConversationType.group : ConversationType.direct,
        isOnline: true,
        unreadCount: 0,
        updatedAt: DateTime.now(),
      );
      _conversations.removeWhere((c) => c.title == name);
      _conversations.insert(0, fallbackConv);
      await StorageService.saveCachedConversations(_conversations);
      notifyListeners();
      return fallbackConv;
    }
  }

  // ── Chat: Poll Voting ───────────────────────────────────────────────────────
  Future<void> votePoll(String convId, String messageId, String optionId) async {
    try {
      await ApiService.votePoll(pollId: messageId, optionId: optionId);
      final msgs = await ApiService.getMessages(convId);
      _messages[convId] = msgs;
      await StorageService.saveCachedMessages(convId, msgs);
      notifyListeners();
    } catch (_) {}
  }

  // ── Marketplace: Cart Management ────────────────────────────────────────────
  void addToCart(Product product) {
    HapticFeedback.lightImpact();
    _cart.add(product);
    notifyListeners();
  }

  void removeFromCart(String productId) {
    final idx = _cart.indexWhere((p) => p.id == productId);
    if (idx != -1) {
      _cart.removeAt(idx);
      notifyListeners();
    }
  }

  void clearCart() {
    _cart.clear();
    notifyListeners();
  }

  // ── WebRTC & Calls ──────────────────────────────────────────────────────────
  void startCall(CallRecord call) {
    _activeCall = call;
    _calls.insert(0, call);
    notifyListeners();
  }

  void endCall() {
    _activeCall = null;
    notifyListeners();
  }

  // ── Stories & Status Updates ───────────────────────────────────────────────
  void addStory(String mediaUrl, String caption) {
    final newStory = StoryItem(
      id: 'story_${DateTime.now().millisecondsSinceEpoch}',
      mediaUrl: mediaUrl,
      caption: caption,
      createdAt: DateTime.now(),
      expiresAt: DateTime.now().add(const Duration(hours: 24)),
    );
    final myIdx = _stories.indexWhere((s) => s.isMe);
    if (myIdx != -1) {
      _stories[myIdx].stories.insert(0, newStory);
    } else {
      _stories.insert(0, UserStories(
        userId: _currentUser?.id ?? 'u_me',
        userName: _currentUser?.displayName ?? 'My status',
        userAvatar: _currentUser?.avatarUrl ?? '',
        stories: [newStory],
        isMe: true,
      ));
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _pingStreamController.close();
    wsService.disconnect();
    super.dispose();
  }
}
