import 'package:flutter/widgets.dart';

/// A 4-pt spacing scale. Use these tokens instead of magic numbers in
/// `EdgeInsets` / `SizedBox`, so spacing stays consistent and tunable in one
/// place (e.g. `EdgeInsets.all(AppSpacing.lg)`, `SizedBox(height: AppSpacing.md)`).
class AppSpacing {
  AppSpacing._();

  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double xxl = 32;

  // Common ready-made insets.
  static const EdgeInsets pagePadding = EdgeInsets.all(xl);
  static const EdgeInsets cardPadding = EdgeInsets.all(lg);
}

/// A square or directional gap, e.g. `Gap(AppSpacing.md)`.
class Gap extends StatelessWidget {
  final double size;
  const Gap(this.size, {super.key});

  @override
  Widget build(BuildContext context) => SizedBox(width: size, height: size);
}

/// Material 3 window-size classes, plus the width where [AdaptiveNavShell]
/// switches to a rail. One set of numbers for phones, tablets, and the web.
class AppBreakpoints {
  AppBreakpoints._();

  /// Below this: phones (portrait). Single column, bottom bar or drawer.
  static const double medium = 600;

  /// [AdaptiveNavShell] shows a persistent NavigationRail at or above this.
  static const double rail = 800;

  /// At or above: tablets landscape, small laptops.
  static const double expanded = 840;

  /// At or above: desktop browsers. The rail starts extended (labelled).
  static const double large = 1200;

  /// Reading/form content never grows wider than this on big screens
  /// (checkout, product detail, account, settings) — see [ContentBounds].
  static const double contentMaxWidth = 1200;
  static const double formMaxWidth = 640;
}

enum WindowClass { compact, medium, expanded, large }

extension WindowClassX on BuildContext {
  WindowClass get windowClass {
    final w = MediaQuery.sizeOf(this).width;
    if (w >= AppBreakpoints.large) return WindowClass.large;
    if (w >= AppBreakpoints.expanded) return WindowClass.expanded;
    if (w >= AppBreakpoints.medium) return WindowClass.medium;
    return WindowClass.compact;
  }

  /// Page padding that grows with the window: 16 on phones, 24 from tablets up.
  EdgeInsets get pagePadding => EdgeInsets.all(
        windowClass == WindowClass.compact ? AppSpacing.lg : AppSpacing.xl,
      );
}

/// Centres [child] and caps its width, so a form or a detail page doesn't
/// stretch edge to edge in a desktop browser. A no-op on phones.
class ContentBounds extends StatelessWidget {
  final Widget child;
  final double maxWidth;
  const ContentBounds({
    super.key,
    required this.child,
    this.maxWidth = AppBreakpoints.contentMaxWidth,
  });

  /// For single-column forms (sign-in, address, checkout details).
  const ContentBounds.form({super.key, required this.child})
      : maxWidth = AppBreakpoints.formMaxWidth;

  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxWidth),
          child: child,
        ),
      );
}
