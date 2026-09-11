import 'dart:js_interop';

// ignore: avoid_web_libraries_in_flutter
import 'package:web/web.dart' as web;

import 'pos_receipt_data.dart';

/// Web: render the receipt into a blob and open it in a print-sized popup, which
/// is where the browser's own print dialog takes over.
void openReceiptPrint(PosReceiptData data) {
  final blob = web.Blob(
    [data.toHtml().toJS].toJS,
    web.BlobPropertyBag(type: 'text/html'),
  );
  final url = web.URL.createObjectURL(blob);
  web.window.open(url, '_blank', 'width=420,height=700,menubar=no,toolbar=no');
  // Revoke after a short delay so the browser has time to load the page.
  Future.delayed(const Duration(seconds: 10), () => web.URL.revokeObjectURL(url));
}
