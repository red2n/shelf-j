/// POS receipts.
///
/// Import this; the right presentation is picked per platform, the same way
/// `shared/util/image_compress.dart` does it:
/// - web opens the rendered HTML in a print-sized popup,
/// - everything else gets a documented no-op (see `pos_receipt_print_io.dart`).
///
/// The receipt's *contents* are platform-free and live in `pos_receipt_data.dart`.
library;

export 'pos_receipt_data.dart';
export 'pos_receipt_print_io.dart'
    if (dart.library.js_interop) 'pos_receipt_print_web.dart';
