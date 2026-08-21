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
    var token = await StorageService.getToken();
    final cachedUser = await StorageService.getUser();

    if (cachedUser != null || (token != null && token.isNotEmpty)) {
      if (cachedUser != null) {
        _currentUser = cachedUser;
      }

      // Check if token is invalid or old synthetic session
      if (!ApiService.isValidJwt(token)) {
        // Auto-reauthenticate in background to get real JWT from backend
        token = await ApiService.ensureValidToken();
      }

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
      }

      _isLoading = false;
      notifyListeners();

      // Connect WebSocket & fetch latest live data from server if authenticated
      if (token != null && ApiService.isValidJwt(token)) {
        wsService.addListener(_handleIncomingWebSocket);
        await wsService.connect();
        await refreshData();
      }
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
      if (_conversations.isNotEmpty) {
        await StorageService.saveCachedConversations(_conversations);
      } else {
        final cached = await StorageService.getCachedConversations(currentUserId: _currentUser?.id ?? '');
        if (cached.isNotEmpty) {
          _conversations = cached;
        }
      }

      // Fetch messages for each active conversation & cache
      for (final conv in _conversations) {
        try {
          final msgs = await ApiService.getMessages(conv.id);
          if (msgs.isNotEmpty) {
            _messages[conv.id] = msgs;
            await StorageService.saveCachedMessages(conv.id, msgs);
          } else {
            final cached = await StorageService.getCachedMessages(conv.id, currentUserId: _currentUser?.id ?? '');
            if (cached.isNotEmpty) {
              _messages[conv.id] = cached;
            }
          }
        } catch (_) {
          final cached = await StorageService.getCachedMessages(conv.id, currentUserId: _currentUser?.id ?? '');
          if (cached.isNotEmpty) {
            _messages[conv.id] = cached;
          }
        }
      }
    } catch (e) {
      // Offline fallback: load cached conversations & messages
      final cachedConvs = await StorageService.getCachedConversations(currentUserId: _currentUser?.id ?? '');
      if (cachedConvs.isNotEmpty) {
        _conversations = cachedConvs;
        for (final conv in _conversations) {
          final cachedMsgs = await StorageService.getCachedMessages(conv.id, currentUserId: _currentUser?.id ?? '');
          if (cachedMsgs.isNotEmpty) {
            _messages[conv.id] = cachedMsgs;
          }
        }
      }

      if (e.toString().contains('401')) {
        // Token expired or invalid — auto-renew credentials
        final newToken = await ApiService.ensureValidToken();
        if (newToken != null && ApiService.isValidJwt(newToken)) {
          await wsService.connect();
          try {
            final retry = await Future.wait([
              ApiService.getConversations(),
              ApiService.getStories(),
              ApiService.getChannels(),
              ApiService.getCalls(),
              ApiService.getProducts(),
            ]);
            _conversations = retry[0] as List<Conversation>;
            _stories = retry[1] as List<UserStories>;
            _channels = retry[2] as List<Channel>;
            _calls = retry[3] as List<CallRecord>;
            _products = retry[4] as List<Product>;
          } catch (_) {}
        }
      } else {
        _errorMessage = e.toString();
      }
    }

    _isLoading = false;
    notifyListeners();
  }

  // ── WebSocket Handler ───────────────────────────────────────────────────────
  void _handleIncomingWebSocket(Map<String, dynamic> data) {
    final eventType = (data['event_type'] ?? data['eventType'] ?? data['event'] ?? data['type'] ?? '').toString();
    final rawMsg = data['message'] ?? data['Message'] ?? data['payload'] ?? data['data'];

    // 1. Incoming Chat Message Event (EVENT_NEW_MESSAGE = 0 or 1, or explicit message payload)
    final isMessageEvent = eventType == '0' ||
        eventType == '1' ||
        eventType == 'EVENT_NEW_MESSAGE' ||
        eventType == 'new_message' ||
        eventType == 'chat_message' ||
        eventType == 'message' ||
        (rawMsg is Map<String, dynamic> && (rawMsg.containsKey('content') || rawMsg.containsKey('conversation_id') || rawMsg.containsKey('conversationId')));

    if (isMessageEvent) {
      final Map<String, dynamic> payload = (rawMsg is Map<String, dynamic>)
          ? rawMsg
          : data;
      final msg = Message.fromJson(payload, currentUserId: _currentUser?.id ?? '');

      if (msg.conversationId.isNotEmpty) {
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

        // Update or insert conversation in list
        final convIdx = _conversations.indexWhere((c) => c.id == msg.conversationId);
        if (convIdx != -1) {
          final currentTitle = _conversations[convIdx].title;
          final shouldUpdateTitle = (currentTitle.startsWith('User_') ||
                  currentTitle.startsWith('GOCHAT User') ||
                  currentTitle == 'Contact' ||
                  currentTitle.startsWith('BBM User')) &&
              msg.senderName.isNotEmpty &&
              msg.senderName != 'Me' &&
              !msg.senderName.startsWith('User_');

          final updated = _conversations[convIdx].copyWith(
            title: shouldUpdateTitle ? msg.senderName : currentTitle,
            lastMessage: msg,
            updatedAt: DateTime.now(),
          );
          _conversations.removeAt(convIdx);
          _conversations.insert(0, updated);
        } else {
          // New conversation created by sender - add to receiver's list as incoming invitation if not from me
          final isFromMe = msg.isMe || (_currentUser?.id.isNotEmpty == true && msg.senderId == _currentUser?.id);
          final newConv = Conversation(
            id: msg.conversationId,
            title: msg.senderName.isNotEmpty && msg.senderName != 'Me' ? msg.senderName : 'Contact',
            lastMessage: msg,
            type: ConversationType.direct,
            invitationStatus: isFromMe ? InvitationStatus.pendingOutgoing : InvitationStatus.pendingIncoming,
            invitationSenderId: msg.senderId,
            updatedAt: DateTime.now(),
          );
          _conversations.insert(0, newConv);
          // Sync full conversation details from server in background
          ApiService.getConversations().then((convs) {
            if (convs.isNotEmpty) {
              _conversations = convs;
              StorageService.saveCachedConversations(_conversations);
              notifyListeners();
            }
          }).catchError((_) {});
        }
        StorageService.saveCachedConversations(_conversations);

        // If PING message received, trigger haptic and stream event
        if (msg.isPing) {
          HapticFeedback.vibrate();
          _pingStreamController.add(msg.conversationId);
        }

        notifyListeners();
      }
    }
    // 2. Incoming Profile Update Event
    else if (eventType == 'user_profile_updated' || eventType == 'EVENT_USER_PROFILE_UPDATED') {
      final userData = data['user'] ?? data['payload'] ?? data['data'];
      if (userData is Map<String, dynamic>) {
        final userId = userData['id']?.toString() ?? '';
        final newName = userData['display_name']?.toString() ?? '';
        final newAvatar = userData['avatar_url']?.toString() ?? '';

        if (userId.isNotEmpty && newName.isNotEmpty) {
          bool changed = false;
          for (int i = 0; i < _conversations.length; i++) {
            final conv = _conversations[i];
            if (conv.memberIds.contains(userId) || conv.id == userId || conv.partnerPin == userData['pin']) {
              _conversations[i] = conv.copyWith(
                title: newName,
                avatarUrl: newAvatar.isNotEmpty ? newAvatar : conv.avatarUrl,
              );
              changed = true;
            }
          }
          if (changed) {
            StorageService.saveCachedConversations(_conversations);
            notifyListeners();
          }
        }
      }
    }
    // 3. Incoming Invitation Accepted Event
    else if (eventType == 'invitation_accepted' || data['type'] == 'invitation_accepted') {
      final convId = (data['conversation_id'] ?? data['conversationId'] ?? data['conv_id'] ?? '').toString();
      final idx = _conversations.indexWhere((c) => c.id == convId);
      if (idx != -1) {
        _conversations[idx] = _conversations[idx].copyWith(
          invitationStatus: InvitationStatus.accepted,
        );
        StorageService.saveCachedConversations(_conversations);
        notifyListeners();
      }
    }
    // 3. Incoming Live Typing Event
    else if (eventType == '6' || eventType == 'EVENT_TYPING' || eventType == 'typing') {
      final convId = (data['conversation_id'] ?? data['conversationId'] ?? data['conv_id'] ?? '').toString();
      final isTyping = data['is_typing'] == true || data['isTyping'] == true;
      final userName = (data['user_name'] ?? data['userName'] ?? data['actor_id'] ?? 'Contact').toString();

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
    // 4. Incoming PING Nudge Event
    else if (eventType == 'ping' || eventType == 'EVENT_PINNED' || data['is_ping'] == true) {
      final convId = (data['conversation_id'] ?? data['conversationId'] ?? data['conv_id'] ?? '').toString();
      if (convId.isNotEmpty) {
        HapticFeedback.vibrate();
        _pingStreamController.add(convId);
        notifyListeners();
      }
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

      // 1. Immediately hydrate local conversations and messages for instant rendering
      final localConvs = await StorageService.getCachedConversations(currentUserId: _currentUser?.id ?? '');
      if (localConvs.isNotEmpty) {
        _conversations = localConvs;
        for (final conv in _conversations) {
          final cachedMsgs = await StorageService.getCachedMessages(conv.id, currentUserId: _currentUser?.id ?? '');
          if (cachedMsgs.isNotEmpty) {
            _messages[conv.id] = cachedMsgs;
          }
        }
      }
      notifyListeners();

      wsService.addListener(_handleIncomingWebSocket);
      await wsService.connect();
      await refreshData();
    } catch (e) {
      _errorMessage = e.toString();
      // If offline, check if matching user is already cached
      final cachedUser = await StorageService.getUser();
      if (cachedUser != null &&
          (cachedUser.email == email ||
           cachedUser.phone == email ||
           cachedUser.pin == email.toUpperCase())) {
        _currentUser = cachedUser;
      } else {
        rethrow;
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

  // ── Auth: Update Profile ───────────────────────────────────────────────────
  Future<void> updateProfile({
    String? displayName,
    String? statusText,
    String? avatarUrl,
  }) async {
    if (_currentUser == null) return;

    _currentUser = _currentUser!.copyWith(
      displayName: displayName ?? _currentUser!.displayName,
      statusText: statusText ?? _currentUser!.statusText,
      avatarUrl: avatarUrl ?? _currentUser!.avatarUrl,
    );

    await StorageService.saveUser(_currentUser!);
    notifyListeners();

    try {
      final updatedUser = await ApiService.updateProfile(
        displayName: displayName,
        statusText: statusText,
        avatarUrl: avatarUrl,
      );
      if (updatedUser != null) {
        _currentUser = updatedUser;
        await StorageService.saveUser(_currentUser!);
        notifyListeners();
      }
    } catch (_) {}

    // Broadcast profile update to connected contacts
    wsService.send({
      'type': 'user_profile_updated',
      'event_type': 'EVENT_USER_PROFILE_UPDATED',
      'user': {
        'id': _currentUser!.id,
        'display_name': _currentUser!.displayName,
        'status_text': _currentUser!.statusText,
        'avatar_url': _currentUser!.avatarUrl,
        'pin': _currentUser!.pin,
      },
    });
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
      // Broadcast over WebSocket to all connected peers
      wsService.send({
        'type': 'new_message',
        'event_type': 'EVENT_NEW_MESSAGE',
        'conversation_id': convId,
        'message': {
          'id': msgToAdd.id,
          'conversation_id': convId,
          'sender_id': _currentUser?.id ?? 'u_me',
          'sender_name': _currentUser?.displayName ?? 'Me',
          'content': content,
          'type': typeInt,
          'media_url': mediaUrl,
          'media_duration': mediaDuration,
          'is_ping': isPing,
          'poll_data': pollData?.toJson(),
          'product_data': productData,
          'reply_to_id': replyToId,
          'reply_to_text': replyToText,
          'reply_to_sender_name': replyToSenderName,
          'created_at': msgToAdd.createdAt.toIso8601String(),
        },
      });

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

      // Broadcast over WebSocket
      wsService.send({
        'type': 'new_message',
        'event_type': 'EVENT_NEW_MESSAGE',
        'conversation_id': convId,
        'message': {
          'id': optimisticMsg.id,
          'conversation_id': convId,
          'sender_id': _currentUser?.id ?? 'u_me',
          'sender_name': _currentUser?.displayName ?? 'Me',
          'content': content,
          'type': typeInt,
          'media_url': mediaUrl,
          'media_duration': mediaDuration,
          'is_ping': isPing,
          'poll_data': pollData?.toJson(),
          'product_data': productData,
          'reply_to_id': replyToId,
          'reply_to_text': replyToText,
          'reply_to_sender_name': replyToSenderName,
          'created_at': optimisticMsg.createdAt.toIso8601String(),
        },
      });

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
  Future<Conversation> createConversation(
    String name,
    List<String> memberIds, {
    bool isGroup = false,
    InvitationStatus invitationStatus = InvitationStatus.accepted,
    String? partnerPin,
  }) async {
    try {
      final conv = await ApiService.createConversation(
        name: name,
        memberIds: memberIds,
        isGroup: isGroup,
      );
      final withInvitation = conv.copyWith(
        invitationStatus: invitationStatus,
        partnerPin: partnerPin,
        invitationSenderId: _currentUser?.id,
      );
      _conversations.insert(0, withInvitation);
      await StorageService.saveCachedConversations(_conversations);
      notifyListeners();
      return withInvitation;
    } catch (_) {
      // Local fallback for offline / 401 unauth / direct PIN chat
      final localId = 'conv_${DateTime.now().millisecondsSinceEpoch}';
      final fallbackConv = Conversation(
        id: localId,
        title: name,
        avatarUrl: '',
        type: isGroup ? ConversationType.group : ConversationType.direct,
        invitationStatus: invitationStatus,
        partnerPin: partnerPin,
        invitationSenderId: _currentUser?.id,
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

  // ── Chat: Accept Contact Invitation ─────────────────────────────────────────
  Future<void> acceptInvitation(String convId) async {
    final idx = _conversations.indexWhere((c) => c.id == convId);
    if (idx != -1) {
      _conversations[idx] = _conversations[idx].copyWith(
        invitationStatus: InvitationStatus.accepted,
      );
      await StorageService.saveCachedConversations(_conversations);

      // Broadcast acceptance over WebSocket so sender unblocks immediately
      wsService.send({
        'type': 'invitation_accepted',
        'conversation_id': convId,
        'user_id': _currentUser?.id ?? '',
        'user_name': _currentUser?.displayName ?? 'Me',
      });

      // Send greeting system message
      await sendMessage(
        convId,
        '🤝 Contact invitation accepted! You can now chat.',
      );

      notifyListeners();
    }
  }

  // ── Chat: Decline Contact Invitation ─────────────────────────────────────────
  Future<void> declineInvitation(String convId) async {
    final idx = _conversations.indexWhere((c) => c.id == convId);
    if (idx != -1) {
      _conversations[idx] = _conversations[idx].copyWith(
        invitationStatus: InvitationStatus.declined,
      );
      _conversations.removeAt(idx);
      await StorageService.saveCachedConversations(_conversations);

      wsService.send({
        'type': 'invitation_declined',
        'conversation_id': convId,
        'user_id': _currentUser?.id ?? '',
      });

      notifyListeners();
    }
  }

  // ── Auth: Lookup User by BBM PIN ────────────────────────────────────────────
  Future<User?> lookupUserByPin(String pin) async {
    final cleanPin = pin.trim().toUpperCase();
    if (cleanPin.length < 4) return null;

    // 1. Try remote API lookup against backend database
    final remoteUser = await ApiService.lookupUserByPin(cleanPin);
    if (remoteUser != null) return remoteUser;

    // 2. Check local conversations
    for (final conv in _conversations) {
      if (conv.title.toUpperCase().contains(cleanPin) || conv.id.toUpperCase().contains(cleanPin)) {
        return User(
          id: conv.id,
          displayName: conv.title,
          avatarUrl: conv.avatarUrl,
          pin: cleanPin,
          statusText: 'Available on GoChat',
          isOnline: conv.isOnline,
        );
      }
    }

    return null;
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
