import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:record/record.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as p;

/// Service for recording voice notes using the device microphone.
/// Supports start, stop, cancel, and duration tracking.
class VoiceRecorderService {
  static final VoiceRecorderService _instance = VoiceRecorderService._();
  factory VoiceRecorderService() => _instance;
  VoiceRecorderService._();

  AudioRecorder? _recorder;
  Timer? _durationTimer;
  DateTime? _recordingStartTime;
  String? _currentRecordingPath;

  bool _isRecording = false;
  int _durationSeconds = 0;

  bool get isRecording => _isRecording;
  int get durationSeconds => _durationSeconds;

  /// Stream controller for duration updates during recording
  final StreamController<int> _durationController = StreamController<int>.broadcast();
  Stream<int> get durationStream => _durationController.stream;

  /// Check if microphone permission is granted
  Future<bool> hasPermission() async {
    _recorder ??= AudioRecorder();
    return await _recorder!.hasPermission();
  }

  /// Start recording a voice note. Returns true if recording started.
  Future<bool> startRecording() async {
    try {
      _recorder ??= AudioRecorder();

      final hasPerms = await _recorder!.hasPermission();
      if (!hasPerms) {
        debugPrint('[VoiceRecorder] Microphone permission denied');
        return false;
      }

      // Build output path
      String filePath;
      if (kIsWeb) {
        // On web, record package handles storage internally
        filePath = '';
      } else {
        final tempDir = await getTemporaryDirectory();
        filePath = p.join(
          tempDir.path,
          'voice_${DateTime.now().millisecondsSinceEpoch}.m4a',
        );
      }

      _currentRecordingPath = filePath;

      // Configure recording: AAC-LC @ 128kbps, 44.1kHz mono
      const config = RecordConfig(
        encoder: AudioEncoder.aacLc,
        bitRate: 128000,
        sampleRate: 44100,
        numChannels: 1,
      );

      if (kIsWeb) {
        await _recorder!.start(config, path: '');
      } else {
        await _recorder!.start(config, path: filePath);
      }

      _isRecording = true;
      _durationSeconds = 0;
      _recordingStartTime = DateTime.now();

      // Start duration timer
      _durationTimer?.cancel();
      _durationTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        _durationSeconds++;
        _durationController.add(_durationSeconds);
      });

      debugPrint('[VoiceRecorder] Recording started → $filePath');
      return true;
    } catch (e) {
      debugPrint('[VoiceRecorder] Failed to start recording: $e');
      _isRecording = false;
      return false;
    }
  }

  /// Stop recording and return the file path and duration.
  /// Returns null if not recording or on error.
  Future<VoiceRecordingResult?> stopRecording() async {
    if (!_isRecording || _recorder == null) return null;

    try {
      _durationTimer?.cancel();
      _durationTimer = null;

      final path = await _recorder!.stop();
      _isRecording = false;

      // Calculate actual duration
      final actualDuration = _recordingStartTime != null
          ? DateTime.now().difference(_recordingStartTime!).inSeconds
          : _durationSeconds;

      debugPrint('[VoiceRecorder] Recording stopped → $path (${actualDuration}s)');

      if (path == null || (path.isEmpty && !kIsWeb)) {
        return null;
      }

      // Verify file exists on mobile
      if (!kIsWeb && path.isNotEmpty) {
        final file = File(path);
        if (!await file.exists()) {
          debugPrint('[VoiceRecorder] File not found at $path');
          return null;
        }
        final fileSize = await file.length();
        debugPrint('[VoiceRecorder] File size: $fileSize bytes');
      }

      return VoiceRecordingResult(
        filePath: path,
        durationSeconds: actualDuration.clamp(1, 300), // Max 5 minutes
      );
    } catch (e) {
      debugPrint('[VoiceRecorder] Failed to stop recording: $e');
      _isRecording = false;
      return null;
    }
  }

  /// Cancel recording and delete the temp file.
  Future<void> cancelRecording() async {
    if (!_isRecording) return;

    try {
      _durationTimer?.cancel();
      _durationTimer = null;

      await _recorder?.stop();
      _isRecording = false;
      _durationSeconds = 0;

      // Delete the temp file
      if (!kIsWeb && _currentRecordingPath != null && _currentRecordingPath!.isNotEmpty) {
        final file = File(_currentRecordingPath!);
        if (await file.exists()) {
          await file.delete();
        }
      }
      _currentRecordingPath = null;

      debugPrint('[VoiceRecorder] Recording cancelled');
    } catch (e) {
      debugPrint('[VoiceRecorder] Error cancelling: $e');
      _isRecording = false;
    }
  }

  /// Format seconds as MM:SS
  static String formatDuration(int totalSeconds) {
    final mins = totalSeconds ~/ 60;
    final secs = totalSeconds % 60;
    return '${mins.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
  }

  /// Dispose the recorder
  void dispose() {
    _durationTimer?.cancel();
    _recorder?.dispose();
    _recorder = null;
    _durationController.close();
  }
}

/// Result of a completed voice recording.
class VoiceRecordingResult {
  final String filePath;
  final int durationSeconds;

  VoiceRecordingResult({
    required this.filePath,
    required this.durationSeconds,
  });
}
