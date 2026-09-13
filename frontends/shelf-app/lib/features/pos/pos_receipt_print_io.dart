import 'dart:io';

import 'package:flutter/foundation.dart';

import 'pos_receipt_data.dart';

/// Non-web half of the receipt seam.
///
/// The browser print dialog has no analogue on Android, iOS or the desktop,
/// so [openReceiptPrint] stays a documented no-op there: those builds print
/// through [sendToNetworkPrinter] (ESC/POS over a socket, 09.12), a print
/// bridge, or save the receipt as a file — whichever the till's printer
/// settings say. This file exists so a non-web build gets nothing happening
/// rather than a crash; the previous code reached `package:web`
/// unconditionally, so the first completed sale on a phone would have thrown.
void openReceiptPrint(PosReceiptData data) {
  debugPrint('Receipt ${data.shortId}: the browser print dialog is web-only on this build.');
}

/// Sends ESC/POS bytes to a network receipt printer over a raw socket — the
/// port-9100 path every thermal printer speaks. Bounded: a printer that is
/// off or unplugged fails within [timeout] rather than hanging the till.
Future<void> sendToNetworkPrinter(
  Uint8List bytes,
  String host,
  int port, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  final socket = await Socket.connect(host, port, timeout: timeout);
  try {
    socket.add(bytes);
    await socket.flush().timeout(timeout);
  } finally {
    socket.destroy();
  }
}
