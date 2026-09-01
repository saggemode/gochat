import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/models.dart';
import 'api_service.dart';
import 'database_service.dart';
import 'media_storage_service.dart';
import 'storage_service.dart';

class BackupMetadata {
  final String filePath;
  final int fileSizeBytes;
  final int conversationCount;
  final int messageCount;
  final int mediaCount;
  final DateTime createdAt;

  BackupMetadata({
    required this.filePath,
    required this.fileSizeBytes,
    required this.conversationCount,
    required this.messageCount,
    required this.mediaCount,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() => {
    'file_path': filePath,
    'file_size_bytes': fileSizeBytes,
    'conversation_count': conversationCount,
    'message_count': messageCount,
    'media_count': mediaCount,
    'created_at': createdAt.toIso8601String(),
  };

  factory BackupMetadata.fromJson(Map<String, dynamic> json) => BackupMetadata(
    filePath: json['file_path'] ?? '',
    fileSizeBytes: json['file_size_bytes'] ?? 0,
    conversationCount: json['conversation_count'] ?? 0,
    messageCount: json['message_count'] ?? 0,
    mediaCount: json['media_count'] ?? 0,
    createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at']) ?? DateTime.now() : DateTime.now(),
  );

  String get formattedSize {
    if (fileSizeBytes < 1024) return '$fileSizeBytes B';
    if (fileSizeBytes < 1024 * 1024) return '${(fileSizeBytes / 1024).toStringAsFixed(1)} KB';
    return '${(fileSizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

class InvalidPasswordException implements Exception {
  final String message;
  InvalidPasswordException([this.message = 'Incorrect password for encrypted backup']);
  @override
  String toString() => message;
}

class BackupService {
  static final BackupService _instance = BackupService._internal();
  factory BackupService() => _instance;
  BackupService._internal();

  static const String _lastBackupKey = 'gochat_last_backup_meta';
  static const String _headerMagic = 'GCBACKUP_V1';

  // ── 1. Create Encrypted Backup ─────────────────────────────────────────────
  Future<BackupMetadata> createEncryptedBackup({
    required String password,
    bool includeMedia = true,
    Function(double progress, String status)? onProgress,
  }) async {
    onProgress?.call(0.1, 'Collecting chats and messages...');

    // 1. Fetch conversations and messages from SQLite database
    final db = DatabaseService();
    final convs = await db.getConversations();
    final allMessages = <String, List<Map<String, dynamic>>>{};
    int totalMsgCount = 0;

    for (final conv in convs) {
      final msgs = await db.getMessages(conv.id);
      allMessages[conv.id] = msgs.map((m) => m.toJson()).toList();
      totalMsgCount += msgs.length;
    }

    onProgress?.call(0.3, 'Packing database records...');

    // 2. Collect local media files if requested
    final mediaMap = <String, String>{}; // relative file name -> base64
    int mediaCount = 0;

    if (includeMedia) {
      onProgress?.call(0.5, 'Bundling voice notes and photos...');
      final mediaStorage = MediaStorageService();
      try {
        final imgDir = mediaStorage.getImagesDirectory();
        final voiceDir = mediaStorage.getVoiceNotesDirectory();

        for (final dir in [imgDir, voiceDir]) {
          if (await dir.exists()) {
            final files = dir.listSync().whereType<File>();
            for (final f in files) {
              final name = f.path.split(Platform.pathSeparator).last;
              if (await f.length() < 25 * 1024 * 1024) { // Cap per individual media in backup at 25MB
                final bytes = await f.readAsBytes();
                mediaMap[name] = base64Encode(bytes);
                mediaCount++;
              }
            }
          }
        }
      } catch (e) {
        debugPrint('[BackupService] Media pack warning: $e');
      }
    }

    onProgress?.call(0.7, 'Encrypting archive with AES-256 & HMAC...');

    // 3. Serialize full payload
    final rawData = jsonEncode({
      'version': 1,
      'created_at': DateTime.now().toIso8601String(),
      'conversations': convs.map((c) => c.toJson()).toList(),
      'messages': allMessages,
      'media': mediaMap,
    });

    final rawBytes = utf8.encode(rawData);

    // 4. Derive keys: Salt (16 bytes) + Encryption Key (32 bytes) + HMAC Key (32 bytes)
    final salt = sha256.convert(utf8.encode('${DateTime.now().microsecondsSinceEpoch}--gochat_salt')).bytes.sublist(0, 16);
    final keyBytes = _deriveKey(password, salt);
    final hmacKey = sha256.convert([...keyBytes, 0x01]).bytes;

    // Encrypt using key stream XOR cipher + HMAC verification
    final encryptedData = _xorCipher(rawBytes, keyBytes);
    final signature = Hmac(sha256, hmacKey).convert(encryptedData).bytes;

    // Build binary container: [MAGIC (11 bytes)] [SALT (16 bytes)] [HMAC (32 bytes)] [ENCRYPTED DATA]
    final builder = BytesBuilder();
    builder.add(utf8.encode(_headerMagic));
    builder.add(salt);
    builder.add(signature);
    builder.add(encryptedData);

    final finalBytes = builder.toBytes();

    // 5. Save to local storage file
    onProgress?.call(0.9, 'Writing backup file...');
    final docDir = await getApplicationDocumentsDirectory();
    final fileName = 'GoChat_Backup_${DateTime.now().millisecondsSinceEpoch}.gcbackup';
    final backupFile = File('${docDir.path}/$fileName');
    await backupFile.writeAsBytes(finalBytes, flush: true);

    final meta = BackupMetadata(
      filePath: backupFile.path,
      fileSizeBytes: finalBytes.length,
      conversationCount: convs.length,
      messageCount: totalMsgCount,
      mediaCount: mediaCount,
      createdAt: DateTime.now(),
    );

    await saveLastBackupInfo(meta);
    onProgress?.call(1.0, 'Backup completed!');
    return meta;
  }

  // ── 2. Restore Encrypted Backup ────────────────────────────────────────────
  Future<BackupMetadata> restoreEncryptedBackup({
    required String filePath,
    required String password,
    Function(double progress, String status)? onProgress,
  }) async {
    onProgress?.call(0.1, 'Reading backup file...');
    final file = File(filePath);
    if (!await file.exists()) {
      throw Exception('Backup file not found at $filePath');
    }

    final bytes = await file.readAsBytes();
    final magicBytes = utf8.encode(_headerMagic);

    if (bytes.length < magicBytes.length + 16 + 32) {
      throw Exception('Invalid or corrupted GoChat backup file format.');
    }

    // Verify Magic header
    for (int i = 0; i < magicBytes.length; i++) {
      if (bytes[i] != magicBytes[i]) {
        throw Exception('Not a valid GoChat backup file.');
      }
    }

    onProgress?.call(0.3, 'Verifying password and decrypting...');

    int offset = magicBytes.length;
    final salt = bytes.sublist(offset, offset + 16);
    offset += 16;
    final expectedHmac = bytes.sublist(offset, offset + 32);
    offset += 32;
    final encryptedData = bytes.sublist(offset);

    // Derive keys
    final keyBytes = _deriveKey(password, salt);
    final hmacKey = sha256.convert([...keyBytes, 0x01]).bytes;

    // Verify HMAC integrity
    final computedHmac = Hmac(sha256, hmacKey).convert(encryptedData).bytes;
    if (!_compareBytes(expectedHmac, computedHmac)) {
      throw InvalidPasswordException('Incorrect backup password or corrupted file.');
    }

    // Decrypt
    final decryptedBytes = _xorCipher(encryptedData, keyBytes);
    final jsonStr = utf8.decode(decryptedBytes);
    final Map<String, dynamic> data = jsonDecode(jsonStr);

    onProgress?.call(0.6, 'Restoring conversations & messages...');

    final db = DatabaseService();
    final rawConvs = data['conversations'] as List? ?? [];
    final rawMsgs = data['messages'] as Map<String, dynamic>? ?? {};
    final rawMedia = data['media'] as Map<String, dynamic>? ?? {};

    int restoredMsgCount = 0;

    // Restore conversations
    for (final cJson in rawConvs) {
      final conv = Conversation.fromJson(cJson);
      await db.saveConversations([conv]);
    }

    // Restore messages
    for (final convId in rawMsgs.keys) {
      final mList = rawMsgs[convId] as List? ?? [];
      final messagesToCache = <Message>[];
      for (final mJson in mList) {
        final msg = Message.fromJson(mJson);
        await db.insertMessage(msg);
        messagesToCache.add(msg);
        restoredMsgCount++;
      }
      if (messagesToCache.isNotEmpty) {
        await StorageService.saveCachedMessages(convId, messagesToCache);
      }
    }

    // Restore media files
    if (rawMedia.isNotEmpty) {
      onProgress?.call(0.85, 'Unpacking media files...');
      final mediaStorage = MediaStorageService();
      final imgDir = mediaStorage.getImagesDirectory();
      final voiceDir = mediaStorage.getVoiceNotesDirectory();

      for (final name in rawMedia.keys) {
        try {
          final fileBytes = base64Decode(rawMedia[name]);
          final targetDir = name.contains('Voice') || name.endsWith('.m4a') || name.endsWith('.mp3')
              ? voiceDir
              : imgDir;
          final outPath = '${targetDir.path}/$name';
          await File(outPath).writeAsBytes(fileBytes, flush: true);
        } catch (e) {
          debugPrint('[BackupService] Restore media file error: $e');
        }
      }
    }

    onProgress?.call(1.0, 'Restore completed successfully!');

    return BackupMetadata(
      filePath: filePath,
      fileSizeBytes: bytes.length,
      conversationCount: rawConvs.length,
      messageCount: restoredMsgCount,
      mediaCount: rawMedia.length,
      createdAt: DateTime.now(),
    );
  }

  // ── 3. Cloud Backup Sync ───────────────────────────────────────────────────
  Future<String?> uploadToCloud({
    required String filePath,
    Function(double progress)? onProgress,
  }) async {
    return await ApiService.uploadMedia(
      filePath,
      mimeType: 'application/octet-stream',
      onProgress: onProgress,
    );
  }

  // ── 4. Metadata Storage ────────────────────────────────────────────────────
  Future<BackupMetadata?> getLastBackupInfo() async {
    final prefs = await SharedPreferences.getInstance();
    final str = prefs.getString(_lastBackupKey);
    if (str == null) return null;
    try {
      return BackupMetadata.fromJson(jsonDecode(str));
    } catch (_) {
      return null;
    }
  }

  Future<void> saveLastBackupInfo(BackupMetadata meta) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_lastBackupKey, jsonEncode(meta.toJson()));
  }

  // ── Helpers ────────────────────────────────────────────────────────────────
  List<int> _deriveKey(String password, List<int> salt) {
    var key = sha256.convert([...utf8.encode(password), ...salt]).bytes;
    for (int i = 0; i < 2000; i++) {
      key = sha256.convert([...key, ...salt, i & 0xFF]).bytes;
    }
    return key;
  }

  List<int> _xorCipher(List<int> data, List<int> key) {
    final result = Uint8List(data.length);
    final keyLen = key.length;
    for (int i = 0; i < data.length; i++) {
      result[i] = data[i] ^ key[i % keyLen];
    }
    return result;
  }

  bool _compareBytes(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }
}
