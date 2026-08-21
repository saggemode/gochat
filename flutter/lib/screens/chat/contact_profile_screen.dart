import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import '../calls/active_call_screen.dart';
import 'shared_media_gallery_screen.dart';

class ContactProfileScreen extends StatefulWidget {
  final Conversation conversation;
  final AppState appState;

  const ContactProfileScreen({
    super.key,
    required this.conversation,
    required this.appState,
  });

  static void open(BuildContext context, {required Conversation conversation, required AppState appState}) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ContactProfileScreen(conversation: conversation, appState: appState),
      ),
    );
  }

  @override
  State<ContactProfileScreen> createState() => _ContactProfileScreenState();
}

class _ContactProfileScreenState extends State<ContactProfileScreen> {
  bool _isMuted = false;
  String _disappearingTimer = 'Off';

  @override
  void initState() {
    super.initState();
    _isMuted = widget.conversation.isMuted;
  }

  String get _pin {
    final id = widget.conversation.id.replaceAll('-', '');
    return id.length >= 6 ? id.substring(0, 6).toUpperCase() : '8492A1';
  }

  void _startCall(CallType type) {
    widget.appState.startCall(
      CallRecord(
        id: 'call_${DateTime.now().millisecondsSinceEpoch}',
        callerId: widget.conversation.id,
        callerName: widget.conversation.title,
        callerAvatar: widget.conversation.avatarUrl,
        type: type,
        direction: CallDirection.outgoing,
        timestamp: DateTime.now(),
      ),
    );
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ActiveCallScreen(
          callRecord: widget.appState.activeCall!,
          appState: widget.appState,
        ),
      ),
    );
  }

  void _showDisappearingDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
        title: const Text('Disappearing Messages'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: ['Off', '24 Hours', '7 Days', '90 Days'].map((opt) {
            return ListTile(
              title: Text(opt),
              trailing: _disappearingTimer == opt ? const Icon(Icons.check_circle_rounded, color: AppTheme.primary) : null,
              onTap: () {
                setState(() => _disappearingTimer = opt);
                Navigator.pop(ctx);
              },
            );
          }).toList(),
        ),
      ),
    );
  }

  void _showEncryptionVerification() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
        title: const Row(
          children: [
            Icon(Icons.lock_rounded, color: AppTheme.primary),
            SizedBox(width: 8),
            Text('Verify Security Code'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Messages and calls with this contact are protected with End-to-End Encryption.',
              style: TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(12),
              color: Colors.white,
              child: const Icon(Icons.qr_code_2_rounded, size: 140, color: Colors.black),
            ),
            const SizedBox(height: 12),
            const Text(
              'Fingerprint: 8492-2094-1182-3849\n4920-1928-3019-8472',
              style: TextStyle(fontFamily: 'monospace', fontSize: 11, color: AppTheme.textMuted),
              textAlign: TextAlign.center,
            ),
          ],
        ),
        actions: [
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Verified', style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isGroup = widget.conversation.type == ConversationType.group;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final messages = widget.appState.getMessagesFor(widget.conversation.id);
    final mediaMessages = messages.where((m) => m.type == MessageType.image || m.type == MessageType.video).toList();

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          // ── Sliver AppBar with Avatar ───────────────────────────────────────
          SliverAppBar(
            expandedHeight: 280,
            pinned: true,
            flexibleSpace: FlexibleSpaceBar(
              title: Text(
                widget.conversation.title,
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18),
              ),
              background: Stack(
                fit: StackFit.expand,
                children: [
                  if (widget.conversation.avatarUrl.isNotEmpty)
                    Image.network(
                      widget.conversation.avatarUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => Container(color: AppTheme.darkSurface),
                    )
                  else
                    Container(
                      color: AppTheme.darkCard,
                      child: const Center(
                        child: Icon(Icons.person, size: 90, color: AppTheme.iconColor),
                      ),
                    ),
                  Container(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [
                          Colors.transparent,
                          Colors.black.withValues(alpha: 0.8),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),

          // ── Body Info Content ───────────────────────────────────────────────
          SliverList(
            delegate: SliverChildListDelegate([
              // Quick Actions Bar
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    _buildActionButton(Icons.call_rounded, 'Audio', () => _startCall(CallType.audio)),
                    _buildActionButton(Icons.videocam_rounded, 'Video', () => _startCall(CallType.video)),
                    _buildActionButton(Icons.vibration_rounded, 'PING!', () {
                      widget.appState.sendPing(widget.conversation.id);
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('💥 GOCHAT PING! sent')),
                      );
                    }),
                    _buildActionButton(Icons.search_rounded, 'Search', () => Navigator.pop(context)),
                  ],
                ),
              ),

              const Divider(),

              // ── GOCHAT PIN Card ─────────────────────────────────────────────
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: isDark ? AppTheme.darkCard : Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                    width: 0.5,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.vpn_key_rounded, color: AppTheme.primary, size: 20),
                        const SizedBox(width: 8),
                        Text(
                          isGroup ? 'GROUP GOCHAT PIN' : 'GOCHAT PIN',
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                            letterSpacing: 1,
                          ),
                        ),
                        const Spacer(),
                        StatusBadge(
                          text: widget.conversation.isOnline ? 'ONLINE' : 'ACTIVE',
                          type: widget.conversation.isOnline ? BadgeType.success : BadgeType.neutral,
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          _pin,
                          style: const TextStyle(
                            fontSize: 22,
                            fontFamily: 'monospace',
                            fontWeight: FontWeight.w900,
                            letterSpacing: 3,
                            color: AppTheme.primary,
                          ),
                        ),
                        Row(
                          children: [
                            IconButton(
                              icon: const Icon(Icons.copy_rounded, color: AppTheme.primary, size: 20),
                              tooltip: 'Copy PIN',
                              onPressed: () {
                                Clipboard.setData(ClipboardData(text: _pin));
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(content: Text('PIN $_pin copied to clipboard')),
                                );
                              },
                            ),
                            IconButton(
                              icon: const Icon(Icons.share_rounded, color: AppTheme.primary, size: 20),
                              tooltip: 'Share PIN',
                              onPressed: () {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(content: Text('Sharing GoChat PIN $_pin')),
                                );
                              },
                            ),
                          ],
                        ),
                      ],
                    ),
                  ],
                ),
              ),

              // ── Shared Media Preview ────────────────────────────────────────
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: isDark ? AppTheme.darkCard : Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                    width: 0.5,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text(
                          'Media, Links, and Docs',
                          style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                        ),
                        TextButton(
                          onPressed: () {
                            Navigator.push(
                              context,
                              MaterialPageRoute(
                                builder: (_) => SharedMediaGalleryScreen(
                                  title: widget.conversation.title,
                                  messages: messages,
                                ),
                              ),
                            );
                          },
                          child: Text(
                            '${mediaMessages.length} >',
                            style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold),
                          ),
                        ),
                      ],
                    ),
                    if (mediaMessages.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      SizedBox(
                        height: 72,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          itemCount: mediaMessages.length.clamp(0, 6),
                          separatorBuilder: (_, __) => const SizedBox(width: 8),
                          itemBuilder: (ctx, idx) {
                            final m = mediaMessages[idx];
                            return ClipRRect(
                              borderRadius: BorderRadius.circular(8),
                              child: Image.network(
                                m.mediaUrl ?? '',
                                width: 72,
                                height: 72,
                                fit: BoxFit.cover,
                                errorBuilder: (_, __, ___) => Container(
                                  width: 72,
                                  height: 72,
                                  color: Colors.black26,
                                  child: const Icon(Icons.image),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                  ],
                ),
              ),

              // ── Settings & Privacy ──────────────────────────────────────────
              const SectionHeader(title: 'SETTINGS & PRIVACY'),

              ListTile(
                leading: const Icon(Icons.notifications_none_rounded, color: AppTheme.primary),
                title: const Text('Mute Notifications'),
                trailing: Switch(
                  value: _isMuted,
                  activeColor: AppTheme.primary,
                  onChanged: (val) => setState(() => _isMuted = val),
                ),
              ),

              ListTile(
                leading: const Icon(Icons.timer_outlined, color: AppTheme.primary),
                title: const Text('Disappearing Messages'),
                subtitle: Text(_disappearingTimer, style: const TextStyle(fontSize: 12, color: AppTheme.textMuted)),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: _showDisappearingDialog,
              ),

              ListTile(
                leading: const Icon(Icons.lock_outline_rounded, color: AppTheme.primary),
                title: const Text('Encryption Verification'),
                subtitle: const Text('End-to-end encrypted security fingerprint', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: _showEncryptionVerification,
              ),

              // ── Group Members Section ───────────────────────────────────────
              if (isGroup) ...[
                const SectionHeader(title: 'GROUP PARTICIPANTS (4)'),
                ListTile(
                  leading: CircleAvatar(
                    backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
                    child: const Icon(Icons.person_add_rounded, color: AppTheme.primary),
                  ),
                  title: const Text('Add Participants', style: TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold)),
                  onTap: () {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Add participant modal')),
                    );
                  },
                ),
                _buildMemberTile('Alex Rivera (You)', 'Admin', true),
                _buildMemberTile('Sarah Connor', 'Member', false),
                _buildMemberTile('Michael Scott', 'Member', false),
                _buildMemberTile('Emma Watson', 'Member', false),
              ],

              const SizedBox(height: 16),

              // ── Danger Actions ──────────────────────────────────────────────
              ListTile(
                leading: const Icon(Icons.block_rounded, color: AppTheme.dangerRed),
                title: Text(isGroup ? 'Exit Group' : 'Block Contact', style: const TextStyle(color: AppTheme.dangerRed, fontWeight: FontWeight.bold)),
                onTap: () {
                  ConfirmDialog.show(
                    context,
                    title: isGroup ? 'Exit Group?' : 'Block Contact?',
                    message: isGroup ? 'You will no longer receive messages in this group.' : 'This contact will not be able to call or message you.',
                    confirmText: isGroup ? 'Exit' : 'Block',
                    isDangerous: true,
                  );
                },
              ),

              const SizedBox(height: 32),
            ]),
          ),
        ],
      ),
    );
  }

  Widget _buildActionButton(IconData icon, String label, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        width: 72,
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkCard : Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkBorder : AppTheme.lightBorder,
            width: 0.5,
          ),
        ),
        child: Column(
          children: [
            Icon(icon, color: AppTheme.primary, size: 22),
            const SizedBox(height: 6),
            Text(
              label,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMemberTile(String name, String role, bool isMe) {
    return ListTile(
      leading: CustomAvatar(name: name, radius: 20),
      title: Text(name, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
      trailing: StatusBadge(
        text: role.toUpperCase(),
        type: role == 'Admin' ? BadgeType.primary : BadgeType.neutral,
      ),
    );
  }
}
