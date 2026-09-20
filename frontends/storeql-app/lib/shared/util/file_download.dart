/// Hand a generated file (a CSV export, say) to the user.
///
/// Import this; the right implementation is picked per platform, the same way
/// `image_compress.dart` and `pos_receipt.dart` do it. The web build triggers a
/// browser download; everything else gets a documented no-op.
library;

export 'file_download_io.dart'
    if (dart.library.js_interop) 'file_download_web.dart';
