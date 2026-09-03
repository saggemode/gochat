class StoryItem {
  final String id;
  final String mediaUrl;
  final String caption;
  final String mediaType; // 'image', 'video', 'text'
  final String? backgroundColor;
  final DateTime createdAt;
  final DateTime expiresAt;
  final bool isViewed;

  StoryItem({
    required this.id,
    required this.mediaUrl,
    this.caption = '',
    this.mediaType = 'image',
    this.backgroundColor,
    DateTime? createdAt,
    DateTime? expiresAt,
    this.isViewed = false,
  })  : createdAt = createdAt ?? DateTime.now(),
        expiresAt = expiresAt ?? DateTime.now().add(const Duration(hours: 24));

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

    return StoryItem(
      id: json['id']?.toString() ?? '',
      mediaUrl: json['media_url'] ?? json['url'] ?? '',
      caption: json['caption'] ?? json['content'] ?? '',
      mediaType: json['media_type'] ?? json['type'] ?? 'image',
      backgroundColor: json['background_color'],
      createdAt: parseDate(json['created_at']),
      expiresAt: parseDate(json['expires_at']),
      isViewed: json['viewed'] == true || json['is_viewed'] == true,
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
}
