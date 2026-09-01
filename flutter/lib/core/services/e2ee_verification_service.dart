import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:shared_preferences/shared_preferences.dart';

class E2EEVerificationService {
  static final E2EEVerificationService _instance = E2EEVerificationService._internal();
  factory E2EEVerificationService() => _instance;
  E2EEVerificationService._internal();

  static const String _verifiedPrefix = 'e2ee_verified_';

  /// Generates a deterministic 60-digit safety number grouped into 12 blocks of 5 digits (Signal protocol standard)
  String generateSafetyNumber({
    required String myUserId,
    required String peerUserId,
    required String conversationId,
    String? myPin,
    String? peerPin,
  }) {
    // Sort IDs so both participants produce the exact same safety number
    final ids = [myUserId.trim(), peerUserId.trim()]..sort();
    final pins = [(myPin ?? '').trim(), (peerPin ?? '').trim()]..sort();
    final seed = '${ids[0]}:${pins[0]}--${ids[1]}:${pins[1]}--${conversationId.trim()}--gochat_e2ee_salt_v1';

    // Multi-round SHA-512 derivation for 60 numeric digits
    final h1 = sha512.convert(utf8.encode(seed)).bytes;
    final h2 = sha512.convert(h1).bytes;
    final combined = [...h1, ...h2];

    final buffer = StringBuffer();
    for (int i = 0; i < combined.length && buffer.length < 60; i += 2) {
      final val = ((combined[i] << 8) | combined[i + 1]) % 100000;
      buffer.write(val.toString().padLeft(5, '0'));
    }

    final raw60 = buffer.toString().substring(0, 60);

    // Format into 12 blocks of 5 digits separated by spaces
    final chunks = <String>[];
    for (int i = 0; i < 60; i += 5) {
      chunks.add(raw60.substring(i, i + 5));
    }
    return chunks.join(' ');
  }

  /// Generates the QR code payload representation for live camera scanner
  String generateQrPayload({
    required String conversationId,
    required String safetyNumber,
  }) {
    final rawNumber = safetyNumber.replaceAll(' ', '');
    final hash = sha256.convert(utf8.encode(rawNumber)).toString().substring(0, 16);
    return 'gochat-e2ee://v1/$conversationId/$hash';
  }

  /// Validates a scanned QR payload against the expected conversation and safety number
  bool validateScannedQr({
    required String scannedData,
    required String expectedConversationId,
    required String expectedSafetyNumber,
  }) {
    final expectedPayload = generateQrPayload(
      conversationId: expectedConversationId,
      safetyNumber: expectedSafetyNumber,
    );
    if (scannedData.trim() == expectedPayload.trim()) return true;

    // Also support direct match on safety number digits
    final cleanScanned = scannedData.replaceAll(RegExp(r'\s+'), '');
    final cleanExpected = expectedSafetyNumber.replaceAll(' ', '');
    return cleanScanned == cleanExpected;
  }

  /// Checks if a conversation / contact is marked as verified
  Future<bool> isVerified(String conversationId) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool('$_verifiedPrefix$conversationId') ?? false;
  }

  /// Sets the verified status for a contact
  Future<void> setVerified(String conversationId, bool verified) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('$_verifiedPrefix$conversationId', verified);
  }
}
