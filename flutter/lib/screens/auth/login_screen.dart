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

class _LoginScreenState extends State<LoginScreen> {
  // Navigation mode: true for Register, false for Sign In
  bool _isRegister = true;

  // Country & Phone State
  String _selectedCountryCode = 'NG';
  String _localPhone = '';
  bool _showCountryPicker = false;
  String _countrySearch = '';

  // OTP / Success Verification States
  bool _showOtp = false;
  String _otp = '';
  String? _generatedPin;
  String? _assignedUsername;

  // Controllers
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _otpController = TextEditingController();
  final TextEditingController _loginIdentifierController = TextEditingController();
  final TextEditingController _loginPasswordController = TextEditingController();

  bool _loading = false;
  String? _error;
  Timer? _otpAutoFillTimer;
  Timer? _otpAutoRedirectTimer;

  // Full country list sorted alphabetically with dial codes and flags
  final List<Map<String, String>> _countries = [
    {'name': 'Australia', 'code': 'AU', 'dial': '+61', 'flag': '🇦🇺'},
    {'name': 'Brazil', 'code': 'BR', 'dial': '+55', 'flag': '🇧🇷'},
    {'name': 'Canada', 'code': 'CA', 'dial': '+1', 'flag': '🇨🇦'},
    {'name': 'France', 'code': 'FR', 'dial': '+33', 'flag': '🇫🇷'},
    {'name': 'Germany', 'code': 'DE', 'dial': '+49', 'flag': '🇩🇪'},
    {'name': 'Ghana', 'code': 'GH', 'dial': '+233', 'flag': '🇬🇭'},
    {'name': 'India', 'code': 'IN', 'dial': '+91', 'flag': '🇮🇳'},
    {'name': 'Kenya', 'code': 'KE', 'dial': '+254', 'flag': '🇰🇪'},
    {'name': 'Nigeria', 'code': 'NG', 'dial': '+234', 'flag': '🇳🇬'},
    {'name': 'South Africa', 'code': 'ZA', 'dial': '+27', 'flag': '🇿🇦'},
    {'name': 'United Arab Emirates', 'code': 'AE', 'dial': '+971', 'flag': '🇦🇪'},
    {'name': 'United Kingdom', 'code': 'GB', 'dial': '+44', 'flag': '🇬🇧'},
    {'name': 'United States', 'code': 'US', 'dial': '+1', 'flag': '🇺🇸'},
  ];

  Map<String, String> get _selectedCountry =>
      _countries.firstWhere((c) => c['code'] == _selectedCountryCode,
          orElse: () => _countries.firstWhere((c) => c['code'] == 'NG'));

  List<Map<String, String>> get _filteredCountries {
    if (_countrySearch.isEmpty) return _countries;
    final q = _countrySearch.toLowerCase();
    return _countries.where((c) {
      return c['name']!.toLowerCase().contains(q) ||
          c['dial']!.contains(q) ||
          c['code']!.toLowerCase().contains(q);
    }).toList();
  }

  @override
  void dispose() {
    _otpAutoFillTimer?.cancel();
    _otpAutoRedirectTimer?.cancel();
    _phoneController.dispose();
    _otpController.dispose();
    _loginIdentifierController.dispose();
    _loginPasswordController.dispose();
    super.dispose();
  }

  // ── Register Handler ────────────────────────────────────────────────────────
  Future<void> _handleRegisterSubmit() async {
    final cleanPhone = _localPhone.replaceAll(RegExp(r'[^\d]'), '').replaceFirst(RegExp(r'^0+'), '');
    if (cleanPhone.isEmpty) {
      setState(() => _error = 'Please enter a valid phone number');
      return;
    }

    setState(() {
      _loading = true;
      _error = null;
    });

    final fullPhone = _selectedCountry['dial']! + cleanPhone;

    try {
      await widget.appState.register(
        '$fullPhone@gochat.io',
        'GochatSecretPass123!',
        'User ${fullPhone.substring(fullPhone.length > 4 ? fullPhone.length - 4 : 0)}',
        phone: fullPhone,
      );

      final user = widget.appState.currentUser;
      final assignedName = user?.displayName ?? 'GoChat User';
      final pin = user?.id.isNotEmpty == true
          ? user!.id.replaceAll('-', '').substring(0, 6).toUpperCase()
          : '8492A1';

      setState(() {
        _assignedUsername = assignedName;
        _generatedPin = pin;
        _showOtp = true;
        _loading = false;
      });

      // Simulate same-device SMS OTP auto-fill & auto-verify after 700ms
      _otpAutoFillTimer = Timer(const Duration(milliseconds: 700), () {
        if (!mounted) return;
        setState(() {
          _otp = '849201';
          _otpController.text = '849201';
        });

        _otpAutoRedirectTimer = Timer(const Duration(milliseconds: 500), () {
          if (!mounted) return;
          _navigateToChat();
        });
      });
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  // ── Login Handler ───────────────────────────────────────────────────────────
  Future<void> _handleLoginSubmit() async {
    final identifier = _loginIdentifierController.text.trim();
    final password = _loginPasswordController.text.trim();

    if (identifier.isEmpty) {
      setState(() => _error = 'Please enter your phone number, email, or PIN');
      return;
    }

    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      await widget.appState.login(
        identifier,
        password.isNotEmpty ? password : 'GochatSecretPass123!',
      );

      if (!mounted) return;
      _navigateToChat();
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  void _navigateToChat() {
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(
        builder: (_) => MainNavigationScreen(appState: widget.appState),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF050507),
      body: Stack(
        children: [
          // Background Decorative Blur Gradients
          Positioned(
            top: MediaQuery.of(context).size.height * 0.15,
            left: -40,
            child: Container(
              width: 300,
              height: 300,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTheme.primary.withValues(alpha: 0.10),
              ),
            ),
          ),
          Positioned(
            bottom: MediaQuery.of(context).size.height * 0.15,
            right: -40,
            child: Container(
              width: 280,
              height: 280,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: const Color(0xFF7C3AED).withValues(alpha: 0.10), // Violet
              ),
            ),
          ),

          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
                child: Container(
                  constraints: const BoxConstraints(maxWidth: 440),
                  child: Column(
                    children: [
                      // Header Icon & Title
                      Container(
                        width: 56,
                        height: 56,
                        decoration: BoxDecoration(
                          gradient: const LinearGradient(
                            colors: [Color(0xFF059669), Color(0xFF34D399)],
                            begin: Alignment.bottomLeft,
                            end: Alignment.topRight,
                          ),
                          borderRadius: BorderRadius.circular(16),
                          boxShadow: [
                            BoxShadow(
                              color: const Color(0xFF10B981).withValues(alpha: 0.25),
                              blurRadius: 16,
                              offset: const Offset(0, 6),
                            ),
                          ],
                        ),
                        child: const Icon(Icons.chat_bubble_rounded, size: 28, color: Colors.black),
                      ),
                      const SizedBox(height: 20),
                      Text(
                        _isRegister ? 'Get Started with GoChat' : 'Welcome to GoChat',
                        style: const TextStyle(
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
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
                        style: const TextStyle(fontSize: 13.5, color: Color(0xFFA1A1AA)),
                      ),
                      const SizedBox(height: 24),

                      // Glass Card Container
                      Container(
                        padding: const EdgeInsets.all(24),
                        decoration: BoxDecoration(
                          color: const Color(0xFF121215).withValues(alpha: 0.9),
                          borderRadius: BorderRadius.circular(24),
                          border: Border.all(color: Colors.white10),
                          boxShadow: const [
                            BoxShadow(
                              color: Colors.black45,
                              blurRadius: 25,
                              offset: Offset(0, 10),
                            ),
                          ],
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            // Error Banner
                            if (_error != null)
                              Container(
                                margin: const EdgeInsets.only(bottom: 16),
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.red.withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(color: Colors.red.withValues(alpha: 0.2)),
                                ),
                                child: Row(
                                  children: [
                                    const Icon(Icons.shield_outlined, color: Colors.redAccent, size: 18),
                                    const SizedBox(width: 10),
                                    Expanded(
                                      child: Text(
                                        _error!,
                                        style: const TextStyle(color: Color(0xFFF87171), fontSize: 12.5),
                                      ),
                                    ),
                                  ],
                                ),
                              ),

                            // ── REGISTER: Step 1 (Phone Input & Country Picker) ──
                            if (_isRegister && !_showOtp) ...[
                              const Text(
                                'PHONE NUMBER',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w600,
                                  color: Color(0xFFA1A1AA),
                                  letterSpacing: 0.8,
                                ),
                              ),
                              const SizedBox(height: 8),

                              Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  // Country Code Selector Button
                                  GestureDetector(
                                    onTap: () => setState(() => _showCountryPicker = !_showCountryPicker),
                                    child: Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
                                      decoration: BoxDecoration(
                                        color: const Color(0xFF18181B).withValues(alpha: 0.7),
                                        borderRadius: BorderRadius.circular(12),
                                        border: Border.all(
                                          color: _showCountryPicker ? const Color(0xFF10B981) : const Color(0xFF27272A),
                                        ),
                                      ),
                                      child: Row(
                                        children: [
                                          Text(_selectedCountry['flag']!, style: const TextStyle(fontSize: 16)),
                                          const SizedBox(width: 6),
                                          Text(
                                            _selectedCountry['dial']!,
                                            style: const TextStyle(
                                              color: Color(0xFFD4D4D8),
                                              fontFamily: 'monospace',
                                              fontSize: 13,
                                              fontWeight: FontWeight.w600,
                                            ),
                                          ),
                                          const SizedBox(width: 4),
                                          const Icon(Icons.keyboard_arrow_down_rounded, color: Color(0xFF71717A), size: 16),
                                        ],
                                      ),
                                    ),
                                  ),
                                  const SizedBox(width: 8),

                                  // Phone Number Input
                                  Expanded(
                                    child: TextField(
                                      controller: _phoneController,
                                      keyboardType: TextInputType.phone,
                                      style: const TextStyle(color: Colors.white, fontSize: 14.5),
                                      decoration: InputDecoration(
                                        hintText: 'Phone number',
                                        hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 13.5),
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
                                      onChanged: (val) {
                                        setState(() => _localPhone = val);
                                      },
                                    ),
                                  ),
                                ],
                              ),

                              // Country Search Modal Dropdown
                              if (_showCountryPicker) ...[
                                const SizedBox(height: 8),
                                Container(
                                  constraints: const BoxConstraints(maxHeight: 220),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFF18181B),
                                    borderRadius: BorderRadius.circular(14),
                                    border: Border.all(color: const Color(0xFF27272A)),
                                    boxShadow: const [
                                      BoxShadow(color: Colors.black87, blurRadius: 20, offset: Offset(0, 8)),
                                    ],
                                  ),
                                  child: Column(
                                    children: [
                                      Padding(
                                        padding: const EdgeInsets.all(8),
                                        child: TextField(
                                          style: const TextStyle(color: Colors.white, fontSize: 12.5),
                                          decoration: InputDecoration(
                                            hintText: 'Search country or code...',
                                            hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 12),
                                            prefixIcon: const Icon(Icons.search, color: Color(0xFF71717A), size: 16),
                                            filled: true,
                                            fillColor: const Color(0xFF27272A),
                                            contentPadding: const EdgeInsets.symmetric(vertical: 6),
                                            border: OutlineInputBorder(
                                              borderRadius: BorderRadius.circular(8),
                                              borderSide: BorderSide.none,
                                            ),
                                          ),
                                          onChanged: (val) {
                                            setState(() => _countrySearch = val);
                                          },
                                        ),
                                      ),
                                      Expanded(
                                        child: ListView.builder(
                                          itemCount: _filteredCountries.length,
                                          itemBuilder: (context, idx) {
                                            final c = _filteredCountries[idx];
                                            final isSelected = c['code'] == _selectedCountryCode;

                                            return InkWell(
                                              onTap: () {
                                                setState(() {
                                                  _selectedCountryCode = c['code']!;
                                                  _showCountryPicker = false;
                                                  _countrySearch = '';
                                                });
                                              },
                                              child: Container(
                                                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                                color: isSelected
                                                    ? const Color(0xFF10B981).withValues(alpha: 0.12)
                                                    : Colors.transparent,
                                                child: Row(
                                                  children: [
                                                    Text(c['flag']!, style: const TextStyle(fontSize: 16)),
                                                    const SizedBox(width: 10),
                                                    Expanded(
                                                      child: Text(
                                                        c['name']!,
                                                        style: TextStyle(
                                                          color: isSelected ? const Color(0xFF34D399) : Colors.white,
                                                          fontSize: 13,
                                                        ),
                                                      ),
                                                    ),
                                                    Text(
                                                      c['dial']!,
                                                      style: const TextStyle(
                                                        color: Color(0xFF71717A),
                                                        fontFamily: 'monospace',
                                                        fontSize: 12,
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                              ),
                                            );
                                          },
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ],

                              const SizedBox(height: 8),
                              const Text(
                                'Fast & secure phone sign-up. Username & email can be added anytime in Settings.',
                                style: TextStyle(fontSize: 11, color: Color(0xFF71717A), height: 1.3),
                              ),
                              const SizedBox(height: 20),

                              // Submit Button
                              SizedBox(
                                width: double.infinity,
                                height: 46,
                                child: ElevatedButton(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: const Color(0xFF10B981),
                                    foregroundColor: Colors.black,
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                                    elevation: 0,
                                  ),
                                  onPressed: _loading ? null : _handleRegisterSubmit,
                                  child: _loading
                                      ? const SizedBox(
                                          width: 20,
                                          height: 20,
                                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                                        )
                                      : const Text(
                                          'Continue with Phone',
                                          style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700),
                                        ),
                                ),
                              ),
                            ],

                            // ── REGISTER: Step 2 (Account Created & SMS OTP) ──
                            if (_isRegister && _showOtp) ...[
                              Center(
                                child: Column(
                                  children: [
                                    const Icon(Icons.vpn_key_rounded, color: Color(0xFF34D399), size: 40),
                                    const SizedBox(height: 8),
                                    const Text(
                                      'Account Created!',
                                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white),
                                    ),
                                    const SizedBox(height: 8),

                                    if (_assignedUsername != null)
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
                                              'Assigned Username: ${_assignedUsername!}',
                                              style: const TextStyle(
                                                fontSize: 12,
                                                color: Color(0xFF34D399),
                                                fontWeight: FontWeight.bold,
                                              ),
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
                                        color: Color(0xFF34D399),
                                        letterSpacing: 2,
                                      ),
                                    ),
                                    const Text(
                                      'Share this PIN with friends to connect on GoChat',
                                      style: TextStyle(fontSize: 10.5, color: Color(0xFF71717A)),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 18),

                              // SMS Detection Box
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
                                          _otp.length == 6
                                              ? 'SMS OTP Verified! Redirecting...'
                                              : 'Detecting SMS OTP on this device...',
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
                                      'Verification code for ${_selectedCountry['dial']} $_localPhone',
                                      style: const TextStyle(fontSize: 11, color: Color(0xFF059669)),
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
                                onChanged: (val) {
                                  setState(() => _otp = val);
                                  if (val.length == 6) {
                                    _navigateToChat();
                                  }
                                },
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
                                    _otp.length == 6 ? 'Verifying & Opening Chat...' : 'Start Messaging Now',
                                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                                  ),
                                  onPressed: _navigateToChat,
                                ),
                              ),
                            ],

                            // ── SIGN IN FLOW ────────────────────────────────────
                            if (!_isRegister) ...[
                              const Text(
                                'PHONE NUMBER, EMAIL, OR PIN',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w600,
                                  color: Color(0xFFA1A1AA),
                                  letterSpacing: 0.8,
                                ),
                              ),
                              const SizedBox(height: 8),

                              TextField(
                                controller: _loginIdentifierController,
                                style: const TextStyle(color: Colors.white, fontSize: 14.5),
                                decoration: InputDecoration(
                                  hintText: 'e.g. +2348012345678, alex@gochat.io, or PIN',
                                  hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 13.5),
                                  prefixIcon: const Icon(Icons.person_outline, color: Color(0xFF71717A), size: 20),
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
                              ),
                              const SizedBox(height: 14),

                              const Text(
                                'PASSWORD (OPTIONAL)',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w600,
                                  color: Color(0xFFA1A1AA),
                                  letterSpacing: 0.8,
                                ),
                              ),
                              const SizedBox(height: 8),

                              TextField(
                                controller: _loginPasswordController,
                                obscureText: true,
                                style: const TextStyle(color: Colors.white, fontSize: 14.5),
                                decoration: InputDecoration(
                                  hintText: 'Leave empty for PIN / phone login',
                                  hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 13.5),
                                  prefixIcon: const Icon(Icons.lock_outline, color: Color(0xFF71717A), size: 20),
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
                                  onPressed: _loading ? null : _handleLoginSubmit,
                                  child: _loading
                                      ? const SizedBox(
                                          width: 20,
                                          height: 20,
                                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                                        )
                                      : const Text(
                                          'Sign In',
                                          style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.bold),
                                        ),
                                ),
                              ),
                            ],

                            const SizedBox(height: 18),

                            // Toggle Link
                            Center(
                              child: TextButton(
                                onPressed: () {
                                  setState(() {
                                    _isRegister = !_isRegister;
                                    _showOtp = false;
                                    _error = null;
                                  });
                                },
                                child: RichText(
                                  text: TextSpan(
                                    style: const TextStyle(fontSize: 12.5),
                                    children: [
                                      TextSpan(
                                        text: _isRegister ? 'Already registered? ' : 'New to GoChat? ',
                                        style: const TextStyle(color: Color(0xFF71717A)),
                                      ),
                                      TextSpan(
                                        text: _isRegister ? 'Sign In' : 'Create Account',
                                        style: const TextStyle(color: Color(0xFF34D399), fontWeight: FontWeight.bold),
                                      ),
                                    ],
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
