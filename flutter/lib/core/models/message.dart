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
  final int? mediaDuration; // In seconds for audio/video
  final int? mediaSize;     // In bytes
  final PollData? pollData;
  final String? replyToId;
  final String? replyToText;
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
    this.mediaDuration,
    this.mediaSize,
    this.pollData,
    this.replyToId,
    this.replyToText,
    Map<String, List<String>>? reactions,
    DateTime? createdAt,
    this.isMe = false,
  })  : reactions = reactions ?? {},
        createdAt = createdAt ?? DateTime.now();

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
    int? mediaDuration,
    int? mediaSize,
    PollData? pollData,
    String? replyToId,
    String? replyToText,
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
      mediaDuration: mediaDuration ?? this.mediaDuration,
      mediaSize: mediaSize ?? this.mediaSize,
      pollData: pollData ?? this.pollData,
      replyToId: replyToId ?? this.replyToId,
      replyToText: replyToText ?? this.replyToText,
      reactions: reactions ?? this.reactions,
      createdAt: createdAt ?? this.createdAt,
      isMe: isMe ?? this.isMe,
    );
  }

  factory Message.fromJson(Map<String, dynamic> json, {String currentUserId = ''}) {
    final senderId = json['sender_id']?.toString() ?? '';
    return Message(
      id: json['id']?.toString() ?? '',
      conversationId: json['conversation_id']?.toString() ?? '',
      senderId: senderId,
      senderName: json['sender_name'] ?? 'User',
      content: json['content'] ?? json['text'] ?? '',
      type: _parseMessageType(json['type'] ?? json['media_type']),
      status: _parseMessageStatus(json['status']),
      mediaUrl: json['media_url'],
      mediaThumbnail: json['thumbnail_url'],
      mediaDuration: json['duration'],
      mediaSize: json['file_size'],
      pollData: json['poll_data'] != null ? PollData.fromJson(json['poll_data']) : null,
      replyToId: json['reply_to_id'],
      replyToText: json['reply_to_text'],
      reactions: _parseReactions(json['reactions']),
      createdAt: json['created_at'] != null
          ? DateTime.tryParse(json['created_at'].toString()) ?? DateTime.now()
          : DateTime.now(),
      isMe: currentUserId.isNotEmpty && senderId == currentUserId,
    );
  }

  static MessageType _parseMessageType(dynamic val) {
    if (val == null) return MessageType.text;
    final str = val.toString().toLowerCase();
    switch (str) {
      case 'image':
        return MessageType.image;
      case 'video':
        return MessageType.video;
      case 'audio':
      case 'voice':
        return MessageType.voice;
      case 'file':
      case 'document':
        return MessageType.file;
      case 'poll':
        return MessageType.poll;
      case 'canvas':
        return MessageType.canvas;
      case 'game':
        return MessageType.game;
      default:
        return MessageType.text;
    }
  }

  static MessageStatus _parseMessageStatus(dynamic val) {
    if (val == null) return MessageStatus.sent;
    final str = val.toString().toLowerCase();
    switch (str) {
      case 'pending':
        return MessageStatus.pending;
      case 'sent':
        return MessageStatus.sent;
      case 'delivered':
        return MessageStatus.delivered;
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
