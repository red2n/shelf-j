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

/// Navigation that stays hidden in every view. The app/product icon in the app
/// bar toggles a left-docked [NavigationDrawer] open and closed — click it to
/// dock the menu, click again (or tap outside / pick an item) to undock it.
class AdaptiveNavShell extends StatefulWidget {
  final String title;
  final List<AdaptiveNavDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final Widget child;
  final List<Widget> actions;

  /// Icon shown at the start of the app bar; tapping it toggles the menu.
  final IconData leadingIcon;

  /// Optional app-bar theming (used e.g. by the POS shell's accent colour).
  final Color? appBarBackgroundColor;
  final Color? appBarForegroundColor;

  const AdaptiveNavShell({
    super.key,
    required this.title,
    required this.destinations,
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.child,
    this.actions = const [],
    this.leadingIcon = Icons.storefront_rounded,
    this.appBarBackgroundColor,
    this.appBarForegroundColor,
  });

  @override
  State<AdaptiveNavShell> createState() => _AdaptiveNavShellState();
}

class _AdaptiveNavShellState extends State<AdaptiveNavShell> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

  void _toggleDrawer() {
    final state = _scaffoldKey.currentState;
    if (state == null) return;
    if (state.isDrawerOpen) {
      Navigator.of(context).pop();
    } else {
      state.openDrawer();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: _scaffoldKey,
      appBar: AppBar(
        backgroundColor: widget.appBarBackgroundColor,
        foregroundColor: widget.appBarForegroundColor,
        // Replace the automatic hamburger with the product icon as the toggle.
        automaticallyImplyLeading: false,
        leading: IconButton(
          icon: Icon(widget.leadingIcon),
          tooltip: 'Toggle menu',
          onPressed: _toggleDrawer,
        ),
        title: Text(widget.title, overflow: TextOverflow.ellipsis),
        actions: widget.actions,
      ),
      drawer: _buildDrawer(context),
      body: widget.child,
    );
  }

  Widget _buildDrawer(BuildContext context) {
    return NavigationDrawer(
      selectedIndex: widget.selectedIndex,
      onDestinationSelected: (i) {
        Navigator.of(context).pop(); // undock after picking
        widget.onDestinationSelected(i);
      },
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(28, 24, 16, 16),
          child: Row(
            children: [
              Icon(widget.leadingIcon),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  widget.title,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
            ],
          ),
        ),
        ...widget.destinations.map((d) => NavigationDrawerDestination(
              icon: Icon(d.icon),
              selectedIcon: Icon(d.selectedIcon),
              label: Text(d.label),
            )),
      ],
    );
  }
}
