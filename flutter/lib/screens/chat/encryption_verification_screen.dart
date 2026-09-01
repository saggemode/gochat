import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../../core/models/models.dart';
import '../../core/services/e2ee_verification_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';

class EncryptionVerificationScreen extends StatefulWidget {
  final Conversation conversation;
  final AppState appState;

  const EncryptionVerificationScreen({
    super.key,
    required this.conversation,
    required this.appState,
  });

  static void open(
    BuildContext context, {
    required Conversation conversation,
    required AppState appState,
  }) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => EncryptionVerificationScreen(
          conversation: conversation,
          appState: appState,
        ),
      ),
    );
  }

  @override
  State<EncryptionVerificationScreen> createState() => _EncryptionVerificationScreenState();
}

class _EncryptionVerificationScreenState extends State<EncryptionVerificationScreen> {
  final _service = E2EEVerificationService();
  bool _isVerified = false;
  bool _isScanning = false;
  late String _safetyNumber;
  late String _qrPayload;

  @override
  void initState() {
    super.initState();
    final myUser = widget.appState.currentUser;
    final myId = myUser?.id ?? 'u_me';
    final peerId = widget.conversation.memberIds.firstWhere(
      (id) => id.isNotEmpty && id != myId,
      orElse: () => widget.conversation.id,
    );

    _safetyNumber = _service.generateSafetyNumber(
      myUserId: myId,
      peerUserId: peerId,
      conversationId: widget.conversation.id,
      myPin: myUser?.pin,
      peerPin: widget.conversation.title,
    );

    _qrPayload = _service.generateQrPayload(
      conversationId: widget.conversation.id,
      safetyNumber: _safetyNumber,
    );

    _service.isVerified(widget.conversation.id).then((val) {
      if (mounted) setState(() => _isVerified = val);
    });
  }

  void _toggleVerification() async {
    final next = !_isVerified;
    await _service.setVerified(widget.conversation.id, next);
    HapticFeedback.lightImpact();
    setState(() => _isVerified = next);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(next ? '✅ Security code marked as verified' : 'Security code unverified'),
          backgroundColor: next ? AppTheme.primary : Colors.grey.shade800,
        ),
      );
    }
  }

  void _openCameraScanner() {
    setState(() => _isScanning = true);
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    if (_isScanning) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Scan QR Code'),
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: () => setState(() => _isScanning = false),
          ),
        ),
        body: Stack(
          children: [
            MobileScanner(
              onDetect: (capture) {
                final barcodes = capture.barcodes;
                for (final barcode in barcodes) {
                  final rawValue = barcode.rawValue;
                  if (rawValue != null && rawValue.isNotEmpty) {
                    final matched = _service.validateScannedQr(
                      scannedData: rawValue,
                      expectedConversationId: widget.conversation.id,
                      expectedSafetyNumber: _safetyNumber,
                    );

                    setState(() => _isScanning = false);

                    if (matched) {
                      _service.setVerified(widget.conversation.id, true);
                      setState(() => _isVerified = true);
                      HapticFeedback.heavyImpact();
                      _showMatchSuccessDialog();
                    } else {
                      HapticFeedback.vibrate();
                      _showMismatchDialog();
                    }
                    break;
                  }
                }
              },
            ),
            Center(
              child: Container(
                width: 260,
                height: 260,
                decoration: BoxDecoration(
                  border: Border.all(color: AppTheme.primary, width: 3),
                  borderRadius: BorderRadius.circular(20),
                ),
              ),
            ),
            Positioned(
              bottom: 40,
              left: 20,
              right: 20,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.7),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Text(
                  'Point camera at the QR code on your friend\'s screen to verify their security code.',
                  style: TextStyle(color: Colors.white, fontSize: 13),
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          ],
        ),
      );
    }

    // Format blocks into 4 rows of 3 blocks each
    final blocks = _safetyNumber.split(' ');

    return Scaffold(
      appBar: AppBar(
        title: const Text('Verify Security Code'),
      ),
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        children: [
          // 1. Status Banner
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: _isVerified
                  ? AppTheme.primary.withValues(alpha: 0.15)
                  : (isDark ? AppTheme.darkCard : Colors.grey.shade100),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: _isVerified ? AppTheme.primary : AppTheme.darkBorder,
                width: 1,
              ),
            ),
            child: Row(
              children: [
                Icon(
                  _isVerified ? Icons.verified_user_rounded : Icons.lock_outline_rounded,
                  color: _isVerified ? AppTheme.primary : AppTheme.textMuted,
                  size: 26,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _isVerified ? 'Verified End-to-End Encryption' : 'End-to-End Encrypted',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 14.5,
                          color: _isVerified ? AppTheme.primary : (isDark ? Colors.white : Colors.black87),
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        _isVerified
                            ? 'Security code matches. All messages, calls, and files are cryptographically authentic.'
                            : 'To verify that messages and calls with ${widget.conversation.title} are end-to-end encrypted, scan this QR code or compare the 60-digit number.',
                        style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),

          // 2. Interactive QR Code Box
          Center(
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(20),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.15),
                    blurRadius: 15,
                    offset: const Offset(0, 5),
                  ),
                ],
              ),
              child: QrImageView(
                data: _qrPayload,
                version: QrVersions.auto,
                size: 200.0,
                backgroundColor: Colors.white,
                eyeStyle: const QrEyeStyle(
                  eyeShape: QrEyeShape.square,
                  color: Color(0xFF075E54),
                ),
                dataModuleStyle: const QrDataModuleStyle(
                  dataModuleShape: QrDataModuleShape.square,
                  color: Colors.black,
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),

          // 3. Scan QR Code Action Button
          Center(
            child: OutlinedButton.icon(
              icon: const Icon(Icons.qr_code_scanner_rounded, color: AppTheme.primary),
              label: const Text('Scan QR Code', style: TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold)),
              style: OutlinedButton.styleFrom(
                side: const BorderSide(color: AppTheme.primary, width: 1.2),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
              ),
              onPressed: _openCameraScanner,
            ),
          ),
          const SizedBox(height: 24),

          // 4. 60-digit safety numbers grid (4 rows of 3 blocks)
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: isDark ? AppTheme.darkCard : Colors.grey.shade100,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: isDark ? AppTheme.darkBorder : Colors.grey.shade300),
            ),
            child: Column(
              children: [
                for (int row = 0; row < 4; row++) ...[
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      for (int col = 0; col < 3; col++) ...[
                        Text(
                          blocks[row * 3 + col],
                          style: TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 14.5,
                            fontWeight: FontWeight.bold,
                            letterSpacing: 1.2,
                            color: isDark ? Colors.white : Colors.black87,
                          ),
                        ),
                      ],
                    ],
                  ),
                  if (row < 3) const SizedBox(height: 10),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 5. Copy Code & Mark as Verified Actions
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.copy_rounded, size: 18),
                  label: const Text('Copy Code'),
                  style: OutlinedButton.styleFrom(
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: _safetyNumber));
                    HapticFeedback.lightImpact();
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Copied 60-digit security code to clipboard')),
                    );
                  },
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: ElevatedButton.icon(
                  icon: Icon(_isVerified ? Icons.check_circle_rounded : Icons.verified_outlined, size: 18),
                  label: Text(_isVerified ? 'Verified' : 'Mark Verified'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _isVerified ? AppTheme.primary : Colors.grey.shade700,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                  onPressed: _toggleVerification,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  void _showMatchSuccessDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
        title: const Row(
          children: [
            Icon(Icons.verified_rounded, color: AppTheme.primary, size: 28),
            SizedBox(width: 10),
            Text('Safety Code Verified!'),
          ],
        ),
        content: Text(
          'The security code for ${widget.conversation.title} matched successfully. End-to-End Encryption authenticity is confirmed.',
        ),
        actions: [
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Done', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }

  void _showMismatchDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Theme.of(context).brightness == Brightness.dark ? AppTheme.darkSurface : Colors.white,
        title: const Row(
          children: [
            Icon(Icons.error_outline_rounded, color: Colors.redAccent, size: 28),
            SizedBox(width: 10),
            Text('Code Mismatch'),
          ],
        ),
        content: const Text(
          'The scanned QR code does not match this conversation\'s security code. Please check that you are scanning the matching contact.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }
}
