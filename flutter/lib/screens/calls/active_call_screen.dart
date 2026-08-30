import 'package:flutter/material.dart';
import '../../core/models/call.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';

class ActiveCallScreen extends StatefulWidget {
  final CallRecord callRecord;
  final AppState appState;

  const ActiveCallScreen({
    super.key,
    required this.callRecord,
    required this.appState,
  });

  @override
  State<ActiveCallScreen> createState() => _ActiveCallScreenState();
}

class _ActiveCallScreenState extends State<ActiveCallScreen> {
  bool _isMuted = false;
  bool _isSpeakerOn = true;
  bool _isVideoDisabled = false;

  @override
  Widget build(BuildContext context) {
    final isVideo =
        widget.callRecord.type == CallType.video && !_isVideoDisabled;

    return Scaffold(
      backgroundColor: isVideo ? Colors.black : const Color(0xFF0F1B21),
      body: SafeArea(
        child: Stack(
          children: [
            // Video Background Simulation or Avatar Pulse
            if (isVideo)
              Positioned.fill(
                child: Image.network(
                  widget.callRecord.callerAvatar,
                  fit: BoxFit.cover,
                  errorBuilder: (ctx, _, __) =>
                      Container(color: AppTheme.darkSurface),
                ),
              )
            else
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: AppTheme.primary.withValues(alpha: 0.4),
                          width: 4,
                        ),
                      ),
                      child: CircleAvatar(
                        radius: 60,
                        backgroundColor: AppTheme.darkCard,
                        backgroundImage:
                            widget.callRecord.callerAvatar.isNotEmpty
                            ? NetworkImage(widget.callRecord.callerAvatar)
                            : null,
                        child: widget.callRecord.callerAvatar.isEmpty
                            ? const Icon(
                                Icons.person,
                                size: 60,
                                color: AppTheme.iconColor,
                              )
                            : null,
                      ),
                    ),
                    const SizedBox(height: 24),
                    Text(
                      widget.callRecord.callerName,
                      style: const TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                        color: AppTheme.textLight,
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'End-to-End Encrypted (02:18)',
                      style: TextStyle(
                        fontSize: 13,
                        color: AppTheme.primary,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ),

            // Top bar controls (minimize, add participant)
            Positioned(
              top: 16,
              left: 16,
              right: 16,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  IconButton(
                    icon: const Icon(
                      Icons.keyboard_arrow_down,
                      size: 30,
                      color: Colors.white,
                    ),
                    onPressed: () => Navigator.pop(context),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.black45,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: const Row(
                      children: [
                        Icon(Icons.lock, size: 14, color: AppTheme.primary),
                        SizedBox(width: 4),
                        Text(
                          'Protected by E2EE',
                          style: TextStyle(fontSize: 11, color: Colors.white70),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(
                      Icons.person_add_rounded,
                      color: Colors.white,
                    ),
                    onPressed: () {},
                  ),
                ],
              ),
            ),

            // Bottom Call Action Controls
            Positioned(
              bottom: 30,
              left: 20,
              right: 20,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  vertical: 16,
                  horizontal: 20,
                ),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.65),
                  borderRadius: BorderRadius.circular(32),
                  border: Border.all(color: Colors.white12),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    _buildCallButton(
                      icon: _isSpeakerOn
                          ? Icons.volume_up_rounded
                          : Icons.volume_off_rounded,
                      isActive: _isSpeakerOn,
                      onTap: () => setState(() => _isSpeakerOn = !_isSpeakerOn),
                    ),
                    if (widget.callRecord.type == CallType.video)
                      _buildCallButton(
                        icon: _isVideoDisabled
                            ? Icons.videocam_off_rounded
                            : Icons.videocam_rounded,
                        isActive: !_isVideoDisabled,
                        onTap: () => setState(
                          () => _isVideoDisabled = !_isVideoDisabled,
                        ),
                      ),
                    _buildCallButton(
                      icon: _isMuted
                          ? Icons.mic_off_rounded
                          : Icons.mic_rounded,
                      isActive: !_isMuted,
                      onTap: () => setState(() => _isMuted = !_isMuted),
                    ),
                    // End Call button
                    GestureDetector(
                      onTap: () {
                        widget.appState.endCall();
                        Navigator.pop(context);
                      },
                      child: Container(
                        width: 54,
                        height: 54,
                        decoration: const BoxDecoration(
                          color: AppTheme.dangerRed,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.call_end_rounded,
                          color: Colors.white,
                          size: 28,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCallButton({
    required IconData icon,
    required bool isActive,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(
          color: isActive ? Colors.white24 : Colors.white10,
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: Colors.white, size: 22),
      ),
    );
  }
}
