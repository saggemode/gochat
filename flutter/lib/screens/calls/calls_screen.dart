import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import 'active_call_screen.dart';

class CallsScreen extends StatelessWidget {
  final AppState appState;

  const CallsScreen({super.key, required this.appState});

  Widget _buildDirectionIcon(CallDirection dir) {
    switch (dir) {
      case CallDirection.incoming:
        return const Icon(Icons.call_received_rounded, size: 16, color: AppTheme.onlineGreen);
      case CallDirection.outgoing:
        return const Icon(Icons.call_made_rounded, size: 16, color: AppTheme.onlineGreen);
      case CallDirection.missed:
        return const Icon(Icons.call_missed_rounded, size: 16, color: AppTheme.dangerRed);
    }
  }

  void _startCall(BuildContext context, CallRecord record, CallType type) {
    final newCall = CallRecord(
      id: 'call_${DateTime.now().millisecondsSinceEpoch}',
      callerId: record.callerId,
      callerName: record.callerName,
      callerAvatar: record.callerAvatar,
      type: type,
      direction: CallDirection.outgoing,
      timestamp: DateTime.now(),
    );
    appState.startCall(newCall);
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ActiveCallScreen(
          callRecord: appState.activeCall!,
          appState: appState,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final calls = appState.calls;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          'Calls',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isDark ? AppTheme.textLight : AppTheme.textDark,
          ),
        ),
        actions: [
          IconButton(icon: const Icon(Icons.search), onPressed: () {}),
          IconButton(icon: const Icon(Icons.more_vert), onPressed: () {}),
        ],
      ),
      body: ListView(
        children: [
          // Create Call link tile
          ListTile(
            leading: Container(
              width: 48,
              height: 48,
              decoration: const BoxDecoration(
                color: AppTheme.primary,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.link_rounded, color: Colors.black, size: 24),
            ),
            title: Text(
              'Create call link',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: isDark ? AppTheme.textLight : AppTheme.textDark,
              ),
            ),
            subtitle: Text(
              'Share a link for your GoChat audio/video room',
              style: TextStyle(
                color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                fontSize: 13,
              ),
            ),
            onTap: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('🔗 Call link generated & copied to clipboard')),
              );
            },
          ),

          const SectionHeader(title: 'RECENT CALLS'),

          if (calls.isEmpty)
            Padding(
              padding: const EdgeInsets.all(32.0),
              child: EmptyStateView(
                icon: Icons.phone_missed_rounded,
                title: 'No Recent Calls',
                description: 'To start a voice or video call with a contact, open a chat room and tap the call icon.',
              ),
            )
          else
            ...calls.map((call) {
              final dateStr = DateFormat('MMM dd, hh:mm a').format(call.timestamp);
              return ListTile(
                leading: CustomAvatar(
                  imageUrl: call.callerAvatar,
                  name: call.callerName,
                  radius: 24,
                ),
                title: Text(
                  call.callerName,
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: isDark ? AppTheme.textLight : AppTheme.textDark,
                  ),
                ),
                subtitle: Row(
                  children: [
                    _buildDirectionIcon(call.direction),
                    const SizedBox(width: 6),
                    Text(
                      dateStr,
                      style: TextStyle(
                        fontSize: 12,
                        color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                      ),
                    ),
                  ],
                ),
                trailing: IconButton(
                  icon: Icon(
                    call.type == CallType.video ? Icons.videocam_rounded : Icons.call_rounded,
                    color: AppTheme.primary,
                  ),
                  onPressed: () => _startCall(context, call, call.type),
                ),
              );
            }),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        heroTag: 'calls_fab',
        onPressed: () {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Select contact for new call')),
          );
        },
        child: const Icon(Icons.add_call),
      ),
    );
  }
}
