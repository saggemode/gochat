class ChannelPost {
  final String id;
  final String channelId;
  final String content;
  final String? mediaUrl;
  final String? mediaType;
  final int viewsCount;
  final int forwardsCount;
  final Map<String, int> reactions; // emoji -> count
  final DateTime createdAt;

  ChannelPost({
    required this.id,
    required this.channelId,
    required this.content,
    this.mediaUrl,
    this.mediaType,
    this.viewsCount = 0,
    this.forwardsCount = 0,
    Map<String, int>? reactions,
    DateTime? createdAt,
  })  : reactions = reactions ?? {},
        createdAt = createdAt ?? DateTime.now();

  int get likesCount => reactions.values.fold(0, (sum, v) => sum + v);

  factory ChannelPost.fromJson(Map<String, dynamic> json) {
    return ChannelPost(
      id: json['id']?.toString() ?? '',
      channelId: json['channel_id']?.toString() ?? '',
      content: json['content'] ?? '',
      mediaUrl: json['media_url'],
      mediaType: json['media_type'],
      viewsCount: json['views_count'] ?? 0,
      forwardsCount: json['forwards_count'] ?? 0,
      reactions: (json['reactions'] as Map<String, dynamic>?)
              ?.map((k, v) => MapEntry(k, v as int)) ??
          {},
      createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at']) : null,
    );
  }
}

class Channel {
  final String id;
  final String name;
  final String description;
  final String avatarUrl;
  final int followersCount;
  final bool isVerified;
  final bool isFollowing;
  final List<ChannelPost> recentPosts;

  Channel({
    required this.id,
    required this.name,
    this.description = '',
    this.avatarUrl = '',
    this.followersCount = 0,
    this.isVerified = false,
    this.isFollowing = false,
    List<ChannelPost>? recentPosts,
  }) : recentPosts = recentPosts ?? [];

  Channel copyWith({
    String? id,
    String? name,
    String? description,
    String? avatarUrl,
    int? followersCount,
    bool? isVerified,
    bool? isFollowing,
    List<ChannelPost>? recentPosts,
  }) {
    return Channel(
      id: id ?? this.id,
      name: name ?? this.name,
      description: description ?? this.description,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      followersCount: followersCount ?? this.followersCount,
      isVerified: isVerified ?? this.isVerified,
      isFollowing: isFollowing ?? this.isFollowing,
      recentPosts: recentPosts ?? this.recentPosts,
    );
  }
}
