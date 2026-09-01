import 'dart:async';
import 'package:flutter/material.dart';
import 'core/models/call.dart';
import 'core/services/push_notification_service.dart';
import 'core/state/app_state.dart';
import 'core/theme/app_theme.dart';
import 'screens/auth/login_screen.dart';
import 'screens/calls/incoming_voip_call_screen.dart';
import 'screens/main_navigation_screen.dart';

final GlobalKey<NavigatorState> rootNavigatorKey = GlobalKey<NavigatorState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final appState = AppState();
  await appState.init();

  runApp(GoChatApp(appState: appState));
}

class GoChatApp extends StatefulWidget {
  final AppState appState;

  const GoChatApp({super.key, required this.appState});

  @override
  State<GoChatApp> createState() => _GoChatAppState();
}

class _GoChatAppState extends State<GoChatApp> {
  StreamSubscription<CallRecord>? _callSubscription;

  @override
  void initState() {
    super.initState();
    // Listen for incoming VoIP calls and automatically present the full-screen ringing UI
    _callSubscription = widget.appState.onIncomingCall.listen((call) {
      if (PushNotificationService().voipCallPushEnabled &&
          rootNavigatorKey.currentContext != null) {
        IncomingVoipCallScreen.show(
          rootNavigatorKey.currentContext!,
          callRecord: call,
          appState: widget.appState,
        );
      }
    });
  }

  @override
  void dispose() {
    _callSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.appState,
      builder: (context, _) {
        return MaterialApp(
          navigatorKey: rootNavigatorKey,
          title: 'GoChat',
          debugShowCheckedModeBanner: false,
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          themeMode: widget.appState.themeMode,
          home: widget.appState.isAuthenticated
              ? MainNavigationScreen(appState: widget.appState)
              : LoginScreen(appState: widget.appState),
        );
      },
    );
  }
}
