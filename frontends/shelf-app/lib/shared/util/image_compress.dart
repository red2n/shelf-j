/// Client-side product-image compression.
///
/// Import this; the right implementation is picked per platform:
/// - web uses a canvas re-encode (fast, native browser encoder, prefers WebP),
/// - mobile/desktop uses `package:image` on a background isolate (JPEG — the pure-Dart
///   encoder has no WebP writer).
///
/// Both honour the same budget and ladder from `image_budget.dart`, so the guarantee
/// ("never upload more than [kProductImageMaxBytes]") does not depend on the platform.
///
/// ```dart
/// final upload = await compressProductImage(picked, sourceContentType: 'image/jpeg');
/// await dio.put(path, data: upload.bytes,
///     options: Options(contentType: upload.contentType));
/// ```
library;

export 'image_budget.dart';
export 'image_compress_io.dart'
    if (dart.library.js_interop) 'image_compress_web.dart';
