import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../models/models.dart';
import 'api_service.dart';
import 'database_service.dart';
import 'websocket_service.dart';

/// Worker service that flushes queued offline messages to the server.
/// 
/// Lifecycle:
/// 1. Offline send → Saved in SQLite + `sync_outbox` table with status `MessageStatus.pending` (🕒).
/// 2. Internet / WebSocket connects → `SyncOutboxService.flush()` runs automatically.
/// 3. Uploads pending local media files, delivers message payloads, updates status to `MessageStatus.sent` (✓).
class SyncOutboxService {
  static final SyncOutboxService _instance = SyncOutboxService._();
  factory SyncOutboxService() => _instance;
  SyncOutboxService._();

  bool _isFlushing = false;

  /// Process all pending outbox queue items
  Future<int> flush({
    required DatabaseService db,
    required WebSocketService wsService,
    Function(Message updatedMessage)? onMessageSent,
  }) async {
    if (_isFlushing) return 0;
    _isFlushing = true;

    int flushedCount = 0;

    try {
      final items = await db.getOutboxItems();
      if (items.isEmpty) {
        _isFlushing = false;
        return 0;
      }

      debugPrint('[SyncOutboxService] Flushing ${items.length} pending outbox messages...');

      for (final item in items) {
        final messageId = item['id'] as String;
        final convId = item['conversation_id'] as String;
        final payloadJson = item['payload_json'] as String;

        try {
          final payload = jsonDecode(payloadJson) as Map<String, dynamic>;
          final content = payload['content']?.toString() ?? '';
          final typeInt = payload['type'] is int ? payload['type'] as int : 0;
          String? mediaUrl = payload['media_url']?.toString();
          final mediaDuration = payload['media_duration'] as int?;

          // 1. If media is a local file path, upload to server first
          if (mediaUrl != null &&
              mediaUrl.isNotEmpty &&
              !mediaUrl.startsWith('http://') &&
              !mediaUrl.startsWith('https://')) {
            try {
              final file = File(mediaUrl);
              if (await file.exists()) {
                final mimeType = typeInt == 5 || typeInt == 3
                    ? 'audio/mp4'
                    : (typeInt == 1 ? 'image/jpeg' : 'application/octet-stream');

                final remoteUrl = await ApiService.uploadMedia(
                  mediaUrl,
                  mimeType: mimeType,
                );
                if (remoteUrl != null && remoteUrl.isNotEmpty) {
                  mediaUrl = remoteUrl;
                }
              }
            } catch (e) {
              debugPrint('[SyncOutboxService] Media upload warning: $e');
            }
          }

          // 2. Deliver message to server via REST or WebSocket
          final realMsg = await ApiService.sendMessage(
            conversationId: convId,
            content: content,
            type: typeInt,
            mediaUrl: mediaUrl,
          );

          // 3. Mark message as sent in SQLite
          await db.updateMessageStatus(messageId, MessageStatus.sent);
          if (mediaUrl != null) {
            await db.updateMessageMedia(
              messageId: messageId,
              mediaUrl: mediaUrl,
            );
          }

          // 4. Remove from outbox
          await db.removeFromOutbox(messageId);
          flushedCount++;

          // 5. Broadcast updated message
          final updatedMsg = realMsg.copyWith(
            id: messageId, // keep local ID if match
            status: MessageStatus.sent,
            mediaDuration: mediaDuration,
            mediaUrl: mediaUrl,
          );
          onMessageSent?.call(updatedMsg);

          // Broadcast over WebSocket for peer sync
          wsService.send({
            'type': 'new_message',
            'event_type': 'EVENT_NEW_MESSAGE',
            'conversation_id': convId,
            'message': updatedMsg.toJson(),
          });
        } catch (e) {
          debugPrint('[SyncOutboxService] Failed to flush message $messageId: $e');
          await db.incrementOutboxRetry(messageId);
        }
      }
    } catch (e) {
      debugPrint('[SyncOutboxService] Outbox flush loop error: $e');
    } finally {
      _isFlushing = false;
    }

    return flushedCount;
  }
}
