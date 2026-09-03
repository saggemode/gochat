import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/models.dart';
import '../../core/services/services.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/custom_avatar.dart';
import 'chat_room_screen.dart';
import 'new_chat_by_pin_dialog.dart';

class SelectContactScreen extends StatefulWidget {
  final AppState appState;

  const SelectContactScreen({super.key, required this.appState});

  static Future<void> open(BuildContext context, AppState appState) {
    return Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => SelectContactScreen(appState: appState),
      ),
    );
  }

  @override
  State<SelectContactScreen> createState() => _SelectContactScreenState();
}

class _SelectContactScreenState extends State<SelectContactScreen> {
  final TextEditingController _searchController = TextEditingController();
  bool _isSearching = false;
  String _searchQuery = '';
  bool _hasPermission = true;

  @override
  void initState() {
    super.initState();
    _checkPermissionAndSync();
  }

  Future<void> _checkPermissionAndSync() async {
    final granted = await ContactSyncService().hasPermission();
    if (mounted) {
      setState(() => _hasPermission = granted);
    }
    // Always attempt sync (or load cached)
    await widget.appState.syncDeviceContacts();
    final updatedGranted = await ContactSyncService().hasPermission();
    if (mounted) {
      setState(() => _hasPermission = updatedGranted);
    }
  }

  Future<void> _requestPermission() async {
    final granted = await ContactSyncService().requestPermission();
    if (mounted) {
      setState(() => _hasPermission = granted);
    }
    if (granted) {
      await widget.appState.syncDeviceContacts(force: true);
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _startChatWithContact(SyncedContact contact) async {
    HapticFeedback.lightImpact();

    // 1. Check if conversation already exists
    final cleanPin = contact.pin?.trim().toUpperCase();
    final contactId = contact.id.trim();

    Conversation? existingConv;
    for (final c in widget.appState.conversations) {
      if (c.type == ConversationType.direct) {
        final pinMatch = cleanPin != null &&
            cleanPin.isNotEmpty &&
            ((c.partnerPin != null && c.partnerPin!.toUpperCase() == cleanPin) ||
                c.title.toUpperCase().contains(cleanPin));
        final memberMatch = contactId.isNotEmpty &&
            (c.memberIds.contains(contactId) || c.id == contactId);
        final nameMatch = c.title.toLowerCase() == contact.displayName.toLowerCase() ||
            (contact.gochatName != null &&
                contact.gochatName!.isNotEmpty &&
                c.title.toLowerCase() == contact.gochatName!.toLowerCase());

        if (pinMatch || memberMatch || nameMatch) {
          existingConv = c;
          break;
        }
      }
    }

    if (existingConv != null) {
      if (!mounted) return;
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(
          builder: (_) => ChatRoomScreen(
            conversation: existingConv!,
            appState: widget.appState,
          ),
        ),
      );
      return;
    }

    // 2. Create new conversation on server
    try {
      final newConv = await widget.appState.createConversation(
        contact.displayName,
        [contact.id],
        partnerPin: contact.pin,
      );

      if (!mounted) return;
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(
          builder: (_) => ChatRoomScreen(
            conversation: newConv,
            appState: widget.appState,
          ),
        ),
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Could not start chat: $e'),
            backgroundColor: Colors.redAccent,
          ),
        );
      }
    }
  }

  void _inviteContact(SyncedContact contact) {
    HapticFeedback.selectionClick();
    final inviteText = 'Hey ${contact.displayName}! Chat with me on GoChat. It is private, fast, and secure. Download now at https://gochat.im';
    Clipboard.setData(ClipboardData(text: inviteText));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Invitation link copied for ${contact.displayName}!'),
        behavior: SnackBarBehavior.floating,
        action: SnackBarAction(
          label: 'OK',
          onPressed: () {},
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final registered = widget.appState.registeredContacts;
    final invite = widget.appState.inviteContacts;

    // Apply search filter
    final filteredRegistered = _searchQuery.isEmpty
        ? registered
        : registered.where((c) {
            final q = _searchQuery.toLowerCase();
            return c.displayName.toLowerCase().contains(q) ||
                (c.gochatName != null && c.gochatName!.toLowerCase().contains(q)) ||
                c.phone.contains(q) ||
                (c.pin != null && c.pin!.toLowerCase().contains(q));
          }).toList();

    final filteredInvite = _searchQuery.isEmpty
        ? invite
        : invite.where((c) {
            final q = _searchQuery.toLowerCase();
            return c.displayName.toLowerCase().contains(q) || c.phone.contains(q);
          }).toList();

    return Scaffold(
      backgroundColor: isDark ? AppTheme.darkBg : AppTheme.lightBg,
      appBar: AppBar(
        titleSpacing: 0,
        title: _isSearching
            ? TextField(
                controller: _searchController,
                autofocus: true,
                style: const TextStyle(fontSize: 16),
                decoration: const InputDecoration(
                  hintText: 'Search name, number or PIN...',
                  border: InputBorder.none,
                ),
                onChanged: (val) => setState(() => _searchQuery = val.trim()),
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Select contact',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                  Text(
                    '${registered.length} contacts on GoChat',
                    style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                  ),
                ],
              ),
        actions: [
          if (_isSearching)
            IconButton(
              icon: const Icon(Icons.close_rounded),
              onPressed: () {
                setState(() {
                  _isSearching = false;
                  _searchQuery = '';
                  _searchController.clear();
                });
              },
            )
          else
            IconButton(
              icon: const Icon(Icons.search_rounded),
              onPressed: () => setState(() => _isSearching = true),
            ),
          IconButton(
            icon: widget.appState.isSyncingContacts
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : const Icon(Icons.refresh_rounded),
            tooltip: 'Rescan phone contacts',
            onPressed: widget.appState.isSyncingContacts
                ? null
                : () => widget.appState.syncDeviceContacts(force: true),
          ),
          PopupMenuButton<String>(
            onSelected: (val) {
              if (val == 'pin') {
                NewChatByPinDialog.show(context, widget.appState);
              } else if (val == 'invite_all') {
                Clipboard.setData(const ClipboardData(
                  text: 'Join me on GoChat! Download: https://gochat.im',
                ));
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Invite message copied to clipboard!')),
                );
              }
            },
            itemBuilder: (ctx) => [
              const PopupMenuItem(
                value: 'pin',
                child: Row(
                  children: [
                    Icon(Icons.pin_rounded, size: 20),
                    SizedBox(width: 12),
                    Text('Add contact by PIN'),
                  ],
                ),
              ),
              const PopupMenuItem(
                value: 'invite_all',
                child: Row(
                  children: [
                    Icon(Icons.share_rounded, size: 20),
                    SizedBox(width: 12),
                    Text('Invite friends'),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => widget.appState.syncDeviceContacts(force: true),
        child: ListView(
          padding: const EdgeInsets.symmetric(vertical: 8),
          children: [
            // ── Permission Notice Banner (if permission not granted) ─────────
            if (!_hasPermission) _buildPermissionCard(isDark),

            // ── Quick Action Options ─────────────────────────────────────────
            if (!_isSearching) ...[
              _buildActionTile(
                icon: Icons.group_add_rounded,
                title: 'New group',
                color: AppTheme.primary,
                    onTap: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Group chat coming soon!')),
                  );
                },
              ),
              _buildActionTile(
                icon: Icons.tag_rounded,
                title: 'New contact by PIN',
                color: Colors.deepPurpleAccent,
                onTap: () => NewChatByPinDialog.show(context, widget.appState),
              ),
              const Divider(height: 16),
            ],

            // ── Section 1: Contacts on GoChat ────────────────────────────────
            if (filteredRegistered.isNotEmpty) ...[
              _buildSectionHeader('CONTACTS ON GOCHAT', filteredRegistered.length),
              ...filteredRegistered.map((contact) => _buildGoChatContactTile(contact, isDark)),
            ] else if (widget.appState.isSyncingContacts) ...[
              const Padding(
                padding: EdgeInsets.all(24.0),
                child: Center(
                  child: Column(
                    children: [
                      CircularProgressIndicator(strokeWidth: 2),
                      SizedBox(height: 12),
                      Text(
                        'Scanning device contacts...',
                        style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
                      ),
                    ],
                  ),
                ),
              ),
            ] else if (_hasPermission && registered.isEmpty) ...[
              Padding(
                padding: const EdgeInsets.all(24.0),
                child: Center(
                  child: Column(
                    children: [
                      Icon(Icons.people_outline_rounded, size: 48, color: AppTheme.textMuted.withValues(alpha: 0.5)),
                      const SizedBox(height: 12),
                      const Text(
                        'None of your contacts are on GoChat yet',
                        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                      ),
                      const SizedBox(height: 6),
                      const Text(
                        'Invite your friends below or add them using their GoChat PIN!',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
                      ),
                    ],
                  ),
                ),
              ),
            ],

            // ── Section 2: Invite to GoChat ──────────────────────────────────
            if (filteredInvite.isNotEmpty) ...[
              const SizedBox(height: 12),
              _buildSectionHeader('INVITE TO GOCHAT', filteredInvite.length),
              ...filteredInvite.map((contact) => _buildInviteTile(contact, isDark)),
            ],

            const SizedBox(height: 48),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionCard(bool isDark) {
    return Container(
      margin: const EdgeInsets.all(12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1E2633) : const Color(0xFFEFF6FF),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: AppTheme.primary.withValues(alpha: 0.3),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: AppTheme.primary.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.contacts_rounded, color: AppTheme.primary, size: 22),
              ),
              const SizedBox(width: 12),
              const Expanded(
                child: Text(
                  'Find friends from your contacts',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Text(
            'GoChat scans your phone contacts to discover people who registered with their phone number and show their online status and last seen.',
            style: TextStyle(fontSize: 13, color: AppTheme.textMuted, height: 1.4),
          ),
          const SizedBox(height: 12),
          ElevatedButton.icon(
            onPressed: _requestPermission,
            icon: const Icon(Icons.check_circle_outline_rounded, size: 18),
            label: const Text('Allow Contacts Access'),
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionTile({
    required IconData icon,
    required String title,
    required Color color,
    required VoidCallback onTap,
  }) {
    return ListTile(
      leading: CircleAvatar(
        radius: 20,
        backgroundColor: color.withValues(alpha: 0.15),
        child: Icon(icon, color: color, size: 22),
      ),
      title: Text(
        title,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
      ),
      onTap: onTap,
    );
  }

  Widget _buildSectionHeader(String title, int count) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 6),
      child: Row(
        children: [
          Text(
            title,
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.bold,
              letterSpacing: 0.5,
              color: AppTheme.textMuted,
            ),
          ),
          const SizedBox(width: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
            decoration: BoxDecoration(
              color: AppTheme.primary.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              '$count',
              style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: AppTheme.primary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGoChatContactTile(SyncedContact contact, bool isDark) {
    final lastSeenStr = SyncedContact.formatLastSeen(contact.lastSeen, contact.isOnline);

    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      leading: CustomAvatar(
        imageUrl: contact.avatarUrl ?? '',
        name: contact.displayName,
        radius: 22,
        isOnline: contact.isOnline,
        showOnlineBadge: true,
      ),
      title: Row(
        children: [
          Expanded(
            child: Text(
              contact.displayName,
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (contact.pin != null && contact.pin!.isNotEmpty)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.deepPurpleAccent.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                contact.pin!,
                style: const TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.bold,
                  color: Colors.deepPurpleAccent,
                  letterSpacing: 0.5,
                ),
              ),
            ),
        ],
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 2),
          Row(
            children: [
              Text(
                lastSeenStr,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: contact.isOnline ? FontWeight.bold : FontWeight.normal,
                  color: contact.isOnline ? AppTheme.onlineGreen : AppTheme.textMuted,
                ),
              ),
              if (contact.phone.isNotEmpty) ...[
                const Text(' · ', style: TextStyle(color: AppTheme.textMuted, fontSize: 12)),
                Text(
                  contact.phone,
                  style: const TextStyle(color: AppTheme.textMuted, fontSize: 11),
                ),
              ],
            ],
          ),
          if (contact.statusText != null && contact.statusText!.isNotEmpty) ...[
            const SizedBox(height: 2),
            Text(
              contact.statusText!,
              style: TextStyle(
                fontSize: 12,
                fontStyle: FontStyle.italic,
                color: isDark ? Colors.white70 : Colors.black54,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ],
      ),
      onTap: () => _startChatWithContact(contact),
    );
  }

  Widget _buildInviteTile(SyncedContact contact, bool isDark) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      leading: CircleAvatar(
        radius: 20,
        backgroundColor: isDark ? const Color(0xFF2A374A) : const Color(0xFFE2E8F0),
        child: Text(
          contact.displayName.isNotEmpty ? contact.displayName[0].toUpperCase() : '?',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isDark ? Colors.white70 : Colors.black54,
          ),
        ),
      ),
      title: Text(
        contact.displayName,
        style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 14),
      ),
      subtitle: Text(
        contact.phone,
        style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
      ),
      trailing: TextButton(
        onPressed: () => _inviteContact(contact),
        style: TextButton.styleFrom(
          foregroundColor: AppTheme.primary,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
            side: const BorderSide(color: AppTheme.primary, width: 1.2),
          ),
        ),
        child: const Text(
          'INVITE',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
        ),
      ),
    );
  }
}
