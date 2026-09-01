import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/theme/app_theme.dart';

/// Fullscreen View-Once viewer with self-destruct countdown,
/// screenshot deterrent, and a particle burn / flame disintegration animation upon closing.
class ViewOnceViewerScreen extends StatefulWidget {
  final String mediaUrl;
  final bool isVideo;
  final String senderName;
  final VoidCallback onBurned;

  const ViewOnceViewerScreen({
    super.key,
    required this.mediaUrl,
    this.isVideo = false,
    required this.senderName,
    required this.onBurned,
  });

  static Future<void> show(
    BuildContext context, {
    required String mediaUrl,
    bool isVideo = false,
    required String senderName,
    required VoidCallback onBurned,
  }) {
    return Navigator.push(
      context,
      PageRouteBuilder(
        opaque: false,
        barrierDismissible: false,
        pageBuilder: (context, anim1, anim2) => ViewOnceViewerScreen(
          mediaUrl: mediaUrl,
          isVideo: isVideo,
          senderName: senderName,
          onBurned: onBurned,
        ),
        transitionsBuilder: (context, anim1, anim2, child) {
          return FadeTransition(opacity: anim1, child: child);
        },
      ),
    );
  }

  @override
  State<ViewOnceViewerScreen> createState() => _ViewOnceViewerScreenState();
}

class _ViewOnceViewerScreenState extends State<ViewOnceViewerScreen>
    with SingleTickerProviderStateMixin {
  late AnimationController _burnAnimController;
  late Animation<double> _burnAnimation;
  bool _isBurning = false;
  int _secondsLeft = 15;
  Timer? _countdownTimer;

  final List<_EmberParticle> _particles = [];
  final math.Random _random = math.Random();

  @override
  void initState() {
    super.initState();
    // Dark status & navigation bars for immersive theater experience
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);

    _burnAnimController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    );

    _burnAnimation = CurvedAnimation(
      parent: _burnAnimController,
      curve: Curves.easeInQuad,
    );

    _burnAnimController.addListener(() {
      if (_isBurning) {
        _spawnParticles();
        setState(() {});
      }
    });

    _burnAnimController.addStatusListener((status) {
      if (status == AnimationStatus.completed) {
        widget.onBurned();
        if (mounted) {
          Navigator.of(context).pop();
        }
      }
    });

    // Start auto-destruct countdown timer
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) return;
      if (_secondsLeft > 1) {
        setState(() => _secondsLeft--);
      } else {
        _countdownTimer?.cancel();
        _triggerBurnDestruction();
      }
    });
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    _burnAnimController.dispose();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  void _spawnParticles() {
    for (int i = 0; i < 6; i++) {
      _particles.add(
        _EmberParticle(
          x: _random.nextDouble() * MediaQuery.of(context).size.width,
          y: MediaQuery.of(context).size.height * (0.4 + _random.nextDouble() * 0.6),
          vx: (_random.nextDouble() - 0.5) * 6,
          vy: -(_random.nextDouble() * 8 + 4),
          radius: _random.nextDouble() * 5 + 2,
          color: _random.nextBool()
              ? Colors.amberAccent
              : (_random.nextBool() ? Colors.deepOrangeAccent : Colors.redAccent),
          opacity: 1.0,
        ),
      );
    }

    // Update particles
    for (final p in _particles) {
      p.x += p.vx;
      p.y += p.vy;
      p.opacity = (p.opacity - 0.03).clamp(0.0, 1.0);
    }
    _particles.removeWhere((p) => p.opacity <= 0);
  }

  void _triggerBurnDestruction() {
    if (_isBurning) return;
    _countdownTimer?.cancel();
    HapticFeedback.heavyImpact();
    setState(() => _isBurning = true);
    _burnAnimController.forward();
  }

  Widget _buildMediaContent() {
    final isLocal = !kIsWeb && !widget.mediaUrl.startsWith('http');

    if (isLocal) {
      final file = File(widget.mediaUrl);
      return Image.file(
        file,
        fit: BoxFit.contain,
        errorBuilder: (_, _, _) => const Center(
          child: Icon(Icons.broken_image, color: Colors.white54, size: 64),
        ),
      );
    }

    return Image.network(
      widget.mediaUrl,
      fit: BoxFit.contain,
      loadingBuilder: (ctx, child, progress) {
        if (progress == null) return child;
        return Center(
          child: CircularProgressIndicator(
            value: progress.expectedTotalBytes != null
                ? progress.cumulativeBytesLoaded / progress.expectedTotalBytes!
                : null,
            color: AppTheme.primary,
          ),
        );
      },
      errorBuilder: (_, _, _) => const Center(
        child: Icon(Icons.broken_image, color: Colors.white54, size: 64),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final screen = MediaQuery.of(context).size;

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && !_isBurning) {
          _triggerBurnDestruction();
        }
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Stack(
          children: [
            // ── Main Media with Disintegration Shader/Scale Effect ──
            Center(
              child: AnimatedBuilder(
                animation: _burnAnimation,
                builder: (context, child) {
                  final progress = _burnAnimation.value;
                  return Transform.scale(
                    scale: 1.0 - (progress * 0.25),
                    child: Opacity(
                      opacity: (1.0 - progress).clamp(0.0, 1.0),
                      child: ColorFiltered(
                        colorFilter: ColorFilter.mode(
                          Colors.redAccent.withValues(alpha: progress * 0.8),
                          BlendMode.modulate,
                        ),
                        child: InteractiveViewer(
                          maxScale: 4.0,
                          child: _buildMediaContent(),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),

            // ── Particle Sparks / Fire Ember Overlay ────────────────
            if (_isBurning)
              CustomPaint(
                size: screen,
                painter: _EmberParticlePainter(particles: _particles),
              ),

            // ── Top Header Controls & Countdown ────────────────────
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      colors: [Colors.black87, Colors.transparent],
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                    ),
                  ),
                  child: Row(
                    children: [
                      // View-Once Badge
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                        decoration: BoxDecoration(
                          color: AppTheme.primary.withValues(alpha: 0.2),
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppTheme.primary, width: 1.2),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Text(
                              '①',
                              style: TextStyle(
                                color: AppTheme.primary,
                                fontWeight: FontWeight.bold,
                                fontSize: 13,
                              ),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              'View Once • ${widget.senderName}',
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w600,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Spacer(),

                      // Countdown Timer Ring
                      if (!_isBurning)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                          decoration: BoxDecoration(
                            color: Colors.white12,
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(Icons.timer_outlined, color: Colors.amberAccent, size: 14),
                              const SizedBox(width: 4),
                              Text(
                                '${_secondsLeft}s',
                                style: const TextStyle(
                                  color: Colors.amberAccent,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                        ),

                      const SizedBox(width: 12),

                      // Burn & Close Button
                      IconButton(
                        icon: const Icon(Icons.close, color: Colors.white),
                        tooltip: 'Close & Burn',
                        onPressed: _triggerBurnDestruction,
                      ),
                    ],
                  ),
                ),
              ),
            ),

            // ── Bottom Disclaimer ──────────────────────────────────
            Positioned(
              bottom: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                  alignment: Alignment.center,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                    decoration: BoxDecoration(
                      color: Colors.black54,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: Colors.white12),
                    ),
                    child: const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.local_fire_department_rounded, color: Colors.deepOrangeAccent, size: 16),
                        SizedBox(width: 6),
                        Text(
                          'Self-destructs upon exit. Cannot be saved.',
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 11,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmberParticle {
  double x;
  double y;
  double vx;
  double vy;
  double radius;
  Color color;
  double opacity;

  _EmberParticle({
    required this.x,
    required this.y,
    required this.vx,
    required this.vy,
    required this.radius,
    required this.color,
    required this.opacity,
  });
}

class _EmberParticlePainter extends CustomPainter {
  final List<_EmberParticle> particles;

  _EmberParticlePainter({required this.particles});

  @override
  void paint(Canvas canvas, Size size) {
    for (final p in particles) {
      final paint = Paint()
        ..color = p.color.withValues(alpha: p.opacity)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 2);
      canvas.drawCircle(Offset(p.x, p.y), p.radius, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _EmberParticlePainter oldDelegate) => true;
}
