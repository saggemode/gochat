import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:qr_flutter/qr_flutter.dart';
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

class _QrScannerScreenState extends State<QrScannerScreen>
    with SingleTickerProviderStateMixin {
  int _selectedTab = 0; // 0: Scan QR, 1: My Code
  bool _isFlashlightOn = false;
  bool _isProcessingScan = false;
  late MobileScannerController _scannerController;
  late AnimationController _laserController;
  late Animation<double> _laserAnimation;

  @override
  void initState() {
    super.initState();
    _scannerController = MobileScannerController(
      detectionSpeed: DetectionSpeed.normal,
      facing: CameraFacing.back,
      torchEnabled: false,
    );
    _laserController = AnimationController(
      duration: const Duration(milliseconds: 1800),
      vsync: this,
    )..repeat(reverse: true);
    _laserAnimation = Tween<double>(
      begin: 0.0,
      end: 240.0,
    ).animate(_laserController);
  }

  @override
  void dispose() {
    _scannerController.dispose();
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

  Future<void> _handleScannedPin(String rawData) async {
    if (!mounted) return;
    HapticFeedback.mediumImpact();

    // Extract PIN or ID from format: "gochat:pin:8492A1" or raw string
    String cleanPin = rawData.trim();
    if (cleanPin.toLowerCase().startsWith('gochat:pin:')) {
      cleanPin = cleanPin.substring('gochat:pin:'.length).trim().toUpperCase();
    } else if (cleanPin.contains(':')) {
      cleanPin = cleanPin.split(':').last.trim().toUpperCase();
    } else {
      cleanPin = cleanPin.toUpperCase();
    }

    if (cleanPin.isEmpty) {
      _isProcessingScan = false;
      return;
    }

    final user = await widget.appState.lookupUserByPin(cleanPin);

    final title = user?.displayName ?? 'GOCHAT Contact ($cleanPin)';
    final avatar = user?.avatarUrl ?? '';

    final recipientId = user?.id ?? '';
    final memberIds = recipientId.isNotEmpty ? [recipientId] : [cleanPin];

    Conversation targetConv;
    final matchIndex = widget.appState.conversations.indexWhere(
      (c) =>
          c.title.toUpperCase().contains(cleanPin) ||
          c.id.toUpperCase().contains(cleanPin) ||
          (recipientId.isNotEmpty && c.id == recipientId) ||
          c.partnerPin?.toUpperCase() == cleanPin,
    );

    if (matchIndex != -1) {
      targetConv = widget.appState.conversations[matchIndex];
    } else {
      targetConv = await widget.appState.createConversation(
        title,
        memberIds,
        invitationStatus: InvitationStatus.pendingOutgoing,
        partnerPin: cleanPin,
      );
      if (avatar.isNotEmpty) {
        targetConv = targetConv.copyWith(avatarUrl: avatar);
      }
      // Send initial invitation request message
      await widget.appState.sendMessage(
        targetConv.id,
        '👋 Hi! I scanned your QR code on GoChat ($cleanPin).',
      );
    }

    if (!mounted) return;
    Navigator.pop(context);
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) =>
            ChatRoomScreen(conversation: targetConv, appState: widget.appState),
      ),
    );

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('🎉 Connected with $title ($cleanPin)'),
        backgroundColor: AppTheme.primary,
      ),
    );
  }

  void _showManualPinDialog() {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark
            ? AppTheme.darkSurface
            : Colors.white,
        title: const Text('Enter GOCHAT PIN'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: controller,
              autofocus: true,
              textCapitalization: TextCapitalization.characters,
              maxLength: 12,
              decoration: InputDecoration(
                hintText: 'e.g. 8492A1',
                prefixIcon: const Icon(
                  Icons.vpn_key_rounded,
                  color: AppTheme.primary,
                ),
                suffixIcon: IconButton(
                  icon: const Icon(
                    Icons.paste_rounded,
                    color: AppTheme.primary,
                    size: 20,
                  ),
                  tooltip: 'Paste from clipboard',
                  onPressed: () async {
                    final clipData = await Clipboard.getData('text/plain');
                    if (clipData?.text != null && clipData!.text!.isNotEmpty) {
                      controller.text = clipData.text!.trim().toUpperCase();
                    }
                  },
                ),
              ),
            ),
          ],
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
            child: const Text(
              'Connect',
              style: TextStyle(
                color: Colors.black,
                fontWeight: FontWeight.bold,
              ),
            ),
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
          icon: const Icon(
            Icons.arrow_back_ios_new_rounded,
            color: Colors.white,
          ),
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
                _isFlashlightOn
                    ? Icons.flash_on_rounded
                    : Icons.flash_off_rounded,
                color: _isFlashlightOn ? Colors.amber : Colors.white,
              ),
              onPressed: () async {
                await _scannerController.toggleTorch();
                setState(() => _isFlashlightOn = !_isFlashlightOn);
              },
            ),
            IconButton(
              icon: const Icon(
                Icons.flip_camera_ios_rounded,
                color: Colors.white,
              ),
              tooltip: 'Switch Camera',
              onPressed: () => _scannerController.switchCamera(),
            ),
            IconButton(
              icon: const Icon(
                Icons.keyboard_alt_outlined,
                color: Colors.white,
              ),
              tooltip: 'Enter PIN manually',
              onPressed: _showManualPinDialog,
            ),
          ],
        ],
      ),
      body: _selectedTab == 0
          ? _buildScannerView()
          : _buildMyCodeView(user, isDark),
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
    return Stack(
      children: [
        // Camera View with MobileScanner
        MobileScanner(
          controller: _scannerController,
          onDetect: (capture) {
            final barcodes = capture.barcodes;
            for (final barcode in barcodes) {
              final rawVal = barcode.rawValue;
              if (rawVal != null && rawVal.isNotEmpty && !_isProcessingScan) {
                _isProcessingScan = true;
                _handleScannedPin(rawVal);
                break;
              }
            }
          },
          errorBuilder: (context, error) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(
                      Icons.no_photography_rounded,
                      size: 64,
                      color: Colors.white54,
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      'Camera is not available on this device or permission is disabled.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white70, fontSize: 14),
                    ),
                    const SizedBox(height: 20),
                    ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppTheme.primary,
                      ),
                      icon: const Icon(
                        Icons.keyboard_alt_rounded,
                        color: Colors.black,
                      ),
                      label: const Text(
                        'Enter PIN Manually',
                        style: TextStyle(
                          color: Colors.black,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      onPressed: _showManualPinDialog,
                    ),
                  ],
                ),
              ),
            );
          },
        ),

        // Semi-transparent overlay with cutout and scanning reticle
        Column(
          children: [
            const SizedBox(height: 20),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(16),
              ),
              child: const Text(
                'Point camera at a GoChat QR code',
                style: TextStyle(color: Colors.white70, fontSize: 13),
              ),
            ),
            const Spacer(),

            // Viewfinder Box
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
                            decoration: const BoxDecoration(
                              color: AppTheme.primary,
                              boxShadow: [
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

            // Bottom Quick Actions
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: Colors.white,
                      side: const BorderSide(color: Colors.white24),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 12,
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                      ),
                    ),
                    icon: const Icon(Icons.paste_rounded, size: 18),
                    label: const Text('Paste PIN'),
                    onPressed: () async {
                      final clipData = await Clipboard.getData('text/plain');
                      if (clipData?.text != null &&
                          clipData!.text!.isNotEmpty) {
                        _handleScannedPin(clipData.text!);
                      } else if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Clipboard is empty')),
                        );
                      }
                    },
                  ),
                  ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.primary,
                      foregroundColor: Colors.black,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 12,
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                      ),
                    ),
                    icon: const Icon(Icons.keyboard_alt_rounded, size: 18),
                    label: const Text(
                      'Enter PIN',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    onPressed: _showManualPinDialog,
                  ),
                ],
              ),
            ),
          ],
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
      child: SingleChildScrollView(
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
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Colors.black,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'GOCHAT PIN: $_myPin',
                  style: const TextStyle(
                    fontSize: 14,
                    fontFamily: 'monospace',
                    fontWeight: FontWeight.w900,
                    color: AppTheme.primary,
                    letterSpacing: 2,
                  ),
                ),
                const SizedBox(height: 20),

                // Authentic QR Code Visual with QrImageView
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.black12),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: QrImageView(
                    data: 'gochat:pin:$_myPin',
                    version: QrVersions.auto,
                    size: 180,
                    backgroundColor: Colors.white,
                    eyeStyle: const QrEyeStyle(
                      eyeShape: QrEyeShape.square,
                      color: Colors.black,
                    ),
                    dataModuleStyle: const QrDataModuleStyle(
                      dataModuleShape: QrDataModuleShape.square,
                      color: Colors.black,
                    ),
                  ),
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
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
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
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        icon: const Icon(Icons.share_rounded, size: 18),
                        label: const Text(
                          'Share Card',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        onPressed: () {
                          Clipboard.setData(
                            ClipboardData(text: 'gochat:pin:$_myPin'),
                          );
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(
                                'GoChat PIN ($_myPin) link copied to share!',
                              ),
                            ),
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
      ),
    );
  }
}
