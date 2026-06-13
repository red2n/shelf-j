import 'package:flutter/material.dart';

class AdaptiveNavDestination {
  final String label;
  final IconData icon;
  final IconData selectedIcon;

  const AdaptiveNavDestination({
    required this.label,
    required this.icon,
    required this.selectedIcon,
  });
}

/// Renders a NavigationRail on wide screens (≥640 px) and a
/// BottomNavigationBar on narrow screens. Keeps a single widget tree for
/// both mobile and web/desktop.
class AdaptiveNavShell extends StatelessWidget {
  final String title;
  final List<AdaptiveNavDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final Widget child;
  final List<Widget> actions;

  const AdaptiveNavShell({
    super.key,
    required this.title,
    required this.destinations,
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.child,
    this.actions = const [],
  });

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final useRail = width >= 640;
    final extendRail = width >= 1100;

    if (useRail) {
      return Scaffold(
        appBar: AppBar(
          title: Row(
            children: [
              const Icon(Icons.storefront_rounded),
              const SizedBox(width: 8),
              Text(title),
            ],
          ),
          actions: actions,
        ),
        body: Row(
          children: [
            NavigationRail(
              extended: extendRail,
              selectedIndex: selectedIndex,
              onDestinationSelected: onDestinationSelected,
              destinations: destinations
                  .map((d) => NavigationRailDestination(
                        icon: Icon(d.icon),
                        selectedIcon: Icon(d.selectedIcon),
                        label: Text(d.label),
                      ))
                  .toList(),
            ),
            const VerticalDivider(width: 1),
            Expanded(child: child),
          ],
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: actions,
      ),
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: selectedIndex,
        onDestinationSelected: onDestinationSelected,
        destinations: destinations
            .map((d) => NavigationDestination(
                  icon: Icon(d.icon),
                  selectedIcon: Icon(d.selectedIcon),
                  label: d.label,
                ))
            .toList(),
      ),
    );
  }
}
