enum CallType {
  audio,
  video,
}

enum CallDirection {
  incoming,
  outgoing,
  missed,
}

class CallRecord {
  final String id;
  final String callerId;
  final String callerName;
  final String callerAvatar;
  final CallType type;
  final CallDirection direction;
  final DateTime timestamp;
  final int durationSeconds;

  CallRecord({
    required this.id,
    required this.callerId,
    required this.callerName,
    this.callerAvatar = '',
    this.type = CallType.audio,
    this.direction = CallDirection.outgoing,
    DateTime? timestamp,
    this.durationSeconds = 0,
  }) : timestamp = timestamp ?? DateTime.now();

  factory CallRecord.fromJson(Map<String, dynamic> json) {
    return CallRecord(
      id: json['id']?.toString() ?? '',
      callerId: json['caller_id']?.toString() ?? '',
      callerName: json['caller_name'] ?? 'Contact',
      callerAvatar: json['caller_avatar'] ?? '',
      type: json['type'] == 'video' ? CallType.video : CallType.audio,
      direction: _parseDirection(json['direction']),
      timestamp: json['timestamp'] != null
          ? DateTime.tryParse(json['timestamp']) ?? DateTime.now()
          : DateTime.now(),
      durationSeconds: json['duration'] ?? 0,
    );
  }

  static CallDirection _parseDirection(dynamic val) {
    final str = val?.toString().toLowerCase();
    if (str == 'incoming') return CallDirection.incoming;
    if (str == 'missed') return CallDirection.missed;
    return CallDirection.outgoing;
  }
}
