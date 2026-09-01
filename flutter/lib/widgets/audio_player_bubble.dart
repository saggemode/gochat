import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:just_audio/just_audio.dart';
import '../core/constants/api_constants.dart';
import '../core/services/media_storage_service.dart';
import '../core/theme/app_theme.dart';

class AudioPlayerBubble extends StatefulWidget {
  final int durationSeconds;
  final bool isMe;
  final String? audioUrl;

  const AudioPlayerBubble({
    super.key,
    required this.durationSeconds,
    required this.isMe,
    this.audioUrl,
  });

  @override
  State<AudioPlayerBubble> createState() => _AudioPlayerBubbleState();
}

class _AudioPlayerBubbleState extends State<AudioPlayerBubble> {
  bool _isPlaying = false;
  double _currentProgress = 0.0; // 0.0 to 1.0
  double _speed = 1.0;
  Timer? _fallbackTimer;
  late List<double> _waveformAmplitudes;

  // Real audio player
  AudioPlayer? _player;
  StreamSubscription<Duration>? _positionSub;
  StreamSubscription<PlayerState>? _stateSub;
  Duration _totalDuration = Duration.zero;
  bool _hasRealAudio = false;

  // Receiver Download State
  bool _isDownloaded = true;
  bool _isDownloading = false;
  double _downloadProgress = 0.0;

  @override
  void initState() {
    super.initState();
    // Generate organic voice note amplitude bars
    final rnd = Random(widget.durationSeconds);
    _waveformAmplitudes = List.generate(28, (index) {
      final base = sin(index / 4.0).abs() * 0.7 + 0.3;
      return (base * rnd.nextDouble() * 0.8 + 0.2).clamp(0.15, 1.0);
    });

    final rawUrl = widget.audioUrl?.trim() ?? '';
    final isRemote = rawUrl.startsWith('http://') || rawUrl.startsWith('https://');
    _isDownloaded = widget.isMe || !isRemote;

    _hasRealAudio = rawUrl.isNotEmpty;
    if (_hasRealAudio && _isDownloaded) {
      _initPlayer();
    }
  }

  Future<void> _startDownload() async {
    HapticFeedback.lightImpact();
    setState(() {
      _isDownloading = true;
      _downloadProgress = 0.1;
    });

    for (int i = 1; i <= 10; i++) {
      await Future.delayed(const Duration(milliseconds: 60));
      if (mounted) {
        setState(() => _downloadProgress = i / 10.0);
      }
    }

    if (mounted) {
      setState(() {
        _isDownloading = false;
        _isDownloaded = true;
      });
      _initPlayer();
    }
  }

  Future<void> _initPlayer() async {
    try {
      _player = AudioPlayer();

      // Listen to position updates
      _positionSub = _player!.positionStream.listen((pos) {
        if (!mounted) return;
        if (_totalDuration.inMilliseconds > 0) {
          setState(() {
            _currentProgress = (pos.inMilliseconds / _totalDuration.inMilliseconds).clamp(0.0, 1.0);
          });
        }
      });

      // Listen to player state
      _stateSub = _player!.playerStateStream.listen((state) {
        if (!mounted) return;
        setState(() {
          _isPlaying = state.playing;
          if (state.processingState == ProcessingState.completed) {
            _currentProgress = 1.0;
            _isPlaying = false;
          }
        });
      });

      final rawUrl = widget.audioUrl?.trim() ?? '';
      if (rawUrl.isEmpty) {
        _hasRealAudio = false;
        return;
      }

      // 1. Handle Base64 Data URI (e.g. data:audio/mp4;base64,...)
      if (rawUrl.startsWith('data:audio') || rawUrl.startsWith('data:') || rawUrl.contains(';base64,')) {
        try {
          final b64Index = rawUrl.indexOf('base64,');
          final b64Data = b64Index != -1 ? rawUrl.substring(b64Index + 7) : rawUrl;
          final bytes = base64Decode(b64Data.trim());
          final savedPath = await MediaStorageService().saveVoiceNoteBytes(bytes);
          if (savedPath.isNotEmpty) {
            final dur = await _player!.setFilePath(savedPath);
            if (dur != null && mounted) {
              setState(() {
                _totalDuration = dur;
                _hasRealAudio = true;
              });
            }
            return;
          }
        } catch (e) {
          debugPrint('[AudioPlayerBubble] Error decoding base64 audio: $e');
        }
      }

      // 2. Check if local file path
      final isLocalFile = !rawUrl.startsWith('http://') && !rawUrl.startsWith('https://');
      if (isLocalFile) {
        final file = File(rawUrl);
        if (await file.exists()) {
          final dur = await _player!.setFilePath(rawUrl);
          if (dur != null && mounted) {
            setState(() {
              _totalDuration = dur;
              _hasRealAudio = true;
            });
          }
          return;
        }
      }

      // 3. Remote URL: handle relative paths or localhost
      String targetUrl = rawUrl;
      if (targetUrl.startsWith('/')) {
        targetUrl = '${ApiConstants.baseUrl}$targetUrl';
      } else if (targetUrl.startsWith('http://localhost') || targetUrl.startsWith('http://127.0.0.1')) {
        targetUrl = targetUrl.replaceFirst('http://localhost:8080', ApiConstants.baseUrl)
                             .replaceFirst('http://127.0.0.1:8080', ApiConstants.baseUrl);
      }

      final duration = await _player!.setUrl(targetUrl);
      if (duration != null && mounted) {
        setState(() {
          _totalDuration = duration;
          _hasRealAudio = true;
        });
      }
    } catch (e) {
      debugPrint('[AudioPlayerBubble] Error initializing audio player: $e');
      _hasRealAudio = false;
    }
  }

  @override
  void dispose() {
    _fallbackTimer?.cancel();
    _positionSub?.cancel();
    _stateSub?.cancel();
    _player?.dispose();
    super.dispose();
  }

  void _togglePlay() {
    HapticFeedback.lightImpact();
    if (_hasRealAudio && _player != null) {
      _toggleRealPlay();
    } else {
      _toggleFallbackPlay();
    }
  }

  void _toggleRealPlay() async {
    try {
      if (_isPlaying) {
        await _player!.pause();
      } else {
        if (_currentProgress >= 1.0) {
          await _player!.seek(Duration.zero);
        }
        await _player!.setSpeed(_speed);
        await _player!.play();
      }
    } catch (e) {
      debugPrint('[AudioPlayerBubble] Real play failed, falling back to simulated play: $e');
      _toggleFallbackPlay();
    }
  }

  void _toggleFallbackPlay() {
    setState(() {
      _isPlaying = !_isPlaying;
      if (_isPlaying) {
        if (_currentProgress >= 1.0) {
          _currentProgress = 0.0;
        }
        _startFallbackTimer();
      } else {
        _fallbackTimer?.cancel();
      }
    });
  }

  void _startFallbackTimer() {
    _fallbackTimer?.cancel();
    const intervalMs = 100;
    final totalMs = (widget.durationSeconds * 1000) / _speed;
    final stepProgress = intervalMs / totalMs;

    _fallbackTimer = Timer.periodic(const Duration(milliseconds: 100), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() {
        _currentProgress += stepProgress;
        if (_currentProgress >= 1.0) {
          _currentProgress = 1.0;
          _isPlaying = false;
          timer.cancel();
        }
      });
    });
  }

  void _toggleSpeed() {
    HapticFeedback.selectionClick();
    setState(() {
      if (_speed == 1.0) {
        _speed = 1.5;
      } else if (_speed == 1.5) {
        _speed = 2.0;
      } else {
        _speed = 1.0;
      }
      if (_hasRealAudio && _player != null) {
        _player!.setSpeed(_speed);
      } else if (_isPlaying) {
        _startFallbackTimer();
      }
    });
  }

  void _seekTo(double progress) {
    setState(() => _currentProgress = progress.clamp(0.0, 1.0));
    if (_hasRealAudio && _player != null) {
      final totalMs = _totalDuration.inMilliseconds > 0
          ? _totalDuration.inMilliseconds
          : widget.durationSeconds * 1000;
      _player!.seek(Duration(milliseconds: (totalMs * progress).round()));
    }
  }

  String _formatDuration(int totalSeconds, double progress) {
    final currentSeconds = (totalSeconds * progress).round();
    final mins = currentSeconds ~/ 60;
    final secs = currentSeconds % 60;
    return '${mins.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}';
  }

  int get _effectiveDuration {
    if (_totalDuration.inSeconds > 0) return _totalDuration.inSeconds;
    return widget.durationSeconds;
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final playedColor = widget.isMe ? AppTheme.accent : AppTheme.primary;
    final unplayedColor = widget.isMe
        ? (isDark ? Colors.white.withValues(alpha: 0.35) : Colors.black26)
        : (isDark ? AppTheme.textMuted.withValues(alpha: 0.5) : Colors.black12);

    return Container(
      constraints: const BoxConstraints(maxWidth: 280),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Play / Pause / Download Circle
          GestureDetector(
            onTap: () {
              if (!_isDownloaded && !_isDownloading) {
                _startDownload();
              } else if (_isDownloaded) {
                _togglePlay();
              }
            },
            child: Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: !_isDownloaded
                    ? AppTheme.primary
                    : (widget.isMe
                        ? AppTheme.accent.withValues(alpha: 0.25)
                        : AppTheme.primary.withValues(alpha: 0.2)),
              ),
              child: _isDownloading
                  ? Padding(
                      padding: const EdgeInsets.all(10),
                      child: CircularProgressIndicator(
                        value: _downloadProgress > 0 ? _downloadProgress : null,
                        strokeWidth: 2.5,
                        color: Colors.white,
                      ),
                    )
                  : Icon(
                      !_isDownloaded
                          ? Icons.arrow_downward_rounded
                          : (_isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded),
                      color: !_isDownloaded
                          ? Colors.white
                          : (widget.isMe ? (isDark ? Colors.white : Colors.black) : AppTheme.primary),
                      size: 26,
                    ),
            ),
          ),
          const SizedBox(width: 10),

          // Waveform Canvas & Time
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                // Interactive Waveform with Scrubbing
                GestureDetector(
                  onHorizontalDragUpdate: (details) {
                    final box = context.findRenderObject() as RenderBox?;
                    if (box != null) {
                      final local = box.globalToLocal(details.globalPosition);
                      _seekTo(local.dx / 180);
                    }
                  },
                  child: SizedBox(
                    height: 28,
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: List.generate(_waveformAmplitudes.length, (idx) {
                        final barProgress = idx / _waveformAmplitudes.length;
                        final isPlayed = barProgress <= _currentProgress;
                        final height = _waveformAmplitudes[idx] * 24.0;

                        return Container(
                          width: 3,
                          height: height.clamp(4.0, 24.0),
                          decoration: BoxDecoration(
                            color: isPlayed ? playedColor : unplayedColor,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        );
                      }),
                    ),
                  ),
                ),
                const SizedBox(height: 4),

                // Timestamp & Speed Toggle
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      _formatDuration(_effectiveDuration, _currentProgress),
                      style: TextStyle(
                        fontSize: 11,
                        color: widget.isMe
                            ? (isDark ? Colors.white70 : Colors.black54)
                            : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    GestureDetector(
                      onTap: _toggleSpeed,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: widget.isMe
                              ? (isDark ? Colors.black26 : Colors.black12)
                              : (isDark ? AppTheme.darkSurface : const Color(0xFFF0F2F5)),
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(
                            color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                            width: 0.5,
                          ),
                        ),
                        child: Text(
                          '${_speed}x',
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                            color: widget.isMe
                                ? (isDark ? Colors.white : Colors.black)
                                : AppTheme.primary,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
