import 'package:intl/intl.dart';

class SyncedContact {
  final String id; // GoChat user ID (empty if unregistered)
  final String phonebookName; // Name saved in phone contacts
  final String? gochatName; // Registered display name on GoChat
  final String phone;
  final String? email;
  final String? avatarUrl;
  final String? statusText;
  final bool isRegistered;
  final bool isOnline;
  final DateTime? lastSeen;
  final String? pin;

  SyncedContact({
    this.id = '',
    required this.phonebookName,
    this.gochatName,
    required this.phone,
    this.email,
    this.avatarUrl,
    this.statusText,
    this.isRegistered = false,
    this.isOnline = false,
    this.lastSeen,
    this.pin,
  });

  String get displayName {
    if (phonebookName.isNotEmpty) return phonebookName;
    if (gochatName != null && gochatName!.isNotEmpty) return gochatName!;
    return phone;
  }

  factory SyncedContact.fromJson(Map<String, dynamic> json) {
    DateTime? parsedLastSeen;
    final ls = json['last_seen'];
    if (ls is int && ls > 0) {
      parsedLastSeen = DateTime.fromMillisecondsSinceEpoch(ls * 1000);
    } else if (ls is String && ls.isNotEmpty) {
      parsedLastSeen = DateTime.tryParse(ls);
    }

    return SyncedContact(
      id: (json['id'] ?? '').toString(),
      phonebookName: (json['phonebook_name'] ?? json['name'] ?? '').toString(),
      gochatName: json['gochat_name']?.toString() ?? json['name']?.toString(),
      phone: (json['phone'] ?? '').toString(),
      email: json['email']?.toString(),
      avatarUrl: json['avatar']?.toString() ?? json['avatar_url']?.toString(),
      statusText: json['status_text']?.toString(),
      isRegistered: json['is_registered'] == true || (json['id'] != null && json['id'].toString().isNotEmpty),
      isOnline: json['is_online'] == true,
      lastSeen: parsedLastSeen,
      pin: json['pin']?.toString(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'phonebook_name': phonebookName,
      'gochat_name': gochatName,
      'phone': phone,
      'email': email,
      'avatar': avatarUrl,
      'status_text': statusText,
      'is_registered': isRegistered,
      'is_online': isOnline,
      'last_seen': lastSeen?.millisecondsSinceEpoch != null ? (lastSeen!.millisecondsSinceEpoch ~/ 1000) : null,
      'pin': pin,
    };
  }

  SyncedContact copyWith({
    String? id,
    String? phonebookName,
    String? gochatName,
    String? phone,
    String? email,
    String? avatarUrl,
    String? statusText,
    bool? isRegistered,
    bool? isOnline,
    DateTime? lastSeen,
    String? pin,
  }) {
    return SyncedContact(
      id: id ?? this.id,
      phonebookName: phonebookName ?? this.phonebookName,
      gochatName: gochatName ?? this.gochatName,
      phone: phone ?? this.phone,
      email: email ?? this.email,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      statusText: statusText ?? this.statusText,
      isRegistered: isRegistered ?? this.isRegistered,
      isOnline: isOnline ?? this.isOnline,
      lastSeen: lastSeen ?? this.lastSeen,
      pin: pin ?? this.pin,
    );
  }

  /// Formats last seen timestamp into human-readable string like WhatsApp & Telegram
  static String formatLastSeen(DateTime? lastSeen, bool isOnline) {
    if (isOnline) return 'Online';
    if (lastSeen == null) return 'last seen recently';

    final now = DateTime.now();
    final difference = now.difference(lastSeen);

    if (difference.isNegative || difference.inMinutes < 1) {
      return 'last seen just now';
    } else if (difference.inMinutes < 60) {
      return 'last seen ${difference.inMinutes}m ago';
    } else if (difference.inHours < 24 && now.day == lastSeen.day) {
      return 'last seen today at ${DateFormat.jm().format(lastSeen)}';
    } else if (difference.inHours < 48 && now.subtract(const Duration(days: 1)).day == lastSeen.day) {
      return 'last seen yesterday at ${DateFormat.jm().format(lastSeen)}';
    } else if (difference.inDays < 7) {
      return 'last seen ${DateFormat('EEEE').format(lastSeen)} at ${DateFormat.jm().format(lastSeen)}';
    } else {
      return 'last seen ${DateFormat('MMM d').format(lastSeen)}';
    }
  }
}
