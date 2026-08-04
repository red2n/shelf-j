import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/shared/util/image_budget.dart';

void main() {
  group('fitWithin', () {
    test('leaves an already-small image untouched', () {
      expect(fitWithin(800, 600, 1280), (width: 800, height: 600));
    });

    test('scales the long edge down to the cap, preserving aspect ratio', () {
      expect(fitWithin(4000, 3000, 1280), (width: 1280, height: 960));
      expect(fitWithin(3000, 4000, 1280), (width: 960, height: 1280));
    });

    test('never returns a zero edge for an extreme panorama', () {
      final size = fitWithin(20000, 3, 1280);
      expect(size.width, 1280);
      expect(size.height, greaterThanOrEqualTo(1));
    });

    test('rejects a source with no pixels', () {
      expect(() => fitWithin(0, 100, 1280), throwsA(isA<ImageCompressException>()));
    });
  });

  group('canUploadUnchanged', () {
    test('passes through a small PNG so line art is not smeared by a lossy pass', () {
      expect(
        canUploadUnchanged(
          byteLength: 40 * 1024,
          width: 512,
          height: 512,
          contentType: 'image/png',
        ),
        isTrue,
      );
    });

    test('always re-encodes JPEG, so EXIF GPS is stripped even when small', () {
      expect(
        canUploadUnchanged(
          byteLength: 40 * 1024,
          width: 512,
          height: 512,
          contentType: 'image/jpeg',
        ),
        isFalse,
      );
    });

    test('re-encodes an oversized PNG even when it is under the byte budget', () {
      expect(
        canUploadUnchanged(
          byteLength: 100 * 1024,
          width: 4000,
          height: 3000,
          contentType: 'image/png',
        ),
        isFalse,
      );
    });

    test('re-encodes a within-bounds PNG that busts the byte budget', () {
      expect(
        canUploadUnchanged(
          byteLength: kProductImageMaxBytes + 1,
          width: 800,
          height: 600,
          contentType: 'image/png',
        ),
        isFalse,
      );
    });

    test('rejects a PNG of exactly the cap — the budget is strictly under', () {
      expect(
        canUploadUnchanged(
          byteLength: kProductImageMaxBytes,
          width: 800,
          height: 600,
          contentType: 'image/png',
        ),
        isFalse,
      );
    });
  });

  group('the ladder', () {
    test('matches the cap product-svc enforces', () {
      // Must equal ProductService.MAX_IMAGE_BYTES, which rejects anything at or above
      // it and is backed by a CHECK constraint on product_images. If these drift, the
      // owner gets a server rejection the client promised would not happen.
      expect(kProductImageMaxBytes, 256 * 1024);
    });

    test('descends monotonically, so the first fit is the best-quality fit', () {
      for (var i = 1; i < kProductImageAttempts.length; i++) {
        final previous = kProductImageAttempts[i - 1];
        final current = kProductImageAttempts[i];
        expect(current.maxEdge, lessThanOrEqualTo(previous.maxEdge));
        expect(
          current.maxEdge < previous.maxEdge || current.quality < previous.quality,
          isTrue,
          reason: 'rung $i must be strictly smaller than rung ${i - 1}',
        );
      }
    });

    test('opens at the full edge cap', () {
      expect(kProductImageAttempts.first.maxEdge, kProductImageMaxEdge);
    });
  });

  group('contentTypeForExtension', () {
    test('maps the extensions product-svc accepts', () {
      expect(contentTypeForExtension('png'), 'image/png');
      expect(contentTypeForExtension('PNG'), 'image/png');
      expect(contentTypeForExtension('webp'), 'image/webp');
      expect(contentTypeForExtension('jpg'), 'image/jpeg');
      expect(contentTypeForExtension('jpeg'), 'image/jpeg');
    });

    test('falls back to JPEG for unknown or missing extensions', () {
      expect(contentTypeForExtension(null), 'image/jpeg');
      expect(contentTypeForExtension(''), 'image/jpeg');
      expect(contentTypeForExtension('heic'), 'image/jpeg');
    });
  });

  test('formatBytes reports whole KB', () {
    expect(formatBytes(94 * 1024), '94 KB');
    expect(formatBytes(kProductImageMaxBytes), '256 KB');
  });
}
