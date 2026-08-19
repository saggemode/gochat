import 'package:flutter/material.dart';
import 'core/state/app_state.dart';
import 'core/theme/app_theme.dart';
import 'screens/auth/login_screen.dart';
import 'screens/main_navigation_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final appState = AppState();
  await appState.init();

  runApp(GoChatApp(appState: appState));
}

class GoChatApp extends StatelessWidget {
  final AppState appState;

  const GoChatApp({super.key, required this.appState});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'GoChat',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.darkTheme,
      home: appState.isAuthenticated
          ? MainNavigationScreen(appState: appState)
          : LoginScreen(appState: appState),
    );
  }
}
