import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/storage/app_storage.dart';
import 'package:shelf_app/features/pos/pos_printer_settings.dart';
import 'package:shelf_app/features/pos/pos_printer_settings_dialog.dart';
import 'package:shelf_app/features/pos/pos_receipt_printer.dart';

// The till's printer dialog (09.12): the modes this build can perform, the
// fields each needs, the refusals before anything is saved, a test page that
// reaches the transport, and settings that survive on the device.

class _MemStorage implements AppStorage {
  final Map<String, String> map = {};
  @override
  Future<String?> read({required String key}) async => map[key];
  @override
  Future<void> write({required String key, required String? value}) async {
    if (value == null) {
      map.remove(key);
    } else {
      map[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => map.remove(key);
  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      map.removeWhere((k, _) => !keep.contains(k));
}

class _Sent {
  final List<(String, int, int)> network = [];
  final List<Uint8List> bytes = [];
}

Future<(_MemStorage, _Sent, PrinterSettingsNotifier)> _pump(WidgetTester tester,
    {bool networkFails = false}) async {
  tester.view.physicalSize = const Size(900, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final storage = _MemStorage();
  final sent = _Sent();
  final notifier = PrinterSettingsNotifier(storage: storage);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      printerSettingsProvider.overrideWith((ref) => notifier),
      receiptPrinterFactoryProvider.overrideWithValue((s) => ReceiptPrinter(
            settings: s,
            network: (b, h, p) async {
              if (networkFails) throw Exception('Connection refused');
              sent.network.add((h, p, b.length));
              sent.bytes.add(b);
            },
            bridge: (u, b) async => sent.bytes.add(b),
            saver: (n, c) async => '/docs/$n',
          )),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () =>
                showDialog<void>(context: context, builder: (_) => const PrinterSettingsDialog()),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return (storage, sent, notifier);
}

Future<void> _chooseMode(WidgetTester tester, PrinterMode mode) async {
  await tester.tap(find.byKey(const Key('printer-mode')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(mode.label).last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the modes offered are the ones this build can perform', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const Key('printer-mode')));
    await tester.pumpAndSettle();
    for (final m in PrinterMode.available) {
      expect(find.text(m.label), findsWidgets);
    }
    // On the Dart VM the build is not the web: no browser dialog on offer.
    expect(find.text(PrinterMode.browser.label), findsNothing);
  });

  testWidgets('a network printer without a host is refused before anything is saved',
      (tester) async {
    final (storage, _, notifier) = await _pump(tester);
    await _chooseMode(tester, PrinterMode.network);
    await tester.enterText(find.byKey(const Key('printer-port')), '99999');
    await tester.tap(find.byKey(const Key('printer-save')));
    await tester.pumpAndSettle();
    expect(find.text('The printer needs a host name or IP address.'), findsOneWidget);
    expect(find.text('The port must be between 1 and 65535.'), findsOneWidget);
    expect(storage.map, isEmpty);
    expect(notifier.state.mode, PrinterMode.defaultMode);
    expect(find.text('Receipt printer'), findsOneWidget);
  });

  testWidgets('valid settings are kept on the device and the dialog closes', (tester) async {
    final (storage, _, notifier) = await _pump(tester);
    await _chooseMode(tester, PrinterMode.network);
    await tester.enterText(find.byKey(const Key('printer-host')), ' 10.0.0.5 ');
    await tester.enterText(find.byKey(const Key('printer-port')), '9100');
    await tester.tap(find.text('58 mm'));
    await tester.tap(find.byKey(const Key('printer-drawer')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('printer-save')));
    await tester.pumpAndSettle();
    expect(find.text('Receipt printer'), findsNothing);
    expect(notifier.state.mode, PrinterMode.network);
    expect(notifier.state.host, '10.0.0.5');
    expect(notifier.state.paper.name, 'mm58');
    expect(notifier.state.openDrawer, isTrue);
    expect(storage.map['pos_printer'], contains('10.0.0.5'));
    expect(find.text('Printer settings saved on this till.'), findsOneWidget);
  });

  testWidgets('the test page reaches the printer with ESC/POS bytes and never kicks the drawer',
      (tester) async {
    final (_, sent, _) = await _pump(tester);
    await _chooseMode(tester, PrinterMode.network);
    await tester.enterText(find.byKey(const Key('printer-host')), 'printer.local');
    await tester.tap(find.byKey(const Key('printer-drawer')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('printer-test')));
    await tester.pumpAndSettle();
    expect(sent.network, [('printer.local', 9100, sent.bytes.single.length)]);
    expect(sent.bytes.single.sublist(0, 2), [0x1B, 0x40]);
    final bytes = sent.bytes.single;
    var kick = false;
    for (var i = 0; i + 1 < bytes.length; i++) {
      if (bytes[i] == 0x1B && bytes[i + 1] == 0x70) kick = true;
    }
    expect(kick, isFalse);
    expect(find.text('Receipt printed on printer.local.'), findsOneWidget);
  });

  testWidgets('a test page that cannot reach the printer says so, and saves nothing',
      (tester) async {
    final (storage, sent, _) = await _pump(tester, networkFails: true);
    await _chooseMode(tester, PrinterMode.network);
    await tester.enterText(find.byKey(const Key('printer-host')), 'printer.local');
    await tester.tap(find.byKey(const Key('printer-test')));
    await tester.pumpAndSettle();
    expect(find.text('Could not print: Connection refused'), findsOneWidget);
    expect(sent.bytes, isEmpty);
    expect(storage.map, isEmpty);
  });

  testWidgets('a bridge needs an address, and gets the test page when it has one',
      (tester) async {
    final (_, sent, _) = await _pump(tester);
    await _chooseMode(tester, PrinterMode.bridge);
    await tester.enterText(find.byKey(const Key('printer-bridge')), 'nowhere');
    await tester.tap(find.byKey(const Key('printer-test')));
    await tester.pumpAndSettle();
    expect(find.text('The print bridge needs an http or https address.'), findsOneWidget);
    expect(sent.bytes, isEmpty);
    await tester.enterText(find.byKey(const Key('printer-bridge')), 'http://192.168.1.20:9109/print');
    await tester.tap(find.byKey(const Key('printer-test')));
    await tester.pumpAndSettle();
    expect(sent.bytes, hasLength(1));
  });
}
