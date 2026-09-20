import 'package:flutter/material.dart';

class AdaptiveNavDestination {
  final String label;
  final IconData icon;
  final IconData selectedIcon;

  /// Count shown on the destination's icon; zero or null shows no badge. Used by
  /// POS to surface sales still waiting to reach the server from wherever the
  /// cashier happens to be in the terminal.
  final int? badgeCount;

  const AdaptiveNavDestination({
    required this.label,
    required this.icon,
    required this.selectedIcon,
    this.badgeCount,
  });

  /// The destination's icon, badged when it has a non-zero count.
  Widget iconWidget({bool selected = false}) {
    final icon = Icon(selected ? selectedIcon : this.icon);
    final count = badgeCount ?? 0;
    return count > 0 ? Badge.count(count: count, child: icon) : icon;
  }
}

/// How the shell presents navigation below the [AdaptiveNavShell._railBreakpoint].
enum CompactNavStyle {
  /// A [NavigationDrawer] toggled from the app-bar leading icon — for shells
  /// with more destinations than a bottom bar comfortably holds (e.g. Admin).
  drawer,

  /// A persistent bottom [NavigationBar] — Material's recommended pattern for
  /// 3–5 top-level destinations on a phone (e.g. Storefront, POS).
  bottomBar,
}

/// Responsive navigation. On wide layouts (tablet / desktop / web) a persistent
/// [NavigationRail] sits beside the content and the app-bar icon expands/collapses
/// its labels. On phones it collapses to either a [NavigationDrawer] (toggled by
/// that same app-bar icon) or a bottom [NavigationBar], per [compactStyle].
class AdaptiveNavShell extends StatefulWidget {
  final String title;
  final List<AdaptiveNavDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final Widget child;
  final List<Widget> actions;

  /// Icon shown at the start of the app bar; tapping it toggles the drawer.
  /// Unused when [compactStyle] is [CompactNavStyle.bottomBar].
  final IconData leadingIcon;

  /// Optional app-bar theming (used e.g. by the POS shell's accent colour).
  final Color? appBarBackgroundColor;
  final Color? appBarForegroundColor;

  final CompactNavStyle compactStyle;

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
    this.compactStyle = CompactNavStyle.drawer,
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

  // onLeading == null omits the leading toggle icon entirely (bottom-bar mode
  // has no drawer to open, so there's nothing for it to control).
  PreferredSizeWidget _appBar({VoidCallback? onLeading}) {
    return AppBar(
      backgroundColor: widget.appBarBackgroundColor,
      foregroundColor: widget.appBarForegroundColor,
      // Replace the automatic hamburger with the product icon as the toggle.
      automaticallyImplyLeading: false,
      leading: onLeading == null
          ? null
          : IconButton(
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
      return widget.compactStyle == CompactNavStyle.bottomBar
          ? _buildBottomBar(context)
          : _buildNarrow(context);
    });
  }

  // Phones (drawer style): nav lives in a drawer toggled from the app-bar icon.
  Widget _buildNarrow(BuildContext context) {
    return Scaffold(
      key: _scaffoldKey,
      appBar: _appBar(onLeading: _toggleDrawer),
      drawer: _buildDrawer(context),
      body: widget.child,
    );
  }

  // Phones (bottom-bar style): nav lives in a persistent NavigationBar — the
  // Material-recommended pattern for a small, flat set of destinations.
  Widget _buildBottomBar(BuildContext context) {
    return Scaffold(
      appBar: _appBar(),
      body: widget.child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: widget.selectedIndex,
        onDestinationSelected: widget.onDestinationSelected,
        destinations: widget.destinations
            .map((d) => NavigationDestination(
                  icon: d.iconWidget(),
                  selectedIcon: d.iconWidget(selected: true),
                  label: d.label,
                ))
            .toList(),
      ),
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
                              icon: d.iconWidget(),
                              selectedIcon: d.iconWidget(selected: true),
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
              icon: d.iconWidget(),
              selectedIcon: d.iconWidget(selected: true),
              label: Text(d.label),
            )),
      ],
    );
  }
}
