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

/// The POS channel's accent (amber), expressed through the theme system instead
/// of a raw [Color] constant, so anything themed off it — app bar, buttons, the
/// bottom nav indicator — stays in sync and carries a correct contrasting
/// foreground. [PosShell] installs this via [AppTheme.applyPosAccent]; screens
/// read it with `context.channelAccent`.
@immutable
class ChannelAccent extends ThemeExtension<ChannelAccent> {
  final Color color;
  final Color onColor;

  const ChannelAccent({required this.color, required this.onColor});

  @override
  ChannelAccent copyWith({Color? color, Color? onColor}) => ChannelAccent(
        color: color ?? this.color,
        onColor: onColor ?? this.onColor,
      );

  @override
  ChannelAccent lerp(ThemeExtension<ChannelAccent>? other, double t) {
    if (other is! ChannelAccent) return this;
    return ChannelAccent(
      color: Color.lerp(color, other.color, t)!,
      onColor: Color.lerp(onColor, other.onColor, t)!,
    );
  }
}

extension ChannelAccentX on BuildContext {
  /// Falls back to the ambient primary/onPrimary outside a channel-themed shell.
  ChannelAccent get channelAccent =>
      Theme.of(this).extension<ChannelAccent>() ??
      ChannelAccent(
        color: Theme.of(this).colorScheme.primary,
        onColor: Theme.of(this).colorScheme.onPrimary,
      );
}

class AppTheme {
  // Amber accent used on POS action buttons (visually distinct from admin charcoal).
  static const Color posAccent = _amber;
  static const Color posAccentForeground = _charcoal;

  static ThemeData get light {
    final scheme = ColorScheme.fromSeed(
      brightness: Brightness.light,
      seedColor: _charcoal,
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
      outline: _gray,
      outlineVariant: const Color(0xFFE0E0DC),
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      appBarTheme: const AppBarTheme(
        centerTitle: false,
        backgroundColor: _charcoal,
        foregroundColor: _ivory,
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        color: scheme.surface,
      ),
      inputDecorationTheme: InputDecorationTheme(
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
        filled: true,
        fillColor: const Color(0xFFF8F8E8),
      ),
      dividerTheme: DividerThemeData(color: scheme.outline),
      extensions: const [StatusColors.light],
    );
  }

  static ThemeData get dark {
    final scheme = ColorScheme.fromSeed(
      brightness: Brightness.dark,
      seedColor: _charcoal,
      primary: _amber,
      onPrimary: _charcoal,
      primaryContainer: const Color(0xFF5C4B00),
      onPrimaryContainer: _amber,
      secondary: _green,
      onSecondary: _charcoal,
      secondaryContainer: const Color(0xFF1E4A18),
      onSecondaryContainer: _green,
      surface: const Color(0xFF1E1E1A),
      onSurface: _ivory,
      outline: const Color(0xFF575752),
      outlineVariant: const Color(0xFF3A3A36),
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      appBarTheme: const AppBarTheme(
        centerTitle: false,
        backgroundColor: Color(0xFF2C2C28),
        foregroundColor: _ivory,
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        color: scheme.surfaceContainerHighest,
      ),
      inputDecorationTheme: InputDecorationTheme(
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
        filled: true,
        fillColor: scheme.surfaceContainerHighest,
      ),
      dividerTheme: DividerThemeData(color: scheme.outline),
      extensions: const [StatusColors.dark],
    );
  }

  /// Applied by [PosShell] to scope the amber channel accent (app bar, primary
  /// action buttons, bottom-nav indicator) to the POS subtree only — without
  /// touching `colorScheme.primary`, which POS screens still read for
  /// unrelated meanings (e.g. a settled-payment checkmark).
  static ThemeData applyPosAccent(ThemeData base) {
    return base.copyWith(
      appBarTheme: base.appBarTheme.copyWith(
        backgroundColor: posAccent,
        foregroundColor: posAccentForeground,
      ),
      navigationBarTheme: (base.navigationBarTheme).copyWith(
        indicatorColor: posAccent.withValues(alpha: 0.55),
      ),
      extensions: [
        ...base.extensions.values,
        const ChannelAccent(color: posAccent, onColor: posAccentForeground),
      ],
    );
  }
}
