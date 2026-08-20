import 'message.dart';

enum ConversationType {
  direct,
  group,
  channel,
}

class Conversation {
  final String id;
  final String title;
  final String avatarUrl;
  final ConversationType type;
  final Message? lastMessage;
  final int unreadCount;
  final bool isPinned;
  final bool isMuted;
  final bool isOnline;
  final DateTime updatedAt;
  final List<String> memberIds;

  Conversation({
    required this.id,
    required this.title,
    this.avatarUrl = '',
    this.type = ConversationType.direct,
    this.lastMessage,
    this.unreadCount = 0,
    this.isPinned = false,
    this.isMuted = false,
    this.isOnline = false,
    DateTime? updatedAt,
    List<String>? memberIds,
  })  : updatedAt = updatedAt ?? DateTime.now(),
        memberIds = memberIds ?? [];

  Conversation copyWith({
    String? id,
    String? title,
    String? avatarUrl,
    ConversationType? type,
    Message? lastMessage,
    int? unreadCount,
    bool? isPinned,
    bool? isMuted,
    bool? isOnline,
    DateTime? updatedAt,
    List<String>? memberIds,
  }) {
    return Conversation(
      id: id ?? this.id,
      title: title ?? this.title,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      type: type ?? this.type,
      lastMessage: lastMessage ?? this.lastMessage,
      unreadCount: unreadCount ?? this.unreadCount,
      isPinned: isPinned ?? this.isPinned,
      isMuted: isMuted ?? this.isMuted,
      isOnline: isOnline ?? this.isOnline,
      updatedAt: updatedAt ?? this.updatedAt,
      memberIds: memberIds ?? this.memberIds,
    );
  }

  factory Conversation.fromJson(Map<String, dynamic> json, {String currentUserId = ''}) {
    return Conversation(
      id: json['id']?.toString() ?? '',
      title: json['title'] ?? json['name'] ?? 'Chat',
      avatarUrl: json['avatar_url'] ?? '',
      type: json['type'] == 'group'
          ? ConversationType.group
          : (json['type'] == 'channel' ? ConversationType.channel : ConversationType.direct),
      lastMessage: json['last_message'] != null
          ? Message.fromJson(json['last_message'], currentUserId: currentUserId)
          : null,
      unreadCount: json['unread_count'] ?? 0,
      isPinned: json['is_pinned'] == true,
      isMuted: json['is_muted'] == true,
      isOnline: json['is_online'] == true,
      updatedAt: json['updated_at'] != null
          ? DateTime.tryParse(json['updated_at'].toString()) ?? DateTime.now()
          : DateTime.now(),
      memberIds: (json['member_ids'] as List?)?.map((e) => e.toString()).toList() ?? [],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'avatar_url': avatarUrl,
      'type': type.name,
      'last_message': lastMessage?.toJson(),
      'unread_count': unreadCount,
      'is_pinned': isPinned,
      'is_muted': isMuted,
      'is_online': isOnline,
      'updated_at': updatedAt.toIso8601String(),
      'member_ids': memberIds,
    };
  }
}
