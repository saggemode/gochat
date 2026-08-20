import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../qr/qr_scanner_screen.dart';
import 'chat_room_screen.dart';

class NewChatByPinDialog extends StatefulWidget {
  final AppState appState;

  const NewChatByPinDialog({super.key, required this.appState});

  static Future<void> show(BuildContext context, AppState appState) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => NewChatByPinDialog(appState: appState),
    );
  }

  @override
  State<NewChatByPinDialog> createState() => _NewChatByPinDialogState();
}

class _NewChatByPinDialogState extends State<NewChatByPinDialog> {
  final _pinController = TextEditingController();
  final _nameController = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _pinController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  void _startChat() {
    final rawPin = _pinController.text.trim().toUpperCase();
    if (rawPin.isEmpty) {
      setState(() => _error = 'Please enter a BBM PIN');
      return;
    }
    if (rawPin.length < 4) {
      setState(() => _error = 'PIN must be at least 4 characters');
      return;
    }

    final customName = _nameController.text.trim();
    final title = customName.isNotEmpty ? customName : 'BBM User ($rawPin)';

    // Look for existing conversation or create new
    final existing = widget.appState.conversations.firstWhere(
      (c) => c.title.toUpperCase().contains(rawPin) || c.id.toUpperCase().contains(rawPin),
      orElse: () {
        final newConv = Conversation(
          id: 'conv_pin_$rawPin',
          title: title,
          avatarUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
          type: ConversationType.direct,
          isOnline: true,
          unreadCount: 0,
        );
        widget.appState.createConversation(title, []);
        return newConv;
      },
    );

    Navigator.pop(context);
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChatRoomScreen(
          conversation: existing,
          appState: widget.appState,
        ),
      ),
    );

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('💬 Chat started with PIN $rawPin'),
        backgroundColor: AppTheme.primary,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return Container(
      padding: EdgeInsets.fromLTRB(24, 20, 24, 24 + bottomInset),
      decoration: BoxDecoration(
        color: isDark ? AppTheme.darkSurface : Colors.white,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Handle Bar
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: isDark ? Colors.white24 : Colors.black12,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Header
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: AppTheme.primary.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(Icons.vpn_key_rounded, color: AppTheme.primary, size: 22),
                ),
                const SizedBox(width: 12),
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Start Chat by BBM PIN',
                        style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                      ),
                      Text(
                        'Connect instantly without sharing phone number',
                        style: TextStyle(fontSize: 12, color: AppTheme.textMuted),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),

            // PIN Field
            const Text(
              'RECIPIENT BBM PIN',
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1),
            ),
            const SizedBox(height: 6),
            TextField(
              controller: _pinController,
              autofocus: true,
              textCapitalization: TextCapitalization.characters,
              maxLength: 8,
              style: const TextStyle(
                fontSize: 18,
                fontFamily: 'monospace',
                fontWeight: FontWeight.bold,
                letterSpacing: 3,
              ),
              decoration: InputDecoration(
                hintText: 'e.g. 8492A1',
                counterText: '',
                prefixIcon: const Icon(Icons.tag_rounded, color: AppTheme.primary),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.paste_rounded, color: AppTheme.primary, size: 20),
                  tooltip: 'Paste from clipboard',
                  onPressed: () async {
                    final data = await Clipboard.getData('text/plain');
                    if (data != null && data.text != null) {
                      setState(() {
                        _pinController.text = data.text!.trim().toUpperCase();
                      });
                    }
                  },
                ),
              ),
              onSubmitted: (_) => _startChat(),
            ),

            const SizedBox(height: 14),

            // Optional Name Field
            const Text(
              'CONTACT NAME (OPTIONAL)',
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: AppTheme.textMuted, letterSpacing: 1),
            ),
            const SizedBox(height: 6),
            TextField(
              controller: _nameController,
              decoration: const InputDecoration(
                hintText: 'e.g. Sarah Connor',
                prefixIcon: Icon(Icons.person_outline_rounded),
              ),
              onSubmitted: (_) => _startChat(),
            ),

            if (_error != null) ...[
              const SizedBox(height: 10),
              Text(
                _error!,
                style: const TextStyle(color: AppTheme.dangerRed, fontSize: 12, fontWeight: FontWeight.w600),
              ),
            ],

            const SizedBox(height: 22),

            // Action Buttons
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    icon: const Icon(Icons.qr_code_scanner_rounded, size: 18),
                    label: const Text('Scan QR'),
                    onPressed: () {
                      Navigator.pop(context);
                      QrScannerScreen.open(context, widget.appState);
                    },
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  flex: 2,
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.primary,
                      foregroundColor: Colors.black,
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    icon: const Icon(Icons.chat_bubble_rounded, size: 18),
                    label: const Text('Start Chat', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
                    onPressed: _startChat,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
