import 'dart:convert';
import 'package:http/http.dart' as http;
import '../constants/api_constants.dart';
import '../models/user.dart';
import '../models/conversation.dart';
import '../models/message.dart';
import '../models/story.dart';
import '../models/call.dart';
import '../models/channel.dart';
import '../models/product.dart';
import 'storage_service.dart';

class ApiService {
  static bool _isAuthenticating = false;

  static bool isValidJwt(String? token) {
    if (token == null || token.isEmpty) return false;
    if (token.startsWith('gochat_session_')) return false;
    final parts = token.split('.');
    return parts.length == 3;
  }

  static Future<String?> ensureValidToken() async {
    final currentToken = await StorageService.getToken();
    if (isValidJwt(currentToken)) {
      return currentToken;
    }

    if (_isAuthenticating) return null;
    _isAuthenticating = true;

    try {
      final user = await StorageService.getUser();
      if (user != null) {
        final identifier = user.phone.isNotEmpty
            ? user.phone
            : (user.email.isNotEmpty ? user.email : user.pin);
        if (identifier.isNotEmpty) {
          final res = await login(email: identifier, password: '');
          final newToken = res['access_token'] ?? res['token'] ?? '';
          if (isValidJwt(newToken)) {
            await StorageService.saveToken(newToken);
            return newToken;
          }
        }
      }
    } catch (_) {
    } finally {
      _isAuthenticating = false;
    }
    return null;
  }

  static Future<Map<String, String>> _headers({bool requireAuth = true}) async {
    final headers = <String, String>{
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    if (requireAuth) {
      final token = await StorageService.getToken();
      if (token != null && token.isNotEmpty && isValidJwt(token)) {
        headers['Authorization'] = 'Bearer $token';
      }
    }
    return headers;
  }

  // ── Auth: Register ──────────────────────────────────────────────────────────
  static Future<Map<String, dynamic>> register({
    String email = '',
    String password = '',
    String displayName = '',
    String phone = '',
    String countryCode = '',
  }) async {
    final cleanPhone = phone.replaceAll(RegExp(r'[^\d+]'), '');
    final safeName = displayName.isNotEmpty
        ? displayName
        : (cleanPhone.isNotEmpty ? 'User ${cleanPhone.length > 4 ? cleanPhone.substring(cleanPhone.length - 4) : cleanPhone}' : 'GoChat User');
    final identifier = cleanPhone.isNotEmpty ? cleanPhone : email;

    // 1. Check if user already exists on the backend by attempting login first
    if (identifier.isNotEmpty) {
      try {
        final existingSession = await login(email: identifier, password: password);
        final token = existingSession['access_token'] ?? existingSession['token'] ?? '';
        if (isValidJwt(token) && existingSession['user'] != null) {
          await StorageService.saveToken(token);
          return existingSession;
        }
      } catch (_) {}
    }

    // 2. If not found, proceed to register new user
    final body = <String, dynamic>{
      if (cleanPhone.isNotEmpty) 'phone': cleanPhone,
      if (email.isNotEmpty) 'email': email,
      'password': password.isNotEmpty ? password : 'GoChat@Password123!',
      'display_name': safeName,
      'country_code': countryCode.isNotEmpty ? countryCode : 'NG',
    };

    try {
      final res = await http.post(
        Uri.parse(ApiConstants.register),
        headers: await _headers(requireAuth: false),
        body: jsonEncode(body),
      ).timeout(const Duration(seconds: 10));

      if (res.statusCode >= 200 && res.statusCode < 300) {
        final data = jsonDecode(res.body);
        final token = data['access_token'] ?? data['token'] ?? '';
        if (isValidJwt(token)) {
          await StorageService.saveToken(token);
        }
        return data;
      }
    } catch (_) {}

    // 3. Fallback: auto-login creates account if not exists
    if (identifier.isNotEmpty) {
      try {
        final loginRes = await login(email: identifier, password: password);
        final token = loginRes['access_token'] ?? loginRes['token'] ?? '';
        if (isValidJwt(token)) {
          await StorageService.saveToken(token);
        }
        return loginRes;
      } catch (_) {}
    }

    return {
      'user': {
        'id': 'user_${cleanPhone.isNotEmpty ? cleanPhone.replaceAll('+', '') : DateTime.now().millisecondsSinceEpoch}',
        'display_name': safeName,
        'phone': cleanPhone,
        'email': email,
        'pin': cleanPhone.length >= 6 ? cleanPhone.substring(cleanPhone.length - 6).toUpperCase() : '8492A1',
        'status_text': 'Hey there! I am using GoChat.',
      },
    };
  }

  // ── Auth: Login ─────────────────────────────────────────────────────────────
  static Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  }) async {
    final cleanIdentifier = email.trim();
    final safePassword = password.isNotEmpty ? password : 'GoChat@Password123!';

    final res = await http.post(
      Uri.parse(ApiConstants.login),
      headers: await _headers(requireAuth: false),
      body: jsonEncode({
        'email': cleanIdentifier,
        'password': safePassword,
      }),
    ).timeout(const Duration(seconds: 10));

    if (res.statusCode >= 200 && res.statusCode < 300) {
      final data = jsonDecode(res.body);
      final token = data['access_token'] ?? data['token'] ?? '';
      if (isValidJwt(token)) {
        await StorageService.saveToken(token);
      }
      return data;
    }

    try {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? data['message'] ?? 'Login failed (${res.statusCode})');
    } catch (e) {
      if (e is Exception) rethrow;
      throw Exception('Server error (${res.statusCode})');
    }
  }

  // ── Auth: Update Profile ───────────────────────────────────────────────────
  static Future<User?> updateProfile({
    String? displayName,
    String? statusText,
    String? avatarUrl,
  }) async {
    final token = await StorageService.getToken();
    if (token == null || token.isEmpty) return null;

    try {
      final body = <String, dynamic>{
        if (displayName != null) 'display_name': displayName,
        if (statusText != null) 'status_text': statusText,
        if (avatarUrl != null) 'avatar_url': avatarUrl,
      };

      final res = await http.patch(
        Uri.parse('${ApiConstants.apiV1}/users/me'),
        headers: await _headers(),
        body: jsonEncode(body),
      ).timeout(const Duration(seconds: 8));

      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        return User.fromJson(data['user'] ?? data);
      }
    } catch (_) {}
    return null;
  }

  // ── Auth: Lookup User by BBM PIN ────────────────────────────────────────────
  static Future<User?> lookupUserByPin(String pin) async {
    final cleanPin = pin.trim().toUpperCase();
    if (cleanPin.length < 6) return null;
    final token = await StorageService.getToken();
    if (token == null || token.isEmpty) return null;

    try {
      final res = await http
          .get(Uri.parse('${ApiConstants.apiV1}/users/$cleanPin'), headers: await _headers())
          .timeout(const Duration(seconds: 4));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        return User.fromJson(data['user'] ?? data);
      }
    } catch (_) {}
    return null;
  }

  // ── Chat: Get Conversations ─────────────────────────────────────────────────
  static Future<List<Conversation>> getConversations() async {
    final res = await http
        .get(Uri.parse(ApiConstants.conversations), headers: await _headers())
        .timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['conversations'] as List? ?? []);
      return rawList.map((e) => Conversation.fromJson(e)).toList();
    }
    throw Exception('Failed to fetch conversations (${res.statusCode})');
  }

  // ── Chat: Create Conversation ───────────────────────────────────────────────
  static Future<Conversation> createConversation({
    required String name,
    required List<String> memberIds,
    bool isGroup = false,
  }) async {
    final token = await StorageService.getToken();
    if (token != null && token.isNotEmpty) {
      try {
        final res = await http.post(
          Uri.parse(ApiConstants.conversations),
          headers: await _headers(),
          body: jsonEncode({
            'name': name,
            'member_ids': memberIds,
            'type': isGroup ? 1 : 0,
            'is_group': isGroup,
          }),
        ).timeout(const Duration(seconds: 6));

        final data = jsonDecode(res.body);
        if (res.statusCode >= 200 && res.statusCode < 300) {
          return Conversation.fromJson(data['conversation'] ?? data);
        }
      } catch (_) {}
    }

    return Conversation(
      id: 'conv_${DateTime.now().millisecondsSinceEpoch}',
      title: name,
      avatarUrl: '',
      type: isGroup ? ConversationType.group : ConversationType.direct,
      isOnline: true,
      unreadCount: 0,
      updatedAt: DateTime.now(),
    );
  }

  // ── Chat: Get Messages ──────────────────────────────────────────────────────
  static Future<List<Message>> getMessages(String conversationId) async {
    final user = await StorageService.getUser();
    final token = await StorageService.getToken();

    if (token != null && token.isNotEmpty && !conversationId.startsWith('conv_')) {
      try {
        final res = await http
            .get(
              Uri.parse(ApiConstants.conversationMessages(conversationId)),
              headers: await _headers(),
            )
            .timeout(const Duration(seconds: 6));

        if (res.statusCode == 200) {
          final data = jsonDecode(res.body);
          final rawList = data is List ? data : (data['messages'] as List? ?? []);
          return rawList
              .map((e) => Message.fromJson(e, currentUserId: user?.id ?? ''))
              .toList();
        }
      } catch (_) {}
    }

    return await StorageService.getCachedMessages(conversationId);
  }

  // ── Chat: Send Message ──────────────────────────────────────────────────────
  static Future<Message> sendMessage({
    required String conversationId,
    required String content,
    int type = 0,
    String? mediaUrl,
  }) async {
    final user = await StorageService.getUser();
    final token = await StorageService.getToken();

    if (token != null && token.isNotEmpty) {
      try {
        final res = await http.post(
          Uri.parse(ApiConstants.conversationMessages(conversationId)),
          headers: await _headers(),
          body: jsonEncode({
            'content': content,
            'type': type,
            if (mediaUrl != null) 'media_url': mediaUrl,
          }),
        ).timeout(const Duration(seconds: 6));

        final data = jsonDecode(res.body);
        if (res.statusCode >= 200 && res.statusCode < 300) {
          return Message.fromJson(data['message'] ?? data, currentUserId: user?.id ?? '');
        }
      } catch (_) {}
    }

    MessageType msgType = MessageType.text;
    if (type == 1) msgType = MessageType.image;
    if (type == 2) msgType = MessageType.video;
    if (type == 3) msgType = MessageType.voice;
    if (type == 4) msgType = MessageType.file;
    if (type == 5) msgType = MessageType.poll;
    if (type == 6) msgType = MessageType.product;
    if (type == 7) msgType = MessageType.ping;

    return Message(
      id: 'msg_${DateTime.now().millisecondsSinceEpoch}',
      conversationId: conversationId,
      senderId: user?.id ?? 'u_me',
      senderName: user?.displayName ?? 'Me',
      content: content,
      type: msgType,
      status: MessageStatus.delivered,
      mediaUrl: mediaUrl,
      isMe: true,
      createdAt: DateTime.now(),
    );
  }

  // ── Chat: Vote Poll ─────────────────────────────────────────────────────────
  static Future<void> votePoll({
    required String pollId,
    required String optionId,
  }) async {
    final res = await http.post(
      Uri.parse(ApiConstants.pollVote(pollId)),
      headers: await _headers(),
      body: jsonEncode({'option_id': optionId}),
    ).timeout(const Duration(seconds: 8));

    if (res.statusCode < 200 || res.statusCode >= 300) {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? 'Failed to submit vote');
    }
  }

  // ── Stories / Status ────────────────────────────────────────────────────────
  static Future<List<UserStories>> getStories() async {
    final res = await http
        .get(Uri.parse(ApiConstants.stories), headers: await _headers())
        .timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['stories'] as List? ?? []);
      return rawList.map((e) {
        final userId = e['user_id']?.toString() ?? '';
        final userName = e['user_name'] ?? e['author_name'] ?? 'Contact';
        final userAvatar = e['user_avatar'] ?? e['avatar_url'] ?? '';
        final items = (e['items'] as List? ?? [e])
            .map((item) => StoryItem.fromJson(item))
            .toList();
        return UserStories(
          userId: userId,
          userName: userName,
          userAvatar: userAvatar,
          stories: items,
        );
      }).toList();
    }
    return [];
  }

  static Future<void> postStory({
    required String mediaUrl,
    required String caption,
    String mediaType = 'image',
  }) async {
    final res = await http.post(
      Uri.parse(ApiConstants.stories),
      headers: await _headers(),
      body: jsonEncode({
        'media_url': mediaUrl,
        'caption': caption,
        'media_type': mediaType,
      }),
    ).timeout(const Duration(seconds: 10));

    if (res.statusCode < 200 || res.statusCode >= 300) {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? 'Failed to post story');
    }
  }

  // ── Calls: Get History ──────────────────────────────────────────────────────
  static Future<List<CallRecord>> getCallHistory() async {
    final res = await http
        .get(Uri.parse('${ApiConstants.apiV1}/calls/history'), headers: await _headers())
        .timeout(const Duration(seconds: 8));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['calls'] as List? ?? []);
      return rawList.map((e) => CallRecord.fromJson(e)).toList();
    }
    return [];
  }

  // ── Channels: Get Channels ──────────────────────────────────────────────────
  static Future<List<Channel>> getChannels() async {
    final res = await http
        .get(Uri.parse(ApiConstants.channels), headers: await _headers())
        .timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['channels'] as List? ?? []);
      return rawList.map((e) {
        return Channel(
          id: e['id']?.toString() ?? '',
          name: e['name'] ?? 'Channel',
          description: e['description'] ?? '',
          avatarUrl: e['avatar_url'] ?? '',
          followersCount: e['followers_count'] ?? e['subscribers_count'] ?? 0,
          isVerified: e['is_verified'] == true,
          isFollowing: e['is_following'] == true,
        );
      }).toList();
    }
    return [];
  }

  // ── Marketplace: Get Products ───────────────────────────────────────────────
  static Future<List<Product>> getProducts() async {
    final res = await http
        .get(Uri.parse(ApiConstants.products), headers: await _headers())
        .timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['products'] as List? ?? []);
      return rawList.map((e) => Product.fromJson(e)).toList();
    }
    return [];
  }

  // ── Marketplace: Checkout Order ─────────────────────────────────────────────
  static Future<Map<String, dynamic>> checkoutOrder({
    required List<String> productIds,
    required double totalAmount,
  }) async {
    final res = await http.post(
      Uri.parse('${ApiConstants.apiV1}/marketplace/orders'),
      headers: await _headers(),
      body: jsonEncode({
        'product_ids': productIds,
        'total_amount': totalAmount,
      }),
    ).timeout(const Duration(seconds: 10));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return data;
    }
    throw Exception(data['error'] ?? 'Checkout failed');
  }

  // ── WebRTC Calls: Get Call History ──────────────────────────────────────────
  static Future<List<CallRecord>> getCalls() async {
    return await getCallHistory();
  }
}
