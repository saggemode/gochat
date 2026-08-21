import 'package:flutter/material.dart';

class SignInForm extends StatelessWidget {
  final TextEditingController identifierController;
  final bool loading;
  final VoidCallback onSubmit;

  const SignInForm({
    super.key,
    required this.identifierController,
    required this.loading,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'PHONE NUMBER, EMAIL, OR GOCHAT PIN',
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: Color(0xFFA1A1AA),
            letterSpacing: 0.8,
          ),
        ),
        const SizedBox(height: 8),

        TextField(
          controller: identifierController,
          style: const TextStyle(color: Colors.white, fontSize: 14.5),
          decoration: InputDecoration(
            hintText: 'e.g. +2348012345678, alex@gochat.io, or 8492A1',
            hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 13.5),
            prefixIcon: const Icon(Icons.account_circle_outlined, color: Color(0xFF71717A), size: 20),
            filled: true,
            fillColor: const Color(0xFF18181B).withValues(alpha: 0.7),
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: Color(0xFF27272A)),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: Color(0xFF10B981)),
            ),
          ),
          onSubmitted: (_) => onSubmit(),
        ),
        const SizedBox(height: 10),

        Row(
          children: [
            const Icon(Icons.shield_outlined, color: Color(0xFF10B981), size: 14),
            const SizedBox(width: 6),
            const Expanded(
              child: Text(
                'Passwordless login. A 6-digit SMS OTP code will be verified.',
                style: TextStyle(fontSize: 11, color: Color(0xFF71717A)),
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),

        SizedBox(
          width: double.infinity,
          height: 46,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF10B981),
              foregroundColor: Colors.black,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            onPressed: loading ? null : onSubmit,
            child: loading
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                  )
                : const Text(
                    'Send Verification Code',
                    style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.bold),
                  ),
          ),
        ),
      ],
    );
  }
}
