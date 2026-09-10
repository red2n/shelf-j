import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';
import 'package:shelf_app/features/pos/cart_screen.dart';
import 'package:shelf_app/features/pos/pos_providers.dart';
import 'package:shelf_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// The age check at the register, driven the way a cashier drives it: scan a
// barcode, and see whether the item reaches the sale.
//
// product-svc has known which items are age-restricted since 69f2d03, and the
// till never asked. Every test here is about the moment between the scan and
// the item landing in the basket.
// ---------------------------------------------------------------------------

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Routes the three calls a scan makes. Age-check answers are set per variant.
class _Till implements HttpClientAdapter {
  final Map<String, (int, String)> ageCheck = {};
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    var status = 200;
    var body = '{"data":[]}';
    if (o.path.contains('/catalog/variants/by-barcode/')) {
      final code = o.path.split('/').last;
      body = '{"data":{"variantId":"v-$code","sku":"$code","productName":"Item $code"}}';
    } else if (o.path.contains('/prices/resolve')) {
      body = '{"data":{"unitPrice":8.0,"currency":"GBP"}}';
    } else if (o.path.endsWith('/age-check')) {
      final variant = o.path.split('/')[o.path.split('/').length - 2];
      final answer = ageCheck[variant] ?? (200, '{"data":{"restricted":false}}');
      status = answer.$1;
      body = answer.$2;
    }
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _alcohol18 =
    (200, '{"data":{"restricted":true,"category":"ALCOHOL","minimumAge":18,"country":"GB","tenantOverride":false}}');

Future<_Till> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final till = _Till();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = till;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(
                id: 'store-1',
                name: 'High Street',
                code: 'HS',
                type: 'STORE',
                status: 'ACTIVE',
                country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
  return till;
}

Future<void> _scan(WidgetTester tester, String code) async {
  await tester.enterText(
      find.widgetWithText(TextField, 'Scan barcode or type SKU…'), code);
  await tester.testTextInput.receiveAction(TextInputAction.done);
  await tester.pumpAndSettle();
}

List<PosLine> _basket(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first))
        .read(posCartProvider);

void main() {
  testWidgets('an unrestricted item goes straight in, with no prompt',
      (tester) async {
    await _pump(tester);
    await _scan(tester, 'BREAD');

    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), hasLength(1));
  });

  testWidgets('a restricted item waits for the check before it is added',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    expect(find.text('Age-restricted item'), findsOneWidget);
    // Not in the basket while the cashier is still deciding.
    expect(_basket(tester), isEmpty);

    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();
    expect(_basket(tester), hasLength(1));
  });

  testWidgets('refusing the sale keeps the item out', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    await tester.tap(find.text('Refuse sale'));
    await tester.pumpAndSettle();
    expect(_basket(tester), isEmpty);
  });

  testWidgets('the store country is the one whose law is asked about',
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    await _scan(tester, 'WINE');

    final ask = till.requests.lastWhere((r) => r.path.endsWith('/age-check'));
    expect(ask.queryParameters['country'], 'GB');
  });

  testWidgets('one check covers the sale for the same age', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.ageCheck['v-BEER'] = _alcohol18;

    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    // A customer already shown to be 18 is not asked again for the next bottle.
    await _scan(tester, 'BEER');
    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), hasLength(2));
  });

  testWidgets('a higher age asks again', (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = _alcohol18;
    till.ageCheck['v-LOTTO'] = (
      200,
      '{"data":{"restricted":true,"category":"ALCOHOL","minimumAge":21,"country":"GB","tenantOverride":true}}'
    );

    await _scan(tester, 'WINE');
    await tester.tap(find.text('Checked — 18+'));
    await tester.pumpAndSettle();

    await _scan(tester, 'LOTTO');
    expect(find.text('Checked — 21+'), findsOneWidget);
  });

  testWidgets("a check that can't be made keeps the item out and says why",
      (tester) async {
    final till = await _pump(tester);
    till.ageCheck['v-WINE'] = (503, '{"error":{"code":"UPSTREAM_UNAVAILABLE","details":[]}}');
    await _scan(tester, 'WINE');

    // No prompt to click through: there is nothing the cashier could confirm.
    expect(find.text('Age-restricted item'), findsNothing);
    expect(_basket(tester), isEmpty);
    expect(find.textContaining("Couldn't check the age restriction"), findsOneWidget);
  });
}
