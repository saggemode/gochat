import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/models/models.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import '../chat/chat_room_screen.dart';

class QrScannerScreen extends StatefulWidget {
  final AppState appState;

  const QrScannerScreen({super.key, required this.appState});

  static void open(BuildContext context, AppState appState) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => QrScannerScreen(appState: appState)),
    );
  }

  @override
  State<QrScannerScreen> createState() => _QrScannerScreenState();
}

class _QrScannerScreenState extends State<QrScannerScreen> with SingleTickerProviderStateMixin {
  int _selectedTab = 0; // 0: Scan QR, 1: My Code
  bool _isFlashlightOn = false;
  late AnimationController _laserController;
  late Animation<double> _laserAnimation;

  @override
  void initState() {
    super.initState();
    _laserController = AnimationController(
      duration: const Duration(milliseconds: 1800),
      vsync: this,
    )..repeat(reverse: true);
    _laserAnimation = Tween<double>(begin: 0.0, end: 240.0).animate(_laserController);
  }

  @override
  void dispose() {
    _laserController.dispose();
    super.dispose();
  }

  String get _myPin {
    final user = widget.appState.currentUser;
    if (user != null && user.pin.isNotEmpty) {
      return user.pin;
    }
    return '8492A1';
  }

  void _handleScannedPin(String pin) {
    HapticFeedback.mediumImpact();
    final cleanPin = pin.replaceAll('gochat:pin:', '').trim().toUpperCase();

    // Check if conversation already exists or create new
    final existing = widget.appState.conversations.firstWhere(
      (c) => c.title.toUpperCase().contains(cleanPin) || c.id.toUpperCase().contains(cleanPin),
      orElse: () => Conversation(
        id: 'conv_pin_$cleanPin',
        title: 'BBM Contact ($cleanPin)',
        avatarUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
        type: ConversationType.direct,
        isOnline: true,
      ),
    );

    Navigator.pop(context);
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChatRoomScreen(conversation: existing, appState: widget.appState),
      ),
    );

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('🎉 Connected to BBM PIN: $cleanPin'),
        backgroundColor: AppTheme.primary,
      ),
    );
  }

  void _showManualPinDialog() {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
        title: const Text('Enter BBM PIN'),
        content: TextField(
          controller: controller,
          autofocus: true,
          textCapitalization: TextCapitalization.characters,
          maxLength: 8,
          decoration: const InputDecoration(
            hintText: 'e.g. 8492A1',
            prefixIcon: Icon(Icons.vpn_key_rounded, color: AppTheme.primary),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
            onPressed: () {
              final text = controller.text.trim();
              if (text.isNotEmpty) {
                Navigator.pop(ctx);
                _handleScannedPin(text);
              }
            },
            child: const Text('Connect', style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final user = widget.appState.currentUser;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white),
          onPressed: () => Navigator.pop(context),
        ),
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildTabPill(0, 'Scan Code'),
            const SizedBox(width: 8),
            _buildTabPill(1, 'My Code'),
          ],
        ),
        centerTitle: true,
        actions: [
          if (_selectedTab == 0) ...[
            IconButton(
              icon: Icon(
                _isFlashlightOn ? Icons.flash_on_rounded : Icons.flash_off_rounded,
                color: _isFlashlightOn ? Colors.amber : Colors.white,
              ),
              onPressed: () => setState(() => _isFlashlightOn = !_isFlashlightOn),
            ),
            IconButton(
              icon: const Icon(Icons.keyboard_alt_outlined, color: Colors.white),
              tooltip: 'Enter PIN manually',
              onPressed: _showManualPinDialog,
            ),
          ],
        ],
      ),
      body: _selectedTab == 0 ? _buildScannerView() : _buildMyCodeView(user, isDark),
    );
  }

  Widget _buildTabPill(int index, String label) {
    final isSelected = _selectedTab == index;
    return GestureDetector(
      onTap: () => setState(() => _selectedTab = index),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        decoration: BoxDecoration(
          color: isSelected ? AppTheme.primary : Colors.white12,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.bold,
            color: isSelected ? Colors.black : Colors.white,
          ),
        ),
      ),
    );
  }

  Widget _buildScannerView() {
    return Column(
      children: [
        const SizedBox(height: 20),
        const Text(
          'Point camera at a GoChat BBM QR code',
          style: TextStyle(color: Colors.white70, fontSize: 13),
        ),
        const Spacer(),

        // ── Camera Viewfinder Box ─────────────────────────────────────────────
        Center(
          child: Container(
            width: 260,
            height: 260,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: AppTheme.primary, width: 2.5),
              boxShadow: [
                BoxShadow(
                  color: AppTheme.primary.withValues(alpha: 0.25),
                  blurRadius: 20,
                  spreadRadius: 2,
                ),
              ],
            ),
            child: Stack(
              children: [
                // Corner Accents
                _buildCorner(Alignment.topLeft),
                _buildCorner(Alignment.topRight),
                _buildCorner(Alignment.bottomLeft),
                _buildCorner(Alignment.bottomRight),

                // Animated Laser Line
                AnimatedBuilder(
                  animation: _laserAnimation,
                  builder: (ctx, _) {
                    return Positioned(
                      top: _laserAnimation.value,
                      left: 12,
                      right: 12,
                      child: Container(
                        height: 2.5,
                        decoration: BoxDecoration(
                          color: AppTheme.primary,
                          boxShadow: const [
                            BoxShadow(
                              color: AppTheme.primary,
                              blurRadius: 8,
                              spreadRadius: 2,
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
        ),

        const Spacer(),

        // Bottom Simulated Scan triggers
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.white,
                  side: const BorderSide(color: Colors.white24),
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                icon: const Icon(Icons.photo_library_outlined, size: 18),
                label: const Text('Scan Image'),
                onPressed: () => _handleScannedPin('92B104'),
              ),
              ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  foregroundColor: Colors.black,
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                icon: const Icon(Icons.qr_code_scanner_rounded, size: 18),
                label: const Text('Simulate Scan', style: TextStyle(fontWeight: FontWeight.bold)),
                onPressed: () => _handleScannedPin('8492A1'),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildCorner(Alignment alignment) {
    return Align(
      alignment: alignment,
      child: Container(
        width: 24,
        height: 24,
        margin: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: AppTheme.primary,
          borderRadius: BorderRadius.circular(4),
        ),
      ),
    );
  }

  Widget _buildMyCodeView(User? user, bool isDark) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(24),
            boxShadow: const [
              BoxShadow(
                color: Colors.black45,
                blurRadius: 20,
                offset: Offset(0, 10),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CustomAvatar(
                imageUrl: user?.avatarUrl,
                name: user?.displayName ?? 'Me',
                radius: 32,
              ),
              const SizedBox(height: 12),
              Text(
                user?.displayName ?? 'My GoChat Account',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.black),
              ),
              const SizedBox(height: 4),
              Text(
                'BBM PIN: $_myPin',
                style: const TextStyle(
                  fontSize: 14,
                  fontFamily: 'monospace',
                  fontWeight: FontWeight.w900,
                  color: AppTheme.primary,
                  letterSpacing: 2,
                ),
              ),
              const SizedBox(height: 20),

              // QR Code Visual
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.black12),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: const Icon(Icons.qr_code_2_rounded, size: 180, color: Colors.black),
              ),

              const SizedBox(height: 16),
              const Text(
                'Scan this code with GoChat to add me to your contacts instantly.',
                style: TextStyle(color: Colors.black54, fontSize: 12),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 20),

              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        foregroundColor: Colors.black,
                        side: const BorderSide(color: Colors.black26),
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      ),
                      icon: const Icon(Icons.copy_rounded, size: 18),
                      label: const Text('Copy PIN'),
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: _myPin));
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('PIN $_myPin copied!')),
                        );
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppTheme.primary,
                        foregroundColor: Colors.black,
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      ),
                      icon: const Icon(Icons.share_rounded, size: 18),
                      label: const Text('Share Card', style: TextStyle(fontWeight: FontWeight.bold)),
                      onPressed: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('Sharing GoChat Contact Card ($_myPin)')),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
