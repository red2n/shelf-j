import 'dart:js_interop';
import 'dart:typed_data';

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

/// A browser cannot open a raw socket. A web till reaches a thermal printer
/// through a print bridge on the LAN instead (see the printer settings), so
/// this transport says so rather than pretending.
Future<void> sendToNetworkPrinter(
  Uint8List bytes,
  String host,
  int port, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  throw UnsupportedError(
      'A browser cannot reach a network printer directly. Use a print bridge, or the desktop till.');
}
