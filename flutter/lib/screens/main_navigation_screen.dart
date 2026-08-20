import 'package:flutter/material.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/app_drawer.dart';
import 'chat/chat_list_screen.dart';
import 'stories/stories_screen.dart';
import 'channels/channels_screen.dart';
import 'calls/calls_screen.dart';
import 'marketplace/marketplace_screen.dart';
import 'settings/settings_screen.dart';

class MainNavigationScreen extends StatefulWidget {
  final AppState appState;

  const MainNavigationScreen({super.key, required this.appState});

  @override
  State<MainNavigationScreen> createState() => _MainNavigationScreenState();
}

class _MainNavigationScreenState extends State<MainNavigationScreen> {
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  int _currentIndex = 0;

  @override
  void initState() {
    super.initState();
    widget.appState.addListener(_onStateChange);
  }

  @override
  void dispose() {
    widget.appState.removeListener(_onStateChange);
    super.dispose();
  }

  void _onStateChange() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final unreadChats = widget.appState.conversations
        .fold(0, (sum, c) => sum + c.unreadCount);

    final screens = [
      ChatListScreen(
        appState: widget.appState,
        onOpenDrawer: () => _scaffoldKey.currentState?.openDrawer(),
      ),
      StoriesScreen(appState: widget.appState),
      ChannelsScreen(appState: widget.appState),
      CallsScreen(appState: widget.appState),
      MarketplaceScreen(appState: widget.appState),
      SettingsScreen(appState: widget.appState),
    ];

    return Scaffold(
      key: _scaffoldKey,
      drawer: AppDrawer(
        appState: widget.appState,
        currentIndex: _currentIndex,
        onSelectTab: (idx) => setState(() => _currentIndex = idx),
      ),
      body: IndexedStack(
        index: _currentIndex,
        children: screens,
      ),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: AppTheme.darkBorder, width: 0.5)),
        ),
        child: BottomNavigationBar(
          currentIndex: _currentIndex,
          onTap: (idx) => setState(() => _currentIndex = idx),
          items: [
            BottomNavigationBarItem(
              icon: Badge(
                label: Text('$unreadChats'),
                isLabelVisible: unreadChats > 0,
                backgroundColor: AppTheme.accent,
                textColor: Colors.black,
                child: const Icon(Icons.chat_rounded),
              ),
              activeIcon: const Icon(Icons.chat_rounded, color: AppTheme.primary),
              label: 'Chats',
            ),
            const BottomNavigationBarItem(
              icon: Icon(Icons.auto_stories_outlined),
              activeIcon: Icon(Icons.auto_stories, color: AppTheme.primary),
              label: 'Updates',
            ),
            const BottomNavigationBarItem(
              icon: Icon(Icons.groups_outlined),
              activeIcon: Icon(Icons.groups_rounded, color: AppTheme.primary),
              label: 'Channels',
            ),
            const BottomNavigationBarItem(
              icon: Icon(Icons.call_outlined),
              activeIcon: Icon(Icons.call_rounded, color: AppTheme.primary),
              label: 'Calls',
            ),
            BottomNavigationBarItem(
              icon: Badge(
                label: Text('${widget.appState.cart.length}'),
                isLabelVisible: widget.appState.cart.isNotEmpty,
                child: const Icon(Icons.storefront_outlined),
              ),
              activeIcon: const Icon(Icons.storefront, color: AppTheme.primary),
              label: 'Store',
            ),
            const BottomNavigationBarItem(
              icon: Icon(Icons.settings_outlined),
              activeIcon: Icon(Icons.settings, color: AppTheme.primary),
              label: 'Settings',
            ),
          ],
        ),
      ),
    );
  }
}
