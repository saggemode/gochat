import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../core/models/call.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
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
    appState.startCall(record.callerName, record.callerAvatar, type);
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

    return Scaffold(
      appBar: AppBar(
        title: const Text('Calls', style: TextStyle(fontWeight: FontWeight.bold)),
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
              width: 52,
              height: 52,
              decoration: const BoxDecoration(
                color: AppTheme.primary,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.link_rounded, color: Colors.white, size: 26),
            ),
            title: const Text(
              'Create call link',
              style: TextStyle(fontWeight: FontWeight.bold, color: AppTheme.textLight),
            ),
            subtitle: const Text(
              'Share a link for your GoChat call',
              style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
            ),
            onTap: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('🔗 Call link generated & copied to clipboard')),
              );
            },
          ),

          const Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              'Recent',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.bold,
                color: AppTheme.textMuted,
              ),
            ),
          ),

          if (calls.isEmpty)
            const Padding(
              padding: EdgeInsets.all(32.0),
              child: Center(
                child: Text('No recent calls', style: TextStyle(color: AppTheme.textMuted)),
              ),
            )
          else
            ...calls.map((call) {
              final dateStr = DateFormat('MMM dd, hh:mm a').format(call.timestamp);
              return ListTile(
                leading: CircleAvatar(
                  radius: 26,
                  backgroundColor: AppTheme.darkCard,
                  backgroundImage: call.callerAvatar.isNotEmpty
                      ? NetworkImage(call.callerAvatar)
                      : null,
                  child: call.callerAvatar.isEmpty
                      ? const Icon(Icons.person, color: AppTheme.iconColor)
                      : null,
                ),
                title: Text(
                  call.callerName,
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: call.direction == CallDirection.missed
                        ? AppTheme.dangerRed
                        : AppTheme.textLight,
                  ),
                ),
                subtitle: Row(
                  children: [
                    _buildDirectionIcon(call.direction),
                    const SizedBox(width: 4),
                    Text(dateStr, style: const TextStyle(fontSize: 12, color: AppTheme.textMuted)),
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
        backgroundColor: AppTheme.primary,
        onPressed: () {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Select contact to call')),
          );
        },
        child: const Icon(Icons.add_call),
      ),
    );
  }
}
