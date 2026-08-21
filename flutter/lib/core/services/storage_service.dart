import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/models.dart';

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

  // ── Theme Preference ────────────────────────────────────────────────────────
  static Future<void> saveThemeMode(String mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_keyThemeMode, mode);
  }

  static Future<String?> getThemeMode() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_keyThemeMode);
  }

  // ── Offline Conversation Caching ────────────────────────────────────────────
  static Future<void> saveCachedConversations(List<Conversation> conversations) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final data = conversations.map((c) => c.toJson()).toList();
      await prefs.setString(_keyConversations, jsonEncode(data));
    } catch (_) {}
  }

  static Future<List<Conversation>> getCachedConversations({String currentUserId = ''}) async {
    try {
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

  // ── Offline Message Caching ─────────────────────────────────────────────────
  static Future<void> saveCachedMessages(String convId, List<Message> messages) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final data = messages.map((m) => m.toJson()).toList();
      await prefs.setString('$_keyMessagesPrefix$convId', jsonEncode(data));
    } catch (_) {}
  }

  static Future<List<Message>> getCachedMessages(String convId, {String currentUserId = ''}) async {
    try {
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
