import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/call.dart';
import '../../core/services/voip_call_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/custom_avatar.dart';
import 'active_call_screen.dart';

class IncomingVoipCallScreen extends StatefulWidget {
  final CallRecord callRecord;
  final AppState appState;

  const IncomingVoipCallScreen({
    super.key,
    required this.callRecord,
    required this.appState,
  });

  static Future<void> show(
    BuildContext context, {
    required CallRecord callRecord,
    required AppState appState,
  }) {
    return Navigator.push(
      context,
      PageRouteBuilder(
        opaque: true,
        pageBuilder: (_, _, _) => IncomingVoipCallScreen(
          callRecord: callRecord,
          appState: appState,
        ),
        transitionsBuilder: (_, anim, _, child) {
          return FadeTransition(opacity: anim, child: child);
        },
      ),
    );
  }

  @override
  State<IncomingVoipCallScreen> createState() => _IncomingVoipCallScreenState();
}

class _IncomingVoipCallScreenState extends State<IncomingVoipCallScreen>
    with SingleTickerProviderStateMixin {
  late AnimationController _radarController;
  final VoipCallService _voipService = VoipCallService();

  @override
  void initState() {
    super.initState();
    _radarController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2000),
    )..repeat();

    // Start ringtone & vibration
    _voipService.startRinging(widget.callRecord);
  }

  @override
  void dispose() {
    _radarController.dispose();
    _voipService.stopRinging();
    super.dispose();
  }

  void _acceptCall() async {
    HapticFeedback.heavyImpact();
    await _voipService.stopRinging();
    await widget.appState.acceptCall(widget.callRecord.id);

    if (mounted) {
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(
          builder: (_) => ActiveCallScreen(
            callRecord: widget.callRecord,
            appState: widget.appState,
          ),
        ),
      );
    }
  }

  void _declineCall() async {
    HapticFeedback.mediumImpact();
    await _voipService.stopRinging();
    await widget.appState.rejectCall(widget.callRecord.id);

    if (mounted) {
      Navigator.pop(context);
    }
  }

  void _sendQuickMessage(String text) {
    _declineCall();
    widget.appState.sendMessage(
      widget.callRecord.callerId,
      '📞 $text',
    );
  }

  void _showQuickMessageSheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1E262C),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'Decline with Quick Message',
                style: TextStyle(fontWeight: FontWeight.bold, color: Colors.white, fontSize: 16),
              ),
            ),
            const Divider(height: 1, color: Colors.white24),
            ListTile(
              title: const Text("Can't talk now. What's up?", style: TextStyle(color: Colors.white)),
              onTap: () {
                Navigator.pop(ctx);
                _sendQuickMessage("Can't talk now. What's up?");
              },
            ),
            ListTile(
              title: const Text("I'll call you right back.", style: TextStyle(color: Colors.white)),
              onTap: () {
                Navigator.pop(ctx);
                _sendQuickMessage("I'll call you right back.");
              },
            ),
            ListTile(
              title: const Text("I'm in a meeting.", style: TextStyle(color: Colors.white)),
              onTap: () {
                Navigator.pop(ctx);
                _sendQuickMessage("I'm in a meeting.");
              },
            ),
            ListTile(
              title: const Text("Call you later!", style: TextStyle(color: Colors.white)),
              onTap: () {
                Navigator.pop(ctx);
                _sendQuickMessage("Call you later!");
              },
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isVideo = widget.callRecord.type == CallType.video;
    final callerName = widget.callRecord.callerName.isNotEmpty
        ? widget.callRecord.callerName
        : 'GoChat Contact';

    return Scaffold(
      backgroundColor: const Color(0xFF0D1418),
      body: SafeArea(
        child: Column(
          children: [
            const SizedBox(height: 30),

            // Top Status Bar: Call Type Badge & Security Info
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: Colors.white12, width: 0.5),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    isVideo ? Icons.videocam_rounded : Icons.call_rounded,
                    color: AppTheme.primary,
                    size: 16,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    isVideo ? 'Incoming HD Video Call' : 'Incoming HD Audio Call',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(width: 6),
                  const Icon(Icons.lock_rounded, color: AppTheme.primary, size: 13),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // Caller Name & PIN
            Text(
              callerName,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 28,
                fontWeight: FontWeight.bold,
                letterSpacing: 0.5,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 6),
            const Text(
              'End-to-End Encrypted VoIP Call',
              style: TextStyle(
                color: Colors.white70,
                fontSize: 14,
              ),
            ),

            const Spacer(),

            // Pulsing Glowing Radar Circles with Avatar
            Center(
              child: SizedBox(
                width: 240,
                height: 240,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    // Outer Radar Pulse 1
                    AnimatedBuilder(
                      animation: _radarController,
                      builder: (_, _) {
                        final val = _radarController.value;
                        return Container(
                          width: 140 + (100 * val),
                          height: 140 + (100 * val),
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: AppTheme.primary.withValues(alpha: max(0, 1.0 - val) * 0.4),
                              width: 2,
                            ),
                          ),
                        );
                      },
                    ),

                    // Outer Radar Pulse 2
                    AnimatedBuilder(
                      animation: _radarController,
                      builder: (_, _) {
                        final val = (_radarController.value + 0.5) % 1.0;
                        return Container(
                          width: 140 + (100 * val),
                          height: 140 + (100 * val),
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: AppTheme.primary.withValues(alpha: max(0, 1.0 - val) * 0.4),
                              width: 2,
                            ),
                          ),
                        );
                      },
                    ),

                    // Avatar Circle with Glow
                    Container(
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        boxShadow: [
                          BoxShadow(
                            color: AppTheme.primary.withValues(alpha: 0.45),
                            blurRadius: 36,
                            spreadRadius: 8,
                          ),
                        ],
                      ),
                      child: CustomAvatar(
                        imageUrl: widget.callRecord.callerAvatar,
                        name: callerName,
                        radius: 54,
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const Spacer(),

            // Quick Actions Bar (Remind Me, Message)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 40),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  _buildSecondaryAction(
                    icon: Icons.alarm_rounded,
                    label: 'Remind Me',
                    onTap: () {
                      HapticFeedback.lightImpact();
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('⏰ Reminder set for in 10 minutes')),
                      );
                    },
                  ),
                  _buildSecondaryAction(
                    icon: Icons.message_rounded,
                    label: 'Message',
                    onTap: _showQuickMessageSheet,
                  ),
                ],
              ),
            ),

            const SizedBox(height: 36),

            // Main Accept / Decline Call Buttons
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 36),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  // Decline Button (Red)
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      GestureDetector(
                        onTap: _declineCall,
                        child: Container(
                          width: 72,
                          height: 72,
                          decoration: BoxDecoration(
                            color: AppTheme.dangerRed,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: AppTheme.dangerRed.withValues(alpha: 0.5),
                                blurRadius: 20,
                                offset: const Offset(0, 6),
                              ),
                            ],
                          ),
                          child: const Icon(
                            Icons.call_end_rounded,
                            color: Colors.white,
                            size: 32,
                          ),
                        ),
                      ),
                      const SizedBox(height: 10),
                      const Text(
                        'Decline',
                        style: TextStyle(
                          color: Colors.white70,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),

                  // Accept Button (Green)
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      GestureDetector(
                        onTap: _acceptCall,
                        child: Container(
                          width: 72,
                          height: 72,
                          decoration: BoxDecoration(
                            color: AppTheme.primary,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: AppTheme.primary.withValues(alpha: 0.55),
                                blurRadius: 22,
                                offset: const Offset(0, 6),
                              ),
                            ],
                          ),
                          child: Icon(
                            isVideo ? Icons.videocam_rounded : Icons.call_rounded,
                            color: Colors.black,
                            size: 34,
                          ),
                        ),
                      ),
                      const SizedBox(height: 10),
                      const Text(
                        'Accept',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            const SizedBox(height: 40),
          ],
        ),
      ),
    );
  }

  Widget _buildSecondaryAction({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.12),
              shape: BoxShape.circle,
            ),
            child: Icon(icon, color: Colors.white, size: 22),
          ),
          const SizedBox(height: 6),
          Text(
            label,
            style: const TextStyle(color: Colors.white60, fontSize: 12),
          ),
        ],
      ),
    );
  }
}
