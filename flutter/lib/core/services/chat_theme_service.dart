import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/chat_theme.dart';

class ChatThemeService {
  static final ChatThemeService _instance = ChatThemeService._();
  factory ChatThemeService() => _instance;
  ChatThemeService._();

  static const String _keyPrefix = 'gochat_theme_conv_';
  static const String _globalKey = 'gochat_global_chat_theme';

  // In-memory cache for fast sync access
  final Map<String, ChatTheme> _cachedThemes = {};
  ChatTheme _globalTheme = ChatTheme.defaultEmerald;

  ChatTheme get globalTheme => _globalTheme;

  Future<void> init() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final globalRaw = prefs.getString(_globalKey);
      if (globalRaw != null && globalRaw.isNotEmpty) {
        _globalTheme = ChatTheme.fromJson(jsonDecode(globalRaw));
      }
    } catch (e) {
      debugPrint('[ChatThemeService] Error initializing themes: $e');
    }
  }

  /// Get theme for specific conversation. Falls back to global theme if none set.
  Future<ChatTheme> getThemeForConversation(String conversationId) async {
    if (_cachedThemes.containsKey(conversationId)) {
      return _cachedThemes[conversationId]!;
    }

    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString('$_keyPrefix$conversationId');
      if (raw != null && raw.isNotEmpty) {
        final theme = ChatTheme.fromJson(jsonDecode(raw));
        _cachedThemes[conversationId] = theme;
        return theme;
      }
    } catch (e) {
      debugPrint('[ChatThemeService] Error reading theme for $conversationId: $e');
    }

    return _globalTheme;
  }

  /// Synchronous getter from in-memory cache
  ChatTheme getCachedTheme(String conversationId) {
    return _cachedThemes[conversationId] ?? _globalTheme;
  }

  /// Set and persist custom theme for a specific conversation
  Future<void> setThemeForConversation(String conversationId, ChatTheme theme) async {
    _cachedThemes[conversationId] = theme;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('$_keyPrefix$conversationId', jsonEncode(theme.toJson()));
    } catch (e) {
      debugPrint('[ChatThemeService] Error saving theme for $conversationId: $e');
    }
  }

  /// Reset conversation to default theme
  Future<void> resetThemeForConversation(String conversationId) async {
    _cachedThemes.remove(conversationId);
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove('$_keyPrefix$conversationId');
    } catch (e) {
      debugPrint('[ChatThemeService] Error resetting theme for $conversationId: $e');
    }
  }

  /// Set global default theme
  Future<void> setGlobalTheme(ChatTheme theme) async {
    _globalTheme = theme;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_globalKey, jsonEncode(theme.toJson()));
    } catch (e) {
      debugPrint('[ChatThemeService] Error saving global theme: $e');
    }
  }
}
