import 'dart:async';
import 'dart:convert';
import 'dart:io';
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
  final Map<String, Set<String>> _typingUsers =
      {}; // convId -> set of userNames typing
  final Map<String, Set<String>> _recordingUsers =
      {}; // convId -> set of userNames recording audio
  final StreamController<String> _pingStreamController =
      StreamController<String>.broadcast();
  final StreamController<CallRecord> _incomingCallController =
      StreamController<CallRecord>.broadcast();
  final StreamController<Map<String, dynamic>> _callSignalingController =
      StreamController<Map<String, dynamic>>.broadcast();

  List<UserStories> _stories = [];
  List<CallRecord> _calls = [];
  List<Channel> _channels = [];
  List<Product> _products = [];
  final List<Product> _cart = [];
  StoreProfile? _myStore;
  List<MarketplaceOrder> _sellerOrders = [];
  List<StoreCoupon> _storeCoupons = [];
  CallRecord? _activeCall;
  List<SyncedContact> _syncedContacts = [];
  bool _isSyncingContacts = false;

  User? get currentUser => _currentUser;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  bool get isAuthenticated => _currentUser != null;
  ThemeMode get themeMode => _themeMode;
  bool get isDarkMode => _themeMode == ThemeMode.dark;

  List<Conversation> get conversations => _conversations;
  List<SyncedContact> get syncedContacts => _syncedContacts;
  List<SyncedContact> get registeredContacts =>
      _syncedContacts.where((c) => c.isRegistered).toList();
  List<SyncedContact> get inviteContacts =>
      _syncedContacts.where((c) => !c.isRegistered).toList();
  bool get isSyncingContacts => _isSyncingContacts;
  List<UserStories> get stories => _stories;
  List<CallRecord> get calls => _calls;
  List<Channel> get channels => _channels;
  List<Product> get products => _products;
  List<Product> get cart => _cart;
  StoreProfile? get myStore => _myStore;
  bool get hasStore => _myStore != null;
  List<MarketplaceOrder> get sellerOrders => _sellerOrders;
  List<StoreCoupon> get storeCoupons => _storeCoupons;
  CallRecord? get activeCall => _activeCall;
  Stream<String> get onPingReceived => _pingStreamController.stream;
  Stream<CallRecord> get onIncomingCall => _incomingCallController.stream;
  Stream<Map<String, dynamic>> get onCallSignaling =>
      _callSignalingController.stream;

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
    final modeStr = mode == ThemeMode.light
        ? 'light'
        : (mode == ThemeMode.system ? 'system' : 'dark');
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
      _myStore = await StorageService.getMyStore();

      // Check if token is invalid or old synthetic session
      if (!ApiService.isValidJwt(token)) {
        // Auto-reauthenticate in background to get real JWT from backend
        token = await ApiService.ensureValidToken();
      }

      // Instant offline load from SQLite database & cache
      await MediaStorageService().init();
      final cachedConvs = await StorageService.getCachedConversations(
        currentUserId: _currentUser?.id ?? '',
      );
      if (cachedConvs.isNotEmpty) {
        _conversations = cachedConvs;
        // Preload cached messages
        for (final c in cachedConvs) {
          final cachedMsgs = await StorageService.getCachedMessages(
            c.id,
            currentUserId: _currentUser?.id ?? '',
          );
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
        await _flushOutbox();
        await refreshData();
        if (_currentUser != null) {
          PushNotificationService().registerTokenWithBackend(userId: _currentUser!.id);
        }
      }
    } else {
      _isLoading = false;
      notifyListeners();
    }
  }

  /// Flush any pending messages in SQLite outbox queue
  Future<void> _flushOutbox() async {
    try {
      await SyncOutboxService().flush(
        db: DatabaseService(),
        wsService: wsService,
        onMessageSent: (updatedMsg) {
          final convMsgs = _messages[updatedMsg.conversationId];
          if (convMsgs != null) {
            final idx = convMsgs.indexWhere((m) => m.id == updatedMsg.id);
            if (idx != -1) {
              convMsgs[idx] = updatedMsg;
              notifyListeners();
            }
          }
        },
      );
    } catch (_) {}
  }

  // ── Refresh Live Data ───────────────────────────────────────────────────────
  Future<void> refreshData() async {
    if (_currentUser == null) return;

    try {
      // Retry up to 3 times for Render cold-start timeouts
      List<dynamic> results = [];
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          results = await Future.wait([
            ApiService.getConversations(),
            ApiService.getStories(),
            ApiService.getChannels(),
            ApiService.getCalls(),
            ApiService.getProducts(),
          ]);
          break; // Success, exit retry loop
        } catch (retryError) {
          if (attempt < 2) {
            await Future.delayed(Duration(seconds: 2 * (attempt + 1)));
          } else {
            rethrow;
          }
        }
      }
      if (results.isEmpty) return;

      _conversations = results[0] as List<Conversation>;
      _stories = results[1] as List<UserStories>;
      _channels = results[2] as List<Channel>;
      _calls = results[3] as List<CallRecord>;
      _products = results[4] as List<Product>;

      // Persist conversations to offline storage
      if (_conversations.isNotEmpty) {
        await StorageService.saveCachedConversations(_conversations);
      } else {
        final cached = await StorageService.getCachedConversations(
          currentUserId: _currentUser?.id ?? '',
        );
        if (cached.isNotEmpty) {
          _conversations = cached;
        }
      }

      // Load cached synced contacts for instant contact picker
      loadCachedContacts();

      // Fetch messages for each active conversation & cache
      for (final conv in _conversations) {
        try {
          final msgs = await ApiService.getMessages(conv.id);
          if (msgs.isNotEmpty) {
            _messages[conv.id] = msgs;
            await StorageService.saveCachedMessages(conv.id, msgs);
          } else {
            final cached = await StorageService.getCachedMessages(
              conv.id,
              currentUserId: _currentUser?.id ?? '',
            );
            if (cached.isNotEmpty) {
              _messages[conv.id] = cached;
            }
          }
        } catch (_) {
          final cached = await StorageService.getCachedMessages(
            conv.id,
            currentUserId: _currentUser?.id ?? '',
          );
          if (cached.isNotEmpty) {
            _messages[conv.id] = cached;
          }
        }
      }
    } catch (e) {
      // Offline fallback: load cached conversations & messages
      final cachedConvs = await StorageService.getCachedConversations(
        currentUserId: _currentUser?.id ?? '',
      );
      if (cachedConvs.isNotEmpty) {
        _conversations = cachedConvs;
        for (final conv in _conversations) {
          final cachedMsgs = await StorageService.getCachedMessages(
            conv.id,
            currentUserId: _currentUser?.id ?? '',
          );
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
    final eventType =
        (data['event_type'] ??
                data['eventType'] ??
                data['event'] ??
                data['type'] ??
                '')
            .toString();
    final rawMsg =
        data['message'] ?? data['Message'] ?? data['payload'] ?? data['data'];

    // 1. Incoming Chat Message Event (EVENT_NEW_MESSAGE = 0 or 1, or explicit message payload)
    final isMessageEvent =
        eventType == '0' ||
        eventType == '1' ||
        eventType == 'EVENT_NEW_MESSAGE' ||
        eventType == 'new_message' ||
        eventType == 'chat_message' ||
        eventType == 'message' ||
        (rawMsg is Map<String, dynamic> &&
            (rawMsg.containsKey('content') ||
                rawMsg.containsKey('conversation_id') ||
                rawMsg.containsKey('conversationId')));

    if (isMessageEvent) {
      final convId = (data['conversation_id'] ??
              data['conversationId'] ??
              data['conv_id'] ??
              (rawMsg is Map ? (rawMsg['conversation_id'] ?? rawMsg['conversationId']) : null) ??
              '')
          .toString();

      // If notification payload without message body, fetch latest messages from server
      if ((rawMsg == null || (rawMsg is Map && rawMsg['content'] == null)) &&
          data['content'] == null) {
        if (convId.isNotEmpty) {
          fetchMessagesFor(convId);
        }
        return;
      }

      final Map<String, dynamic> payload = (rawMsg is Map<String, dynamic>)
          ? rawMsg
          : data;
      final msg = Message.fromJson(
        payload,
        currentUserId: _currentUser?.id ?? '',
      );

      if (msg.conversationId.isNotEmpty) {
        if (!_messages.containsKey(msg.conversationId)) {
          _messages[msg.conversationId] = [];
        }
        final existingIdx = _messages[msg.conversationId]!.indexWhere(
          (m) => m.id == msg.id,
        );
        if (existingIdx == -1) {
          _messages[msg.conversationId]!.add(msg);
        } else {
          _messages[msg.conversationId]![existingIdx] = msg;
        }

        // Update cached messages
        StorageService.saveCachedMessages(
          msg.conversationId,
          _messages[msg.conversationId]!,
        );

        // Update or insert conversation in list
        final convIdx = _conversations.indexWhere(
          (c) => c.id == msg.conversationId,
        );
        if (convIdx != -1) {
          final currentTitle = _conversations[convIdx].title;
          final shouldUpdateTitle =
              (currentTitle.startsWith('User_') ||
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
          // New conversation created by sender - add to receiver's list
          final newConv = Conversation(
            id: msg.conversationId,
            title: msg.senderName.isNotEmpty && msg.senderName != 'Me'
                ? msg.senderName
                : 'Contact',
            lastMessage: msg,
            type: ConversationType.direct,
            invitationStatus: InvitationStatus.accepted,
            invitationSenderId: msg.senderId,
            updatedAt: DateTime.now(),
          );
          _conversations.insert(0, newConv);
          // Sync full conversation details from server in background, merging to preserve latest messages
          ApiService.getConversations()
              .then((convs) {
                if (convs.isNotEmpty) {
                  final updatedList = convs.map((newC) {
                    final existingIdx = _conversations.indexWhere((c) => c.id == newC.id);
                    if (existingIdx != -1) {
                      final ex = _conversations[existingIdx];
                      return newC.copyWith(
                        lastMessage: ex.lastMessage ?? newC.lastMessage,
                        unreadCount: ex.unreadCount,
                      );
                    }
                    return newC;
                  }).toList();
                  _conversations = updatedList;
                  StorageService.saveCachedConversations(_conversations);
                  notifyListeners();
                }
              })
              .catchError((_) {});
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
    // 2. Incoming Live Game Move Event (Tic-Tac-Toe / Connect 4)
    else if (eventType == 'game_move' || eventType == 'EVENT_GAME_MOVE') {
      final convId = (data['conversation_id'] ?? '').toString();
      final msgId = (data['message_id'] ?? '').toString();
      final gameJson = data['game_data'];
      if (convId.isNotEmpty && msgId.isNotEmpty && gameJson is Map<String, dynamic>) {
        final gameData = GameData.fromJson(gameJson);
        final msgs = _messages[convId];
        if (msgs != null) {
          final idx = msgs.indexWhere((m) => m.id == msgId);
          if (idx != -1) {
            msgs[idx] = msgs[idx].copyWith(gameData: gameData);
            DatabaseService().updateGameData(msgId, gameData);
            notifyListeners();
          }
        }
      }
    }
    // 3. Incoming Profile Update Event
    else if (eventType == 'user_profile_updated' ||
        eventType == 'EVENT_USER_PROFILE_UPDATED') {
      final userData = data['user'] ?? data['payload'] ?? data['data'];
      if (userData is Map<String, dynamic>) {
        final userId = userData['id']?.toString() ?? '';
        final newName = userData['display_name']?.toString() ?? '';
        final newAvatar = userData['avatar_url']?.toString() ?? '';

        if (userId.isNotEmpty && newName.isNotEmpty) {
          bool changed = false;
          for (int i = 0; i < _conversations.length; i++) {
            final conv = _conversations[i];
            if (conv.memberIds.contains(userId) ||
                conv.id == userId ||
                conv.partnerPin == userData['pin']) {
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
    else if (eventType == 'invitation_accepted' ||
        data['type'] == 'invitation_accepted') {
      final convId =
          (data['conversation_id'] ??
                  data['conversationId'] ??
                  data['conv_id'] ??
                  '')
              .toString();
      final idx = _conversations.indexWhere((c) => c.id == convId);
      if (idx != -1) {
        _conversations[idx] = _conversations[idx].copyWith(
          invitationStatus: InvitationStatus.accepted,
        );
        StorageService.saveCachedConversations(_conversations);
        fetchStories(); // Refresh stories to show new contact's status immediately
        notifyListeners();
      }
    }
    // 4. Incoming Story Event
    else if (eventType == 'story_created' ||
        eventType == 'chat:stories' ||
        data['type'] == 'story_created') {
      final storyId = (data['story_id'] ?? data['id'] ?? 'story_${DateTime.now().millisecondsSinceEpoch}').toString();
      final userId = (data['user_id'] ?? data['actor_id'] ?? data['sender_id'] ?? '').toString();
      final userName = (data['user_name'] ?? data['sender_name'] ?? data['author_name'] ?? 'Friend').toString();
      final userAvatar = (data['user_avatar'] ?? data['avatar_url'] ?? '').toString();
      final mediaUrl = (data['media_url'] ?? data['url'] ?? '').toString();
      final caption = (data['caption'] ?? data['content'] ?? '').toString();
      final mediaType = (data['media_type'] ?? data['type'] ?? 'image').toString();
      final backgroundColor = data['background_color']?.toString();

      if (userId.isNotEmpty && userId != _currentUser?.id) {
        final newStory = StoryItem(
          id: storyId,
          mediaUrl: mediaUrl,
          caption: caption,
          mediaType: mediaType,
          backgroundColor: backgroundColor,
          createdAt: DateTime.now(),
          expiresAt: DateTime.now().add(const Duration(hours: 24)),
        );

        final userStoryIdx = _stories.indexWhere((s) => s.userId == userId || (s.userName.isNotEmpty && s.userName.toLowerCase() == userName.toLowerCase()));
        if (userStoryIdx != -1) {
          final existing = _stories[userStoryIdx];
          if (!existing.stories.any((st) => st.id == storyId)) {
            existing.stories.insert(0, newStory);
          }
        } else {
          _stories.insert(
            _stories.any((s) => s.isMe) ? 1 : 0,
            UserStories(
              userId: userId,
              userName: userName,
              userAvatar: userAvatar,
              stories: [newStory],
              isMe: false,
            ),
          );
        }
        notifyListeners();
      }

      fetchStories();
    }
    // 3. Incoming Message Status Updates (Delivered / Read Receipts -> Green Ticks)
    else if (eventType == 'read_receipt' ||
        eventType == 'EVENT_READ_RECEIPT' ||
        eventType == 'message_read' ||
        eventType == 'message_delivered') {
      final convId = (data['conversation_id'] ?? data['conversationId'] ?? '').toString();
      final msgId = (data['message_id'] ?? data['messageId'] ?? '').toString();
      final isRead = eventType == 'read_receipt' ||
          eventType == 'EVENT_READ_RECEIPT' ||
          eventType == 'message_read';
      final newStatus = isRead ? MessageStatus.read : MessageStatus.delivered;

      if (convId.isNotEmpty) {
        final msgs = _messages[convId];
        if (msgs != null) {
          bool updated = false;
          for (int i = 0; i < msgs.length; i++) {
            if (msgs[i].isMe && (msgId.isEmpty || msgs[i].id == msgId || msgId == 'all')) {
              if (msgs[i].status != MessageStatus.read) {
                msgs[i] = msgs[i].copyWith(status: newStatus);
                DatabaseService().updateMessageStatus(msgs[i].id, newStatus);
                updated = true;
              }
            }
          }
          if (updated) {
            StorageService.saveCachedMessages(convId, msgs);
            notifyListeners();
          }
        }
      }
    }
    // 4. Incoming Live Typing Event
    else if (eventType == '6' ||
        eventType == 'EVENT_TYPING' ||
        eventType == 'typing') {
      final convId =
          (data['conversation_id'] ??
                  data['conversationId'] ??
                  data['conv_id'] ??
                  '')
              .toString();
      final isTyping = data['is_typing'] == true || data['isTyping'] == true;
      final userName =
          (data['user_name'] ??
                  data['userName'] ??
                  data['actor_id'] ??
                  'Contact')
              .toString();

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
    // 4. Incoming Recording Audio Event
    else if (eventType == 'recording_audio' ||
        eventType == 'EVENT_RECORDING_AUDIO' ||
        data['type'] == 'recording_audio') {
      final convId =
          (data['conversation_id'] ??
                  data['conversationId'] ??
                  data['conv_id'] ??
                  '')
              .toString();
      final isRecording =
          data['is_recording'] == true || data['isRecording'] == true;
      final userName =
          (data['user_name'] ??
                  data['userName'] ??
                  data['actor_id'] ??
                  'Contact')
              .toString();
      final senderId =
          (data['user_id'] ?? data['userId'] ?? data['sender_id'] ?? '')
              .toString();

      // Ignore own recording broadcast
      if (_currentUser?.id.isNotEmpty == true &&
          senderId.isNotEmpty &&
          senderId == _currentUser?.id) {
        return;
      }

      if (convId.isNotEmpty) {
        if (!_recordingUsers.containsKey(convId)) {
          _recordingUsers[convId] = {};
        }
        if (isRecording) {
          _recordingUsers[convId]!.add(userName);
        } else {
          _recordingUsers[convId]!.remove(userName);
        }
        notifyListeners();
      }
    }
    // 4. Incoming PING Nudge Event
    else if (eventType == 'ping' ||
        eventType == 'EVENT_PINNED' ||
        data['is_ping'] == true) {
      final convId =
          (data['conversation_id'] ??
                  data['conversationId'] ??
                  data['conv_id'] ??
                  '')
              .toString();
      if (convId.isNotEmpty) {
        HapticFeedback.vibrate();
        _pingStreamController.add(convId);
        notifyListeners();
      }
    }
    // 5. Incoming Call Events (call_initiated, call_accepted, call_rejected, call_ended, call_signaling)
    else if (eventType.startsWith('call_') ||
        data.containsKey('call_payload')) {
      Map<String, dynamic> callPayload = {};
      if (data['call_payload'] != null) {
        if (data['call_payload'] is Map<String, dynamic>) {
          callPayload = data['call_payload'] as Map<String, dynamic>;
        } else if (data['call_payload'] is String) {
          try {
            callPayload =
                jsonDecode(data['call_payload'] as String)
                    as Map<String, dynamic>;
          } catch (_) {}
        }
      } else if (data['call'] is Map<String, dynamic>) {
        callPayload = data['call'] as Map<String, dynamic>;
      } else {
        callPayload = data;
      }

      if (eventType == 'call_initiated') {
        final incomingCall = CallRecord.fromJson(
          callPayload,
          currentUserId: _currentUser?.id ?? '',
        );
        _activeCall = incomingCall;
        _calls.removeWhere((c) => c.id == incomingCall.id);
        _calls.insert(0, incomingCall);
        HapticFeedback.vibrate();
        _incomingCallController.add(incomingCall);
        VoipCallService().startRinging(incomingCall);
        notifyListeners();
      } else if (eventType == 'call_accepted') {
        VoipCallService().stopRinging();
        if (_activeCall != null) {
          _activeCall = _activeCall!.copyWith(status: CallStatus.active);
          final idx = _calls.indexWhere((c) => c.id == _activeCall!.id);
          if (idx != -1) {
            _calls[idx] = _activeCall!;
          }
          notifyListeners();
        }
      } else if (eventType == 'call_rejected' || eventType == 'call_ended') {
        VoipCallService().stopRinging();
        if (_activeCall != null) {
          final isRejected = eventType == 'call_rejected';
          _activeCall = _activeCall!.copyWith(
            status: isRejected ? CallStatus.rejected : CallStatus.ended,
          );
          final idx = _calls.indexWhere((c) => c.id == _activeCall!.id);
          if (idx != -1) {
            _calls[idx] = _activeCall!;
          }
          _activeCall = null;
          notifyListeners();
        }
      } else if (eventType == 'call_signaling') {
        _callSignalingController.add(callPayload);
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
      final localConvs = await StorageService.getCachedConversations(
        currentUserId: _currentUser?.id ?? '',
      );
      if (localConvs.isNotEmpty) {
        _conversations = localConvs;
        for (final conv in _conversations) {
          final cachedMsgs = await StorageService.getCachedMessages(
            conv.id,
            currentUserId: _currentUser?.id ?? '',
          );
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
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
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
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      _currentUser = null;
      rethrow;
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

  // ── Chat: Send Live Recording Audio Event ──────────────────────────────────
  void sendRecordingAudioEvent(String convId, bool isRecording) {
    wsService.send({
      'type': 'recording_audio',
      'event_type': 'EVENT_RECORDING_AUDIO',
      'conversation_id': convId,
      'is_recording': isRecording,
      'user_name': _currentUser?.displayName ?? 'User',
      'user_id': _currentUser?.id ?? '',
    });
  }

  bool isUserRecordingAudio(String convId) =>
      _recordingUsers[convId]?.isNotEmpty == true;

  String getRecordingAudioText(String convId) {
    final users = _recordingUsers[convId];
    if (users == null || users.isEmpty) return '';
    if (users.length == 1) return '🎙️ recording audio...';
    return '🎙️ ${users.length} people recording audio...';
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

  /// Mark all incoming messages in a conversation as read and send read receipts over WebSocket
  void markConversationAsRead(String convId) {
    if (convId.isEmpty) return;
    final msgs = _messages[convId];
    if (msgs != null && msgs.isNotEmpty) {
      bool hasUnread = false;
      for (int i = 0; i < msgs.length; i++) {
        if (!msgs[i].isMe && msgs[i].status != MessageStatus.read) {
          msgs[i] = msgs[i].copyWith(status: MessageStatus.read);
          DatabaseService().updateMessageStatus(msgs[i].id, MessageStatus.read);
          hasUnread = true;
        }
      }
      if (hasUnread) {
        StorageService.saveCachedMessages(convId, msgs);
        notifyListeners();
      }
    }

    // Broadcast read receipt to peer so their ticks turn green
    wsService.send({
      'type': 'read_receipt',
      'event_type': 'EVENT_READ_RECEIPT',
      'conversation_id': convId,
      'reader_id': _currentUser?.id ?? '',
    });
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
    String? mediaThumbnail,
    String? telegramFileId,
    int? mediaDuration,
    int? mediaSize,
    PollData? pollData,
    GameData? gameData,
    Map<String, dynamic>? productData,
    bool isPing = false,
    bool isViewOnce = false,
    int? disappearingDurationSeconds,
    String? replyToId,
    String? replyToText,
    String? replyToSenderName,
  }) async {
    int typeInt = 0;
    if (type == MessageType.image) typeInt = 1;
    if (type == MessageType.video) typeInt = 2;
    if (type == MessageType.audio) typeInt = 3;
    if (type == MessageType.file) typeInt = 4;
    if (type == MessageType.voice) typeInt = 5;
    if (type == MessageType.poll) typeInt = 6;
    if (type == MessageType.product) typeInt = 7;
    if (type == MessageType.ping) typeInt = 8;
    if (type == MessageType.game) typeInt = 9;

    final expiresAt = disappearingDurationSeconds != null
        ? DateTime.now().add(Duration(seconds: disappearingDurationSeconds))
        : null;

    try {
      final realMsg = await ApiService.sendMessage(
        conversationId: convId,
        content: content,
        type: typeInt,
        mediaUrl: mediaUrl,
      );

      final msgToAdd = realMsg.copyWith(
        type: type,
        mediaUrl: mediaUrl ?? realMsg.mediaUrl,
        mediaThumbnail: mediaThumbnail ?? realMsg.mediaThumbnail,
        telegramFileId: telegramFileId ?? realMsg.telegramFileId,
        mediaDuration: mediaDuration ?? realMsg.mediaDuration,
        mediaSize: mediaSize ?? realMsg.mediaSize,
        pollData: pollData,
        gameData: gameData,
        productData: productData,
        isPing: isPing,
        isViewOnce: isViewOnce,
        disappearingDurationSeconds: disappearingDurationSeconds,
        expiresAt: expiresAt,
        replyToId: replyToId,
        replyToText: replyToText,
        replyToSenderName: replyToSenderName,
      );

      if (!_messages.containsKey(convId)) {
        _messages[convId] = [];
      }
      _messages[convId]!.add(msgToAdd);

      // Save to SQLite database
      await DatabaseService().insertMessage(msgToAdd);

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
      // Find recipient_id for direct 1:1 conversation so gateway can target peer directly
      String? recipientId;
      Conversation? targetConv;
      for (final c in _conversations) {
        if (c.id == convId) {
          targetConv = c;
          break;
        }
      }
      if (targetConv != null) {
        for (final mId in targetConv.memberIds) {
          if (mId.isNotEmpty && mId != _currentUser?.id) {
            recipientId = mId;
            break;
          }
        }
      }

      // Broadcast over WebSocket to all connected peers
      wsService.send({
        'type': 'new_message',
        'event_type': 'EVENT_NEW_MESSAGE',
        'conversation_id': convId,
        if (recipientId != null && recipientId.isNotEmpty) 'recipient_id': recipientId,
        'message': {
          'id': msgToAdd.id,
          'conversation_id': convId,
          'sender_id': _currentUser?.id ?? 'u_me',
          'sender_name': _currentUser?.displayName ?? 'Me',
          'content': content,
          'type': type.name,
          'media_type': type.name,
          'media_url': mediaUrl,
          'media_duration': mediaDuration,
          'media_size': mediaSize,
          'is_ping': isPing,
          'is_view_once': isViewOnce,
          'disappearing_duration': disappearingDurationSeconds,
          'expires_at': expiresAt?.toIso8601String(),
          'poll_data': pollData?.toJson(),
          'game_data': gameData?.toJson(),
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
        status: MessageStatus.pending,
        mediaUrl: mediaUrl,
        mediaDuration: mediaDuration,
        mediaSize: mediaSize,
        pollData: pollData,
        gameData: gameData,
        productData: productData,
        isPing: isPing,
        isViewOnce: isViewOnce,
        disappearingDurationSeconds: disappearingDurationSeconds,
        expiresAt: expiresAt,
        replyToId: replyToId,
        replyToText: replyToText,
        replyToSenderName: replyToSenderName,
        createdAt: DateTime.now(),
        isMe: true,
      );

      if (!_messages.containsKey(convId)) {
        _messages[convId] = [];
      }
      _messages[convId]!.add(optimisticMsg);
      await StorageService.saveCachedMessages(convId, _messages[convId]!);

      // Save to SQLite Outbox queue for automatic background flush
      await DatabaseService().addToOutbox(
        messageId: optimisticMsg.id,
        conversationId: convId,
        payload: {
          'content': content,
          'type': typeInt,
          'media_url': mediaUrl,
          'media_duration': mediaDuration,
          'poll_data': pollData?.toJson(),
          'game_data': gameData?.toJson(),
          'product_data': productData,
        },
      );

      final idx = _conversations.indexWhere((c) => c.id == convId);
      if (idx != -1) {
        _conversations[idx] = _conversations[idx].copyWith(
          lastMessage: optimisticMsg,
          updatedAt: DateTime.now(),
        );
        final updated = _conversations.removeAt(idx);
        _conversations.insert(0, updated);
        await StorageService.saveCachedConversations(_conversations);
      }

      // Also broadcast optimistic message over WebSocket for immediate peer sync
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
          'type': type.name,
          'media_type': type.name,
          'media_url': mediaUrl,
          'media_duration': mediaDuration,
          'is_ping': isPing,
          'is_view_once': isViewOnce,
          'disappearing_duration': disappearingDurationSeconds,
          'expires_at': expiresAt?.toIso8601String(),
          'poll_data': pollData?.toJson(),
          'game_data': gameData?.toJson(),
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

  /// Make a move in an in-chat mini-game (Tic-Tac-Toe or Connect 4)
  Future<void> makeGameMove(String convId, String messageId, GameData updatedGame) async {
    final msgs = _messages[convId];
    if (msgs != null) {
      final idx = msgs.indexWhere((m) => m.id == messageId);
      if (idx != -1) {
        msgs[idx] = msgs[idx].copyWith(gameData: updatedGame);
        notifyListeners();
      }
    }

    // Save to SQLite
    await DatabaseService().updateGameData(messageId, updatedGame);

    // Broadcast live over WebSocket to opponent
    wsService.send({
      'type': 'game_move',
      'event_type': 'EVENT_GAME_MOVE',
      'conversation_id': convId,
      'message_id': messageId,
      'game_data': updatedGame.toJson(),
    });
  }

  /// Mark a View-Once message as opened (burns local media and updates state)
  Future<void> markViewOnceAsOpened(String convId, String messageId) async {
    if (_messages.containsKey(convId)) {
      final idx = _messages[convId]!.indexWhere((m) => m.id == messageId);
      if (idx != -1) {
        final current = _messages[convId]![idx];
        _messages[convId]![idx] = current.copyWith(
          isOpened: true,
          content: 'Opened',
          mediaUrl: null,
        );
        notifyListeners();
      }
    }

    // Burn from SQLite & permanent device storage
    await DatabaseService().markViewOnceAsOpened(messageId);
  }

  /// Auto-purge expired disappearing messages from active memory and SQLite
  Future<void> cleanupExpiredMessages() async {
    await DatabaseService().cleanupExpiredMessages();
    bool changed = false;

    _messages.forEach((convId, msgList) {
      final beforeCount = msgList.length;
      msgList.removeWhere((m) => m.isExpired);
      if (msgList.length != beforeCount) {
        changed = true;
      }
    });

    if (changed) {
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

  // ── Chat: Fetch Latest Messages for Conversation ───────────────────────────
  Future<void> fetchMessagesFor(String convId) async {
    if (convId.isEmpty || convId.startsWith('conv_')) return;
    try {
      final msgs = await ApiService.getMessages(convId);
      if (msgs.isNotEmpty) {
        _messages[convId] = msgs;
        await StorageService.saveCachedMessages(convId, msgs);
        await DatabaseService().saveMessages(convId, msgs);

        final convIdx = _conversations.indexWhere((c) => c.id == convId);
        if (convIdx != -1) {
          final last = msgs.last;
          _conversations[convIdx] = _conversations[convIdx].copyWith(
            lastMessage: last,
            updatedAt: last.createdAt,
          );
          await StorageService.saveCachedConversations(_conversations);
        }

        notifyListeners();
      }
    } catch (_) {}
  }

  // ── Contacts: Scan & Sync Device Contacts ──────────────────────────────────
  Future<void> syncDeviceContacts({bool force = false}) async {
    if (_currentUser == null) return;
    _isSyncingContacts = true;
    notifyListeners();

    try {
      _syncedContacts = await ContactSyncService().scanAndSyncContacts(
        currentUserId: _currentUser!.id,
      );
    } catch (e) {
      debugPrint('[AppState] Error syncing device contacts: $e');
    } finally {
      _isSyncingContacts = false;
      notifyListeners();
    }
  }

  Future<void> loadCachedContacts() async {
    try {
      final cached = await ContactSyncService().getCachedContacts();
      if (cached.isNotEmpty) {
        _syncedContacts = cached;
        notifyListeners();
      }
    } catch (_) {}
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
      // Remove any broken fallback conversations with 'conv_'
      _conversations.removeWhere((c) =>
          c.id.startsWith('conv_') &&
          (c.title == name || (partnerPin != null && c.partnerPin == partnerPin)));
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

      // Refresh stories so this contact's status shows up immediately
      fetchStories();

      notifyListeners();
    }
  }

  // ── Stories / Status Helpers ────────────────────────────────────────────────
  Future<void> fetchStories() async {
    try {
      final s = await ApiService.getStories();
      if (s.isNotEmpty) {
        _stories = s;
        notifyListeners();
      }
    } catch (_) {}
  }

  UserStories? getStoriesForUser(String userId) {
    if (userId.isEmpty) return null;
    return _stories.where((s) => s.userId == userId).firstOrNull;
  }

  UserStories? getStoriesForConversation(Conversation conv) {
    final otherId = conv.memberIds.firstWhere(
      (id) => id.isNotEmpty && id != _currentUser?.id,
      orElse: () => conv.id,
    );
    return _stories.where((s) =>
      s.userId == otherId ||
      (s.userName.isNotEmpty && s.userName.toLowerCase() == conv.title.toLowerCase())
    ).firstOrNull;
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
      if (conv.title.toUpperCase().contains(cleanPin) ||
          conv.id.toUpperCase().contains(cleanPin)) {
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
  Future<void> votePoll(
    String convId,
    String messageId,
    String optionId,
  ) async {
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
    if (_currentUser != null && product.sellerId.isNotEmpty && product.sellerId == _currentUser!.id) {
      return; // Cannot add own product to cart
    }
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

  void toggleWishlist(String productId) {
    HapticFeedback.selectionClick();
    final idx = _products.indexWhere((p) => p.id == productId);
    if (idx != -1) {
      _products[idx] = _products[idx].copyWith(
        isWishlisted: !_products[idx].isWishlisted,
      );
      notifyListeners();
    }
  }

  Future<StoreProfile> createStore(StoreProfile store) async {
    try {
      final created = await ApiService.createBusinessProfile(store);
      _myStore = created;
    } catch (_) {
      _myStore = store;
    }
    await StorageService.saveMyStore(_myStore!);
    notifyListeners();
    return _myStore!;
  }

  Future<Product> createStoreProduct(Product product) async {
    Product resultProduct = product;
    if (resultProduct.sellerId.isEmpty && _currentUser != null) {
      resultProduct = resultProduct.copyWith(sellerId: _currentUser!.id);
    }
    try {
      final created = await ApiService.createProduct(resultProduct);
      resultProduct = created;
    } catch (_) {}
    if (resultProduct.sellerId.isEmpty && _currentUser != null) {
      resultProduct = resultProduct.copyWith(sellerId: _currentUser!.id);
    }
    _products.removeWhere((p) => p.id == resultProduct.id);
    _products.insert(0, resultProduct);
    notifyListeners();
    return resultProduct;
  }

  Future<Product> updateStoreProduct(Product product) async {
    Product updated = product;
    try {
      updated = await ApiService.updateProduct(product);
    } catch (_) {}
    final idx = _products.indexWhere((p) => p.id == product.id);
    if (idx != -1) {
      _products[idx] = updated;
    }
    notifyListeners();
    return updated;
  }

  Future<void> deleteStoreProduct(String productId) async {
    try {
      await ApiService.deleteProduct(productId);
    } catch (_) {}
    _products.removeWhere((p) => p.id == productId);
    _cart.removeWhere((p) => p.id == productId);
    notifyListeners();
  }

  Future<void> loadSellerOrders() async {
    try {
      final orders = await ApiService.getSellerOrders();
      if (orders.isNotEmpty) {
        _sellerOrders = orders;
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<void> loadStoreCoupons() async {
    try {
      final list = await ApiService.getStoreCoupons();
      if (list.isNotEmpty) {
        _storeCoupons = list;
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<StoreCoupon> createStoreCoupon(StoreCoupon coupon) async {
    StoreCoupon created = coupon;
    try {
      created = await ApiService.createStoreCoupon(coupon);
    } catch (_) {}
    _storeCoupons.insert(0, created);
    notifyListeners();
    return created;
  }

  void deleteStoreCoupon(String couponId) {
    _storeCoupons.removeWhere((c) => c.id == couponId);
    notifyListeners();
  }

  // ── Product Variants Management ─────────────────────────────────────────────
  Future<ProductVariant> addProductVariant(
    String productId,
    ProductVariant variant,
  ) async {
    ProductVariant created = variant;
    try {
      created = await ApiService.createProductVariant(productId, variant);
    } catch (_) {}

    final pIdx = _products.indexWhere((p) => p.id == productId);
    if (pIdx != -1) {
      final currentList = List<ProductVariant>.from(_products[pIdx].variants);
      currentList.add(created);
      _products[pIdx] = _products[pIdx].copyWith(variants: currentList);
      notifyListeners();
    }
    return created;
  }

  Future<void> deleteProductVariant(String productId, String variantId) async {
    try {
      await ApiService.deleteProductVariant(productId, variantId);
    } catch (_) {}

    final pIdx = _products.indexWhere((p) => p.id == productId);
    if (pIdx != -1) {
      final currentList = List<ProductVariant>.from(_products[pIdx].variants);
      currentList.removeWhere((v) => v.id == variantId);
      _products[pIdx] = _products[pIdx].copyWith(variants: currentList);
      notifyListeners();
    }
  }

  Future<List<ProductVariant>> loadProductVariants(String productId) async {
    try {
      final list = await ApiService.listProductVariants(productId);
      if (list.isNotEmpty) {
        final pIdx = _products.indexWhere((p) => p.id == productId);
        if (pIdx != -1) {
          _products[pIdx] = _products[pIdx].copyWith(variants: list);
          notifyListeners();
        }
        return list;
      }
    } catch (_) {}
    return [];
  }

  // ── Order Fulfillment & Status ──────────────────────────────────────────────
  Future<void> updateOrderStatus(String orderId, String newStatus) async {
    try {
      await ApiService.updateOrderStatus(orderId, newStatus);
    } catch (_) {}

    final idx = _sellerOrders.indexWhere((o) => o.id == orderId);
    if (idx != -1) {
      final updated = MarketplaceOrder(
        id: _sellerOrders[idx].id,
        orderNumber: _sellerOrders[idx].orderNumber,
        buyerId: _sellerOrders[idx].buyerId,
        buyerName: _sellerOrders[idx].buyerName,
        buyerPhone: _sellerOrders[idx].buyerPhone,
        buyerPin: _sellerOrders[idx].buyerPin,
        sellerId: _sellerOrders[idx].sellerId,
        storeName: _sellerOrders[idx].storeName,
        grandTotal: _sellerOrders[idx].grandTotal,
        status: newStatus,
        items: _sellerOrders[idx].items,
        createdAt: _sellerOrders[idx].createdAt,
        shippingAddress: _sellerOrders[idx].shippingAddress,
      );
      _sellerOrders[idx] = updated;
      notifyListeners();
    }
  }

  Future<void> addProductReview(
    String productId,
    double rating,
    String comment,
  ) async {
    try {
      await ApiService.createReview(productId, rating, comment);
    } catch (_) {}
  }

  Future<void> askProductQuestion(String productId, String question) async {
    try {
      await ApiService.askProductQuestion(productId, question);
    } catch (_) {}
  }

  // ── WebRTC & Calls ──────────────────────────────────────────────────────────
  Future<CallRecord> startCall({
    required String receiverId,
    required String receiverName,
    String receiverAvatar = '',
    CallType type = CallType.audio,
  }) async {
    final optimisticCall = CallRecord(
      id: 'call_${DateTime.now().millisecondsSinceEpoch}',
      callerId: _currentUser?.id ?? 'u_me',
      callerName: _currentUser?.displayName ?? 'Me',
      callerAvatar: _currentUser?.avatarUrl ?? '',
      receiverId: receiverId,
      receiverName: receiverName,
      receiverAvatar: receiverAvatar,
      type: type,
      direction: CallDirection.outgoing,
      status: CallStatus.dialing,
      timestamp: DateTime.now(),
    );

    _activeCall = optimisticCall;
    _calls.removeWhere((c) => c.id == optimisticCall.id);
    _calls.insert(0, optimisticCall);
    notifyListeners();

    try {
      final backendCall = await ApiService.startCall(
        receiverId: receiverId,
        type: type == CallType.video ? 'video' : 'voice',
      );
      _activeCall = backendCall.copyWith(
        receiverName: receiverName,
        receiverAvatar: receiverAvatar,
      );
      final idx = _calls.indexWhere(
        (c) => c.id == optimisticCall.id || c.id == backendCall.id,
      );
      if (idx != -1) {
        _calls[idx] = _activeCall!;
      } else {
        _calls.insert(0, _activeCall!);
      }
      notifyListeners();
      return _activeCall!;
    } catch (_) {
      return optimisticCall;
    }
  }

  Future<void> acceptCall(String callId) async {
    if (_activeCall != null) {
      _activeCall = _activeCall!.copyWith(status: CallStatus.active);
      final idx = _calls.indexWhere((c) => c.id == callId);
      if (idx != -1) {
        _calls[idx] = _activeCall!;
      }
      notifyListeners();
    }
    try {
      final res = await ApiService.acceptCall(callId);
      if (_activeCall != null && _activeCall!.id == callId) {
        _activeCall = res;
        final idx = _calls.indexWhere((c) => c.id == callId);
        if (idx != -1) _calls[idx] = res;
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<void> rejectCall(String callId, {bool isBusy = false}) async {
    if (_activeCall != null && _activeCall!.id == callId) {
      _activeCall = _activeCall!.copyWith(
        status: isBusy ? CallStatus.busy : CallStatus.rejected,
      );
      final idx = _calls.indexWhere((c) => c.id == callId);
      if (idx != -1) _calls[idx] = _activeCall!;
      _activeCall = null;
      notifyListeners();
    }
    try {
      await ApiService.rejectCall(callId, isBusy: isBusy);
    } catch (_) {}
  }

  Future<void> endCall([String? callId]) async {
    final targetId = callId ?? _activeCall?.id;
    if (_activeCall != null) {
      _activeCall = _activeCall!.copyWith(status: CallStatus.ended);
      final idx = _calls.indexWhere((c) => c.id == _activeCall!.id);
      if (idx != -1) _calls[idx] = _activeCall!;
      _activeCall = null;
      notifyListeners();
    }
    if (targetId != null && targetId.isNotEmpty) {
      try {
        await ApiService.endCall(targetId);
      } catch (_) {}
    }
  }

  Future<void> sendSignalingMessage({
    required String callId,
    required String receiverId,
    required String type,
    String? sdp,
    String? candidate,
  }) async {
    try {
      await ApiService.sendSignalingMessage(
        callId: callId,
        receiverId: receiverId,
        type: type,
        sdp: sdp,
        candidate: candidate,
      );
    } catch (_) {}
  }

  // ── Stories & Status Updates ───────────────────────────────────────────────
  Future<void> addStory(String mediaUrl, String caption, {String mediaType = 'image', String? backgroundColor}) async {
    final storyId = 'story_${DateTime.now().millisecondsSinceEpoch}';
    final newStory = StoryItem(
      id: storyId,
      mediaUrl: mediaUrl,
      caption: caption,
      mediaType: mediaType,
      backgroundColor: backgroundColor,
      createdAt: DateTime.now(),
      expiresAt: DateTime.now().add(const Duration(hours: 24)),
    );
    final myIdx = _stories.indexWhere((s) => s.isMe);
    if (myIdx != -1) {
      _stories[myIdx].stories.insert(0, newStory);
    } else {
      _stories.insert(
        0,
        UserStories(
          userId: _currentUser?.id ?? 'u_me',
          userName: _currentUser?.displayName ?? 'My status',
          userAvatar: _currentUser?.avatarUrl ?? '',
          stories: [newStory],
          isMe: true,
        ),
      );
    }
    notifyListeners();

    // Broadcast & Post to Backend API in background so friends receive it
    try {
      String finalMediaUrl = mediaUrl;
      if (mediaUrl.isNotEmpty && !mediaUrl.startsWith('http://') && !mediaUrl.startsWith('https://')) {
        final uploaded = await ApiService.uploadMedia(mediaUrl);
        if (uploaded != null && uploaded.isNotEmpty) {
          finalMediaUrl = uploaded;
        } else {
          // Fallback to base64 Data URI so friend receives the full image immediately
          try {
            final file = File(mediaUrl);
            if (await file.exists()) {
              final bytes = await file.readAsBytes();
              if (bytes.isNotEmpty) {
                final ext = mediaUrl.split('.').last.toLowerCase();
                finalMediaUrl = 'data:image/$ext;base64,${base64Encode(bytes)}';
              }
            }
          } catch (_) {}
        }
      }

      // Send live WebSocket broadcast event to all connected friends
      wsService.send({
        'type': 'story_created',
        'event': 'story_created',
        'story_id': storyId,
        'user_id': _currentUser?.id ?? '',
        'user_name': _currentUser?.displayName ?? '',
        'user_avatar': _currentUser?.avatarUrl ?? '',
        'media_url': finalMediaUrl,
        'caption': caption,
        'media_type': mediaType,
        'background_color': backgroundColor,
      });

      await ApiService.postStory(
        mediaUrl: finalMediaUrl,
        caption: caption,
        mediaType: mediaType,
      );
    } catch (_) {
      // Local story was already added and displayed; remote sync error handled gracefully
    }
  }

  @override
  void dispose() {
    _pingStreamController.close();
    _incomingCallController.close();
    _callSignalingController.close();
    wsService.disconnect();
    super.dispose();
  }
}
