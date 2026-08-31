import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';
import '../models/models.dart';

/// WhatsApp-style local SQLite database service (`gochat_msgstore.db`).
/// Provides fast, indexed, offline-first persistence for chats, messages, and outbox sync.
class DatabaseService {
  static final DatabaseService _instance = DatabaseService._();
  factory DatabaseService() => _instance;
  DatabaseService._();

  static const String _dbName = 'gochat_msgstore.db';
  static const int _dbVersion = 2;

  Database? _db;

  Future<Database> get database async {
    if (_db != null && _db!.isOpen) return _db!;
    _db = await _initDatabase();
    return _db!;
  }

  Future<Database> _initDatabase() async {
    final dbPath = await getDatabasesPath();
    final path = p.join(dbPath, _dbName);

    debugPrint('[DatabaseService] Initializing SQLite DB at $path');

    return await openDatabase(
      path,
      version: _dbVersion,
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) {
          try {
            await db.execute('ALTER TABLE messages ADD COLUMN is_view_once INTEGER DEFAULT 0;');
            await db.execute('ALTER TABLE messages ADD COLUMN is_opened INTEGER DEFAULT 0;');
            await db.execute('ALTER TABLE messages ADD COLUMN disappearing_duration INTEGER;');
            await db.execute('ALTER TABLE messages ADD COLUMN expires_at INTEGER;');
          } catch (e) {
            debugPrint('[DatabaseService] Migration v2 column addition notice: $e');
          }
        }
      },
      onCreate: (db, version) async {
        final batch = db.batch();

        // 1. Conversations Table
        batch.execute('''
          CREATE TABLE conversations (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            avatar_url TEXT,
            type TEXT NOT NULL,
            invitation_status TEXT NOT NULL,
            invitation_sender_id TEXT,
            partner_pin TEXT,
            unread_count INTEGER DEFAULT 0,
            is_online INTEGER DEFAULT 0,
            is_muted INTEGER DEFAULT 0,
            is_pinned INTEGER DEFAULT 0,
            last_message_json TEXT,
            member_ids_json TEXT,
            updated_at INTEGER NOT NULL
          );
        ''');

        // 2. Messages Table
        batch.execute('''
          CREATE TABLE messages (
            id TEXT PRIMARY KEY,
            conversation_id TEXT NOT NULL,
            sender_id TEXT NOT NULL,
            sender_name TEXT,
            content TEXT,
            type TEXT NOT NULL,
            status TEXT NOT NULL,
            media_path TEXT,
            media_url TEXT,
            media_thumbnail TEXT,
            media_duration INTEGER,
            media_size INTEGER,
            is_ping INTEGER DEFAULT 0,
            is_view_once INTEGER DEFAULT 0,
            is_opened INTEGER DEFAULT 0,
            disappearing_duration INTEGER,
            expires_at INTEGER,
            poll_data_json TEXT,
            product_data_json TEXT,
            reply_to_id TEXT,
            reply_to_text TEXT,
            reply_to_sender_name TEXT,
            reactions_json TEXT,
            created_at INTEGER NOT NULL
          );
        ''');

        // 3. Performance Indexes
        batch.execute('''
          CREATE INDEX idx_messages_conv_created 
          ON messages (conversation_id, created_at DESC);
        ''');

        batch.execute('''
          CREATE INDEX idx_messages_status 
          ON messages (status);
        ''');

        // 4. Offline Sync Outbox Queue Table
        batch.execute('''
          CREATE TABLE sync_outbox (
            id TEXT PRIMARY KEY,
            conversation_id TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            retry_count INTEGER DEFAULT 0,
            last_attempt INTEGER DEFAULT 0,
            created_at INTEGER NOT NULL
          );
        ''');

        await batch.commit();
        debugPrint('[DatabaseService] SQLite schema created successfully.');
      },
    );
  }

  // ── Conversations ───────────────────────────────────────────────────────────

  /// Save or replace a list of conversations in a single transaction
  Future<void> saveConversations(List<Conversation> conversations) async {
    try {
      final db = await database;
      final batch = db.batch();

      for (final c in conversations) {
        batch.insert(
          'conversations',
          {
            'id': c.id,
            'title': c.title,
            'avatar_url': c.avatarUrl,
            'type': c.type.name,
            'invitation_status': c.invitationStatus.name,
            'invitation_sender_id': c.invitationSenderId,
            'partner_pin': c.partnerPin,
            'unread_count': c.unreadCount,
            'is_online': c.isOnline ? 1 : 0,
            'is_muted': c.isMuted ? 1 : 0,
            'is_pinned': c.isPinned ? 1 : 0,
            'last_message_json': c.lastMessage != null
                ? jsonEncode(c.lastMessage!.toJson())
                : null,
            'member_ids_json': jsonEncode(c.memberIds),
            'updated_at': c.updatedAt.millisecondsSinceEpoch,
          },
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }

      await batch.commit(noResult: true);
    } catch (e) {
      debugPrint('[DatabaseService] Error saving conversations: $e');
    }
  }

  /// Retrieve all conversations sorted by pinned first, then by updated_at descending
  Future<List<Conversation>> getConversations({String currentUserId = ''}) async {
    try {
      final db = await database;
      final rows = await db.query(
        'conversations',
        orderBy: 'is_pinned DESC, updated_at DESC',
      );

      return rows.map((row) {
        Message? lastMsg;
        if (row['last_message_json'] != null) {
          try {
            lastMsg = Message.fromJson(
              jsonDecode(row['last_message_json'] as String),
              currentUserId: currentUserId,
            );
          } catch (_) {}
        }

        List<String> memberIds = [];
        if (row['member_ids_json'] != null) {
          try {
            final list = jsonDecode(row['member_ids_json'] as String);
            if (list is List) {
              memberIds = list.map((e) => e.toString()).toList();
            }
          } catch (_) {}
        }

        return Conversation(
          id: (row['id'] ?? '').toString(),
          title: (row['title'] ?? 'Contact').toString(),
          avatarUrl: (row['avatar_url'] ?? '').toString(),
          type: ConversationType.values.firstWhere(
            (t) => t.name == row['type'],
            orElse: () => ConversationType.direct,
          ),
          invitationStatus: InvitationStatus.values.firstWhere(
            (s) => s.name == row['invitation_status'],
            orElse: () => InvitationStatus.accepted,
          ),
          invitationSenderId: row['invitation_sender_id'] as String?,
          partnerPin: row['partner_pin'] as String?,
          lastMessage: lastMsg,
          unreadCount: row['unread_count'] as int? ?? 0,
          isOnline: (row['is_online'] as int? ?? 0) == 1,
          isMuted: (row['is_muted'] as int? ?? 0) == 1,
          isPinned: (row['is_pinned'] as int? ?? 0) == 1,
          memberIds: memberIds,
          updatedAt: DateTime.fromMillisecondsSinceEpoch(
            row['updated_at'] as int? ?? DateTime.now().millisecondsSinceEpoch,
          ),
        );
      }).toList();
    } catch (e) {
      debugPrint('[DatabaseService] Error getting conversations: $e');
      return [];
    }
  }

  // ── Messages ────────────────────────────────────────────────────────────────

  /// Save or replace a list of messages for a conversation
  Future<void> saveMessages(String convId, List<Message> messages) async {
    try {
      final db = await database;
      final batch = db.batch();

      for (final m in messages) {
        batch.insert(
          'messages',
          _messageToRow(m),
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }

      await batch.commit(noResult: true);
    } catch (e) {
      debugPrint('[DatabaseService] Error saving messages: $e');
    }
  }

  /// Insert a single message (e.g. newly received or sent)
  Future<void> insertMessage(Message message) async {
    try {
      final db = await database;
      await db.insert(
        'messages',
        _messageToRow(message),
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    } catch (e) {
      debugPrint('[DatabaseService] Error inserting message: $e');
    }
  }

  /// Get messages for a conversation, ordered chronologically (oldest to newest)
  Future<List<Message>> getMessages(
    String convId, {
    int limit = 100,
    int offset = 0,
    String currentUserId = '',
  }) async {
    try {
      final db = await database;
      final rows = await db.query(
        'messages',
        where: 'conversation_id = ?',
        whereArgs: [convId],
        orderBy: 'created_at ASC',
        limit: limit,
        offset: offset,
      );

      return rows.map((row) => _rowToMessage(row, currentUserId: currentUserId)).toList();
    } catch (e) {
      debugPrint('[DatabaseService] Error getting messages for $convId: $e');
      return [];
    }
  }

  /// Update message status (e.g. pending -> sent -> delivered -> read)
  Future<void> updateMessageStatus(String messageId, MessageStatus status) async {
    try {
      final db = await database;
      await db.update(
        'messages',
        {'status': status.name},
        where: 'id = ?',
        whereArgs: [messageId],
      );
    } catch (e) {
      debugPrint('[DatabaseService] Error updating message status: $e');
    }
  }

  /// Update message media info (e.g. local path and remote URL)
  Future<void> updateMessageMedia({
    required String messageId,
    String? mediaPath,
    String? mediaUrl,
  }) async {
    try {
      final db = await database;
      final Map<String, dynamic> updates = {};
      if (mediaPath != null) updates['media_path'] = mediaPath;
      if (mediaUrl != null) updates['media_url'] = mediaUrl;

      if (updates.isNotEmpty) {
        await db.update(
          'messages',
          updates,
          where: 'id = ?',
          whereArgs: [messageId],
        );
      }
    } catch (e) {
      debugPrint('[DatabaseService] Error updating message media: $e');
    }
  }

  /// Instant full-text search across all stored messages
  Future<List<Message>> searchMessages(String query, {String currentUserId = ''}) async {
    try {
      final db = await database;
      final rows = await db.query(
        'messages',
        where: 'content LIKE ?',
        whereArgs: ['%$query%'],
        orderBy: 'created_at DESC',
        limit: 50,
      );

      return rows.map((row) => _rowToMessage(row, currentUserId: currentUserId)).toList();
    } catch (e) {
      debugPrint('[DatabaseService] Error searching messages: $e');
      return [];
    }
  }

  // ── Sync Outbox Queue ───────────────────────────────────────────────────────

  /// Add message to outbox queue for offline retry
  Future<void> addToOutbox({
    required String messageId,
    required String conversationId,
    required Map<String, dynamic> payload,
  }) async {
    try {
      final db = await database;
      await db.insert(
        'sync_outbox',
        {
          'id': messageId,
          'conversation_id': conversationId,
          'payload_json': jsonEncode(payload),
          'retry_count': 0,
          'last_attempt': 0,
          'created_at': DateTime.now().millisecondsSinceEpoch,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    } catch (e) {
      debugPrint('[DatabaseService] Error adding to outbox: $e');
    }
  }

  /// Retrieve all pending outbox items
  Future<List<Map<String, dynamic>>> getOutboxItems() async {
    try {
      final db = await database;
      return await db.query('sync_outbox', orderBy: 'created_at ASC');
    } catch (e) {
      debugPrint('[DatabaseService] Error getting outbox items: $e');
      return [];
    }
  }

  /// Remove item from outbox upon successful delivery
  Future<void> removeFromOutbox(String messageId) async {
    try {
      final db = await database;
      await db.delete('sync_outbox', where: 'id = ?', whereArgs: [messageId]);
    } catch (e) {
      debugPrint('[DatabaseService] Error removing from outbox: $e');
    }
  }

  /// Record a failed attempt on an outbox item
  Future<void> incrementOutboxRetry(String messageId) async {
    try {
      final db = await database;
      await db.rawUpdate('''
        UPDATE sync_outbox 
        SET retry_count = retry_count + 1, last_attempt = ? 
        WHERE id = ?
      ''', [DateTime.now().millisecondsSinceEpoch, messageId]);
    } catch (e) {
      debugPrint('[DatabaseService] Error incrementing outbox retry: $e');
    }
  }

  /// Mark a View-Once message as opened: wipes media URL, marks is_opened = 1, and deletes local file
  Future<void> markViewOnceAsOpened(String messageId) async {
    try {
      final db = await database;
      // 1. Get current row to find media path
      final rows = await db.query('messages', where: 'id = ?', whereArgs: [messageId], limit: 1);
      if (rows.isNotEmpty) {
        final localPath = rows.first['media_path'] as String?;
        if (localPath != null && localPath.isNotEmpty && !kIsWeb) {
          try {
            final file = File(localPath);
            if (await file.exists()) {
              await file.delete();
              debugPrint('[DatabaseService] Burned local media file for view-once: $localPath');
            }
          } catch (e) {
            debugPrint('[DatabaseService] Error deleting local view-once file: $e');
          }
        }
      }

      // 2. Update SQLite record
      await db.update(
        'messages',
        {
          'is_opened': 1,
          'media_path': null,
          'media_url': null,
          'content': 'Opened',
        },
        where: 'id = ?',
        whereArgs: [messageId],
      );
      debugPrint('[DatabaseService] Marked view-once message as opened: $messageId');
    } catch (e) {
      debugPrint('[DatabaseService] Error marking view-once message as opened: $e');
    }
  }

  /// Automatically purges expired disappearing messages from local SQLite database
  Future<int> cleanupExpiredMessages() async {
    try {
      final db = await database;
      final now = DateTime.now().millisecondsSinceEpoch;
      final count = await db.delete(
        'messages',
        where: 'expires_at IS NOT NULL AND expires_at < ?',
        whereArgs: [now],
      );
      if (count > 0) {
        debugPrint('[DatabaseService] Purged $count expired disappearing messages from SQLite');
      }
      return count;
    } catch (e) {
      debugPrint('[DatabaseService] Error cleaning up expired messages: $e');
      return 0;
    }
  }

  // ── Row Mappers ─────────────────────────────────────────────────────────────

  Map<String, dynamic> _messageToRow(Message m) {
    return {
      'id': m.id,
      'conversation_id': m.conversationId,
      'sender_id': m.senderId,
      'sender_name': m.senderName,
      'content': m.content,
      'type': m.type.name,
      'status': m.status.name,
      'media_path': m.mediaUrl != null && !m.mediaUrl!.startsWith('http') ? m.mediaUrl : null,
      'media_url': m.mediaUrl != null && m.mediaUrl!.startsWith('http') ? m.mediaUrl : null,
      'media_thumbnail': m.mediaThumbnail,
      'media_duration': m.mediaDuration,
      'media_size': m.mediaSize,
      'is_ping': m.isPing ? 1 : 0,
      'is_view_once': m.isViewOnce ? 1 : 0,
      'is_opened': m.isOpened ? 1 : 0,
      'disappearing_duration': m.disappearingDurationSeconds,
      'expires_at': m.expiresAt?.millisecondsSinceEpoch,
      'poll_data_json': m.pollData != null ? jsonEncode(m.pollData!.toJson()) : null,
      'product_data_json': m.productData != null ? jsonEncode(m.productData) : null,
      'reply_to_id': m.replyToId,
      'reply_to_text': m.replyToText,
      'reply_to_sender_name': m.replyToSenderName,
      'reactions_json': jsonEncode(m.reactions),
      'created_at': m.createdAt.millisecondsSinceEpoch,
    };
  }

  Message _rowToMessage(Map<String, dynamic> row, {String currentUserId = ''}) {
    final senderId = (row['sender_id'] ?? '').toString();
    final isMe = currentUserId.isNotEmpty && senderId == currentUserId;

    PollData? pollData;
    if (row['poll_data_json'] != null) {
      try {
        pollData = PollData.fromJson(jsonDecode(row['poll_data_json'] as String));
      } catch (_) {}
    }

    Map<String, dynamic>? productData;
    if (row['product_data_json'] != null) {
      try {
        productData = jsonDecode(row['product_data_json'] as String) as Map<String, dynamic>?;
      } catch (_) {}
    }

    Map<String, List<String>> reactions = {};
    if (row['reactions_json'] != null) {
      try {
        final decoded = jsonDecode(row['reactions_json'] as String);
        if (decoded is Map) {
          reactions = decoded.map((k, v) => MapEntry(
            k.toString(),
            (v as List).map((e) => e.toString()).toList(),
          ));
        }
      } catch (_) {}
    }

    final localPath = row['media_path'] as String?;
    final remoteUrl = row['media_url'] as String?;
    final expiresAtMillis = row['expires_at'] as int?;

    return Message(
      id: row['id'] as String,
      conversationId: row['conversation_id'] as String,
      senderId: senderId,
      senderName: (row['sender_name'] ?? (isMe ? 'Me' : 'User')).toString(),
      content: (row['content'] ?? '').toString(),
      type: MessageType.values.firstWhere(
        (t) => t.name == row['type'],
        orElse: () => MessageType.text,
      ),
      status: MessageStatus.values.firstWhere(
        (s) => s.name == row['status'],
        orElse: () => MessageStatus.sent,
      ),
      mediaUrl: localPath ?? remoteUrl,
      mediaThumbnail: row['media_thumbnail'] as String?,
      mediaDuration: row['media_duration'] as int?,
      mediaSize: row['media_size'] as int?,
      isPing: (row['is_ping'] as int? ?? 0) == 1,
      isViewOnce: (row['is_view_once'] as int? ?? 0) == 1,
      isOpened: (row['is_opened'] as int? ?? 0) == 1,
      disappearingDurationSeconds: row['disappearing_duration'] as int?,
      expiresAt: expiresAtMillis != null ? DateTime.fromMillisecondsSinceEpoch(expiresAtMillis) : null,
      pollData: pollData,
      productData: productData,
      replyToId: row['reply_to_id'] as String?,
      replyToText: row['reply_to_text'] as String?,
      replyToSenderName: row['reply_to_sender_name'] as String?,
      reactions: reactions,
      createdAt: DateTime.fromMillisecondsSinceEpoch(
        row['created_at'] as int? ?? DateTime.now().millisecondsSinceEpoch,
      ),
      isMe: isMe,
    );
  }
}
