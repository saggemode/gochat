import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/call.dart';
import '../../core/services/push_notification_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../calls/incoming_voip_call_screen.dart';

class NotificationsSettingsScreen extends StatefulWidget {
  final AppState appState;

  const NotificationsSettingsScreen({super.key, required this.appState});

  static void open(BuildContext context, {required AppState appState}) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => NotificationsSettingsScreen(appState: appState),
      ),
    );
  }

  @override
  State<NotificationsSettingsScreen> createState() =>
      _NotificationsSettingsScreenState();
}

class _NotificationsSettingsScreenState
    extends State<NotificationsSettingsScreen> {
  final PushNotificationService _pushService = PushNotificationService();

  @override
  void initState() {
    super.initState();
    _pushService.init().then((_) {
      if (mounted) setState(() {});
    });
  }

  void _simulateIncomingCall() {
    HapticFeedback.heavyImpact();
    final testRecord = CallRecord(
      id: 'test_call_${DateTime.now().millisecondsSinceEpoch}',
      callerId: 'test_caller_123',
      callerName: 'Sarah Connor',
      callerAvatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400',
      receiverId: widget.appState.currentUser?.id ?? 'me',
      type: CallType.audio,
      direction: CallDirection.incoming,
      status: CallStatus.active,
      timestamp: DateTime.now(),
    );

    IncomingVoipCallScreen.show(
      context,
      callRecord: testRecord,
      appState: widget.appState,
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications & Sounds', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
      ),
      body: ListView(
        children: [
          // ── Push Notifications Master Toggle ─────────────────────────────────
          SwitchListTile(
            title: const Text('Allow Push Notifications', style: TextStyle(fontWeight: FontWeight.bold)),
            subtitle: const Text(
              'Receive instant alerts when messages arrive and app is closed',
              style: TextStyle(fontSize: 12, color: AppTheme.textMuted),
            ),
            value: _pushService.isEnabled,
            activeTrackColor: AppTheme.primary.withValues(alpha: 0.5),
            activeColor: AppTheme.primary,
            onChanged: (val) async {
              await _pushService.updateSettings(isEnabled: val);
              setState(() {});
            },
          ),
          const Divider(),

          // ── Section: Message Alerts ──────────────────────────────────────────
          _buildSectionHeader('MESSAGE NOTIFICATIONS'),
          SwitchListTile(
            title: const Text('Conversation Sounds'),
            subtitle: const Text('Play sound for incoming and outgoing messages', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            value: _pushService.soundEnabled,
            activeTrackColor: AppTheme.primary.withValues(alpha: 0.5),
            activeColor: AppTheme.primary,
            onChanged: _pushService.isEnabled
                ? (val) async {
                    await _pushService.updateSettings(soundEnabled: val);
                    setState(() {});
                  }
                : null,
          ),
          SwitchListTile(
            title: const Text('Haptic Vibration'),
            subtitle: const Text('Vibrate on incoming message alert', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            value: _pushService.vibrateEnabled,
            activeTrackColor: AppTheme.primary.withValues(alpha: 0.5),
            activeColor: AppTheme.primary,
            onChanged: _pushService.isEnabled
                ? (val) async {
                    await _pushService.updateSettings(vibrateEnabled: val);
                    setState(() {});
                  }
                : null,
          ),
          SwitchListTile(
            title: const Text('Message Preview'),
            subtitle: const Text('Show message sender and text preview in notification banner', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            value: _pushService.previewEnabled,
            activeTrackColor: AppTheme.primary.withValues(alpha: 0.5),
            activeColor: AppTheme.primary,
            onChanged: _pushService.isEnabled
                ? (val) async {
                    await _pushService.updateSettings(previewEnabled: val);
                    setState(() {});
                  }
                : null,
          ),
          const Divider(),

          // ── Section: VoIP Calls ──────────────────────────────────────────────
          _buildSectionHeader('VOIP CALLS & LOCKSCREEN'),
          SwitchListTile(
            title: const Text('Full-Screen VoIP Call Screen', style: TextStyle(fontWeight: FontWeight.bold)),
            subtitle: const Text(
              'Ring like a real phone call with CallKit / Android Telecom when locked',
              style: TextStyle(fontSize: 12, color: AppTheme.textMuted),
            ),
            value: _pushService.voipCallPushEnabled,
            activeTrackColor: AppTheme.primary.withValues(alpha: 0.5),
            activeColor: AppTheme.primary,
            onChanged: (val) async {
              await _pushService.updateSettings(voipCallPushEnabled: val);
              setState(() {});
            },
          ),
          const Divider(),

          // ── Section: Testing & Diagnostics ───────────────────────────────────
          _buildSectionHeader('SIMULATION & DIAGNOSTICS'),
          ListTile(
            leading: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.15),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.notifications_active_rounded, color: AppTheme.primary, size: 20),
            ),
            title: const Text('Send Test Push Notification'),
            subtitle: const Text('Test in-app heads-up notification banner and FCM delivery', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            onTap: () => _pushService.sendTestNotification(context),
          ),
          ListTile(
            leading: Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.green.withValues(alpha: 0.15),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.ring_volume_rounded, color: Colors.green, size: 20),
            ),
            title: const Text('Simulate Incoming VoIP Call'),
            subtitle: const Text('Launch full-screen lockscreen ringing experience with radar glow & vibration', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
            onTap: _simulateIncomingCall,
          ),

          // Token info card
          Container(
            margin: const EdgeInsets.all(16),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: isDark ? AppTheme.darkCard : const Color(0xFFF3F4F6),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                width: 0.5,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Row(
                  children: [
                    Icon(Icons.shield_outlined, size: 16, color: AppTheme.primary),
                    SizedBox(width: 6),
                    Text('FCM / APNs Pipeline Status', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'Device Token: ${_pushService.fcmToken ?? 'Generating...'}',
                  style: const TextStyle(fontSize: 11, fontFamily: 'monospace', color: AppTheme.textMuted),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Architecture: FCM HTTP v1 + APNs VoIP Push (VoIP wake locks enabled)',
                  style: TextStyle(fontSize: 11, color: AppTheme.textMuted),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.1,
          color: AppTheme.primary,
        ),
      ),
    );
  }
}
