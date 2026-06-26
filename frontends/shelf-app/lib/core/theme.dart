import 'package:flutter/material.dart';

// Brand palette
const Color _ivory = Color(0xFFFFFFF0);      // background / surface
const Color _gray = Color(0xFFCCCCCC);        // borders / outlines
const Color _charcoal = Color(0xFF575757);    // text / primary
const Color _amber = Color(0xFFFFE9A9);       // highlight / POS accent
const Color _green = Color(0xFFB6D7A8);       // success container / secondary

// Legible text-safe derivations (same hue, higher contrast)
const Color _forestGreen = Color(0xFF3D7A30);
const Color _darkAmber = Color(0xFFAA7B00);

/// Semantic status colours as a [ThemeExtension], so widgets read them from the
/// active theme (`context.status.success`) and they adapt to light/dark
/// automatically. Always pair the colour with text or an icon — never convey
/// status by hue alone (colour-blind users).
@immutable
class StatusColors extends ThemeExtension<StatusColors> {
  final Color success;
  final Color warning;

  const StatusColors({required this.success, required this.warning});

  static const light = StatusColors(success: _forestGreen, warning: _darkAmber);
  static const dark = StatusColors(success: _green, warning: _amber);

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
  // Amber accent used on POS action buttons (visually distinct from admin charcoal)
  static const Color posAccent = _amber;

  static ThemeData get light => ThemeData(
        useMaterial3: true,
        colorScheme: const ColorScheme.light(
          primary: _charcoal,
          onPrimary: _ivory,
          primaryContainer: _amber,
          onPrimaryContainer: _charcoal,
          secondary: _forestGreen,
          onSecondary: _ivory,
          secondaryContainer: _green,
          onSecondaryContainer: _charcoal,
          surface: _ivory,
          onSurface: _charcoal,
          surfaceContainerHighest: Color(0xFFEEEEE4),
          onSurfaceVariant: _charcoal,
          outline: _gray,
          outlineVariant: Color(0xFFE0E0DC),
        ),
        appBarTheme: const AppBarTheme(
          centerTitle: false,
          backgroundColor: _charcoal,
          foregroundColor: _ivory,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          color: _ivory,
        ),
        inputDecorationTheme: InputDecorationTheme(
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
          filled: true,
          fillColor: const Color(0xFFF8F8E8),
        ),
        dividerTheme: const DividerThemeData(color: _gray),
        extensions: const [StatusColors.light],
      );

  static ThemeData get dark => ThemeData(
        useMaterial3: true,
        colorScheme: const ColorScheme.dark(
          primary: _amber,
          onPrimary: _charcoal,
          primaryContainer: Color(0xFF5C4B00),
          onPrimaryContainer: _amber,
          secondary: _green,
          onSecondary: _charcoal,
          secondaryContainer: Color(0xFF1E4A18),
          onSecondaryContainer: _green,
          surface: Color(0xFF1E1E1A),
          onSurface: _ivory,
          surfaceContainerHighest: Color(0xFF2C2C28),
          onSurfaceVariant: _gray,
          outline: Color(0xFF575752),
          outlineVariant: Color(0xFF3A3A36),
        ),
        appBarTheme: const AppBarTheme(
          centerTitle: false,
          backgroundColor: Color(0xFF2C2C28),
          foregroundColor: _ivory,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          color: const Color(0xFF2C2C28),
        ),
        extensions: const [StatusColors.dark],
      );
}
