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
    return StoryItem(
      id: json['id']?.toString() ?? '',
      mediaUrl: json['media_url'] ?? '',
      caption: json['caption'] ?? '',
      mediaType: json['media_type'] ?? 'image',
      backgroundColor: json['background_color'],
      createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at']) : null,
      expiresAt: json['expires_at'] != null ? DateTime.tryParse(json['expires_at']) : null,
      isViewed: json['is_viewed'] == true,
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
