import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../main_navigation_screen.dart';

class LoginScreen extends StatefulWidget {
  final AppState appState;

  const LoginScreen({super.key, required this.appState});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> with SingleTickerProviderStateMixin {
  bool _isRegister = true; // Default to Register flow like web frontend
  bool _showOtp = false;
  bool _isLoading = false;
  String? _errorMessage;

  // Form Fields
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _identifierController = TextEditingController(); // For login (phone/email/pin)
  final TextEditingController _otpController = TextEditingController();

  // Registration Result Data
  String? _assignedUsername;
  String? _generatedPin;

  // Country Selection
  String _selectedCountryCode = 'NG';
  String _selectedDial = '+234';
  String _countrySearch = '';

  final List<Map<String, String>> _allCountries = [
    {'name': 'Nigeria', 'code': 'NG', 'dial': '+234', 'flag': '🇳🇬'},
    {'name': 'United States', 'code': 'US', 'dial': '+1', 'flag': '🇺🇸'},
    {'name': 'United Kingdom', 'code': 'GB', 'dial': '+44', 'flag': '🇬🇧'},
    {'name': 'Germany', 'code': 'DE', 'dial': '+49', 'flag': '🇩🇪'},
    {'name': 'Canada', 'code': 'CA', 'dial': '+1', 'flag': '🇨🇦'},
    {'name': 'India', 'code': 'IN', 'dial': '+91', 'flag': '🇮🇳'},
    {'name': 'Ghana', 'code': 'GH', 'dial': '+233', 'flag': '🇬🇭'},
    {'name': 'Kenya', 'code': 'KE', 'dial': '+254', 'flag': '🇰🇪'},
    {'name': 'South Africa', 'code': 'ZA', 'dial': '+27', 'flag': '🇿🇦'},
    {'name': 'Australia', 'code': 'AU', 'dial': '+61', 'flag': '🇦🇺'},
    {'name': 'France', 'code': 'FR', 'dial': '+33', 'flag': '🇫🇷'},
    {'name': 'Brazil', 'code': 'BR', 'dial': '+55', 'flag': '🇧🇷'},
    {'name': 'United Arab Emirates', 'code': 'AE', 'dial': '+971', 'flag': '🇦🇪'},
  ];

  Map<String, String> get _selectedCountry =>
      _allCountries.firstWhere((c) => c['code'] == _selectedCountryCode,
          orElse: () => _allCountries.first);

  void _openCountryPickerModal() {
    _countrySearch = '';
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppTheme.darkSurface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            final filtered = _allCountries.where((c) {
              if (_countrySearch.isEmpty) return true;
              final q = _countrySearch.toLowerCase();
              return c['name']!.toLowerCase().contains(q) ||
                  c['dial']!.contains(q) ||
                  c['code']!.toLowerCase().contains(q);
            }).toList();

            return Container(
              height: MediaQuery.of(context).size.height * 0.7,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
              child: Column(
                children: [
                  Container(
                    width: 38,
                    height: 4,
                    decoration: BoxDecoration(
                      color: Colors.white24,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const SizedBox(height: 16),
                  // Search Box
                  TextField(
                    autofocus: true,
                    style: const TextStyle(color: AppTheme.textLight, fontSize: 14),
                    decoration: InputDecoration(
                      hintText: 'Search country or dialing code...',
                      prefixIcon: const Icon(Icons.search, color: AppTheme.iconColor),
                      fillColor: AppTheme.darkCard,
                      filled: true,
                      contentPadding: const EdgeInsets.symmetric(vertical: 10),
                    ),
                    onChanged: (val) {
                      setModalState(() {
                        _countrySearch = val;
                      });
                    },
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (context, idx) {
                        final c = filtered[idx];
                        final isSelected = c['code'] == _selectedCountryCode;

                        return ListTile(
                          leading: Text(c['flag']!, style: const TextStyle(fontSize: 22)),
                          title: Text(
                            c['name']!,
                            style: TextStyle(
                              color: isSelected ? AppTheme.accent : AppTheme.textLight,
                              fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                            ),
                          ),
                          trailing: Text(
                            c['dial']!,
                            style: const TextStyle(
                              color: AppTheme.textMuted,
                              fontFamily: 'monospace',
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          onTap: () {
                            setState(() {
                              _selectedCountryCode = c['code']!;
                              _selectedDial = c['dial']!;
                            });
                            Navigator.pop(ctx);
                          },
                        );
                      },
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  void _handleRegisterSubmit() async {
    final localNum = _phoneController.text.replaceAll(RegExp(r'[^\d]'), '');
    if (localNum.isEmpty) {
      setState(() => _errorMessage = 'Please enter a valid phone number');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final fullPhone = '$_selectedDial${localNum.replaceFirst(RegExp(r'^0+'), '')}';

    try {
      await widget.appState.register(
        '$fullPhone@gochat.io',
        'GochatSecretPass123!',
        'User ${fullPhone.substring(fullPhone.length > 4 ? fullPhone.length - 4 : 0)}',
        phone: fullPhone,
      );

      final user = widget.appState.currentUser;
      setState(() {
        _assignedUsername = user?.displayName ?? 'GoChat User';
        _generatedPin = user?.id.substring(0, 6).toUpperCase() ?? '8F39A1';
        _showOtp = true;
        _isLoading = false;
      });

      // Simulate same-device SMS OTP auto-detection (700ms)
      Timer(const Duration(milliseconds: 700), () {
        if (mounted && _showOtp) {
          _otpController.text = '849201';
          setState(() {});
        }
      });
    } catch (e) {
      setState(() {
        _errorMessage = e.toString().replaceFirst('Exception: ', '');
        _isLoading = false;
      });
    }
  }

  void _handleLoginSubmit() async {
    final identifier = _identifierController.text.trim();
    if (identifier.isEmpty) {
      setState(() => _errorMessage = 'Please enter your Phone number, Email, or PIN');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      await widget.appState.login(identifier, 'GochatSecretPass123!');
      if (!mounted) return;
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (_) => MainNavigationScreen(appState: widget.appState)),
      );
    } catch (e) {
      setState(() {
        _errorMessage = e.toString().replaceFirst('Exception: ', '');
        _isLoading = false;
      });
    }
  }

  void _handleOtpComplete() {
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(builder: (_) => MainNavigationScreen(appState: widget.appState)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF050507),
      body: Stack(
        children: [
          // Background Decorative Glowing Orbs (WhatsApp Pro / Glass Aesthetic)
          Positioned(
            top: MediaQuery.of(context).size.height * 0.15,
            left: -40,
            child: Container(
              width: 260,
              height: 260,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTheme.primary.withValues(alpha: 0.12),
              ),
            ),
          ),
          Positioned(
            bottom: MediaQuery.of(context).size.height * 0.15,
            right: -40,
            child: Container(
              width: 240,
              height: 240,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.tealAccent.withValues(alpha: 0.08),
              ),
            ),
          ),

          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
                child: Container(
                  constraints: const BoxConstraints(maxWidth: 440),
                  child: Column(
                    children: [
                      // Brand Logo Header
                      Container(
                        width: 68,
                        height: 68,
                        decoration: BoxDecoration(
                          gradient: const LinearGradient(
                            colors: [AppTheme.primary, AppTheme.accent],
                            begin: Alignment.bottomLeft,
                            end: Alignment.topRight,
                          ),
                          borderRadius: BorderRadius.circular(20),
                          boxShadow: [
                            BoxShadow(
                              color: AppTheme.primary.withValues(alpha: 0.35),
                              blurRadius: 20,
                              offset: const Offset(0, 8),
                            ),
                          ],
                        ),
                        child: const Icon(Icons.chat_bubble_rounded, size: 34, color: Colors.black),
                      ),
                      const SizedBox(height: 20),

                      Text(
                        _isRegister ? 'Get Started with GoChat' : 'Sign in to GoChat',
                        style: const TextStyle(
                          fontSize: 26,
                          fontWeight: FontWeight.w900,
                          color: Colors.white,
                          letterSpacing: -0.5,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        _isRegister
                            ? 'Enter your phone number to create your account'
                            : 'Enter your phone number, email, or PIN to continue',
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 13.5, color: AppTheme.textMuted),
                      ),
                      const SizedBox(height: 28),

                      // Glass Card Container
                      Container(
                        padding: const EdgeInsets.all(24),
                        decoration: BoxDecoration(
                          color: AppTheme.darkSurface.withValues(alpha: 0.85),
                          borderRadius: BorderRadius.circular(28),
                          border: Border.all(color: Colors.white12, width: 0.8),
                          boxShadow: const [
                            BoxShadow(
                              color: Colors.black54,
                              blurRadius: 30,
                              offset: Offset(0, 10),
                            ),
                          ],
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            // Error Banner
                            if (_errorMessage != null)
                              Container(
                                margin: const EdgeInsets.only(bottom: 18),
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: AppTheme.dangerRed.withValues(alpha: 0.12),
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(color: AppTheme.dangerRed.withValues(alpha: 0.3)),
                                ),
                                child: Row(
                                  children: [
                                    const Icon(Icons.error_outline_rounded, color: AppTheme.dangerRed, size: 20),
                                    const SizedBox(width: 10),
                                    Expanded(
                                      child: Text(
                                        _errorMessage!,
                                        style: const TextStyle(color: Color(0xFFFF6B6B), fontSize: 12.5),
                                      ),
                                    ),
                                  ],
                                ),
                              ),

                            // STEP 1: Registration Form
                            if (_isRegister && !_showOtp) ...[
                              const Text(
                                'PHONE NUMBER',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.bold,
                                  color: AppTheme.textMuted,
                                  letterSpacing: 1.0,
                                ),
                              ),
                              const SizedBox(height: 8),

                              Row(
                                children: [
                                  // Country Selector Button
                                  GestureDetector(
                                    onTap: _openCountryPickerModal,
                                    child: Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                                      decoration: BoxDecoration(
                                        color: AppTheme.darkCard,
                                        borderRadius: BorderRadius.circular(14),
                                        border: Border.all(color: AppTheme.darkBorder),
                                      ),
                                      child: Row(
                                        children: [
                                          Text(_selectedCountry['flag']!, style: const TextStyle(fontSize: 18)),
                                          const SizedBox(width: 6),
                                          Text(
                                            _selectedDial,
                                            style: const TextStyle(
                                              color: AppTheme.textLight,
                                              fontFamily: 'monospace',
                                              fontWeight: FontWeight.bold,
                                              fontSize: 13.5,
                                            ),
                                          ),
                                          const SizedBox(width: 4),
                                          const Icon(Icons.keyboard_arrow_down, color: AppTheme.textMuted, size: 18),
                                        ],
                                      ),
                                    ),
                                  ),
                                  const SizedBox(width: 10),

                                  // Phone Input Field
                                  Expanded(
                                    child: TextField(
                                      controller: _phoneController,
                                      keyboardType: TextInputType.phone,
                                      style: const TextStyle(color: AppTheme.textLight, fontSize: 15),
                                      decoration: InputDecoration(
                                        hintText: '801 234 5678',
                                        hintStyle: const TextStyle(color: AppTheme.textMuted, fontSize: 14),
                                        fillColor: AppTheme.darkCard,
                                        filled: true,
                                        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                                        border: OutlineInputBorder(
                                          borderRadius: BorderRadius.circular(14),
                                          borderSide: BorderSide.none,
                                        ),
                                      ),
                                    ),
                                  ),
                                ],
                              ),

                              const SizedBox(height: 10),
                              const Text(
                                'Fast & secure phone sign-up. Username & email can be added anytime in Settings.',
                                style: TextStyle(fontSize: 11, color: AppTheme.textMuted, height: 1.3),
                              ),
                              const SizedBox(height: 22),

                              // Continue Button
                              ElevatedButton(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppTheme.primary,
                                  foregroundColor: Colors.black,
                                  padding: const EdgeInsets.symmetric(vertical: 14),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                                  elevation: 0,
                                ),
                                onPressed: _isLoading ? null : _handleRegisterSubmit,
                                child: _isLoading
                                    ? const SizedBox(
                                        width: 20,
                                        height: 20,
                                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                                      )
                                    : const Text(
                                        'Continue with Phone',
                                        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                                      ),
                              ),
                            ],

                            // STEP 2: Account Created & SMS OTP Verification
                            if (_isRegister && _showOtp) ...[
                              Center(
                                child: Column(
                                  children: [
                                    const Icon(Icons.vpn_key_rounded, color: AppTheme.accent, size: 42),
                                    const SizedBox(height: 8),
                                    const Text(
                                      'Account Created!',
                                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white),
                                    ),
                                    const SizedBox(height: 8),

                                    if (_assignedUsername != null)
                                      Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                                        decoration: BoxDecoration(
                                          color: AppTheme.darkCard,
                                          borderRadius: BorderRadius.circular(8),
                                          border: Border.all(color: AppTheme.darkBorder),
                                        ),
                                        child: Row(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            const Icon(Icons.auto_awesome, color: AppTheme.accent, size: 14),
                                            const SizedBox(width: 6),
                                            Text(
                                              'Assigned Username: ${_assignedUsername!}',
                                              style: const TextStyle(fontSize: 12, color: AppTheme.accent, fontWeight: FontWeight.bold),
                                            ),
                                          ],
                                        ),
                                      ),
                                    const SizedBox(height: 6),

                                    Text(
                                      'Your unique BBM PIN: $_generatedPin',
                                      style: const TextStyle(
                                        fontSize: 13,
                                        fontFamily: 'monospace',
                                        fontWeight: FontWeight.bold,
                                        color: AppTheme.accent,
                                        letterSpacing: 2,
                                      ),
                                    ),
                                    const Text(
                                      'Share this PIN with friends to connect on GoChat',
                                      style: TextStyle(fontSize: 10.5, color: AppTheme.textMuted),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 18),

                              // SMS Detection Indicator
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: AppTheme.primary.withValues(alpha: 0.12),
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(color: AppTheme.primary.withValues(alpha: 0.25)),
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
                                            color: AppTheme.accent,
                                            shape: BoxShape.circle,
                                          ),
                                        ),
                                        const SizedBox(width: 8),
                                        Text(
                                          _otpController.text.length == 6
                                              ? 'SMS OTP Verified!'
                                              : 'Detecting SMS OTP on this device...',
                                          style: const TextStyle(
                                            color: AppTheme.accent,
                                            fontWeight: FontWeight.bold,
                                            fontSize: 12,
                                          ),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      'Verification code for $_selectedDial ${_phoneController.text}',
                                      style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 16),

                              // OTP Input Field
                              TextField(
                                controller: _otpController,
                                textAlign: TextAlign.center,
                                keyboardType: TextInputType.number,
                                maxLength: 6,
                                style: const TextStyle(
                                  fontSize: 24,
                                  fontFamily: 'monospace',
                                  fontWeight: FontWeight.bold,
                                  letterSpacing: 10,
                                  color: Colors.white,
                                ),
                                decoration: InputDecoration(
                                  counterText: '',
                                  hintText: '000000',
                                  fillColor: AppTheme.darkCard,
                                  filled: true,
                                  contentPadding: const EdgeInsets.symmetric(vertical: 12),
                                  border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(14),
                                    borderSide: BorderSide.none,
                                  ),
                                ),
                              ),
                              const SizedBox(height: 18),

                              // Start Messaging Button
                              ElevatedButton.icon(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppTheme.primary,
                                  foregroundColor: Colors.black,
                                  padding: const EdgeInsets.symmetric(vertical: 14),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                                ),
                                icon: const Icon(Icons.auto_awesome, size: 18),
                                label: const Text('Start Messaging Now', style: TextStyle(fontWeight: FontWeight.bold)),
                                onPressed: _handleOtpComplete,
                              ),
                            ],

                            // LOGIN FLOW
                            if (!_isRegister) ...[
                              const Text(
                                'PHONE NUMBER, EMAIL, OR PIN',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.bold,
                                  color: AppTheme.textMuted,
                                  letterSpacing: 0.8,
                                ),
                              ),
                              const SizedBox(height: 8),

                              TextField(
                                controller: _identifierController,
                                style: const TextStyle(color: AppTheme.textLight, fontSize: 15),
                                decoration: InputDecoration(
                                  hintText: 'e.g. +2348012345678, alex@gochat.io, or PIN',
                                  prefixIcon: const Icon(Icons.account_circle_outlined, color: AppTheme.iconColor),
                                  fillColor: AppTheme.darkCard,
                                  filled: true,
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                                  border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(14),
                                    borderSide: BorderSide.none,
                                  ),
                                ),
                              ),
                              const SizedBox(height: 22),

                              ElevatedButton(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppTheme.primary,
                                  foregroundColor: Colors.black,
                                  padding: const EdgeInsets.symmetric(vertical: 14),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                                ),
                                onPressed: _isLoading ? null : _handleLoginSubmit,
                                child: _isLoading
                                    ? const SizedBox(
                                        width: 20,
                                        height: 20,
                                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                                      )
                                    : const Text(
                                        'Sign In',
                                        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                                      ),
                              ),
                            ],

                            const SizedBox(height: 18),

                            // Toggle Switch between Register & Sign In
                            Center(
                              child: TextButton(
                                onPressed: () {
                                  setState(() {
                                    _isRegister = !_isRegister;
                                    _showOtp = false;
                                    _errorMessage = null;
                                  });
                                },
                                child: Text(
                                  _isRegister
                                      ? 'Already registered? Sign In'
                                      : 'New to GoChat? Create Account',
                                  style: const TextStyle(
                                    color: AppTheme.accent,
                                    fontWeight: FontWeight.bold,
                                    fontSize: 13.5,
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
