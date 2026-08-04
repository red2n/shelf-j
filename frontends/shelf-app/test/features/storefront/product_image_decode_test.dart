import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/storefront/storefront_widgets.dart';
import 'package:shelf_app/shared/util/image_budget.dart';

void main() {
  group('productImageDecodeWidth', () {
    test('sizes the phone list 72x72 tile from the box, not the source', () {
      // The decode that matters: this tile used to pull a full 1280px image in.
      final width = productImageDecodeWidth(
        BoxConstraints.tight(const Size(72, 72)),
        2,
      );
      expect(width, (72 * 2 * 1.35).ceil()); // 195
      expect(width, lessThan(kProductImageMaxEdge));
    });

    test('scales with device pixel ratio', () {
      final box = BoxConstraints.tight(const Size(72, 72));
      expect(productImageDecodeWidth(box, 1), (72 * 1.35).ceil());
      expect(productImageDecodeWidth(box, 3), (72 * 3 * 1.35).ceil());
    });

    test('drives off the longest bounded edge, so cover has pixels to crop', () {
      // Detail screen shape: wider than it is tall, so width governs.
      final width = productImageDecodeWidth(
        const BoxConstraints(maxWidth: 300, maxHeight: 220),
        2,
      );
      expect(width, (300 * 2 * 1.35).ceil()); // 810, comfortably under the clamp
    });

    test('never asks for more than the widest image we store', () {
      final width = productImageDecodeWidth(
        const BoxConstraints(maxWidth: 4000, maxHeight: 4000),
        3,
      );
      expect(width, kProductImageMaxEdge);
    });

    test('uses the bounded edge when only one axis is bounded', () {
      final width = productImageDecodeWidth(
        const BoxConstraints(maxWidth: 100, maxHeight: double.infinity),
        2,
      );
      expect(width, (100 * 2 * 1.35).ceil());
    });

    test('returns null when nothing is bounded, so the caller decodes natively', () {
      expect(
        productImageDecodeWidth(const BoxConstraints(), 2),
        isNull,
      );
    });

    test('returns null for a zero-sized box rather than a zero decode', () {
      expect(
        productImageDecodeWidth(BoxConstraints.tight(Size.zero), 2),
        isNull,
      );
    });

    test('stays far below a full-resolution decode', () {
      // The point of the change: a 72x72 tile at 2x should cost a small fraction of
      // the ~1280x960 decode it replaced.
      final width = productImageDecodeWidth(
        BoxConstraints.tight(const Size(72, 72)),
        2,
      )!;
      // Decoded RGBA scales with pixel count; compare against a 1280-wide 4:3 source.
      final decodedPixels = width * (width * 3 / 4);
      final nativePixels = 1280 * 960;
      expect(decodedPixels / nativePixels, lessThan(0.05));
    });
  });
}
