import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/einvoice_transport_dialog.dart';
import 'package:shelf_app/features/admin/sales_invoices_dialog.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The e-invoicing transport seam. The tile says where invoices leave and what
// the business's country asks for; the dialog offers only the providers this
// deployment can use, says why the others cannot be chosen, and sends the
// choice once; a refusal comes back in the server's words. On a document, the
// newest attempt is shown in a few words, and "Send" or "Send again" appears
// only where a send can do anything — and does one request however fast it is
// tapped.
// ---------------------------------------------------------------------------

Map<String, dynamic> _settings({String network = 'NONE', String? provider}) => {
      'network': network,
      'provider': provider,
      'providerAccount': null,
      'updatedAt': null,
      'senderAddress': '0088:5790000435975',
      'suggestedNetwork': 'PEPPOL',
      'networks': ['PEPPOL', 'FR_PDP', 'KSEF', 'IRP'],
      'providers': {
        'PEPPOL': ['ACCESS_POINT', 'SIMULATED'],
        'FR_PDP': ['SIMULATED'],
        'KSEF': ['SIMULATED'],
        'IRP': ['SIMULATED'],
      },
      'available': {
        'PEPPOL': ['SIMULATED'],
        'FR_PDP': ['SIMULATED'],
        'KSEF': ['SIMULATED'],
        'IRP': ['SIMULATED'],
      },
    };

Map<String, dynamic> _doc(String id, {Map<String, dynamic>? transmission}) => {
      'id': id,
      'orderId': 'o-1',
      'kind': 'INVOICE',
      'typeCode': '380',
      'fullNumber': 'INV/2026/00000${id.substring(2)}',
      'issueDate': '2026-09-16',
      'buyerName': 'Cafe Leeds Ltd',
      'buyerVatId': 'GB555555555',
      'currency': 'GBP',
      'netAmount': 10.0,
      'vatAmount': 2.0,
      'payableAmount': 12.0,
      'peppol': true,
      'formats': ['UBL', 'CII', 'FACTURX'],
      'irpProblems': <String>[],
      'transmission': transmission,
    };

Map<String, dynamic> _tx(String status, {String? ref, String? detail}) => {
      'id': 't-1',
      'invoiceId': 'i-1',
      'network': 'PEPPOL',
      'provider': 'SIMULATED',
      'status': status,
      'attempts': 1,
      'receiver': '9932:GB555555555',
      'providerRef': ref,
      'detail': detail,
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> settings = _settings();
  int saveStatus = 200;
  String saveRefusal =
      '{"error":{"code":"EINVOICE_SENDER_ADDRESS_MISSING","message":"Peppol needs the business\'s own electronic address; set it on the tenant first"}}';
  List<Map<String, dynamic>> documents = [];
  int sendStatus = 200;
  Map<String, dynamic> sendAnswer = _tx('ACCEPTED', ref: 'SIM-1');
  Completer<void>? gate;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    // The gate holds only the send: the reads before it must finish.
    if (gate != null && o.method == 'POST') await gate!.future;
    final path = o.path;
    if (o.method == 'GET' && path.endsWith('/admin/einvoicing/transport')) {
      return jsonResponse('{"data":${jsonEncode(settings)}}');
    }
    if (o.method == 'PUT' && path.endsWith('/admin/einvoicing/transport')) {
      if (saveStatus != 200) return jsonResponse(saveRefusal, saveStatus);
      final body = (o.data is String ? jsonDecode(o.data as String) : o.data)
          as Map<String, dynamic>;
      settings = {
        ..._settings(
            network: body['network'] as String,
            provider: body['provider'] as String?),
        'providerAccount': body['providerAccount']
      };
      return jsonResponse('{"data":${jsonEncode(settings)}}');
    }
    if (o.method == 'GET' && path.endsWith('/admin/orders/o-1/invoices')) {
      return jsonResponse('{"data":${jsonEncode(documents)}}');
    }
    if (o.method == 'POST' &&
        path.endsWith('/admin/sales-invoices/i-1/transmissions')) {
      if (sendStatus != 200) {
        return jsonResponse(
            '{"error":{"code":"EINVOICE_ALREADY_SENT","message":"INV/2026/000001 is accepted as SIM-1; a document is sent again only after the network refused it"}}',
            sendStatus);
      }
      documents = [_doc('i-1', transmission: sendAnswer)];
      return jsonResponse('{"data":${jsonEncode(sendAnswer)}}');
    }
    return jsonResponse(
        '{"error":{"code":"NOT_FOUND","message":"no route ${o.method} $path"}}',
        404);
  }
}

Future<_Server> _pumpTile(WidgetTester tester, {_Server? server}) async {
  final s = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = s;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: const MaterialApp(
      home: Scaffold(body: TransportSettingsTile(canEdit: true)),
    ),
  ));
  await tester.pumpAndSettle();
  return s;
}

Future<_Server> _pumpInvoices(WidgetTester tester,
    {required _Server server}) async {
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => const OrderInvoicesDialog(orderId: 'o-1'),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return server;
}

List<RequestOptions> _of(_Server s, String method) =>
    s.requests.where((r) => r.method == method).toList();

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data)
        as Map<String, dynamic>;

void main() {
  setUpAll(initializeDateFormatting);

  group('the tile and its dialog', () {
    testWidgets(
        'says invoices are not sent, what the country asks for, and lets management choose',
        (tester) async {
      final server = await _pumpTile(tester);
      expect(find.text('Invoices are issued and downloaded, not sent'),
          findsOneWidget);
      expect(
          find.textContaining('Your country asks for Peppol'), findsOneWidget);

      await tester.tap(find.byKey(const Key('einvoice-transport-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('einvoice-transport-network')));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('what your country asks for').last);
      await tester.pumpAndSettle();
      // Only the simulated provider can be chosen here; the access point says why not.
      expect(
          find.textContaining(
              'ACCESS_POINT is deployed but has no credentials'),
          findsOneWidget);
      expect(find.textContaining('nothing leaves the platform'), findsWidgets);
      await tester.enterText(
          find.byKey(const Key('einvoice-transport-account')), 'LE-1');
      await tester.tap(find.byKey(const Key('einvoice-transport-save')));
      await tester.pumpAndSettle();

      final puts = _of(server, 'PUT');
      expect(puts, hasLength(1));
      expect(
          puts.single.path, endsWith('/order-svc/admin/einvoicing/transport'));
      expect(_body(puts.single), {
        'network': 'PEPPOL',
        'provider': 'SIMULATED',
        'providerAccount': 'LE-1'
      });
      expect(find.byKey(const Key('einvoice-transport-save')), findsNothing);
      expect(find.text('Invoices leave over Peppol (access point)'),
          findsOneWidget);
      expect(find.textContaining('simulated: nothing leaves the platform'),
          findsOneWidget);
    });

    testWidgets(
        "a refusal is shown in the server's words, and the dialog stays",
        (tester) async {
      final server = _Server()..saveStatus = 409;
      await _pumpTile(tester, server: server);
      await tester.tap(find.byKey(const Key('einvoice-transport-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('einvoice-transport-network')));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('what your country asks for').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('einvoice-transport-save')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('einvoice-transport-error')), findsOneWidget);
      expect(
          find.textContaining(
              "Peppol needs the business's own electronic address"),
          findsOneWidget);
      expect(find.byKey(const Key('einvoice-transport-save')), findsOneWidget);
    });

    testWidgets('choosing no network sends only the network', (tester) async {
      final server = _Server()
        ..settings = _settings(network: 'KSEF', provider: 'SIMULATED');
      await _pumpTile(tester, server: server);
      expect(find.text('Invoices leave over Poland — KSeF'), findsOneWidget);
      await tester.tap(find.byKey(const Key('einvoice-transport-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('einvoice-transport-network')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Not sent — issued and downloaded only').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('einvoice-transport-save')));
      await tester.pumpAndSettle();
      expect(_body(_of(server, 'PUT').single), {'network': 'NONE'});
    });
  });

  group('on a document', () {
    testWidgets(
        'the newest attempt is told in a few words, and only a refused one offers Send again',
        (tester) async {
      final server = _Server()
        ..settings = _settings(network: 'PEPPOL', provider: 'SIMULATED')
        ..documents = [
          _doc('i-1',
              transmission: _tx('REJECTED',
                  detail: 'the network knows no participant 9932:X')),
          _doc('i-2', transmission: _tx('ACCEPTED', ref: 'SIM-2')),
          _doc('i-3'),
        ];
      await _pumpInvoices(tester, server: server);
      expect(
          find.text(
              'Refused by PEPPOL: the network knows no participant 9932:X'),
          findsOneWidget);
      expect(find.text('Delivered over PEPPOL · SIM-2'), findsOneWidget);
      expect(find.byKey(const Key('sales-invoice-send-i-1')), findsOneWidget);
      expect(find.text('Send again'), findsOneWidget);
      expect(find.byKey(const Key('sales-invoice-send-i-2')), findsNothing);
      // Never sent, and sendable: a plain Send.
      expect(find.byKey(const Key('sales-invoice-send-i-3')), findsOneWidget);
      expect(find.text('Send'), findsOneWidget);
    });

    testWidgets(
        'sends once however fast it is tapped, and shows what the network said',
        (tester) async {
      final server = _Server()
        ..settings = _settings(network: 'PEPPOL', provider: 'SIMULATED')
        ..documents = [
          _doc('i-1',
              transmission: _tx('REJECTED', detail: 'unknown participant'))
        ]
        ..gate = Completer<void>();
      await _pumpInvoices(tester, server: server);
      await tester.tap(find.byKey(const Key('sales-invoice-send-i-1')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('sales-invoice-send-i-1')),
          warnIfMissed: false);
      await tester.pump();
      server.gate!.complete();
      server.gate = null;
      await tester.pumpAndSettle();
      expect(_of(server, 'POST'), hasLength(1));
      expect(_of(server, 'POST').single.path,
          endsWith('/order-svc/admin/sales-invoices/i-1/transmissions'));
      expect(find.text('Delivered over PEPPOL · SIM-1'), findsOneWidget);
      expect(find.byKey(const Key('sales-invoice-send-i-1')), findsNothing);
    });

    testWidgets("the server's refusal is shown in its words", (tester) async {
      final server = _Server()
        ..settings = _settings(network: 'PEPPOL', provider: 'SIMULATED')
        ..documents = [
          _doc('i-1',
              transmission: _tx('FAILED', detail: 'gave up after 8 attempts'))
        ]
        ..sendStatus = 409;
      await _pumpInvoices(tester, server: server);
      expect(find.text('Not sent: gave up after 8 attempts'), findsOneWidget);
      await tester.tap(find.byKey(const Key('sales-invoice-send-i-1')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('sales-invoice-error')), findsOneWidget);
      expect(find.textContaining('sent again only after the network refused'),
          findsOneWidget);
    });

    testWidgets('with no network chosen there is nothing to send',
        (tester) async {
      final server = _Server()..documents = [_doc('i-1')];
      await _pumpInvoices(tester, server: server);
      expect(find.text('Invoice INV/2026/000001'), findsOneWidget);
      expect(find.byKey(const Key('sales-invoice-send-i-1')), findsNothing);
      expect(find.text('Send'), findsNothing);
    });
  });
}
