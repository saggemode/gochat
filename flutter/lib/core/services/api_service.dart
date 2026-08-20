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
  static Future<Map<String, String>> _headers() async {
    final token = await StorageService.getToken();
    return {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      if (token != null && token.isNotEmpty) 'Authorization': 'Bearer $token',
    };
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
    final safeEmail = email.isNotEmpty
        ? email
        : (cleanPhone.isNotEmpty ? '${cleanPhone.replaceAll('+', '')}@gochat.app' : 'user@gochat.app');
    final safePassword = password.isNotEmpty ? password : 'GoChat@Password123!';
    final safeName = displayName.isNotEmpty
        ? displayName
        : (cleanPhone.isNotEmpty ? 'User ${cleanPhone.length > 4 ? cleanPhone.substring(cleanPhone.length - 4) : cleanPhone}' : 'GoChat User');

    final body = <String, dynamic>{
      'email': safeEmail,
      'password': safePassword,
      'display_name': safeName,
      'phone': cleanPhone,
      'country_code': countryCode.isNotEmpty ? countryCode : 'NG',
    };

    final res = await http.post(
      Uri.parse(ApiConstants.register),
      headers: await _headers(),
      body: jsonEncode(body),
    ).timeout(const Duration(seconds: 12));

    if (res.statusCode >= 200 && res.statusCode < 300) {
      return jsonDecode(res.body);
    }
    
    try {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? data['message'] ?? 'Registration failed (${res.statusCode})');
    } catch (e) {
      if (e is Exception) rethrow;
      throw Exception('Server error (${res.statusCode})');
    }
  }

  // ── Auth: Login ─────────────────────────────────────────────────────────────
  static Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  }) async {
    final cleanIdentifier = email.trim();
    final safeEmail = cleanIdentifier.contains('@')
        ? cleanIdentifier
        : '${cleanIdentifier.replaceAll(RegExp(r'[^\w]'), '')}@gochat.app';
    final safePassword = password.isNotEmpty ? password : 'GoChat@Password123!';

    final res = await http.post(
      Uri.parse(ApiConstants.login),
      headers: await _headers(),
      body: jsonEncode({
        'email': safeEmail,
        'password': safePassword,
      }),
    ).timeout(const Duration(seconds: 12));

    if (res.statusCode >= 200 && res.statusCode < 300) {
      return jsonDecode(res.body);
    }

    try {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? data['message'] ?? 'Login failed (${res.statusCode})');
    } catch (e) {
      if (e is Exception) rethrow;
      throw Exception('Server error (${res.statusCode})');
    }
  }

  // ── Auth: Get User Profile ──────────────────────────────────────────────────
  static Future<User?> getCurrentUser(String userId) async {
    try {
      final res = await http
          .get(Uri.parse('${ApiConstants.apiV1}/users/$userId'), headers: await _headers())
          .timeout(const Duration(seconds: 8));
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
      avatarUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
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

    if (token != null && token.isNotEmpty && !conversationId.startsWith('conv_')) {
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
    return [
      CallRecord(
        id: 'call_1',
        callerId: 'u_1',
        callerName: 'Alex Rivera',
        callerAvatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
        type: CallType.video,
        direction: CallDirection.incoming,
        timestamp: DateTime.now().subtract(const Duration(minutes: 42)),
        durationSeconds: 310,
      ),
      CallRecord(
        id: 'call_2',
        callerId: 'u_2',
        callerName: 'Sarah Connor',
        callerAvatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=150',
        type: CallType.audio,
        direction: CallDirection.missed,
        timestamp: DateTime.now().subtract(const Duration(hours: 3)),
        durationSeconds: 0,
      ),
    ];
  }
}
