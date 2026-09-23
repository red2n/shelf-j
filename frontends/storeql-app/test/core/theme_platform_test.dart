import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/theme.dart';

/// One look everywhere, native manners per platform: the theme reads
/// `defaultTargetPlatform`, which on the web is the browser's OS.
void main() {
  tearDown(() => debugDefaultTargetPlatformOverride = null);

  test('iOS and macOS centre the app-bar title; Android and Windows start-align it', () {
    for (final apple in [TargetPlatform.iOS, TargetPlatform.macOS]) {
      debugDefaultTargetPlatformOverride = apple;
      expect(AppTheme.light.appBarTheme.centerTitle, isTrue, reason: '$apple');
    }
    for (final other in [TargetPlatform.android, TargetPlatform.windows, TargetPlatform.linux]) {
      debugDefaultTargetPlatformOverride = other;
      expect(AppTheme.light.appBarTheme.centerTitle, isFalse, reason: '$other');
    }
  });

  test('a mouse gets compact density, 40dp targets and a 440dp snackbar; touch keeps 48dp', () {
    debugDefaultTargetPlatformOverride = TargetPlatform.linux;
    var t = AppTheme.dark;
    expect(t.visualDensity, VisualDensity.compact);
    expect(t.filledButtonTheme.style!.minimumSize!.resolve({})!.height, 40);
    expect(t.snackBarTheme.width, 440);
    expect(t.snackBarTheme.insetPadding, isNull);

    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    t = AppTheme.dark;
    expect(t.visualDensity, VisualDensity.standard);
    expect(t.filledButtonTheme.style!.minimumSize!.resolve({})!.height, 48);
    expect(t.snackBarTheme.width, isNull);
    expect(t.snackBarTheme.insetPadding, isNotNull);
  });

  test('the POS accent is a soft amber in light and a deep amber container in dark', () {
    final light = AppTheme.applyPosAccent(AppTheme.light);
    final dark = AppTheme.applyPosAccent(AppTheme.dark);
    expect(light.appBarTheme.backgroundColor, ChannelAccent.light.color);
    expect(dark.appBarTheme.backgroundColor, ChannelAccent.dark.color);
    expect(dark.appBarTheme.backgroundColor, isNot(light.appBarTheme.backgroundColor));
  });
}
