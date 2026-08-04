/// Mobile/desktop implementation of [compressProductImage] — see `image_compress.dart`.
///
/// Uses `package:image`, a pure-Dart codec, so there is no platform channel and no
/// per-OS branch. It is markedly slower than the browser's native encoder (a 12MP source
/// takes a beat), which is why the whole ladder runs on a background isolate via
/// [compute] rather than blocking the frame.
///
/// Output is JPEG: the pure-Dart library decodes WebP but cannot write it. That costs a
/// little size versus the web path and is not worth a native dependency, since the admin
/// shell is served as Flutter web in practice.
library;

import 'dart:typed_data';

import 'package:flutter/foundation.dart' show compute;
import 'package:image/image.dart' as img;

import 'image_budget.dart';

Future<CompressedImage> compressProductImage(
  Uint8List source, {
  required String sourceContentType,
}) =>
    compute(_compress, (bytes: source, contentType: sourceContentType));

CompressedImage _compress(({Uint8List bytes, String contentType}) input) {
  final decoded = img.decodeImage(input.bytes);
  if (decoded == null) {
    throw const ImageCompressException('That file could not be read as an image.');
  }

  if (canUploadUnchanged(
    byteLength: input.bytes.length,
    width: decoded.width,
    height: decoded.height,
    contentType: input.contentType,
  )) {
    return (bytes: input.bytes, contentType: input.contentType);
  }

  // decodeImage leaves EXIF orientation unapplied, so a phone photo would otherwise be
  // written out sideways. Baking it also drops the EXIF block (GPS included) from the
  // re-encoded output.
  final oriented = img.bakeOrientation(decoded);
  CompressedImage? smallest;

  for (final attempt in kProductImageAttempts) {
    final size = fitWithin(oriented.width, oriented.height, attempt.maxEdge);
    final resized = size.width == oriented.width && size.height == oriented.height
        ? oriented
        : img.copyResize(
            oriented,
            width: size.width,
            height: size.height,
            interpolation: img.Interpolation.average,
          );
    final encoded = (
      bytes: img.encodeJpg(resized, quality: attempt.quality),
      contentType: 'image/jpeg',
    );

    if (encoded.bytes.length <= kProductImageMaxBytes) return encoded;
    smallest = encoded;
  }

  throw ImageCompressException(
    'That image is too detailed to compress under '
    '${formatBytes(kProductImageMaxBytes)} '
    '(smallest we reached was ${formatBytes(smallest!.bytes.length)}). '
    'Try a simpler or smaller photo.',
  );
}
