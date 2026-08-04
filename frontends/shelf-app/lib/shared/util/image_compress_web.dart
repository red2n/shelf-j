/// Web implementation of [compressProductImage] — see `image_compress.dart`.
///
/// Decodes the picked bytes once into an `<img>`, then re-encodes it through a canvas at
/// each rung of the ladder until the output fits the budget. The browser's own encoder
/// does the work, so this is fast (tens of ms for a typical photo) and costs no
/// dependency.
///
/// Two browser behaviours this leans on deliberately:
/// - `<img>` applies EXIF orientation itself (`image-orientation: from-image` is the
///   initial CSS value), and `naturalWidth`/`naturalHeight` report the *oriented* size —
///   so phone photos do not come out sideways.
/// - Drawing to a canvas and reading it back drops all metadata, which is how EXIF GPS
///   from a phone photo is stripped before it ever reaches the storefront.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

// ignore: avoid_web_libraries_in_flutter
import 'package:web/web.dart' as web;

import 'image_budget.dart';

Future<CompressedImage> compressProductImage(
  Uint8List source, {
  required String sourceContentType,
}) async {
  final blob = web.Blob(
    <JSAny?>[source.toJS].toJS,
    web.BlobPropertyBag(type: sourceContentType),
  );
  final objectUrl = web.URL.createObjectURL(blob);
  final image = web.HTMLImageElement();
  try {
    image.src = objectUrl;
    try {
      await image.decode().toDart;
    } catch (_) {
      throw const ImageCompressException(
          'That file could not be read as an image.');
    }

    final width = image.naturalWidth;
    final height = image.naturalHeight;
    if (width <= 0 || height <= 0) {
      throw const ImageCompressException(
          'That file could not be read as an image.');
    }

    if (canUploadUnchanged(
      byteLength: source.length,
      width: width,
      height: height,
      contentType: sourceContentType,
    )) {
      return (bytes: source, contentType: sourceContentType);
    }

    // Preferred output. Falls back to JPEG if the browser has no WebP encoder — see
    // _encode: toDataURL silently hands back PNG for a type it cannot write, and PNG of
    // a photo is far larger than the source, so we must notice rather than trust it.
    var requestedType = 'image/webp';
    CompressedImage? smallest;

    for (final attempt in kProductImageAttempts) {
      final size = fitWithin(width, height, attempt.maxEdge);
      var encoded = _encode(image, size, requestedType, attempt.quality);

      if (requestedType != 'image/jpeg' && encoded.contentType != requestedType) {
        requestedType = 'image/jpeg';
        encoded = _encode(image, size, requestedType, attempt.quality);
      }

      if (encoded.bytes.length < kProductImageMaxBytes) return encoded;
      smallest = encoded;
    }

    throw ImageCompressException(
      'That image is too detailed to compress under '
      '${formatBytes(kProductImageMaxBytes)} '
      '(smallest we reached was ${formatBytes(smallest!.bytes.length)}). '
      'Try a simpler or smaller photo.',
    );
  } finally {
    web.URL.revokeObjectURL(objectUrl);
  }
}

/// Draws [image] into a canvas of exactly [size] and reads it back encoded.
///
/// The returned content type is parsed from the data URL rather than assumed, because
/// that is the only signal the browser gives about which encoder it actually used.
CompressedImage _encode(
  web.HTMLImageElement image,
  ({int width, int height}) size,
  String type,
  int quality,
) {
  final canvas = web.HTMLCanvasElement()
    ..width = size.width
    ..height = size.height;
  final context = canvas.getContext('2d') as web.CanvasRenderingContext2D?;
  if (context == null) {
    throw const ImageCompressException('Could not prepare the image for upload.');
  }
  context.drawImage(image, 0, 0, size.width.toDouble(), size.height.toDouble());

  final dataUrl = canvas.toDataURL(type, (quality / 100).toJS);
  final comma = dataUrl.indexOf(',');
  if (comma < 0 || !dataUrl.startsWith('data:')) {
    throw const ImageCompressException('Could not prepare the image for upload.');
  }
  final header = dataUrl.substring('data:'.length, comma);
  if (!header.contains('base64')) {
    throw const ImageCompressException('Could not prepare the image for upload.');
  }
  final semicolon = header.indexOf(';');
  final mime = semicolon < 0 ? header : header.substring(0, semicolon);

  return (
    bytes: base64Decode(dataUrl.substring(comma + 1)),
    contentType: mime,
  );
}
