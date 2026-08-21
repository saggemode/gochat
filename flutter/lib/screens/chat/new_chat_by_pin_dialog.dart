import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
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
  Timer? _debounceTimer;

  bool _isSearching = false;
  User? _foundUser;
  String? _error;

  @override
  void initState() {
    super.initState();
    _pinController.addListener(_onPinChanged);
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    _pinController.removeListener(_onPinChanged);
    _pinController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  void _onPinChanged() {
    final rawPin = _pinController.text.trim().toUpperCase();
    _debounceTimer?.cancel();

    if (rawPin.length < 6) {
      if (_foundUser != null || _isSearching) {
        setState(() {
          _foundUser = null;
          _isSearching = false;
          _error = null;
        });
      }
      return;
    }

    setState(() {
      _isSearching = true;
      _error = null;
    });

    _debounceTimer = Timer(const Duration(milliseconds: 200), () async {
      final user = await widget.appState.lookupUserByPin(rawPin);
      if (!mounted) return;

      setState(() {
        _isSearching = false;
        _foundUser = user;
        if (user != null) {
          _nameController.text = user.displayName;
        }
      });
    });
  }

  Future<void> _startChat() async {
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
    final title = customName.isNotEmpty
        ? customName
        : (_foundUser?.displayName ?? 'BBM User ($rawPin)');
    final avatarUrl = _foundUser?.avatarUrl ?? '';

    final recipientId = _foundUser?.id ?? '';
    final memberIds = recipientId.isNotEmpty ? [recipientId] : [rawPin];

    Conversation targetConv;
    final matchIndex = widget.appState.conversations.indexWhere(
      (c) => c.title.toUpperCase().contains(rawPin) || c.id.toUpperCase().contains(rawPin) || (recipientId.isNotEmpty && c.id == recipientId),
    );

    if (matchIndex != -1) {
      targetConv = widget.appState.conversations[matchIndex];
    } else {
      targetConv = await widget.appState.createConversation(
        title,
        memberIds,
        invitationStatus: InvitationStatus.pendingOutgoing,
        partnerPin: rawPin,
      );
      if (avatarUrl.isNotEmpty) {
        targetConv = targetConv.copyWith(avatarUrl: avatarUrl);
      }
      // Send initial invitation request message
      await widget.appState.sendMessage(
        targetConv.id,
        '👋 Hi! I added you on GoChat via BBM PIN ($rawPin).',
      );
    }

    if (!mounted) return;
    Navigator.pop(context);
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChatRoomScreen(
          conversation: targetConv,
          appState: widget.appState,
        ),
      ),
    );

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('📨 Invitation sent to $title'),
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
                        'Instant lookup & encrypted messaging',
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
                suffixIcon: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (_isSearching)
                      const Padding(
                        padding: EdgeInsets.all(12),
                        child: SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary),
                        ),
                      )
                    else if (_foundUser != null)
                      const Padding(
                        padding: EdgeInsets.only(right: 6),
                        child: Icon(Icons.check_circle_rounded, color: AppTheme.primary, size: 20),
                      ),
                    IconButton(
                      icon: const Icon(Icons.paste_rounded, color: AppTheme.primary, size: 20),
                      tooltip: 'Paste from clipboard',
                      onPressed: () async {
                        final data = await Clipboard.getData('text/plain');
                        if (data != null && data.text != null) {
                          _pinController.text = data.text!.trim().toUpperCase();
                        }
                      },
                    ),
                  ],
                ),
              ),
              onSubmitted: (_) => _startChat(),
            ),

            // ── Auto-Populated Contact Preview Card ───────────────────────────
            if (_foundUser != null) ...[
              const SizedBox(height: 16),
              AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: AppTheme.primary.withValues(alpha: 0.4),
                    width: 1.2,
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: AppTheme.primary.withValues(alpha: 0.1),
                      blurRadius: 10,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Row(
                  children: [
                    CustomAvatar(
                      imageUrl: _foundUser!.avatarUrl,
                      name: _foundUser!.displayName,
                      radius: 24,
                      isOnline: _foundUser!.isOnline,
                      showOnlineBadge: true,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Flexible(
                                child: Text(
                                  _foundUser!.displayName,
                                  style: const TextStyle(
                                    fontSize: 15,
                                    fontWeight: FontWeight.bold,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              const SizedBox(width: 4),
                              const Icon(Icons.verified, color: AppTheme.primary, size: 15),
                            ],
                          ),
                          const SizedBox(height: 2),
                          Text(
                            _foundUser!.statusText,
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppTheme.textMuted,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 4),
                          Row(
                            children: [
                              StatusBadge(
                                text: 'BBM: ${_foundUser!.pin}',
                                type: BadgeType.primary,
                              ),
                              if (_foundUser!.phone.isNotEmpty) ...[
                                const SizedBox(width: 6),
                                Text(
                                  _foundUser!.phone,
                                  style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                                ),
                              ],
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],

            const SizedBox(height: 16),

            // Optional Custom Display Name Field
            const Text(
              'CONTACT NAME (AUTO-POPULATED)',
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
                    label: Text(
                      _foundUser != null ? 'Chat with ${_foundUser!.displayName.split(' ').first}' : 'Start Chat',
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
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
