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
// Ringing up a weighed item, the way a cashier does: scan, read the scale,
// and see what reaches the basket.
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

class _Till implements HttpClientAdapter {
  final Map<String, String> compliance = {};

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    var body = '{"data":[]}';
    final parts = o.path.split('/');
    if (o.path.contains('/catalog/variants/by-barcode/')) {
      final code = parts.last;
      body = '{"data":{"variantId":"v-$code","sku":"$code","productName":"Item $code"}}';
    } else if (o.path.contains('/prices/resolve')) {
      body = '{"data":{"unitPrice":12.0,"currency":"GBP"}}';
    } else if (o.path.endsWith('/age-check')) {
      body = '{"data":{"restricted":false}}';
    } else if (o.path.endsWith('/compliance')) {
      body = compliance[parts[parts.length - 2]] ?? '{"data":{"soldBy":"EACH"}}';
    }
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

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
            StoreInfo(id: 'store-1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
  return till;
}

Future<void> _scan(WidgetTester tester, String code) async {
  await tester.enterText(find.widgetWithText(TextField, 'Scan barcode or type SKU…'), code);
  await tester.testTextInput.receiveAction(TextInputAction.done);
  await tester.pumpAndSettle();
}

List<PosLine> _basket(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first))
        .read(posCartProvider);

const _byWeight = '{"data":{"soldBy":"WEIGHT","netContentUom":"KG","catchWeight":false}}';

void main() {
  testWidgets('an item sold by weight asks for the scale reading before it is added',
      (tester) async {
    final till = await _pump(tester);
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');

    expect(find.text('Enter the weight'), findsOneWidget);
    expect(_basket(tester), isEmpty);

    await tester.enterText(find.widgetWithText(TextField, 'Weight (kg)'), '0.375');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Add')));
    await tester.pumpAndSettle();

    final line = _basket(tester).single;
    expect(line.qty, 0.375);
    expect(line.measured, isTrue);
    expect(find.text('0.375 kg'), findsOneWidget);
  });

  testWidgets('cancelling the reading keeps the item out', (tester) async {
    final till = await _pump(tester);
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Cancel')));
    await tester.pumpAndSettle();
    expect(_basket(tester), isEmpty);
  });

  testWidgets('a weighed line is weighed again, not stepped by one', (tester) async {
    final till = await _pump(tester);
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');
    await tester.enterText(find.widgetWithText(TextField, 'Weight (kg)'), '0.375');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Add')));
    await tester.pumpAndSettle();

    // No ± on a weighed line: adding "one" to 0.375 kg means nothing.
    expect(find.byTooltip('Increase quantity'), findsNothing);

    await tester.tap(find.text('0.375 kg'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Weight (kg)'), '0.410');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Update')));
    await tester.pumpAndSettle();
    expect(_basket(tester).single.qty, 0.41);
  });

  testWidgets("an item the till can't tell how to sell is kept out", (tester) async {
    final till = await _pump(tester);
    till.compliance['v-MYSTERY'] = '{"data":{}}';
    await _scan(tester, 'MYSTERY');

    expect(find.text('Enter the weight'), findsNothing);
    expect(_basket(tester), isEmpty);
    expect(find.textContaining("Couldn't tell how this item is sold"), findsOneWidget);
  });
}
