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
  bool _isRegister = false;
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _emailController = TextEditingController();
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _passwordController = TextEditingController();
  bool _isLoading = false;

  String _countryDial = '+1';
  final List<Map<String, String>> _countries = [
    {'name': 'United States', 'code': 'US', 'dial': '+1'},
    {'name': 'United Kingdom', 'code': 'GB', 'dial': '+44'},
    {'name': 'Nigeria', 'code': 'NG', 'dial': '+234'},
    {'name': 'Germany', 'code': 'DE', 'dial': '+49'},
    {'name': 'Canada', 'code': 'CA', 'dial': '+1'},
    {'name': 'India', 'code': 'IN', 'dial': '+91'},
    {'name': 'Ghana', 'code': 'GH', 'dial': '+233'},
    {'name': 'Kenya', 'code': 'KE', 'dial': '+254'},
    {'name': 'South Africa', 'code': 'ZA', 'dial': '+27'},
    {'name': 'Australia', 'code': 'AU', 'dial': '+61'},
  ];

  void _handleSubmit() async {
    final identifier = _emailController.text.trim();
    final password = _passwordController.text.trim();
    final name = _nameController.text.trim();
    final rawPhone = _phoneController.text.trim();

    if (_isRegister) {
      if (rawPhone.isEmpty && identifier.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please provide your phone number or email')),
        );
        return;
      }
      if (password.isNotEmpty && password.length < 8) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Password must be at least 8 characters')),
        );
        return;
      }

      setState(() => _isLoading = true);

      final fullPhone = rawPhone.isNotEmpty
          ? (_countryDial + rawPhone.replaceAll(RegExp(r'^[0+]+'), ''))
          : identifier;

      try {
        await widget.appState.register(
          identifier.isNotEmpty ? identifier : '$fullPhone@gochat.io',
          password.isNotEmpty ? password : 'GochatSecretPass123!',
          name.isNotEmpty ? name : 'User ${fullPhone.substring(fullPhone.length > 4 ? fullPhone.length - 4 : 0)}',
          phone: fullPhone,
        );

        if (!mounted) return;
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(
            builder: (_) => MainNavigationScreen(appState: widget.appState),
          ),
        );
      } catch (e) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.toString().replaceFirst('Exception: ', '')),
            backgroundColor: AppTheme.dangerRed,
          ),
        );
      } finally {
        if (mounted) setState(() => _isLoading = false);
      }
    } else {
      if (identifier.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please enter your Phone number or Email')),
        );
        return;
      }

      setState(() => _isLoading = true);

      try {
        await widget.appState.login(
          identifier,
          password.isNotEmpty ? password : 'GochatSecretPass123!',
        );

        if (!mounted) return;
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(
            builder: (_) => MainNavigationScreen(appState: widget.appState),
          ),
        );
      } catch (e) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.toString().replaceFirst('Exception: ', '')),
            backgroundColor: AppTheme.dangerRed,
          ),
        );
      } finally {
        if (mounted) setState(() => _isLoading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.darkBg,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                // Logo Icon
                Container(
                  width: 76,
                  height: 76,
                  decoration: BoxDecoration(
                    color: AppTheme.primary.withValues(alpha: 0.15),
                    shape: BoxShape.circle,
                    border: Border.all(color: AppTheme.primary.withValues(alpha: 0.3), width: 1.5),
                  ),
                  child: const Icon(Icons.chat_bubble_rounded, size: 38, color: AppTheme.primary),
                ),
                const SizedBox(height: 20),
                Text(
                  _isRegister ? 'Register with GoChat' : 'Sign in to GoChat',
                  style: const TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: AppTheme.textLight,
                    letterSpacing: -0.5,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _isRegister
                      ? 'Create your account to start messaging and calling'
                      : 'Enter your phone number or email to sync your account',
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13.5, color: AppTheme.textMuted),
                ),
                const SizedBox(height: 32),

                // Country Selector (When registering with phone)
                if (_isRegister) ...[
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                    decoration: BoxDecoration(
                      color: AppTheme.darkCard,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: AppTheme.darkBorder),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _countryDial,
                        isExpanded: true,
                        dropdownColor: AppTheme.darkSurface,
                        items: _countries.map((c) {
                          return DropdownMenuItem<String>(
                            value: c['dial']!,
                            child: Text(
                              '${c['name']} (${c['dial']})',
                              style: const TextStyle(color: AppTheme.textLight, fontSize: 14),
                            ),
                          );
                        }).toList(),
                        onChanged: (val) {
                          if (val != null) {
                            setState(() => _countryDial = val);
                          }
                        },
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),

                  // Display Name
                  TextField(
                    controller: _nameController,
                    style: const TextStyle(color: AppTheme.textLight),
                    decoration: const InputDecoration(
                      labelText: 'Full Name / Display Name',
                      hintText: 'e.g. Alexandre Sterling',
                      prefixIcon: Icon(Icons.person_outline, color: AppTheme.iconColor),
                    ),
                  ),
                  const SizedBox(height: 14),

                  // Phone Number
                  TextField(
                    controller: _phoneController,
                    keyboardType: TextInputType.phone,
                    style: const TextStyle(color: AppTheme.textLight),
                    decoration: InputDecoration(
                      labelText: 'Phone Number',
                      hintText: '801 234 5678',
                      prefixText: '$_countryDial ',
                      prefixStyle: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold),
                      prefixIcon: const Icon(Icons.phone_outlined, color: AppTheme.iconColor),
                    ),
                  ),
                  const SizedBox(height: 14),
                ],

                // Email / Phone Identifier for Login or optional for Register
                TextField(
                  controller: _emailController,
                  keyboardType: TextInputType.emailAddress,
                  style: const TextStyle(color: AppTheme.textLight),
                  decoration: InputDecoration(
                    labelText: _isRegister ? 'Email Address (Optional)' : 'Phone Number or Email',
                    hintText: _isRegister ? 'alex@example.com' : 'Enter email or phone number',
                    prefixIcon: const Icon(Icons.mail_outline, color: AppTheme.iconColor),
                  ),
                ),
                const SizedBox(height: 14),

                // Password
                TextField(
                  controller: _passwordController,
                  obscureText: true,
                  style: const TextStyle(color: AppTheme.textLight),
                  decoration: const InputDecoration(
                    labelText: 'Password',
                    hintText: 'Minimum 8 characters',
                    prefixIcon: Icon(Icons.lock_outline, color: AppTheme.iconColor),
                  ),
                ),
                const SizedBox(height: 28),

                // Action Button
                SizedBox(
                  width: double.infinity,
                  height: 50,
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.primary,
                      foregroundColor: Colors.white,
                      elevation: 2,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(25)),
                    ),
                    onPressed: _isLoading ? null : _handleSubmit,
                    child: _isLoading
                        ? const SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(strokeWidth: 2.2, color: Colors.white),
                          )
                        : Text(
                            _isRegister ? 'Create Account' : 'Log In',
                            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                          ),
                  ),
                ),
                const SizedBox(height: 20),

                // Toggle Login / Register
                TextButton(
                  onPressed: () {
                    setState(() {
                      _isRegister = !_isRegister;
                    });
                  },
                  child: Text(
                    _isRegister
                        ? 'Already have an account? Sign In'
                        : 'New to GoChat? Register with Phone / Email',
                    style: const TextStyle(color: AppTheme.primary, fontWeight: FontWeight.bold, fontSize: 14),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
