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

/// Responsive navigation. On wide layouts (tablet / desktop / web) a persistent
/// [NavigationRail] sits beside the content and the app-bar icon expands/collapses
/// its labels. On phones it collapses to a [NavigationDrawer] toggled by that same
/// app-bar icon.
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

/// Below this width the shell collapses to a drawer (phones); at or above it a
/// persistent [NavigationRail] is shown (tablets / desktop / web), per Material 3.
const double _railBreakpoint = 800;

class _AdaptiveNavShellState extends State<AdaptiveNavShell> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

  /// On wide layouts the rail is always visible; this toggles icon-only ⇄ labelled.
  bool _railExtended = false;

  void _toggleDrawer() {
    final state = _scaffoldKey.currentState;
    if (state == null) return;
    if (state.isDrawerOpen) {
      Navigator.of(context).pop();
    } else {
      state.openDrawer();
    }
  }

  PreferredSizeWidget _appBar({required VoidCallback onLeading}) {
    return AppBar(
      backgroundColor: widget.appBarBackgroundColor,
      foregroundColor: widget.appBarForegroundColor,
      // Replace the automatic hamburger with the product icon as the toggle.
      automaticallyImplyLeading: false,
      leading: IconButton(
        icon: Icon(widget.leadingIcon),
        tooltip: 'Toggle menu',
        onPressed: onLeading,
      ),
      title: Text(widget.title, overflow: TextOverflow.ellipsis),
      actions: widget.actions,
    );
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      if (constraints.maxWidth >= _railBreakpoint) {
        return _buildWide(context);
      }
      return _buildNarrow(context);
    });
  }

  // Phones: nav lives in a drawer toggled from the app-bar icon.
  Widget _buildNarrow(BuildContext context) {
    return Scaffold(
      key: _scaffoldKey,
      appBar: _appBar(onLeading: _toggleDrawer),
      drawer: _buildDrawer(context),
      body: widget.child,
    );
  }

  // Tablet / desktop / web: a persistent rail beside the content. The app-bar
  // icon expands/collapses it (labels beside icons vs. under them).
  Widget _buildWide(BuildContext context) {
    return Scaffold(
      appBar: _appBar(
        onLeading: () => setState(() => _railExtended = !_railExtended),
      ),
      body: Row(
        children: [
          LayoutBuilder(
            builder: (context, c) => SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: c.maxHeight),
                child: IntrinsicHeight(
                  child: NavigationRail(
                    extended: _railExtended,
                    selectedIndex: widget.selectedIndex,
                    onDestinationSelected: widget.onDestinationSelected,
                    labelType: _railExtended
                        ? NavigationRailLabelType.none
                        : NavigationRailLabelType.all,
                    destinations: widget.destinations
                        .map((d) => NavigationRailDestination(
                              icon: Icon(d.icon),
                              selectedIcon: Icon(d.selectedIcon),
                              label: Text(d.label),
                            ))
                        .toList(),
                  ),
                ),
              ),
            ),
          ),
          const VerticalDivider(width: 1),
          Expanded(child: widget.child),
        ],
      ),
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
