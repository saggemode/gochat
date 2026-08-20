import 'package:flutter/material.dart';

class OtpVerificationView extends StatelessWidget {
  final bool isLogin;
  final String? assignedUsername;
  final String? generatedPin;
  final String destination;
  final String otp;
  final TextEditingController otpController;
  final ValueChanged<String> onOtpChanged;
  final VoidCallback onStartMessaging;

  const OtpVerificationView({
    super.key,
    this.isLogin = false,
    this.assignedUsername,
    this.generatedPin,
    required this.destination,
    required this.otp,
    required this.otpController,
    required this.onOtpChanged,
    required this.onStartMessaging,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Center(
          child: Column(
            children: [
              Icon(
                isLogin ? Icons.mark_email_read_rounded : Icons.vpn_key_rounded,
                color: const Color(0xFF34D399),
                size: 40,
              ),
              const SizedBox(height: 8),
              Text(
                isLogin ? 'Verify Your Identity' : 'Account Created!',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white),
              ),
              const SizedBox(height: 8),
              if (!isLogin && assignedUsername != null)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                  decoration: BoxDecoration(
                    color: const Color(0xFF18181B),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: const Color(0xFF27272A)),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.auto_awesome, color: Color(0xFF34D399), size: 14),
                      const SizedBox(width: 6),
                      Text(
                        'Assigned Username: $assignedUsername',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFF34D399),
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
              if (generatedPin != null) ...[
                const SizedBox(height: 6),
                Text(
                  'Your unique BBM PIN: $generatedPin',
                  style: const TextStyle(
                    fontSize: 13,
                    fontFamily: 'monospace',
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF34D399),
                    letterSpacing: 2,
                  ),
                ),
                const Text(
                  'Share this PIN with friends to connect on GoChat',
                  style: TextStyle(fontSize: 10.5, color: Color(0xFF71717A)),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 18),

        // SMS / OTP Detection Box
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFF064E3B).withValues(alpha: 0.3),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFF10B981).withValues(alpha: 0.2)),
          ),
          child: Column(
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(
                      color: Color(0xFF34D399),
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    otp.length == 6
                        ? 'SMS OTP Verified! Redirecting...'
                        : 'Detecting SMS OTP code...',
                    style: const TextStyle(
                      color: Color(0xFF6EE7B7),
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                'Verification code sent to $destination',
                style: const TextStyle(fontSize: 11, color: Color(0xFF059669)),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // OTP Input Field
        TextField(
          controller: otpController,
          textAlign: TextAlign.center,
          keyboardType: TextInputType.number,
          maxLength: 6,
          style: const TextStyle(
            fontSize: 22,
            fontFamily: 'monospace',
            fontWeight: FontWeight.bold,
            letterSpacing: 12,
            color: Colors.white,
          ),
          decoration: InputDecoration(
            counterText: '',
            hintText: '000000',
            hintStyle: const TextStyle(color: Color(0xFF52525B)),
            filled: true,
            fillColor: const Color(0xFF18181B).withValues(alpha: 0.7),
            contentPadding: const EdgeInsets.symmetric(vertical: 12),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: Color(0xFF27272A)),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: Color(0xFF10B981)),
            ),
          ),
          onChanged: onOtpChanged,
        ),
        const SizedBox(height: 18),

        // Start Messaging Button
        SizedBox(
          width: double.infinity,
          height: 46,
          child: ElevatedButton.icon(
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF10B981),
              foregroundColor: Colors.black,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            icon: const Icon(Icons.auto_awesome, size: 16),
            label: Text(
              otp.length == 6 ? 'Verifying & Opening Chat...' : 'Start Messaging Now',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
            ),
            onPressed: onStartMessaging,
          ),
        ),
      ],
    );
  }
}
