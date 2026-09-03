class StoryViewer {
  final String userId;
  final String displayName;
  final String avatarUrl;
  final DateTime viewedAt;

  StoryViewer({
    required this.userId,
    required this.displayName,
    required this.avatarUrl,
    required this.viewedAt,
  });

  factory StoryViewer.fromJson(Map<String, dynamic> json) {
    DateTime parseDate(dynamic val) {
      if (val == null) return DateTime.now();
      if (val is int) {
        return DateTime.fromMillisecondsSinceEpoch(
          val > 100000000000 ? val : val * 1000,
        );
      }
      return DateTime.tryParse(val.toString()) ?? DateTime.now();
    }

    return StoryViewer(
      userId: (json['user_id'] ?? json['userId'] ?? '').toString(),
      displayName: (json['display_name'] ?? json['displayName'] ?? 'Contact').toString(),
      avatarUrl: (json['avatar_url'] ?? json['avatarUrl'] ?? '').toString(),
      viewedAt: parseDate(json['viewed_at'] ?? json['viewedAt']),
    );
  }

  Map<String, dynamic> toJson() => {
        'user_id': userId,
        'display_name': displayName,
        'avatar_url': avatarUrl,
        'viewed_at': viewedAt.toIso8601String(),
      };
}

class StoryItem {
  final String id;
  final String mediaUrl;
  final String caption;
  final String mediaType; // 'image', 'video', 'text'
  final String? backgroundColor;
  final DateTime createdAt;
  final DateTime expiresAt;
  final bool isViewed;
  final int viewCount;
  final List<StoryViewer> viewers;

  StoryItem({
    required this.id,
    required this.mediaUrl,
    this.caption = '',
    this.mediaType = 'image',
    this.backgroundColor,
    DateTime? createdAt,
    DateTime? expiresAt,
    this.isViewed = false,
    this.viewCount = 0,
    List<StoryViewer>? viewers,
  })  : createdAt = createdAt ?? DateTime.now(),
        expiresAt = expiresAt ?? DateTime.now().add(const Duration(hours: 24)),
        viewers = viewers ?? [];

  StoryItem copyWith({
    String? id,
    String? mediaUrl,
    String? caption,
    String? mediaType,
    String? backgroundColor,
    DateTime? createdAt,
    DateTime? expiresAt,
    bool? isViewed,
    int? viewCount,
    List<StoryViewer>? viewers,
  }) {
    return StoryItem(
      id: id ?? this.id,
      mediaUrl: mediaUrl ?? this.mediaUrl,
      caption: caption ?? this.caption,
      mediaType: mediaType ?? this.mediaType,
      backgroundColor: backgroundColor ?? this.backgroundColor,
      createdAt: createdAt ?? this.createdAt,
      expiresAt: expiresAt ?? this.expiresAt,
      isViewed: isViewed ?? this.isViewed,
      viewCount: viewCount ?? this.viewCount,
      viewers: viewers ?? this.viewers,
    );
  }

  factory StoryItem.fromJson(Map<String, dynamic> json) {
    DateTime? parseDate(dynamic val) {
      if (val == null) return null;
      if (val is int) {
        return DateTime.fromMillisecondsSinceEpoch(
          val > 100000000000 ? val : val * 1000,
        );
      }
      return DateTime.tryParse(val.toString());
    }

    final rawViewers = json['viewers'] as List?;
    final viewersList = rawViewers != null
        ? rawViewers
            .map((v) => StoryViewer.fromJson(v as Map<String, dynamic>))
            .toList()
        : <StoryViewer>[];

    final rawCount = json['view_count'] ?? json['viewCount'] ?? json['views'];
    int count = viewersList.length;
    if (rawCount is int) {
      count = rawCount > count ? rawCount : count;
    } else if (rawCount != null) {
      final parsed = int.tryParse(rawCount.toString());
      if (parsed != null && parsed > count) count = parsed;
    }

    return StoryItem(
      id: json['id']?.toString() ?? '',
      mediaUrl: json['media_url'] ?? json['url'] ?? '',
      caption: json['caption'] ?? json['content'] ?? '',
      mediaType: json['media_type'] ?? json['type'] ?? 'image',
      backgroundColor: json['background_color'],
      createdAt: parseDate(json['created_at']),
      expiresAt: parseDate(json['expires_at']),
      isViewed: json['viewed'] == true || json['is_viewed'] == true,
      viewCount: count,
      viewers: viewersList,
    );
  }
}

class UserStories {
  final String userId;
  final String userName;
  final String userAvatar;
  final List<StoryItem> stories;
  final bool isMe;

  UserStories({
    required this.userId,
    required this.userName,
    required this.userAvatar,
    required this.stories,
    this.isMe = false,
  });

  bool get hasUnseenStories => stories.any((s) => !s.isViewed);
  DateTime get latestStoryTime =>
      stories.isNotEmpty ? stories.last.createdAt : DateTime.now();
  int get totalViewCount => stories.fold(0, (sum, s) => sum + s.viewCount);
}
