// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';
import 'package:gochatmobile/core/state/app_state.dart';
import 'package:gochatmobile/main.dart';

void main() {
  testWidgets('GoChat app smoke test', (WidgetTester tester) async {
    final appState = AppState();
    await tester.pumpWidget(GoChatApp(appState: appState));

    expect(find.byType(GoChatApp), findsOneWidget);
  });
}
