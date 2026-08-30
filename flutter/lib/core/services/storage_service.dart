import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/models.dart';
import 'database_service.dart';

class StorageService {
  static const String _keyToken = 'auth_token';
  static const String _keyUser = 'current_user';
  static const String _keyThemeMode = 'theme_mode';
  static const String _keyConversations = 'cached_conversations';
  static const String _keyMessagesPrefix = 'cached_msgs_';

  // ── Auth & User ─────────────────────────────────────────────────────────────
  static Future<void> saveToken(String token) async {
    if (token.startsWith('gochat_session_') || token.split('.').length != 3) {
      // Reject non-JWT tokens
      return;
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_keyToken, token);
  }

  static Future<String?> getToken() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(_keyToken);
    if (token == null || token.isEmpty) return null;
    if (token.startsWith('gochat_session_') || token.split('.').length != 3) {
      // Purge stale synthetic token from storage immediately
      await prefs.remove(_keyToken);
      return null;
    }
    return token;
  }

  static Future<void> saveUser(User user) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_keyUser, jsonEncode(user.toJson()));
  }

  static Future<User?> getUser() async {
    final prefs = await SharedPreferences.getInstance();
    final userStr = prefs.getString(_keyUser);
    if (userStr == null) return null;
    try {
      return User.fromJson(jsonDecode(userStr));
    } catch (_) {
      return null;
    }
  }

  // ── Store Profile Caching ──────────────────────────────────────────────────
  static const String _keyMyStore = 'my_store_profile';

  static Future<void> saveMyStore(StoreProfile store) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_keyMyStore, jsonEncode(store.toJson()));
  }

  static Future<StoreProfile?> getMyStore() async {
    final prefs = await SharedPreferences.getInstance();
    final str = prefs.getString(_keyMyStore);
    if (str == null || str.isEmpty) return null;
    try {
      return StoreProfile.fromJson(jsonDecode(str));
    } catch (_) {
      return null;
    }
  }

  // ── Theme Preference ────────────────────────────────────────────────────────
  static Future<void> saveThemeMode(String mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_keyThemeMode, mode);
  }

  static Future<String?> getThemeMode() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_keyThemeMode);
  }

  // ── Offline Conversation Caching (SQLite) ──────────────────────────────────
  static Future<void> saveCachedConversations(List<Conversation> conversations) async {
    try {
      await DatabaseService().saveConversations(conversations);
      // Also backup to SharedPreferences as redundancy
      final prefs = await SharedPreferences.getInstance();
      final data = conversations.map((c) => c.toJson()).toList();
      await prefs.setString(_keyConversations, jsonEncode(data));
    } catch (_) {}
  }

  static Future<List<Conversation>> getCachedConversations({String currentUserId = ''}) async {
    try {
      final dbConvs = await DatabaseService().getConversations(currentUserId: currentUserId);
      if (dbConvs.isNotEmpty) return dbConvs;

      // Fallback to SharedPreferences if DB empty
      final prefs = await SharedPreferences.getInstance();
      final str = prefs.getString(_keyConversations);
      if (str == null || str.isEmpty) return [];
      final List list = jsonDecode(str);
      return list
          .map((item) => Conversation.fromJson(item as Map<String, dynamic>, currentUserId: currentUserId))
          .toList();
    } catch (_) {
      return [];
    }
  }

  // ── Offline Message Caching (SQLite) ───────────────────────────────────────
  static Future<void> saveCachedMessages(String convId, List<Message> messages) async {
    try {
      await DatabaseService().saveMessages(convId, messages);
      final prefs = await SharedPreferences.getInstance();
      final data = messages.map((m) => m.toJson()).toList();
      await prefs.setString('$_keyMessagesPrefix$convId', jsonEncode(data));
    } catch (_) {}
  }

  static Future<List<Message>> getCachedMessages(String convId, {String currentUserId = ''}) async {
    try {
      final dbMsgs = await DatabaseService().getMessages(convId, currentUserId: currentUserId);
      if (dbMsgs.isNotEmpty) return dbMsgs;

      final prefs = await SharedPreferences.getInstance();
      final str = prefs.getString('$_keyMessagesPrefix$convId');
      if (str == null || str.isEmpty) return [];
      final List list = jsonDecode(str);
      return list
          .map((item) => Message.fromJson(item as Map<String, dynamic>, currentUserId: currentUserId))
          .toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> clearAuth() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_keyToken);
    await prefs.remove(_keyUser);
  }
}
