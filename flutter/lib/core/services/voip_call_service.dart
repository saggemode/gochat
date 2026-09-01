import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:just_audio/just_audio.dart';
import '../models/call.dart';

class VoipCallService {
  static final VoipCallService _instance = VoipCallService._internal();
  factory VoipCallService() => _instance;
  VoipCallService._internal();

  AudioPlayer? _ringtonePlayer;
  Timer? _vibrationLoopTimer;
  bool _isRinging = false;
  CallRecord? _activeIncomingCall;

  bool get isRinging => _isRinging;
  CallRecord? get activeIncomingCall => _activeIncomingCall;

  final StreamController<CallRecord> _incomingCallStreamController =
      StreamController<CallRecord>.broadcast();
  Stream<CallRecord> get onIncomingCall => _incomingCallStreamController.stream;

  /// Trigger incoming call ringing & vibration
  Future<void> startRinging(CallRecord call) async {
    _activeIncomingCall = call;
    _isRinging = true;
    _incomingCallStreamController.add(call);

    // Start Haptic Vibration Loop
    _vibrationLoopTimer?.cancel();
    _vibrationLoopTimer = Timer.periodic(const Duration(milliseconds: 900), (_) {
      if (_isRinging) {
        HapticFeedback.heavyImpact();
      }
    });

    // Start Looping Ringtone Audio
    try {
      _ringtonePlayer?.dispose();
      _ringtonePlayer = AudioPlayer();
      await _ringtonePlayer!.setLoopMode(LoopMode.one);
      await _ringtonePlayer!.setVolume(1.0);

      // Play synthesized digital phone ring tone stream
      await _ringtonePlayer!.setUrl(
        'https://assets.mixkit.co/active_storage/sfx/2869/2869-preview.mp3',
      );
      if (_isRinging) {
        await _ringtonePlayer!.play();
      }
    } catch (e) {
      debugPrint('Ringtone audio error (haptic active): $e');
    }
  }

  /// Stop ringing & haptics when answered or declined
  Future<void> stopRinging() async {
    _isRinging = false;
    _vibrationLoopTimer?.cancel();
    _vibrationLoopTimer = null;

    try {
      if (_ringtonePlayer != null) {
        await _ringtonePlayer!.stop();
        await _ringtonePlayer!.dispose();
        _ringtonePlayer = null;
      }
    } catch (e) {
      debugPrint('Error stopping ringtone: $e');
    }
  }
}
