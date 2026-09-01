import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/message.dart';

class StarredMessageService {
  static final StarredMessageService _instance = StarredMessageService._internal();
  factory StarredMessageService() => _instance;
  StarredMessageService._internal();

  static const String _prefKeyPrefix = 'gochat_starred_msgs_';
  static const String _prefIdsKey = 'gochat_starred_ids_list';

  final ValueNotifier<List<Message>> starredMessagesNotifier = ValueNotifier<List<Message>>([]);
  final ValueNotifier<Set<String>> starredIdsNotifier = ValueNotifier<Set<String>>({});

  bool _initialized = false;

  Future<void> init() async {
    if (_initialized) return;
    await _loadStarredMessages();
    _initialized = true;
  }

  Future<void> _loadStarredMessages() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final ids = prefs.getStringList(_prefIdsKey) ?? [];
      final List<Message> list = [];
      final Set<String> idSet = ids.toSet();

      for (final id in ids) {
        final jsonStr = prefs.getString('$_prefKeyPrefix$id');
        if (jsonStr != null) {
          try {
            final Map<String, dynamic> data = jsonDecode(jsonStr);
            list.add(Message.fromJson(data));
          } catch (e) {
            debugPrint('Error deserializing starred message $id: $e');
          }
        }
      }

      // Sort by newest first
      list.sort((a, b) => b.createdAt.compareTo(a.createdAt));

      starredIdsNotifier.value = idSet;
      starredMessagesNotifier.value = list;
    } catch (e) {
      debugPrint('Error loading starred messages: $e');
    }
  }

  bool isStarred(String messageId) {
    return starredIdsNotifier.value.contains(messageId);
  }

  Future<bool> toggleStar(Message message) async {
    await init();
    if (isStarred(message.id)) {
      await unstarMessage(message.id);
      return false;
    } else {
      await starMessage(message);
      return true;
    }
  }

  Future<void> starMessage(Message message) async {
    await init();
    try {
      final prefs = await SharedPreferences.getInstance();
      final jsonStr = jsonEncode(message.toJson());
      await prefs.setString('$_prefKeyPrefix${message.id}', jsonStr);

      final currentIds = prefs.getStringList(_prefIdsKey) ?? [];
      if (!currentIds.contains(message.id)) {
        currentIds.add(message.id);
        await prefs.setStringList(_prefIdsKey, currentIds);
      }

      final updatedSet = Set<String>.from(starredIdsNotifier.value)..add(message.id);
      starredIdsNotifier.value = updatedSet;

      final updatedList = List<Message>.from(starredMessagesNotifier.value);
      updatedList.removeWhere((m) => m.id == message.id);
      updatedList.insert(0, message);
      starredMessagesNotifier.value = updatedList;
    } catch (e) {
      debugPrint('Error starring message: $e');
    }
  }

  Future<void> unstarMessage(String messageId) async {
    await init();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove('$_prefKeyPrefix$messageId');

      final currentIds = prefs.getStringList(_prefIdsKey) ?? [];
      currentIds.remove(messageId);
      await prefs.setStringList(_prefIdsKey, currentIds);

      final updatedSet = Set<String>.from(starredIdsNotifier.value)..remove(messageId);
      starredIdsNotifier.value = updatedSet;

      final updatedList = List<Message>.from(starredMessagesNotifier.value)
        ..removeWhere((m) => m.id == messageId);
      starredMessagesNotifier.value = updatedList;
    } catch (e) {
      debugPrint('Error unstarring message: $e');
    }
  }

  List<Message> getStarredMessagesForConversation(String conversationId) {
    return starredMessagesNotifier.value
        .where((m) => m.conversationId == conversationId)
        .toList();
  }

  int getStarredCountForConversation(String conversationId) {
    return getStarredMessagesForConversation(conversationId).length;
  }
}
