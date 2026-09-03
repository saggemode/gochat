import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart' show debugPrint, kIsWeb;
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;
import '../constants/api_constants.dart';
import '../models/models.dart';
import '../utils/media_image_helper.dart';
import 'media_storage_service.dart';
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

  /// Check whether an error is due to missing network / offline connection
  static bool isNetworkException(dynamic error) {
    if (error is SocketException ||
        error is http.ClientException ||
        error is TimeoutException) {
      return true;
    }
    final msg = error.toString().toLowerCase();
    return msg.contains('socketexception') ||
        msg.contains('network is unreachable') ||
        msg.contains('failed host lookup') ||
        msg.contains('no address associated with hostname') ||
        msg.contains('connection refused') ||
        msg.contains('connection reset') ||
        msg.contains('connection closed') ||
        msg.contains('clientexception') ||
        msg.contains('timeoutexception') ||
        msg.contains('os error') ||
        msg.contains('offline');
  }

  /// Verify that the device currently has active internet connectivity
  static Future<bool> hasInternetConnection() async {
    if (kIsWeb) return true;
    try {
      final host = Uri.parse(ApiConstants.baseUrl).host;
      if (host.isNotEmpty && host != 'localhost' && host != '10.0.2.2' && host != '127.0.0.1') {
        final res = await InternetAddress.lookup(host).timeout(const Duration(seconds: 4));
        return res.isNotEmpty && res[0].rawAddress.isNotEmpty;
      }
      final res = await InternetAddress.lookup('google.com').timeout(const Duration(seconds: 4));
      return res.isNotEmpty && res[0].rawAddress.isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  // ── Auth: Register ──────────────────────────────────────────────────────────
  static Future<Map<String, dynamic>> register({
    String email = '',
    String password = '',
    String displayName = '',
    String phone = '',
    String countryCode = '',
  }) async {
    // 1. Verify device has active network connectivity
    final hasNet = await hasInternetConnection();
    if (!hasNet) {
      throw Exception('No internet connection. Please connect to the internet to register.');
    }

    final cleanPhone = phone.replaceAll(RegExp(r'[^\d+]'), '');
    final safeName = displayName.isNotEmpty
        ? displayName
        : (cleanPhone.isNotEmpty
              ? 'User ${cleanPhone.length > 4 ? cleanPhone.substring(cleanPhone.length - 4) : cleanPhone}'
              : 'GoChat User');
    final identifier = cleanPhone.isNotEmpty ? cleanPhone : email;
    final safePassword = password.isNotEmpty ? password : 'GoChat@Password123!';

    final body = <String, dynamic>{
      if (cleanPhone.isNotEmpty) 'phone': cleanPhone,
      if (email.isNotEmpty) 'email': email,
      'password': safePassword,
      'display_name': safeName,
      'country_code': countryCode.isNotEmpty ? countryCode : 'NG',
    };

    http.Response res;
    try {
      res = await http
          .post(
            Uri.parse(ApiConstants.register),
            headers: await _headers(requireAuth: false),
            body: jsonEncode(body),
          )
          .timeout(const Duration(seconds: 20));
    } catch (e) {
      if (isNetworkException(e)) {
        throw Exception('No internet connection. Please connect to the internet to register.');
      }
      rethrow;
    }

    if (res.statusCode >= 200 && res.statusCode < 300) {
      final data = jsonDecode(res.body);
      final token = data['access_token'] ?? data['token'] ?? '';
      if (isValidJwt(token)) {
        await StorageService.saveToken(token);
      }
      return data;
    }

    // If user already exists on the backend, try logging in with the credentials
    final responseBody = res.body;
    if (res.statusCode == 409 ||
        responseBody.toLowerCase().contains('already exists') ||
        responseBody.toLowerCase().contains('already registered')) {
      if (identifier.isNotEmpty) {
        return await login(email: identifier, password: safePassword);
      }
    }

    try {
      final data = jsonDecode(responseBody);
      throw Exception(
        data['error'] ?? data['message'] ?? 'Registration failed (${res.statusCode})',
      );
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
    // 1. Verify device has active network connectivity
    final hasNet = await hasInternetConnection();
    if (!hasNet) {
      throw Exception('No internet connection. Please connect to the internet to sign in.');
    }

    final cleanIdentifier = email.trim();
    final safePassword = password.isNotEmpty ? password : '';

    http.Response res;
    try {
      res = await http
          .post(
            Uri.parse(ApiConstants.login),
            headers: await _headers(requireAuth: false),
            body: jsonEncode({
              'email': cleanIdentifier,
              'password': safePassword,
            }),
          )
          .timeout(const Duration(seconds: 20));
    } catch (e) {
      if (isNetworkException(e)) {
        throw Exception('No internet connection. Please connect to the internet to sign in.');
      }
      rethrow;
    }

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
      throw Exception(
        data['error'] ?? data['message'] ?? 'Login failed (${res.statusCode})',
      );
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
        'display_name': ?displayName,
        'status_text': ?statusText,
        'avatar_url': ?avatarUrl,
      };

      final res = await http
          .patch(
            Uri.parse('${ApiConstants.apiV1}/users/me'),
            headers: await _headers(),
            body: jsonEncode(body),
          )
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
    if (cleanPin.length < 4) return null;
    final token = await StorageService.getToken();
    if (token == null || token.isEmpty) return null;

    try {
      final res = await http
          .get(
            Uri.parse('${ApiConstants.apiV1}/users/$cleanPin'),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 4));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        return User.fromJson(data['user'] ?? data);
      }
    } catch (_) {}
    return null;
  }

  // ── Contacts: Sync Device Contacts with Registered GoChat Users ───────────
  static Future<List<Map<String, dynamic>>> syncContacts(List<String> identifiers) async {
    if (identifiers.isEmpty) return [];
    final token = await StorageService.getToken();
    if (token == null || token.isEmpty) return [];

    try {
      final res = await http
          .post(
            Uri.parse(ApiConstants.syncContacts),
            headers: await _headers(),
            body: jsonEncode({'identifiers': identifiers}),
          )
          .timeout(const Duration(seconds: 10));

      if (res.statusCode >= 200 && res.statusCode < 300) {
        final data = jsonDecode(res.body);
        if (data is List) {
          return data.map((e) => Map<String, dynamic>.from(e as Map)).toList();
        }
      }
    } catch (_) {}
    return [];
  }

  // ── Chat: Get Conversations ─────────────────────────────────────────────────
  static Future<List<Conversation>> getConversations() async {
    final res = await http
        .get(Uri.parse(ApiConstants.conversations), headers: await _headers())
        .timeout(const Duration(seconds: 30));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List
          ? data
          : (data['conversations'] as List? ?? []);
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
        final res = await http
            .post(
              Uri.parse(ApiConstants.conversations),
              headers: await _headers(),
              body: jsonEncode({
                'name': name,
                'member_ids': memberIds,
                'type': isGroup ? 1 : 0,
                'is_group': isGroup,
              }),
            )
            .timeout(const Duration(seconds: 25));

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

    if (token != null &&
        token.isNotEmpty &&
        !conversationId.startsWith('conv_')) {
      try {
        final res = await http
            .get(
              Uri.parse(ApiConstants.conversationMessages(conversationId)),
              headers: await _headers(),
            )
            .timeout(const Duration(seconds: 25));

        if (res.statusCode == 200) {
          final data = jsonDecode(res.body);
          final rawList = data is List
              ? data
              : (data['messages'] as List? ?? []);
          final list = rawList
              .map((e) => Message.fromJson(e, currentUserId: user?.id ?? ''))
              .toList();
          list.sort((a, b) => a.createdAt.compareTo(b.createdAt));
          return list;
        }
      } catch (_) {}
    }

    return await StorageService.getCachedMessages(conversationId);
  }

  // ── Media: Upload File ────────────────────────────────────────────────────
  /// Uploads a file (voice note, image, video, etc.) to /api/v1/media/upload with progress tracking.
  /// Returns the full TelegramUploadResult including file_id and thumbnail.
  static Future<TelegramUploadResult?> uploadMediaResult(
    String filePath, {
    String? mimeType,
    Function(double progress)? onProgress,
  }) async {
    try {
      final token = await StorageService.getToken();
      if (token == null || token.isEmpty) return null;

      final file = File(filePath);
      if (!file.existsSync()) return null;
      final fileLength = await file.length();

      final uri = Uri.parse('${ApiConstants.apiV1}/media/upload');
      final request = http.MultipartRequest('POST', uri);
      request.headers['Authorization'] = 'Bearer $token';

      final fileStream = file.openRead();
      int bytesUploaded = 0;

      final trackedStream = fileStream.transform(
        StreamTransformer<List<int>, List<int>>.fromHandlers(
          handleData: (List<int> data, EventSink<List<int>> sink) {
            bytesUploaded += data.length;
            if (fileLength > 0 && onProgress != null) {
              onProgress((bytesUploaded / fileLength).clamp(0.0, 1.0));
            }
            sink.add(data);
          },
        ),
      );

      final multipartFile = http.MultipartFile(
        'file',
        trackedStream,
        fileLength,
        filename: filePath.split(Platform.pathSeparator).last,
        contentType: mimeType != null
            ? _parseMediaType(mimeType)
            : null,
      );
      request.files.add(multipartFile);

      final streamedResponse = await request.send().timeout(
        const Duration(seconds: 120),
      );
      final responseBody = await streamedResponse.stream.bytesToString();
      final data = jsonDecode(responseBody);

      if (streamedResponse.statusCode >= 200 && streamedResponse.statusCode < 300) {
        final url = (data['url'] ?? data['Url'] ?? data['URL'] ?? '').toString();
        final fileId = (data['object_key'] ?? data['objectKey'] ?? data['file_id'] ?? data['fileId'] ?? '').toString();
        final thumbUrl = (data['thumbnail_url'] ?? data['thumbnailUrl'] ?? '').toString();
        final size = data['size_bytes'] ?? data['size'] ?? fileLength;

        onProgress?.call(1.0);
        return TelegramUploadResult(
          url: url.isNotEmpty ? url : null,
          fileId: fileId.isNotEmpty ? fileId : null,
          thumbnailUrl: thumbUrl.isNotEmpty ? thumbUrl : null,
          size: size is int ? size : int.tryParse(size.toString()),
          mimeType: mimeType,
        );
      }
    } catch (e) {
      debugPrint('[ApiService] uploadMedia error: $e');
    }
    return null;
  }

  /// Uploads a file and returns its public URL.
  static Future<String?> uploadMedia(
    String filePath, {
    String? mimeType,
    Function(double progress)? onProgress,
  }) async {
    final res = await uploadMediaResult(filePath, mimeType: mimeType, onProgress: onProgress);
    return res?.url;
  }

  /// Downloads a file from Telegram CDN proxy via `/api/v1/media/download/<file_id>`
  static Future<String?> downloadTelegramFile(
    String fileIdOrUrl, {
    MediaCategory category = MediaCategory.images,
    String ext = '.jpg',
    Function(double progress)? onProgress,
  }) async {
    try {
      String downloadUrl = fileIdOrUrl;
      if (!downloadUrl.startsWith('http://') && !downloadUrl.startsWith('https://')) {
        if (downloadUrl.startsWith('/') && !isLocalDevicePath(downloadUrl)) {
          downloadUrl = '${ApiConstants.baseUrl}$downloadUrl';
        } else if (!isLocalDevicePath(downloadUrl)) {
          downloadUrl = '${ApiConstants.apiV1}/media/download/$downloadUrl';
        }
      }

      return await MediaStorageService().downloadMediaWithProgress(
        remoteUrl: downloadUrl,
        category: category,
        ext: ext,
        onProgress: onProgress,
      );
    } catch (e) {
      debugPrint('[ApiService] downloadTelegramFile error: $e');
      return null;
    }
  }

  /// Parse a MIME type string into a MediaType for http_parser.
  static MediaType? _parseMediaType(String mime) {
    try {
      final parts = mime.split('/');
      if (parts.length == 2) {
        return MediaType(parts[0], parts[1]);
      }
    } catch (_) {}
    return null;
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
        final res = await http
            .post(
              Uri.parse(ApiConstants.conversationMessages(conversationId)),
              headers: await _headers(),
              body: jsonEncode({
                'content': content,
                'type': type,
                'media_url': ?mediaUrl,
              }),
            )
            .timeout(const Duration(seconds: 6));

        final data = jsonDecode(res.body);
        if (res.statusCode >= 200 && res.statusCode < 300) {
          return Message.fromJson(
            data['message'] ?? data,
            currentUserId: user?.id ?? '',
          );
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
    final res = await http
        .post(
          Uri.parse(ApiConstants.pollVote(pollId)),
          headers: await _headers(),
          body: jsonEncode({'option_id': optionId}),
        )
        .timeout(const Duration(seconds: 8));

    if (res.statusCode < 200 || res.statusCode >= 300) {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? 'Failed to submit vote');
    }
  }

  // ── Stories / Status ────────────────────────────────────────────────────────
  static Future<List<UserStories>> getStories() async {
    final res = await http
        .get(Uri.parse(ApiConstants.stories), headers: await _headers())
        .timeout(const Duration(seconds: 30));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['feed'] as List? ?? data['stories'] as List? ?? []);
      return rawList.map((e) {
        final userId = e['user_id']?.toString() ?? '';
        final userName = e['user_display_name'] ?? e['user_name'] ?? e['author_name'] ?? 'Contact';
        final userAvatar = e['user_avatar_url'] ?? e['user_avatar'] ?? e['avatar_url'] ?? '';
        final storiesList = e['stories'] as List? ?? e['items'] as List?;
        final items = storiesList != null && storiesList.isNotEmpty
            ? storiesList.map((item) => StoryItem.fromJson(item)).toList()
            : <StoryItem>[];
        // Skip users with no stories
        if (items.isEmpty) return null;
        return UserStories(
          userId: userId,
          userName: userName,
          userAvatar: userAvatar,
          stories: items,
        );
      }).whereType<UserStories>().toList();
    }
    return [];
  }

  static Future<void> postStory({
    required String mediaUrl,
    required String caption,
    String mediaType = 'image',
  }) async {
    final res = await http
        .post(
          Uri.parse(ApiConstants.stories),
          headers: await _headers(),
          body: jsonEncode({
            'media_url': mediaUrl,
            'caption': caption,
            'media_type': mediaType,
          }),
        )
        .timeout(const Duration(seconds: 30));

    if (res.statusCode < 200 || res.statusCode >= 300) {
      final data = jsonDecode(res.body);
      throw Exception(data['error'] ?? 'Failed to post story');
    }
  }

  // ── Calls: Start Call ───────────────────────────────────────────────────────
  static Future<CallRecord> startCall({
    required String receiverId,
    String type = 'voice',
  }) async {
    final user = await StorageService.getUser();
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/calls'),
          headers: await _headers(),
          body: jsonEncode({'receiver_id': receiverId, 'type': type}),
        )
        .timeout(const Duration(seconds: 15));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return CallRecord.fromJson(data, currentUserId: user?.id ?? '');
    }
    throw Exception(
      data['error'] ?? 'Failed to start call (${res.statusCode})',
    );
  }

  // ── Calls: Accept Call ──────────────────────────────────────────────────────
  static Future<CallRecord> acceptCall(String callId) async {
    final user = await StorageService.getUser();
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/calls/$callId/accept'),
          headers: await _headers(),
          body: jsonEncode({}),
        )
        .timeout(const Duration(seconds: 15));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return CallRecord.fromJson(data, currentUserId: user?.id ?? '');
    }
    throw Exception(
      data['error'] ?? 'Failed to accept call (${res.statusCode})',
    );
  }

  // ── Calls: Reject Call ──────────────────────────────────────────────────────
  static Future<CallRecord> rejectCall(
    String callId, {
    bool isBusy = false,
  }) async {
    final user = await StorageService.getUser();
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/calls/$callId/reject'),
          headers: await _headers(),
          body: jsonEncode({'is_busy': isBusy}),
        )
        .timeout(const Duration(seconds: 15));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return CallRecord.fromJson(data, currentUserId: user?.id ?? '');
    }
    throw Exception(
      data['error'] ?? 'Failed to reject call (${res.statusCode})',
    );
  }

  // ── Calls: End Call ─────────────────────────────────────────────────────────
  static Future<CallRecord> endCall(String callId) async {
    final user = await StorageService.getUser();
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/calls/$callId/end'),
          headers: await _headers(),
          body: jsonEncode({}),
        )
        .timeout(const Duration(seconds: 15));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return CallRecord.fromJson(data, currentUserId: user?.id ?? '');
    }
    throw Exception(data['error'] ?? 'Failed to end call (${res.statusCode})');
  }

  // ── Calls: Send WebRTC Signaling Message ────────────────────────────────────
  static Future<bool> sendSignalingMessage({
    required String callId,
    required String receiverId,
    required String type,
    String? sdp,
    String? candidate,
  }) async {
    final payload = <String, dynamic>{'receiver_id': receiverId, 'type': type};
    if (sdp != null) payload['sdp'] = sdp;
    if (candidate != null) payload['candidate'] = candidate;

    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/calls/$callId/signaling'),
          headers: await _headers(),
          body: jsonEncode(payload),
        )
        .timeout(const Duration(seconds: 15));

    if (res.statusCode >= 200 && res.statusCode < 300) {
      return true;
    }
    return false;
  }

  // ── Calls: Get History ──────────────────────────────────────────────────────
  static Future<List<CallRecord>> getCallHistory() async {
    final user = await StorageService.getUser();
    final res = await http
        .get(
          Uri.parse('${ApiConstants.apiV1}/calls/history'),
          headers: await _headers(),
        )
        .timeout(const Duration(seconds: 8));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['calls'] as List? ?? []);
      return rawList
          .map((e) => CallRecord.fromJson(e, currentUserId: user?.id ?? ''))
          .toList();
    }
    return [];
  }

  // ── Channels: Get Channels ──────────────────────────────────────────────────
  static Future<List<Channel>> getChannels() async {
    final res = await http
        .get(Uri.parse(ApiConstants.channels), headers: await _headers())
        .timeout(const Duration(seconds: 30));

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
        .get(
          Uri.parse(ApiConstants.products),
          headers: await _headers(requireAuth: false),
        )
        .timeout(const Duration(seconds: 30));

    if (res.statusCode == 200) {
      final data = jsonDecode(res.body);
      final rawList = data is List ? data : (data['products'] as List? ?? []);
      return rawList.map((e) => Product.fromJson(e)).toList();
    }
    return [];
  }

  // ── Business: Store Profile & Products ───────────────────────────────────────
  static Future<StoreProfile?> getBusinessProfile() async {
    try {
      final res = await http
          .get(
            Uri.parse('${ApiConstants.apiV1}/business/profile'),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        if (data['profile'] != null || data['business_name'] != null) {
          return StoreProfile.fromJson(data['profile'] ?? data);
        }
      }
    } catch (_) {}
    return null;
  }

  static Future<StoreProfile> createBusinessProfile(StoreProfile store) async {
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/business/profile'),
          headers: await _headers(),
          body: jsonEncode(store.toJson()),
        )
        .timeout(const Duration(seconds: 30));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return StoreProfile.fromJson(data['profile'] ?? data);
    }
    throw Exception(
      data['error'] ?? 'Failed to create business profile (${res.statusCode})',
    );
  }

  static Future<Product> createProduct(Product product) async {
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/business/products'),
          headers: await _headers(),
          body: jsonEncode({
            'name': product.title,
            'title': product.title,
            'description': product.description,
            'price': product.price,
            'currency': product.currency,
            'category': product.category,
            'category_id': product.category,
            'image_url': product.imageUrl,
            'image_urls': product.images.isNotEmpty ? product.images : (product.imageUrl.isNotEmpty ? [product.imageUrl] : []),
            'in_stock': product.inStock,
          }),
        )
        .timeout(const Duration(seconds: 30));

    final data = jsonDecode(res.body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return Product.fromJson(data['product'] ?? data);
    }
    throw Exception(
      data['error'] ?? 'Failed to create product (${res.statusCode})',
    );
  }

  static Future<Product> updateProduct(Product product) async {
    try {
      final res = await http
          .put(
            Uri.parse('${ApiConstants.apiV1}/business/products/${product.id}'),
            headers: await _headers(),
            body: jsonEncode(product.toJson()),
          )
          .timeout(const Duration(seconds: 15));

      if (res.statusCode >= 200 && res.statusCode < 300) {
        final data = jsonDecode(res.body);
        return Product.fromJson(data['product'] ?? data);
      }
    } catch (_) {}
    return product;
  }

  static Future<bool> deleteProduct(String productId) async {
    try {
      final res = await http
          .delete(
            Uri.parse('${ApiConstants.apiV1}/business/products/$productId'),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return true;
    }
  }

  // ── Product Variants ───────────────────────────────────────────────────────
  static Future<ProductVariant> createProductVariant(
    String productId,
    ProductVariant variant,
  ) async {
    try {
      final res = await http
          .post(
            Uri.parse(
              '${ApiConstants.apiV1}/business/products/$productId/variants',
            ),
            headers: await _headers(),
            body: jsonEncode(variant.toJson()),
          )
          .timeout(const Duration(seconds: 15));

      if (res.statusCode >= 200 && res.statusCode < 300) {
        final data = jsonDecode(res.body);
        return ProductVariant.fromJson(data);
      }
    } catch (_) {}
    return variant;
  }

  static Future<List<ProductVariant>> listProductVariants(
    String productId,
  ) async {
    try {
      final res = await http
          .get(
            Uri.parse(
              '${ApiConstants.apiV1}/business/products/$productId/variants',
            ),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['variants'] as List? ?? []);
        return list.map((e) => ProductVariant.fromJson(e)).toList();
      }
    } catch (_) {}
    return [];
  }

  static Future<ProductVariant> updateProductVariant(
    String productId,
    String variantId,
    ProductVariant variant,
  ) async {
    try {
      final res = await http
          .put(
            Uri.parse(
              '${ApiConstants.apiV1}/business/products/$productId/variants/$variantId',
            ),
            headers: await _headers(),
            body: jsonEncode(variant.toJson()),
          )
          .timeout(const Duration(seconds: 15));

      if (res.statusCode >= 200 && res.statusCode < 300) {
        final data = jsonDecode(res.body);
        return ProductVariant.fromJson(data);
      }
    } catch (_) {}
    return variant;
  }

  static Future<bool> deleteProductVariant(
    String productId,
    String variantId,
  ) async {
    try {
      final res = await http
          .delete(
            Uri.parse(
              '${ApiConstants.apiV1}/business/products/$productId/variants/$variantId',
            ),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return true;
    }
  }

  static Future<void> trackProductView(String productId) async {
    try {
      await http
          .post(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/products/$productId/view',
            ),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 5));
    } catch (_) {}
  }

  // ── Store Orders ────────────────────────────────────────────────────────────
  static Future<List<MarketplaceOrder>> getSellerOrders() async {
    try {
      final res = await http
          .get(
            Uri.parse('${ApiConstants.apiV1}/business/orders'),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['orders'] as List? ?? []);
        return list.map((e) => MarketplaceOrder.fromJson(e)).toList();
      }
    } catch (_) {}
    return [];
  }

  static Future<List<MarketplaceOrder>> getBuyerOrders() async {
    try {
      final res = await http
          .get(
            Uri.parse('${ApiConstants.apiV1}/marketplace/orders/buyer'),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['orders'] as List? ?? []);
        return list.map((e) => MarketplaceOrder.fromJson(e)).toList();
      }
    } catch (_) {}
    return [];
  }

  // ── Store Coupons ───────────────────────────────────────────────────────────
  static Future<List<StoreCoupon>> getStoreCoupons() async {
    try {
      final res = await http
          .get(
            Uri.parse('${ApiConstants.apiV1}/business/coupons'),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['coupons'] as List? ?? []);
        return list.map((e) => StoreCoupon.fromJson(e)).toList();
      }
    } catch (_) {}
    return [];
  }

  static Future<StoreCoupon> createStoreCoupon(StoreCoupon coupon) async {
    try {
      final res = await http
          .post(
            Uri.parse('${ApiConstants.apiV1}/business/coupons'),
            headers: await _headers(),
            body: jsonEncode(coupon.toJson()),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode >= 200 && res.statusCode < 300) {
        final data = jsonDecode(res.body);
        return StoreCoupon.fromJson(data['coupon'] ?? data);
      }
    } catch (_) {}
    return coupon;
  }

  static Future<bool> updateOrderStatus(String orderId, String status) async {
    try {
      final res = await http
          .put(
            Uri.parse('${ApiConstants.apiV1}/business/orders/$orderId/status'),
            headers: await _headers(),
            body: jsonEncode({'status': status}),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> updateOrderTracking(
    String orderId, {
    required String trackingNumber,
    String? carrier,
    String? trackingUrl,
  }) async {
    try {
      final res = await http
          .put(
            Uri.parse(
              '${ApiConstants.apiV1}/business/orders/$orderId/tracking',
            ),
            headers: await _headers(),
            body: jsonEncode({
              'tracking_number': trackingNumber,
              'tracking_carrier': carrier ?? 'Standard Delivery',
              'tracking_url': trackingUrl ?? '',
            }),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return false;
    }
  }

  static Future<Map<String, dynamic>> validateCoupon(
    String businessId,
    String code,
    double subtotal,
  ) async {
    try {
      final res = await http
          .post(
            Uri.parse(ApiConstants.validateCoupon),
            headers: await _headers(),
            body: jsonEncode({
              'business_id': businessId,
              'code': code,
              'subtotal': subtotal,
            }),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        return jsonDecode(res.body);
      }
    } catch (_) {}
    return {'valid': false};
  }

  static Future<bool> toggleWishlist(String productId) async {
    try {
      final res = await http
          .post(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/products/$productId/wishlist',
            ),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        return data['is_wishlisted'] == true;
      }
    } catch (_) {}
    return false;
  }

  static Future<List<Product>> getWishlist() async {
    try {
      final res = await http
          .get(Uri.parse(ApiConstants.wishlist), headers: await _headers())
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['products'] as List? ?? []);
        return list.map((e) => Product.fromJson(e)).toList();
      }
    } catch (_) {}
    return [];
  }

  static Future<bool> createReview(
    String productId,
    double rating,
    String comment,
  ) async {
    try {
      final res = await http
          .post(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/products/$productId/reviews',
            ),
            headers: await _headers(),
            body: jsonEncode({'rating': rating, 'comment': comment}),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return false;
    }
  }

  static Future<List<Map<String, dynamic>>> listReviews(
    String productId,
  ) async {
    try {
      final res = await http
          .get(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/products/$productId/reviews',
            ),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['reviews'] as List? ?? []);
        return List<Map<String, dynamic>>.from(list);
      }
    } catch (_) {}
    return [];
  }

  static Future<bool> askProductQuestion(
    String productId,
    String question,
  ) async {
    try {
      final res = await http
          .post(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/products/$productId/questions',
            ),
            headers: await _headers(),
            body: jsonEncode({'question': question}),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> answerProductQuestion(
    String questionId,
    String answer,
  ) async {
    try {
      final res = await http
          .post(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/questions/$questionId/answer',
            ),
            headers: await _headers(),
            body: jsonEncode({'answer': answer}),
          )
          .timeout(const Duration(seconds: 15));
      return res.statusCode >= 200 && res.statusCode < 300;
    } catch (_) {
      return false;
    }
  }

  static Future<List<Map<String, dynamic>>> getProductQuestions(
    String productId,
  ) async {
    try {
      final res = await http
          .get(
            Uri.parse(
              '${ApiConstants.apiV1}/marketplace/products/$productId/questions',
            ),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 15));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        final list = data is List ? data : (data['questions'] as List? ?? []);
        return List<Map<String, dynamic>>.from(list);
      }
    } catch (_) {}
    return [];
  }

  // ── Marketplace: Checkout Order ─────────────────────────────────────────────
  static Future<Map<String, dynamic>> checkoutOrder({
    required List<String> productIds,
    required double totalAmount,
  }) async {
    final res = await http
        .post(
          Uri.parse('${ApiConstants.apiV1}/marketplace/orders'),
          headers: await _headers(),
          body: jsonEncode({
            'product_ids': productIds,
            'total_amount': totalAmount,
          }),
        )
        .timeout(const Duration(seconds: 30));

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
