import 'package:flutter/material.dart';

/// Semantic status colours as a [ThemeExtension], so widgets read them from the
/// active theme (`context.status.success`) and they adapt to light/dark
/// automatically. Always pair the colour with text or an icon — never convey
/// status by hue alone (colour-blind users).
@immutable
class StatusColors extends ThemeExtension<StatusColors> {
  final Color success;
  final Color warning;

  const StatusColors({required this.success, required this.warning});

  static const light =
      StatusColors(success: Color(0xFF2E7D32), warning: Color(0xFFE65100));
  static const dark =
      StatusColors(success: Color(0xFF81C784), warning: Color(0xFFFFB74D));

  @override
  StatusColors copyWith({Color? success, Color? warning}) => StatusColors(
        success: success ?? this.success,
        warning: warning ?? this.warning,
      );

  @override
  StatusColors lerp(ThemeExtension<StatusColors>? other, double t) {
    if (other is! StatusColors) return this;
    return StatusColors(
      success: Color.lerp(success, other.success, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
    );
  }
}

extension StatusColorsX on BuildContext {
  StatusColors get status =>
      Theme.of(this).extension<StatusColors>() ?? StatusColors.light;
}

class AppTheme {
  static const Color _seed = Color(0xFF1A5276);
  // Amber used on POS action buttons (visually distinct from admin blue)
  static const Color posAccent = Color(0xFFE67E22);

  static ThemeData get light => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: _seed),
        appBarTheme: const AppBarTheme(centerTitle: false),
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
        inputDecorationTheme: InputDecorationTheme(
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
          filled: true,
        ),
        extensions: const [StatusColors.light],
      );

  static ThemeData get dark => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _seed,
          brightness: Brightness.dark,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
        extensions: const [StatusColors.dark],
      );
}
