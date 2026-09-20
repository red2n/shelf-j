/// Printing a receipt the way this till is set up to (09.12).
///
/// One entry point for every place the till prints — the sale, the reprint,
/// the offline copy, the test page — that turns the receipt into what the
/// printer settings ask for: the browser's dialog, ESC/POS to a network
/// printer or a print bridge, or a file. The transports are injected so the
/// tests drive the dispatch without a printer, a browser or a disk.
library;

import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../shared/util/file_download.dart';
import 'pos_printer_settings.dart';
import 'pos_receipt.dart';
import 'pos_receipt_escpos.dart';

/// How a receipt was produced, for the record the server keeps of it.
enum ReceiptMethod {
  print('PRINT'),
  thermal('THERMAL'),
  save('SAVE'),
  none('NONE');

  const ReceiptMethod(this.code);
  final String code;
}

class PrintOutcome {
  final bool ok;
  final ReceiptMethod method;
  final String message;
  final String? savedPath;

  const PrintOutcome({
    required this.ok,
    required this.method,
    required this.message,
    this.savedPath,
  });
}

typedef NetworkSender = Future<void> Function(Uint8List bytes, String host, int port);
typedef BridgeSender = Future<void> Function(String url, Uint8List bytes);
typedef BrowserOpener = void Function(PosReceiptData data);
typedef FileSaver = Future<String?> Function(String filename, String content);

class ReceiptPrinter {
  ReceiptPrinter({
    required this.settings,
    NetworkSender? network,
    BridgeSender? bridge,
    BrowserOpener? browser,
    FileSaver? saver,
    Dio? bridgeDio,
  })  : _network = network ?? ((b, h, p) => sendToNetworkPrinter(b, h, p)),
        _bridge = bridge ?? ((url, b) => sendToPrintBridge(bridgeDio ?? Dio(), url, b)),
        _browser = browser ?? openReceiptPrint,
        _saver = saver ?? ((name, content) => saveTextFile(name, content, mimeType: 'text/html'));

  final PrinterSettings settings;
  final NetworkSender _network;
  final BridgeSender _bridge;
  final BrowserOpener _browser;
  final FileSaver _saver;

  /// The receipt as this till's printer takes it.
  Uint8List encode(PosReceiptData data, {bool? openDrawer}) => EscPosReceipt(
        paper: settings.paper,
        openDrawer: openDrawer ?? settings.openDrawer,
      ).encode(data);

  /// Produces the receipt. Never throws: the outcome says what happened, in
  /// words the cashier can read, because the customer is standing there.
  Future<PrintOutcome> print(PosReceiptData data, {bool? openDrawer}) async {
    try {
      switch (settings.mode) {
        case PrinterMode.browser:
          _browser(data);
          return const PrintOutcome(
              ok: true, method: ReceiptMethod.print, message: 'Receipt sent to the browser.');
        case PrinterMode.network:
          await _network(encode(data, openDrawer: openDrawer), settings.host.trim(), settings.port);
          return PrintOutcome(
              ok: true,
              method: ReceiptMethod.thermal,
              message: 'Receipt printed on ${settings.host.trim()}.');
        case PrinterMode.bridge:
          await _bridge(settings.bridgeUrl.trim(), encode(data, openDrawer: openDrawer));
          return const PrintOutcome(
              ok: true, method: ReceiptMethod.thermal, message: 'Receipt sent to the printer.');
        case PrinterMode.save:
          final path = await _saver(_filename(data), data.toHtml());
          return PrintOutcome(
              ok: true,
              method: ReceiptMethod.save,
              message: path == null ? 'Receipt downloaded.' : 'Receipt saved to $path.',
              savedPath: path);
        case PrinterMode.none:
          return const PrintOutcome(
              ok: true, method: ReceiptMethod.none, message: 'No paper receipt on this till.');
      }
    } catch (e) {
      return PrintOutcome(
          ok: false, method: ReceiptMethod.none, message: 'Could not print: ${_why(e)}');
    }
  }

  /// Keeps a copy of the receipt as a file, whatever the printer mode.
  Future<PrintOutcome> save(PosReceiptData data) async {
    try {
      final path = await _saver(_filename(data), data.toHtml());
      return PrintOutcome(
          ok: true,
          method: ReceiptMethod.save,
          message: path == null ? 'Receipt downloaded.' : 'Receipt saved to $path.',
          savedPath: path);
    } catch (e) {
      return PrintOutcome(
          ok: false, method: ReceiptMethod.none, message: 'Could not save: ${_why(e)}');
    }
  }

  static String _filename(PosReceiptData d) =>
      'receipt-${(d.fiscalNumber ?? d.shortId).replaceAll(RegExp(r'[^A-Za-z0-9-]'), '_')}.html';

  static String _why(Object e) {
    if (e is DioException) {
      final status = e.response?.statusCode;
      if (status != null) return 'the print bridge answered $status.';
      return 'the print bridge could not be reached.';
    }
    final s = e.toString();
    return s.startsWith('Exception: ') ? s.substring(11) : s.replaceFirst(RegExp(r'^\w+Error: '), '');
  }
}

/// Posts ESC/POS bytes to a print bridge: any agent on the LAN that accepts
/// `application/octet-stream` and hands the bytes to the printer. A browser
/// till cannot open a socket, so this is how it reaches thermal paper.
Future<void> sendToPrintBridge(Dio dio, String url, Uint8List bytes) async {
  await dio.post<void>(
    url,
    data: Stream.fromIterable([bytes]),
    options: Options(
      headers: {Headers.contentTypeHeader: 'application/octet-stream', Headers.contentLengthHeader: bytes.length},
      sendTimeout: const Duration(seconds: 5),
      receiveTimeout: const Duration(seconds: 5),
      responseType: ResponseType.plain,
    ),
  );
}

/// Builds a printer for given settings. Overridden in tests so a test page or
/// a sale reaches a fake transport rather than a socket.
final receiptPrinterFactoryProvider = Provider<ReceiptPrinter Function(PrinterSettings)>(
  (ref) => (settings) => ReceiptPrinter(settings: settings),
);

/// The printer for this till, rebuilt whenever its settings change.
final receiptPrinterProvider = Provider<ReceiptPrinter>((ref) {
  final settings = ref.watch(printerSettingsProvider);
  return ref.watch(receiptPrinterFactoryProvider)(settings);
});
