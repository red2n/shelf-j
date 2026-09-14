import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/product_safety_dialog.dart';

// ---------------------------------------------------------------------------
// Product safety information (01.12): what a business sees it still lacks,
// what it sends, what is refused before sending, and what the server refuses.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Products implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  String sheet = '{"data":{"productId":"p1","recorded":false,"noWarnings":false,"required":true,'
      '"missing":["MANUFACTURER_NAME","MANUFACTURER_ADDRESS","MANUFACTURER_CONTACT","MANUFACTURER_COUNTRY","WARNINGS"]}}';
  int sheetStatus = 200;
  int putStatus = 200;
  String putBody = '{"data":{}}';
  String missing = '{"data":[]}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final (status, body) = o.method == 'PUT'
        ? (putStatus, putBody)
        : o.path.endsWith('/missing')
            ? (200, missing)
            : (sheetStatus, sheet);
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<RequestOptions> puts() => requests.where((r) => r.method == 'PUT').toList();
}

Future<_Products> _open(WidgetTester tester, void Function(_Products) setUp,
    {Widget? home}) async {
  tester.view.physicalSize = const Size(1200, 2200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final api = _Products();
  setUp(api);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = api;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: home ??
            Builder(
              builder: (context) => TextButton(
                onPressed: () => showProductSafetyDialog(context,
                    productId: 'p1', productName: 'Candle'),
                child: const Text('open'),
              ),
            ),
      ),
    ),
  ));
  if (home == null) {
    await tester.tap(find.text('open'));
  }
  await tester.pumpAndSettle();
  return api;
}

Future<void> _enter(WidgetTester tester, String key, String text) =>
    tester.enterText(find.byKey(Key(key)), text);

void main() {
  testWidgets('an unstated product says the law requires it and what is missing', (tester) async {
    final api = await _open(tester, (_) {});

    expect(api.requests.first.path, '/product-svc/admin/products/p1/safety-information');
    expect(find.textContaining('requires this to offer the product online'), findsOneWidget);
    expect(find.textContaining("Still missing: the manufacturer's name"), findsOneWidget);
    expect(find.textContaining('the warnings, or that none apply'), findsOneWidget);
  });

  testWidgets('a statement is filled in, sent trimmed with the country upper-cased, and closes',
      (tester) async {
    final api = await _open(tester, (_) {});
    await _enter(tester, 'safety-manufacturer-name', '  Atelier Lumière SAS ');
    await _enter(tester, 'safety-manufacturer-address', '12 rue de la Paix, Paris');
    await _enter(tester, 'safety-manufacturer-contact', 'securite@lumiere.fr');
    await _enter(tester, 'safety-manufacturer-country', 'fr');
    await _enter(tester, 'safety-warnings', 'Keep away from open flame.');
    await tester.tap(find.byKey(const Key('safety-save')));
    await tester.pumpAndSettle();

    expect(api.puts().single.path, '/product-svc/admin/products/p1/safety-information');
    expect(api.puts().single.data, {
      'manufacturerName': 'Atelier Lumière SAS',
      'manufacturerAddress': '12 rue de la Paix, Paris',
      'manufacturerContact': 'securite@lumiere.fr',
      'manufacturerCountry': 'FR',
      'responsiblePersonName': null,
      'responsiblePersonAddress': null,
      'responsiblePersonContact': null,
      'warnings': 'Keep away from open flame.',
      'noWarnings': false,
    });
    expect(find.byKey(const Key('safety-save')), findsNothing, reason: 'the dialog closed');
  });

  testWidgets('stating that no warnings apply sends that, not the warnings text', (tester) async {
    final api = await _open(tester, (a) => a.sheet =
        '{"data":{"productId":"p1","recorded":true,"manufacturerName":"Acme","warnings":"old text","noWarnings":false,"required":false,"missing":[]}}');
    expect(find.textContaining('Not required in this business'), findsOneWidget);
    expect(find.byKey(const Key('safety-missing')), findsNothing);
    await tester.tap(find.byKey(const Key('safety-no-warnings')));
    await tester.pump();
    expect(tester.widget<TextField>(find.descendant(of: find.byKey(const Key('safety-warnings')), matching: find.byType(TextField))).enabled, isFalse);
    await tester.tap(find.byKey(const Key('safety-save')));
    await tester.pumpAndSettle();

    expect(api.puts().single.data['noWarnings'], true);
    expect(api.puts().single.data['warnings'], isNull);
    expect(api.puts().single.data['manufacturerName'], 'Acme');
  });

  testWidgets('a contact or country that is not one is refused before anything is sent',
      (tester) async {
    final api = await _open(tester, (_) {});
    await _enter(tester, 'safety-manufacturer-contact', 'call us');
    await _enter(tester, 'safety-rp-contact', 'http://rep.example');
    await _enter(tester, 'safety-manufacturer-country', 'D1');
    await tester.tap(find.byKey(const Key('safety-save')));
    await tester.pumpAndSettle();

    expect(find.text('An e-mail address or an https:// link'), findsNWidgets(2));
    expect(find.text('A two-letter country code, such as DE'), findsOneWidget);
    expect(api.puts(), isEmpty);
  });

  testWidgets("the server's refusal is shown in the dialog, which stays open", (tester) async {
    await _open(tester, (a) {
      a.putStatus = 400;
      a.putBody = '{"error":{"code":"PRODUCT_SAFETY_INFORMATION_REQUIRED","message":"An online offer in this business\'s market must show the manufacturer. Missing: MANUFACTURER_ADDRESS","details":["MANUFACTURER_ADDRESS"]}}';
    });
    await _enter(tester, 'safety-manufacturer-name', 'Acme');
    await tester.tap(find.byKey(const Key('safety-save')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('safety-error')), findsOneWidget);
    expect(find.textContaining('Missing: MANUFACTURER_ADDRESS'), findsOneWidget);
    expect(find.byKey(const Key('safety-save')), findsOneWidget);
  });

  testWidgets('a failed read offers to try again and cannot save', (tester) async {
    final api = await _open(tester, (a) {
      a.sheetStatus = 404;
      a.sheet = '{"error":{"code":"PRODUCT_NOT_FOUND","message":"No such product"}}';
    });
    expect(find.text('No such product'), findsOneWidget);
    expect(find.text('Try again'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const Key('safety-save'))).onPressed, isNull);
    expect(api.puts(), isEmpty);
  });

  testWidgets('online products listed before the rule are named with what each lacks', (tester) async {
    await _open(tester, (a) => a.missing =
        '{"data":[{"productId":"old1","name":"Old Candle","missing":["MANUFACTURER_NAME","WARNINGS"]}]}',
        home: const MissingSafetyBanner());
    expect(find.textContaining('1 online product lacks'), findsOneWidget);
    await tester.tap(find.byKey(const Key('missing-safety-banner')));
    await tester.pumpAndSettle();
    expect(find.text('Old Candle'), findsOneWidget);
    expect(find.textContaining("Missing the manufacturer's name; the warnings"), findsOneWidget);
  });

  testWidgets('no banner when nothing is missing', (tester) async {
    await _open(tester, (_) {}, home: const MissingSafetyBanner());
    expect(find.byKey(const Key('missing-safety-banner')), findsNothing);
  });
}
