enum CallType { audio, video }

enum CallDirection { incoming, outgoing, missed }

enum CallStatus { dialing, active, rejected, missed, ended, busy }

class CallRecord {
  final String id;
  final String callerId;
  final String callerName;
  final String callerAvatar;
  final String receiverId;
  final String receiverName;
  final String receiverAvatar;
  final CallType type;
  final CallDirection direction;
  final CallStatus status;
  final DateTime timestamp;
  final int durationSeconds;

  CallRecord({
    required this.id,
    required this.callerId,
    required this.callerName,
    this.callerAvatar = '',
    this.receiverId = '',
    this.receiverName = '',
    this.receiverAvatar = '',
    this.type = CallType.audio,
    this.direction = CallDirection.outgoing,
    this.status = CallStatus.dialing,
    DateTime? timestamp,
    this.durationSeconds = 0,
  }) : timestamp = timestamp ?? DateTime.now();

  String get partnerName => callerName.isNotEmpty
      ? callerName
      : (receiverName.isNotEmpty ? receiverName : 'Contact');
  String get partnerAvatar =>
      callerAvatar.isNotEmpty ? callerAvatar : receiverAvatar;

  CallRecord copyWith({
    String? id,
    String? callerId,
    String? callerName,
    String? callerAvatar,
    String? receiverId,
    String? receiverName,
    String? receiverAvatar,
    CallType? type,
    CallDirection? direction,
    CallStatus? status,
    DateTime? timestamp,
    int? durationSeconds,
  }) {
    return CallRecord(
      id: id ?? this.id,
      callerId: callerId ?? this.callerId,
      callerName: callerName ?? this.callerName,
      callerAvatar: callerAvatar ?? this.callerAvatar,
      receiverId: receiverId ?? this.receiverId,
      receiverName: receiverName ?? this.receiverName,
      receiverAvatar: receiverAvatar ?? this.receiverAvatar,
      type: type ?? this.type,
      direction: direction ?? this.direction,
      status: status ?? this.status,
      timestamp: timestamp ?? this.timestamp,
      durationSeconds: durationSeconds ?? this.durationSeconds,
    );
  }

  factory CallRecord.fromJson(
    Map<String, dynamic> json, {
    String currentUserId = '',
  }) {
    final callerId =
        json['caller_id']?.toString() ?? json['callerId']?.toString() ?? '';
    final receiverId =
        json['receiver_id']?.toString() ?? json['receiverId']?.toString() ?? '';
    final callerName = json['caller_name'] ?? json['callerName'] ?? '';
    final receiverName = json['receiver_name'] ?? json['receiverName'] ?? '';
    final callerAvatar =
        json['caller_avatar'] ??
        json['caller_avatar_url'] ??
        json['callerAvatarUrl'] ??
        '';
    final receiverAvatar =
        json['receiver_avatar'] ??
        json['receiver_avatar_url'] ??
        json['receiverAvatarUrl'] ??
        '';

    final rawType = (json['type'] ?? '').toString().toLowerCase();
    final callType = (rawType == 'video' || rawType == '1')
        ? CallType.video
        : CallType.audio;

    final rawStatus = (json['status'] ?? '').toString().toLowerCase();
    final callStatus = _parseStatus(rawStatus);

    CallDirection direction = CallDirection.outgoing;
    if (json['direction'] != null) {
      direction = _parseDirection(json['direction']);
    } else if (currentUserId.isNotEmpty) {
      if (receiverId == currentUserId) {
        direction =
            (callStatus == CallStatus.missed ||
                callStatus == CallStatus.rejected)
            ? CallDirection.missed
            : CallDirection.incoming;
      } else {
        direction = CallDirection.outgoing;
      }
    }

    // Parse timestamp (could be ISO string or UNIX timestamp int)
    DateTime ts = DateTime.now();
    final rawTime =
        json['start_time'] ??
        json['startTime'] ??
        json['timestamp'] ??
        json['created_at'];
    if (rawTime is int) {
      ts = DateTime.fromMillisecondsSinceEpoch(
        rawTime > 10000000000 ? rawTime : rawTime * 1000,
      );
    } else if (rawTime is String) {
      ts = DateTime.tryParse(rawTime) ?? DateTime.now();
    }

    final duration =
        json['duration_sec'] ?? json['durationSec'] ?? json['duration'] ?? 0;

    // If I am the caller, the displayed partner name/avatar should be the receiver if available
    String displayName = callerName;
    String displayAvatar = callerAvatar;
    if (currentUserId.isNotEmpty &&
        callerId == currentUserId &&
        receiverName.isNotEmpty) {
      displayName = receiverName;
      displayAvatar = receiverAvatar;
    } else if (displayName.isEmpty) {
      displayName = receiverName.isNotEmpty ? receiverName : 'Contact';
    }

    return CallRecord(
      id: json['id']?.toString() ?? json['call_id']?.toString() ?? '',
      callerId: callerId,
      callerName: displayName,
      callerAvatar: displayAvatar,
      receiverId: receiverId,
      receiverName: receiverName,
      receiverAvatar: receiverAvatar,
      type: callType,
      direction: direction,
      status: callStatus,
      timestamp: ts,
      durationSeconds: duration is int
          ? duration
          : int.tryParse(duration.toString()) ?? 0,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'caller_id': callerId,
      'caller_name': callerName,
      'caller_avatar': callerAvatar,
      'receiver_id': receiverId,
      'receiver_name': receiverName,
      'receiver_avatar': receiverAvatar,
      'type': type == CallType.video ? 'video' : 'voice',
      'direction': direction.name,
      'status': status.name,
      'timestamp': timestamp.toIso8601String(),
      'duration': durationSeconds,
    };
  }

  static CallDirection _parseDirection(dynamic val) {
    final str = val?.toString().toLowerCase();
    if (str == 'incoming') return CallDirection.incoming;
    if (str == 'missed') return CallDirection.missed;
    return CallDirection.outgoing;
  }

  static CallStatus _parseStatus(dynamic val) {
    final str = val?.toString().toLowerCase();
    if (str == 'active' || str == '1') return CallStatus.active;
    if (str == 'rejected' || str == '2') return CallStatus.rejected;
    if (str == 'missed' || str == '3') return CallStatus.missed;
    if (str == 'ended' || str == '4') return CallStatus.ended;
    if (str == 'busy' || str == '5') return CallStatus.busy;
    return CallStatus.dialing;
  }
}
