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
