import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../main_navigation_screen.dart';
import 'country_data.dart';
import 'otp_verification_view.dart';
import 'phone_registration_form.dart';
import 'sign_in_form.dart';

class LoginScreen extends StatefulWidget {
  final AppState appState;

  const LoginScreen({super.key, required this.appState});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _isRegister = true;

  // Country & Phone State
  String _selectedCountryCode = 'NG';
  String _localPhone = '';
  bool _showCountryPicker = false;
  String _countrySearch = '';

  // OTP / Verification States
  bool _showOtp = false;
  String _otp = '';
  String? _generatedPin;
  String? _assignedUsername;
  String _otpDestination = '';

  // Controllers
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _otpController = TextEditingController();
  final TextEditingController _loginIdentifierController = TextEditingController();

  bool _loading = false;
  String? _error;
  Timer? _otpAutoFillTimer;
  Timer? _otpAutoRedirectTimer;

  Map<String, String> get _selectedCountry => CountryData.getCountry(_selectedCountryCode);

  @override
  void dispose() {
    _otpAutoFillTimer?.cancel();
    _otpAutoRedirectTimer?.cancel();
    _phoneController.dispose();
    _otpController.dispose();
    _loginIdentifierController.dispose();
    super.dispose();
  }

  // ── Register Handler (Passwordless) ─────────────────────────────────────────
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
        phone: fullPhone,
        countryCode: _selectedCountry['code'] ?? 'NG',
      );

      final user = widget.appState.currentUser;
      final assignedName = user?.displayName.isNotEmpty == true
          ? user!.displayName
          : 'User ${fullPhone.length > 4 ? fullPhone.substring(fullPhone.length - 4) : fullPhone}';
      final pin = (user?.pin.isNotEmpty == true)
          ? user!.pin
          : (user?.id.isNotEmpty == true ? user!.id.replaceAll('-', '').substring(0, 6).toUpperCase() : '8492A1');

      setState(() {
        _assignedUsername = assignedName;
        _generatedPin = pin;
        _otpDestination = fullPhone;
        _showOtp = true;
        _loading = false;
      });

      _startAutoOtpFlow();
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  // ── Sign In Handler (Passwordless OTP) ──────────────────────────────────────
  Future<void> _handleLoginSubmit() async {
    final identifier = _loginIdentifierController.text.trim();

    if (identifier.isEmpty) {
      setState(() => _error = 'Please enter your phone number, email, or PIN');
      return;
    }

    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      await widget.appState.login(identifier, '');

      final user = widget.appState.currentUser;
      final pin = (user?.pin.isNotEmpty == true)
          ? user!.pin
          : (user?.id.isNotEmpty == true ? user!.id.replaceAll('-', '').substring(0, 6).toUpperCase() : '8492A1');

      setState(() {
        _assignedUsername = user?.displayName;
        _generatedPin = pin;
        _otpDestination = user?.phone.isNotEmpty == true ? user!.phone : identifier;
        _showOtp = true;
        _loading = false;
      });

      _startAutoOtpFlow();
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  void _startAutoOtpFlow() {
    _otpAutoFillTimer?.cancel();
    _otpAutoRedirectTimer?.cancel();

    // Auto-detect and populate simulated 6-digit OTP after 700ms
    _otpAutoFillTimer = Timer(const Duration(milliseconds: 700), () {
      if (!mounted) return;
      setState(() {
        _otp = '849201';
        _otpController.text = '849201';
      });

      _otpAutoRedirectTimer = Timer(const Duration(milliseconds: 600), () {
        if (!mounted) return;
        _navigateToChat();
      });
    });
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
          // Decorative Glow Background
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
                color: const Color(0xFF7C3AED).withValues(alpha: 0.10),
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
                        _isRegister ? 'Get Started with GoChat' : 'Welcome Back to GoChat',
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
                            ? 'Instant passwordless phone sign-up with SMS OTP verification'
                            : 'Sign in with your Phone, Email, or PIN via instant SMS OTP',
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 13, color: Color(0xFFA1A1AA)),
                      ),
                      const SizedBox(height: 28),

                      // Card Container
                      Container(
                        padding: const EdgeInsets.all(24),
                        decoration: BoxDecoration(
                          color: const Color(0xFF121215).withValues(alpha: 0.8),
                          borderRadius: BorderRadius.circular(20),
                          border: Border.all(color: const Color(0xFF27272A)),
                          boxShadow: const [
                            BoxShadow(color: Colors.black54, blurRadius: 30, offset: Offset(0, 10)),
                          ],
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Error Banner
                            if (_error != null)
                              Container(
                                margin: const EdgeInsets.only(bottom: 16),
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.red.withValues(alpha: 0.15),
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(color: Colors.red.withValues(alpha: 0.3)),
                                ),
                                child: Row(
                                  children: [
                                    const Icon(Icons.error_outline_rounded, color: Colors.redAccent, size: 18),
                                    const SizedBox(width: 8),
                                    Expanded(
                                      child: Text(
                                        _error!,
                                        style: const TextStyle(color: Colors.redAccent, fontSize: 13),
                                      ),
                                    ),
                                  ],
                                ),
                              ),

                            // Forms
                            if (_showOtp)
                              OtpVerificationView(
                                isLogin: !_isRegister,
                                assignedUsername: _assignedUsername,
                                generatedPin: _generatedPin,
                                destination: _otpDestination,
                                otp: _otp,
                                otpController: _otpController,
                                onOtpChanged: (val) {
                                  setState(() => _otp = val);
                                  if (val.length == 6) {
                                    _navigateToChat();
                                  }
                                },
                                onStartMessaging: _navigateToChat,
                              )
                            else if (_isRegister)
                              PhoneRegistrationForm(
                                selectedCountry: _selectedCountry,
                                phoneController: _phoneController,
                                showCountryPicker: _showCountryPicker,
                                countrySearch: _countrySearch,
                                loading: _loading,
                                onToggleCountryPicker: () {
                                  setState(() => _showCountryPicker = !_showCountryPicker);
                                },
                                onCountrySearchChanged: (val) {
                                  setState(() => _countrySearch = val);
                                },
                                onCountrySelected: (code) {
                                  setState(() {
                                    _selectedCountryCode = code;
                                    _showCountryPicker = false;
                                    _countrySearch = '';
                                  });
                                },
                                onPhoneChanged: (val) {
                                  setState(() => _localPhone = val);
                                },
                                onSubmit: _handleRegisterSubmit,
                              )
                            else
                              SignInForm(
                                identifierController: _loginIdentifierController,
                                loading: _loading,
                                onSubmit: _handleLoginSubmit,
                              ),

                            const SizedBox(height: 18),

                            // Toggle Sign In / Sign Up Link
                            if (!_showOtp)
                              Center(
                                child: TextButton(
                                  onPressed: () {
                                    setState(() {
                                      _isRegister = !_isRegister;
                                      _error = null;
                                    });
                                  },
                                  child: RichText(
                                    text: TextSpan(
                                      style: const TextStyle(fontSize: 12.5),
                                      children: [
                                        TextSpan(
                                          text: _isRegister ? 'Already have an account? ' : 'New to GoChat? ',
                                          style: const TextStyle(color: Color(0xFF71717A)),
                                        ),
                                        TextSpan(
                                          text: _isRegister ? 'Sign In' : 'Create Account',
                                          style: const TextStyle(
                                            color: Color(0xFF34D399),
                                            fontWeight: FontWeight.bold,
                                          ),
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
