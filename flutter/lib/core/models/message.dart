import 'game_data.dart';

enum MessageType {
  text,
  image,
  video,
  audio,
  voice,
  file,
  poll,
  canvas,
  game,
  product,
  ping,
}

class TelegramUploadResult {
  final String? url;
  final String? fileId;
  final String? thumbnailUrl;
  final int? size;
  final String? mimeType;

  TelegramUploadResult({
    this.url,
    this.fileId,
    this.thumbnailUrl,
    this.size,
    this.mimeType,
  });
}

enum MessageStatus {
  pending,
  sent,
  delivered,
  read,
  failed,
}

class PollOption {
  final String id;
  final String text;
  final int votes;
  final List<String> voterIds;

  PollOption({
    required this.id,
    required this.text,
    this.votes = 0,
    List<String>? voterIds,
  }) : voterIds = voterIds ?? [];

  PollOption copyWith({
    String? id,
    String? text,
    int? votes,
    List<String>? voterIds,
  }) {
    return PollOption(
      id: id ?? this.id,
      text: text ?? this.text,
      votes: votes ?? this.votes,
      voterIds: voterIds ?? this.voterIds,
    );
  }

  factory PollOption.fromJson(Map<String, dynamic> json) {
    return PollOption(
      id: json['id']?.toString() ?? '',
      text: json['text'] ?? '',
      votes: json['votes'] is int ? json['votes'] : (json['voter_ids'] as List?)?.length ?? 0,
      voterIds: (json['voter_ids'] as List?)?.map((e) => e.toString()).toList() ?? [],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'text': text,
      'votes': votes,
      'voter_ids': voterIds,
    };
  }
}

class PollData {
  final String id;
  final String question;
  final List<PollOption> options;
  final bool allowMultiple;
  final bool isAnonymous;
  final DateTime? closesAt;

  PollData({
    required this.id,
    required this.question,
    required this.options,
    this.allowMultiple = false,
    this.isAnonymous = false,
    this.closesAt,
  });

  factory PollData.fromJson(Map<String, dynamic> json) {
    return PollData(
      id: json['id']?.toString() ?? '',
      question: json['question'] ?? '',
      options: (json['options'] as List?)
              ?.map((e) => PollOption.fromJson(e as Map<String, dynamic>))
              .toList() ??
          [],
      allowMultiple: json['allow_multiple'] == true,
      isAnonymous: json['is_anonymous'] == true,
      closesAt: json['closes_at'] != null ? DateTime.tryParse(json['closes_at']) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'question': question,
      'options': options.map((e) => e.toJson()).toList(),
      'allow_multiple': allowMultiple,
      'is_anonymous': isAnonymous,
      'closes_at': closesAt?.toIso8601String(),
    };
  }
}

class Message {
  final String id;
  final String conversationId;
  final String senderId;
  final String senderName;
  final String content;
  final MessageType type;
  final MessageStatus status;
  final String? mediaUrl;
  final String? mediaThumbnail;
  final String? telegramFileId;
  final int? mediaDuration; // In seconds for audio/video
  final int? mediaSize;     // In bytes
  final PollData? pollData;
  final GameData? gameData;
  final Map<String, dynamic>? productData;
  final bool isPing;
  final bool isViewOnce;
  final bool isOpened;
  final int? disappearingDurationSeconds;
  final DateTime? expiresAt;
  final String? replyToId;
  final String? replyToText;
  final String? replyToSenderName;
  final Map<String, List<String>> reactions; // emoji -> list of userIds
  final DateTime createdAt;
  final bool isMe;

  Message({
    required this.id,
    required this.conversationId,
    required this.senderId,
    required this.senderName,
    required this.content,
    this.type = MessageType.text,
    this.status = MessageStatus.sent,
    this.mediaUrl,
    this.mediaThumbnail,
    this.telegramFileId,
    this.mediaDuration,
    this.mediaSize,
    this.pollData,
    this.gameData,
    this.productData,
    this.isPing = false,
    this.isViewOnce = false,
    this.isOpened = false,
    this.disappearingDurationSeconds,
    this.expiresAt,
    this.replyToId,
    this.replyToText,
    this.replyToSenderName,
    Map<String, List<String>>? reactions,
    DateTime? createdAt,
    this.isMe = false,
  })  : reactions = reactions ?? {},
        createdAt = createdAt ?? DateTime.now();

  bool get isExpired {
    if (expiresAt == null) return false;
    return DateTime.now().isAfter(expiresAt!);
  }

  Message copyWith({
    String? id,
    String? conversationId,
    String? senderId,
    String? senderName,
    String? content,
    MessageType? type,
    MessageStatus? status,
    String? mediaUrl,
    String? mediaThumbnail,
    String? telegramFileId,
    int? mediaDuration,
    int? mediaSize,
    PollData? pollData,
    GameData? gameData,
    Map<String, dynamic>? productData,
    bool? isPing,
    bool? isViewOnce,
    bool? isOpened,
    int? disappearingDurationSeconds,
    DateTime? expiresAt,
    String? replyToId,
    String? replyToText,
    String? replyToSenderName,
    Map<String, List<String>>? reactions,
    DateTime? createdAt,
    bool? isMe,
  }) {
    return Message(
      id: id ?? this.id,
      conversationId: conversationId ?? this.conversationId,
      senderId: senderId ?? this.senderId,
      senderName: senderName ?? this.senderName,
      content: content ?? this.content,
      type: type ?? this.type,
      status: status ?? this.status,
      mediaUrl: mediaUrl ?? this.mediaUrl,
      mediaThumbnail: mediaThumbnail ?? this.mediaThumbnail,
      telegramFileId: telegramFileId ?? this.telegramFileId,
      mediaDuration: mediaDuration ?? this.mediaDuration,
      mediaSize: mediaSize ?? this.mediaSize,
      pollData: pollData ?? this.pollData,
      gameData: gameData ?? this.gameData,
      productData: productData ?? this.productData,
      isPing: isPing ?? this.isPing,
      isViewOnce: isViewOnce ?? this.isViewOnce,
      isOpened: isOpened ?? this.isOpened,
      disappearingDurationSeconds: disappearingDurationSeconds ?? this.disappearingDurationSeconds,
      expiresAt: expiresAt ?? this.expiresAt,
      replyToId: replyToId ?? this.replyToId,
      replyToText: replyToText ?? this.replyToText,
      replyToSenderName: replyToSenderName ?? this.replyToSenderName,
      reactions: reactions ?? this.reactions,
      createdAt: createdAt ?? this.createdAt,
      isMe: isMe ?? this.isMe,
    );
  }

  factory Message.fromJson(Map<String, dynamic> json, {String currentUserId = ''}) {
    final senderId = (json['sender_id'] ?? json['senderId'] ?? json['SenderId'] ?? json['actor_id'] ?? json['actorId'] ?? '')
        .toString();
    final msgType = _parseMessageType(json['type'] ?? json['media_type'] ?? json['Type']);
    final contentStr = (json['content'] ?? json['text'] ?? json['Content'] ?? '').toString();
    final isPingVal = json['is_ping'] == true ||
        json['isPing'] == true ||
        msgType == MessageType.ping ||
        contentStr.contains('💥 PING') ||
        contentStr.contains('[PING]');

    return Message(
      id: (json['id'] ?? json['Id'] ?? 'msg_${DateTime.now().millisecondsSinceEpoch}').toString(),
      conversationId: (json['conversation_id'] ?? json['conversationId'] ?? json['ConversationId'] ?? json['conv_id'] ?? '').toString(),
      senderId: senderId,
      senderName: (json['sender_name'] ?? json['senderName'] ?? json['SenderName'] ?? (currentUserId.isNotEmpty && senderId == currentUserId ? 'Me' : 'User')).toString(),
      content: contentStr,
      type: isPingVal ? MessageType.ping : msgType,
      status: _parseMessageStatus(json['status'] ?? json['Status']),
      mediaUrl: json['media_url'] ?? json['mediaUrl'] ?? json['MediaUrl'] ?? json['url'] ?? json['Url'] ?? json['media'] ?? json['Media'],
      mediaThumbnail: json['thumbnail_url'] ?? json['thumbnailUrl'] ?? json['ThumbnailUrl'],
      telegramFileId: json['telegram_file_id'] ?? json['telegramFileId'] ?? json['file_id'] ?? json['fileId'] ?? json['object_key'] ?? json['objectKey'],
      mediaDuration: json['duration'] ?? json['media_duration'] ?? json['mediaDuration'] ?? json['Duration'],
      mediaSize: json['file_size'] ?? json['media_size'] ?? json['mediaSize'] ?? json['MediaSize'] ?? json['size'],
      pollData: json['poll_data'] != null ? PollData.fromJson(json['poll_data']) : null,
      gameData: json['game_data'] != null ? GameData.fromJson(json['game_data']) : null,
      productData: json['product_data'] is Map<String, dynamic> ? json['product_data'] : null,
      isPing: isPingVal,
      isViewOnce: json['is_view_once'] == true || json['isViewOnce'] == true || json['is_view_once'] == 1,
      isOpened: json['is_opened'] == true || json['isOpened'] == true || json['is_opened'] == 1,
      disappearingDurationSeconds: json['disappearing_duration'] ?? json['disappearing_duration_seconds'] ?? json['disappearingDurationSeconds'],
      expiresAt: json['expires_at'] != null ? _parseDateTime(json['expires_at']) : null,
      replyToId: json['reply_to_id'] ?? json['replyToId'] ?? json['parent_id'] ?? json['ParentId'],
      replyToText: json['reply_to_text'] ?? json['replyToText'],
      replyToSenderName: json['reply_to_sender_name'] ?? json['replyToSenderName'],
      reactions: _parseReactions(json['reactions']),
      createdAt: _parseDateTime(json['created_at'] ?? json['createdAt'] ?? json['CreatedAt'] ?? json['send_at'] ?? json['SendAt']),
      isMe: currentUserId.isNotEmpty && senderId == currentUserId,
    );
  }

  static DateTime _parseDateTime(dynamic val) {
    if (val == null) return DateTime.now();
    if (val is int) {
      if (val > 1000000000000) return DateTime.fromMillisecondsSinceEpoch(val);
      return DateTime.fromMillisecondsSinceEpoch(val * 1000);
    }
    if (val is String) {
      final asInt = int.tryParse(val);
      if (asInt != null) {
        if (asInt > 1000000000000) return DateTime.fromMillisecondsSinceEpoch(asInt);
        return DateTime.fromMillisecondsSinceEpoch(asInt * 1000);
      }
      return DateTime.tryParse(val) ?? DateTime.now();
    }
    return DateTime.now();
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'conversation_id': conversationId,
      'sender_id': senderId,
      'sender_name': senderName,
      'content': content,
      'type': type.name,
      'status': status.name,
      'media_url': mediaUrl,
      'thumbnail_url': mediaThumbnail,
      'telegram_file_id': telegramFileId,
      'duration': mediaDuration,
      'file_size': mediaSize,
      'poll_data': pollData?.toJson(),
      'game_data': gameData?.toJson(),
      'product_data': productData,
      'is_ping': isPing,
      'is_view_once': isViewOnce,
      'is_opened': isOpened,
      'disappearing_duration': disappearingDurationSeconds,
      'expires_at': expiresAt?.toIso8601String(),
      'reply_to_id': replyToId,
      'reply_to_text': replyToText,
      'reply_to_sender_name': replyToSenderName,
      'reactions': reactions,
      'created_at': createdAt.toIso8601String(),
    };
  }

  static MessageType _parseMessageType(dynamic val) {
    if (val == null) return MessageType.text;
    if (val is int) {
      switch (val) {
        case 1:
          return MessageType.image;
        case 2:
          return MessageType.video;
        case 3:
          return MessageType.audio;
        case 4:
          return MessageType.file;
        case 5:
          return MessageType.voice;
        case 6:
          return MessageType.poll;
        case 7:
          return MessageType.product;
        case 8:
          return MessageType.ping;
        default:
          return MessageType.text;
      }
    }
    final str = val.toString().toLowerCase();
    switch (str) {
      case '1':
      case 'image':
        return MessageType.image;
      case '2':
      case 'video':
        return MessageType.video;
      case '3':
      case 'audio':
        return MessageType.audio;
      case '4':
      case 'file':
      case 'document':
        return MessageType.file;
      case '5':
      case 'voice':
      case 'voice_note':
        return MessageType.voice;
      case '6':
      case 'poll':
        return MessageType.poll;
      case 'canvas':
        return MessageType.canvas;
      case 'game':
        return MessageType.game;
      case '7':
      case 'product':
        return MessageType.product;
      case '8':
      case 'ping':
        return MessageType.ping;
      default:
        return MessageType.text;
    }
  }

  static MessageStatus _parseMessageStatus(dynamic val) {
    if (val == null) return MessageStatus.sent;
    if (val is int) {
      switch (val) {
        case 0:
          return MessageStatus.pending;
        case 1:
          return MessageStatus.sent;
        case 2:
          return MessageStatus.delivered;
        case 3:
          return MessageStatus.read;
        default:
          return MessageStatus.sent;
      }
    }
    final str = val.toString().toLowerCase();
    switch (str) {
      case '0':
      case 'pending':
        return MessageStatus.pending;
      case '1':
      case 'sent':
        return MessageStatus.sent;
      case '2':
      case 'delivered':
        return MessageStatus.delivered;
      case '3':
      case 'read':
        return MessageStatus.read;
      case 'failed':
        return MessageStatus.failed;
      default:
        return MessageStatus.sent;
    }
  }

  static Map<String, List<String>> _parseReactions(dynamic val) {
    if (val is! Map) return {};
    final map = <String, List<String>>{};
    val.forEach((k, v) {
      if (v is List) {
        map[k.toString()] = v.map((e) => e.toString()).toList();
      }
    });
    return map;
  }
}
