// Run with: flutter test tool/generate_icon.dart
//
// Renders the Shelf-J app icon (a storefront awning over a POS kiosk, in the
// app's brand colors) and writes it to assets/icon/. flutter_launcher_icons
// then turns those PNGs into every iOS/Android icon size — see the
// flutter_launcher_icons section of pubspec.yaml. Re-run this whenever the
// icon design changes; it overwrites the two PNGs in place.
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

const _brandBlue = Color(0xFF1A5276);
const _posAccent = Color(0xFFE67E22);

class _StorefrontKioskGlyph extends StatelessWidget {
  final bool paintBackground;
  final double glyphScale;

  const _StorefrontKioskGlyph({
    required this.paintBackground,
    required this.glyphScale,
  });

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: const Size(1024, 1024),
      painter: _IconPainter(
        paintBackground: paintBackground,
        glyphScale: glyphScale,
      ),
    );
  }
}

class _IconPainter extends CustomPainter {
  final bool paintBackground;
  final double glyphScale;

  _IconPainter({required this.paintBackground, required this.glyphScale});

  @override
  void paint(Canvas canvas, Size size) {
    if (paintBackground) {
      canvas.drawRect(Offset.zero & size, Paint()..color = _brandBlue);
    }

    final s = size.width * glyphScale;
    final dx = (size.width - s) / 2;
    final dy = (size.height - s) / 2;
    canvas.save();
    canvas.translate(dx, dy);
    _paintGlyph(canvas, s);
    canvas.restore();
  }

  void _paintGlyph(Canvas canvas, double s) {
    final white = Paint()..color = Colors.white;
    final accent = Paint()..color = _posAccent;

    // Vertical balance: the design below naturally sits a bit high (more
    // weight near the top from the awning), so nudge the whole composition
    // down within its box to centre the visual mass, not just the box.
    canvas.save();
    canvas.translate(0, s * 0.055);

    // Storefront awning: a flat header strip with a 5-scallop valance below.
    const scallops = 5;
    final headerTop = s * 0.06;
    final headerHeight = s * 0.05;
    final headerBottom = headerTop + headerHeight;
    final scallopDepth = s * 0.10;
    final awningLeft = s * 0.08;
    final awningWidth = s * 0.84;
    final scallopWidth = awningWidth / scallops;

    final awningPath = Path()
      ..moveTo(awningLeft, headerTop)
      ..lineTo(awningLeft + awningWidth, headerTop)
      ..lineTo(awningLeft + awningWidth, headerBottom);
    for (var i = scallops - 1; i >= 0; i--) {
      final x0 = awningLeft + i * scallopWidth;
      final xMid = x0 + scallopWidth / 2;
      awningPath.quadraticBezierTo(
          xMid, headerBottom + scallopDepth, x0, headerBottom);
    }
    awningPath.close();
    canvas.drawPath(awningPath, white);

    // Storefront wall below the awning, with a window cut-out.
    final wallTop = headerBottom + scallopDepth * 0.45;
    final wallRect = Rect.fromLTWH(s * 0.12, wallTop, s * 0.76, s * 0.30);
    canvas.drawRRect(
        RRect.fromRectAndRadius(wallRect, Radius.circular(s * 0.02)), white);
    // Punched through with the background color so it reads as a window;
    // on the transparent foreground layer this just leaves the wall solid,
    // which is fine at the small scale that layer renders at.
    if (paintBackground) {
      final windowRect =
          Rect.fromLTWH(s * 0.20, wallTop + s * 0.055, s * 0.60, s * 0.155);
      canvas.drawRRect(
          RRect.fromRectAndRadius(windowRect, Radius.circular(s * 0.015)),
          Paint()..color = _brandBlue);
    }

    // POS kiosk below the storefront: body + screen + base.
    final kioskTop = wallRect.bottom + s * 0.07;
    final kioskRect = Rect.fromLTWH(s * 0.34, kioskTop, s * 0.32, s * 0.26);
    canvas.drawRRect(
        RRect.fromRectAndRadius(kioskRect, Radius.circular(s * 0.03)), white);
    final screenRect =
        Rect.fromLTWH(s * 0.395, kioskTop + s * 0.045, s * 0.21, s * 0.13);
    canvas.drawRRect(
        RRect.fromRectAndRadius(screenRect, Radius.circular(s * 0.015)),
        accent);
    final baseRect =
        Rect.fromLTWH(s * 0.43, kioskRect.bottom, s * 0.14, s * 0.045);
    canvas.drawRect(baseRect, white);

    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _IconPainter oldDelegate) => false;
}

Future<void> _capture(
    WidgetTester tester, Widget glyph, String outPath) async {
  final key = GlobalKey();
  await tester.pumpWidget(
    Directionality(
      textDirection: TextDirection.ltr,
      child: RepaintBoundary(key: key, child: glyph),
    ),
  );
  await tester.pump();
  final boundary =
      key.currentContext!.findRenderObject() as RenderRepaintBoundary;
  // toImage()/toByteData()/file I/O are real async work — they never
  // resolve inside flutter_test's default fake-async zone, so they must run
  // through runAsync() or the test hangs until the framework's own timeout.
  await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: 1.0);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    final file = File(outPath);
    await file.create(recursive: true);
    await file.writeAsBytes(bytes!.buffer.asUint8List());
  });
}

void main() {
  testWidgets('generate Shelf-J app icon assets', (tester) async {
    tester.view.physicalSize = const Size(1024, 1024);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    // Full icon: opaque brand-blue background + glyph (iOS / legacy Android).
    await _capture(
      tester,
      const _StorefrontKioskGlyph(paintBackground: true, glyphScale: 0.68),
      'assets/icon/icon.png',
    );

    // Foreground-only glyph on a transparent canvas, sized within Android's
    // adaptive-icon safe zone, with the background color supplied separately
    // via flutter_launcher_icons' adaptive_icon_background.
    await _capture(
      tester,
      const _StorefrontKioskGlyph(paintBackground: false, glyphScale: 0.46),
      'assets/icon/icon_foreground.png',
    );
  });
}
