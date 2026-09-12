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

const _counterScale =
    '{"id":"scale-1","identifier":"Deli scale","serialNumber":"SN-1","kind":"COUNTER","status":"IN_SERVICE","standing":"CERTIFIED","certified":true}';
const _secondScale =
    '{"id":"scale-2","identifier":"Checkout 3 scale","serialNumber":"SN-2","kind":"COUNTER","status":"IN_SERVICE","standing":"CERTIFIED","certified":true}';
const _labellingScale =
    '{"id":"label-1","identifier":"Deli labeller","serialNumber":"SN-L","kind":"LABELLING","status":"IN_SERVICE","standing":"CERTIFIED","certified":true,'
    '"labelScheme":"{\\"prefixes\\":[\\"20\\"],\\"itemDigits\\":5,\\"valueKind\\":\\"PRICE\\",\\"valueDecimals\\":2}"}';

class _Till implements HttpClientAdapter {
  final Map<String, String> compliance = {};
  final List<RequestOptions> requests = [];

  /// What the register answers for ?certified=true. One certified counter
  /// scale by default, which the till picks without asking.
  String certified = '[$_counterScale]';
  bool registerDown = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    var body = '{"data":[]}';
    var status = 200;
    final parts = o.path.split('/');
    if (o.path.endsWith('/weighing-instruments')) {
      if (registerDown) {
        status = 503;
        body = '{"error":{"code":"SERVICE_UNAVAILABLE","message":"down"}}';
      } else {
        body = '{"data":$certified}';
      }
    } else if (o.path.contains('/catalog/variants/by-barcode/')) {
      final code = parts.last;
      body = '{"data":{"variantId":"v-$code","sku":"$code","productName":"Item $code"}}';
    } else if (o.path.contains('/prices/resolve')) {
      body = '{"data":{"unitPrice":12.0,"currency":"GBP"}}';
    } else if (o.path.endsWith('/age-check')) {
      body = '{"data":{"restricted":false}}';
    } else if (o.path.endsWith('/compliance')) {
      body = compliance[parts[parts.length - 2]] ?? '{"data":{"soldBy":"EACH"}}';
    }
    return ResponseBody.fromString(body, status, headers: {
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

  // ── the certified scale (Weights and Measures Act 1985 s.11) ────────────────

  testWidgets('a weighed line records the certified scale it was weighed on',
      (tester) async {
    final till = await _pump(tester);
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');
    await tester.enterText(find.widgetWithText(TextField, 'Weight (kg)'), '0.375');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Add')));
    await tester.pumpAndSettle();

    expect(_basket(tester).single.weighingInstrumentId, 'scale-1');
  });

  testWidgets('with no certified scale the till refuses to sell by weight', (tester) async {
    final till = await _pump(tester);
    till.certified = '[]';
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');

    expect(find.text('Enter the weight'), findsNothing);
    expect(_basket(tester), isEmpty);
    expect(find.textContaining('no scale certified for trade'), findsOneWidget);
  });

  testWidgets('an uncertified scale is not offered: out of service means out of trade',
      (tester) async {
    final till = await _pump(tester);
    // The register's ?certified=true never returns one; the till trusts that filter.
    till.certified = '[]';
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');
    expect(_basket(tester), isEmpty);
  });

  testWidgets('when the register cannot be read the till refuses rather than guesses',
      (tester) async {
    final till = await _pump(tester);
    till.registerDown = true;
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');

    expect(find.text('Enter the weight'), findsNothing);
    expect(_basket(tester), isEmpty);
    expect(find.textContaining("Couldn't check which scales are certified"), findsOneWidget);
  });

  testWidgets('two certified scales: the cashier says which one', (tester) async {
    final till = await _pump(tester);
    till.certified = '[$_counterScale,$_secondScale]';
    till.compliance['v-CHEDDAR'] = _byWeight;
    await _scan(tester, 'CHEDDAR');

    expect(find.text('Which scale?'), findsOneWidget);
    await tester.tap(find.text('Checkout 3 scale'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Weight (kg)'), '1.250');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Add')));
    await tester.pumpAndSettle();
    expect(_basket(tester).single.weighingInstrumentId, 'scale-2');
  });

  testWidgets('an item sold by the each never asks for a scale', (tester) async {
    final till = await _pump(tester);
    till.certified = '[]';
    await _scan(tester, 'BREAD');
    expect(_basket(tester), hasLength(1));
    expect(_basket(tester).single.weighingInstrumentId, isNull);
  });

  // ── a labelling scale's barcode ────────────────────────────────────────────

  String withCheck(String twelve) {
    var sum = 0;
    for (var i = 0; i < 12; i++) {
      final d = twelve.codeUnitAt(i) - 48;
      sum += i.isEven ? d : d * 3;
    }
    return twelve + ((10 - (sum % 10)) % 10).toString();
  }

  testWidgets('a price-embedded label from a certified labelling scale rings up already weighed',
      (tester) async {
    final till = await _pump(tester);
    till.certified = '[$_counterScale,$_labellingScale]';
    // 20 + item 12345 + £4.50 + check; the item's barcode in the catalogue is its PLU 12345,
    // priced at £12.00/kg by the fake price resolver.
    till.compliance['v-12345'] = _byWeight;
    await _scan(tester, withCheck('201234500450'));

    expect(find.text('Enter the weight'), findsNothing);
    final line = _basket(tester).single;
    expect(line.qty, 0.375);
    expect(line.soldBy, 'WEIGHT');
    expect(line.weighingInstrumentId, 'label-1');
    expect(till.requests.where((r) => r.path.endsWith('/by-barcode/12345')).length, 1);
  });

  testWidgets('a label whose check digit is wrong is treated as an ordinary code',
      (tester) async {
    final till = await _pump(tester);
    till.certified = '[$_counterScale,$_labellingScale]';
    final good = withCheck('201234500450');
    final bad = good.substring(0, 12) + ((int.parse(good[12]) + 1) % 10).toString();
    await _scan(tester, bad);
    // Looked up as a barcode in its own right, not parsed as a label.
    expect(till.requests.where((r) => r.path.endsWith('/by-barcode/$bad')).length, 1);
  });

  testWidgets('a label is not read when the labelling scale is not certified', (tester) async {
    final till = await _pump(tester);
    till.certified = '[$_counterScale]';
    final code = withCheck('201234500450');
    await _scan(tester, code);
    expect(till.requests.where((r) => r.path.endsWith('/by-barcode/$code')).length, 1);
  });
}
