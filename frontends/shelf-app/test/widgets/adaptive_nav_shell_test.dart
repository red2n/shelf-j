import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/shared/widgets/adaptive_nav_shell.dart';

Widget _harness() => MaterialApp(
      home: AdaptiveNavShell(
        title: 'Admin',
        selectedIndex: 0,
        onDestinationSelected: (_) {},
        destinations: const [
          AdaptiveNavDestination(
              label: 'Home', icon: Icons.home_outlined, selectedIcon: Icons.home),
          AdaptiveNavDestination(
              label: 'Settings',
              icon: Icons.settings_outlined,
              selectedIcon: Icons.settings),
        ],
        child: const SizedBox.shrink(),
      ),
    );

void main() {
  testWidgets('shows a NavigationRail on wide layouts', (tester) async {
    tester.view.physicalSize = const Size(1200, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(_harness());
    expect(find.byType(NavigationRail), findsOneWidget);
  });

  testWidgets('collapses to a drawer (no rail) on narrow layouts',
      (tester) async {
    tester.view.physicalSize = const Size(500, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(_harness());
    expect(find.byType(NavigationRail), findsNothing);
    expect(find.byType(Scaffold), findsOneWidget);
  });
}
