import 'package:flutter/material.dart';
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

  List<Message> getMessagesFor(String convId) {
    return _messages[convId] ?? [];
  }

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

  Future<void> init() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    // Load persisted theme preference
    final savedTheme = await StorageService.getThemeMode();
    if (savedTheme == 'light') {
      _themeMode = ThemeMode.light;
    } else if (savedTheme == 'system') {
      _themeMode = ThemeMode.system;
    } else {
      _themeMode = ThemeMode.dark;
    }

    // Check cached auth
    final token = await StorageService.getToken();
    final cachedUser = await StorageService.getUser();

    if (token != null && token.isNotEmpty && cachedUser != null) {
      _currentUser = cachedUser;
      // Connect WebSocket to live backend
      wsService.addListener(_handleIncomingWebSocket);
      await wsService.connect();
      // Fetch live data
      await refreshData();
    }

    _isLoading = false;
    notifyListeners();
  }

  Future<void> refreshData() async {
    if (!isAuthenticated) return;

    try {
      final results = await Future.wait([
        ApiService.getConversations().catchError((_) => <Conversation>[]),
        ApiService.getStories().catchError((_) => <UserStories>[]),
        ApiService.getChannels().catchError((_) => <Channel>[]),
        ApiService.getCallHistory().catchError((_) => <CallRecord>[]),
        ApiService.getProducts().catchError((_) => <Product>[]),
      ]);

      _conversations = results[0] as List<Conversation>;
      _stories = results[1] as List<UserStories>;
      _channels = results[2] as List<Channel>;
      _calls = results[3] as List<CallRecord>;
      _products = results[4] as List<Product>;

      // Fetch messages for each active conversation
      for (final conv in _conversations) {
        try {
          final msgs = await ApiService.getMessages(conv.id);
          _messages[conv.id] = msgs;
        } catch (_) {}
      }
    } catch (e) {
      _errorMessage = e.toString();
    }

    notifyListeners();
  }

  void _handleIncomingWebSocket(Map<String, dynamic> data) {
    final type = data['type'];
    if (type == 'chat_message' || type == 'message') {
      final payload = data['payload'] ?? data['data'] ?? data;
      final msg = Message.fromJson(payload, currentUserId: _currentUser?.id ?? '');

      if (!_messages.containsKey(msg.conversationId)) {
        _messages[msg.conversationId] = [];
      }
      // Deduplicate by ID
      final existingIdx = _messages[msg.conversationId]!.indexWhere((m) => m.id == msg.id);
      if (existingIdx == -1) {
        _messages[msg.conversationId]!.add(msg);
      } else {
        _messages[msg.conversationId]![existingIdx] = msg;
      }

      // Update last message on conversation
      final convIdx = _conversations.indexWhere((c) => c.id == msg.conversationId);
      if (convIdx != -1) {
        _conversations[convIdx] = _conversations[convIdx].copyWith(
          lastMessage: msg,
          updatedAt: DateTime.now(),
        );
      }
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

      // Connect WebSocket
      wsService.addListener(_handleIncomingWebSocket);
      await wsService.connect();

      await refreshData();
    } catch (e) {
      _errorMessage = e.toString();
      rethrow;
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
      rethrow;
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
    wsService.disconnect();
    notifyListeners();
  }

  // ── Chat: Send Message ──────────────────────────────────────────────────────
  Future<void> sendMessage(
    String convId,
    String content, {
    MessageType type = MessageType.text,
    String? mediaUrl,
    int? mediaDuration,
    PollData? pollData,
  }) async {
    int typeInt = 0;
    if (type == MessageType.image) typeInt = 1;
    if (type == MessageType.video) typeInt = 2;
    if (type == MessageType.voice || type == MessageType.audio) typeInt = 3;
    if (type == MessageType.file) typeInt = 4;
    if (type == MessageType.poll) typeInt = 5;

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
      );

      if (!_messages.containsKey(convId)) {
        _messages[convId] = [];
      }
      _messages[convId]!.add(msgToAdd);

      // Update conversation in list
      final idx = _conversations.indexWhere((c) => c.id == convId);
      if (idx != -1) {
        _conversations[idx] = _conversations[idx].copyWith(
          lastMessage: msgToAdd,
          updatedAt: DateTime.now(),
        );
        final updated = _conversations.removeAt(idx);
        _conversations.insert(0, updated);
      }
      notifyListeners();
    } catch (e) {
      // Optimistic client addition with failed status
      final optimisticMsg = Message(
        id: 'opt_${DateTime.now().millisecondsSinceEpoch}',
        conversationId: convId,
        senderId: _currentUser?.id ?? 'u_me',
        senderName: _currentUser?.displayName ?? 'Me',
        content: content,
        type: type,
        status: MessageStatus.failed,
        mediaUrl: mediaUrl,
        mediaDuration: mediaDuration,
        pollData: pollData,
        isMe: true,
        createdAt: DateTime.now(),
      );
      if (!_messages.containsKey(convId)) {
        _messages[convId] = [];
      }
      _messages[convId]!.add(optimisticMsg);
      notifyListeners();
    }
  }

  // ── Chat: Add Reaction ──────────────────────────────────────────────────────
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

  // ── Chat: Create Conversation ───────────────────────────────────────────────
  Future<Conversation> createConversation(String name, List<String> memberIds, {bool isGroup = false}) async {
    final conv = await ApiService.createConversation(
      name: name,
      memberIds: memberIds,
      isGroup: isGroup,
    );
    _conversations.insert(0, conv);
    notifyListeners();
    return conv;
  }

  // ── Chat: Poll Voting ───────────────────────────────────────────────────────
  Future<void> votePoll(String convId, String messageId, String optionId) async {
    try {
      await ApiService.votePoll(pollId: messageId, optionId: optionId);
      // Refresh messages for conversation
      final msgs = await ApiService.getMessages(convId);
      _messages[convId] = msgs;
      notifyListeners();
    } catch (_) {}
  }

  // ── Stories: Add Story ──────────────────────────────────────────────────────
  Future<void> addStory(String mediaUrl, String caption, {String mediaType = 'image'}) async {
    await ApiService.postStory(mediaUrl: mediaUrl, caption: caption, mediaType: mediaType);
    _stories = await ApiService.getStories();
    notifyListeners();
  }

  // ── Calls: Start & End ──────────────────────────────────────────────────────
  void startCall(String contactName, String avatar, CallType type) {
    _activeCall = CallRecord(
      id: 'call_${DateTime.now().millisecondsSinceEpoch}',
      callerId: 'target',
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

  // ── Marketplace: Cart & Checkout ────────────────────────────────────────────
  void addToCart(Product p) {
    _cart.add(p);
    notifyListeners();
  }

  void removeFromCart(Product p) {
    _cart.removeWhere((item) => item.id == p.id);
    notifyListeners();
  }

  Future<void> checkout() async {
    if (_cart.isEmpty) return;
    final total = _cart.fold(0.0, (sum, p) => sum + p.price);
    final pIds = _cart.map((p) => p.id).toList();

    await ApiService.checkoutOrder(productIds: pIds, totalAmount: total);
    _cart.clear();
    notifyListeners();
  }
}
