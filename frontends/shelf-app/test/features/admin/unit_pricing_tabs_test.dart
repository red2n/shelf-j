import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/format.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/unit_pricing_tabs.dart';

// ---------------------------------------------------------------------------
// Shelf-edge labels and unit-pricing gaps (03.13): what is asked for, what a
// label says at a regular and a promotional price, and what a business is told
// about items that cannot show a unit price.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Api implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  int labelsStatus = 200;
  String labels = '{"data":[]}';
  int gapsStatus = 200;
  String gaps = '{"data":{"required":true,"gaps":[]}}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final (status, body) = o.path.endsWith('/shelf-labels')
        ? (labelsStatus, labels)
        : o.path.endsWith('/unit-pricing/gaps')
            ? (gapsStatus, gaps)
            : o.path.endsWith('/variants/resolve')
                ? (
                    200,
                    '{"data":[{"variantId":"v1","productName":"Rioja 75cl","sku":"RIO-75"},'
                        '{"variantId":"v2","productName":"Loose Carrots","sku":"CAR-KG"},'
                        '{"variantId":"v3","productName":"Gift Box","sku":"GIFT"}]}'
                  )
                : (200, '{"data":[],"meta":{}}');
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Api> _pump(WidgetTester tester, Widget child, void Function(_Api) setUp) async {
  tester.view.physicalSize = const Size(1400, 1800);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final api = _Api();
  setUp(api);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = api;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(home: Scaffold(body: child)),
  ));
  await tester.pumpAndSettle();
  return api;
}

String _gbp(double v) => AppFormat.money(v, currencyCode: 'GBP');

const _labels = '{"data":['
    '{"variantId":"v1","priced":true,"currency":"GBP","regularPrice":1.8,'
    '"regularUnitPrice":{"amount":2.4,"unit":"L","quantity":0.75,"label":"per litre"},'
    '"promotionalPrice":0.9,"promotionalUnitPrice":{"amount":1.2,"unit":"L","quantity":0.75,"label":"per litre"},'
    '"promotionName":"Half price","measureDeclared":true,"unitPriceRequired":true,'
    '"priorPrice":1.8,"priorPriceStatus":"ANNOUNCEABLE","reductionAnnounceable":true},'
    '{"variantId":"v2","priced":true,"currency":"GBP","regularPrice":2.4,"measureDeclared":false,"unitPriceRequired":true},'
    '{"variantId":"v4","priced":true,"currency":"GBP","regularPrice":3.0,"promotionalPrice":2.0,'
    '"promotionName":"Fake sale","measureDeclared":true,"unitPriceRequired":true,'
    '"priorPrice":1.9,"priorPriceStatus":"NOT_LOWER","reductionAnnounceable":false},'
    '{"variantId":"v3","priced":false,"measureDeclared":false,"unitPriceRequired":true}]}';

void main() {
  testWidgets('labels are asked for the variants on the sheet and show both prices with their unit prices',
      (tester) async {
    final api = await _pump(tester, const ShelfLabelsTab(initialVariantIds: ['v1', 'v2', 'v3', 'v4']),
        (a) => a.labels = _labels);
    expect(find.text('Rioja 75cl'), findsOneWidget, reason: 'chips show names, not ids');

    await tester.tap(find.byKey(const Key('label-make')));
    await tester.pumpAndSettle();

    final post = api.requests.singleWhere((r) => r.method == 'POST');
    expect(post.path, '/pricing-svc/prices/shelf-labels');
    expect(post.data, {'variantIds': ['v1', 'v2', 'v3', 'v4'], 'channel': 'POS'});

    final rioja = find.byKey(const Key('label-v1'));
    expect(find.descendant(of: rioja, matching: find.text(_gbp(0.9))), findsOneWidget);
    expect(find.descendant(of: rioja, matching: find.text('${_gbp(1.2)} per litre')), findsOneWidget);
    expect(find.descendant(of: rioja, matching: find.text('Half price · was ${_gbp(1.8)}')), findsOneWidget);

    // 03.12: a promotion whose prior price is not above today's is not labelled a reduction.
    final fake = find.byKey(const Key('label-v4'));
    expect(find.descendant(of: fake, matching: find.text(_gbp(2.0))), findsOneWidget);
    expect(find.descendant(of: fake, matching: find.textContaining('was')), findsNothing);
    expect(find.descendant(of: fake, matching: find.textContaining('sold at this price or less within the last 30 days')), findsOneWidget);
    expect(find.descendant(of: rioja, matching: find.byKey(const Key('label-no-measure'))), findsNothing);

    final carrots = find.byKey(const Key('label-v2'));
    expect(find.descendant(of: carrots, matching: find.text(_gbp(2.4))), findsOneWidget);
    expect(find.descendant(of: carrots, matching: find.textContaining('a unit price is law here')), findsOneWidget);

    final gift = find.byKey(const Key('label-v3'));
    expect(find.descendant(of: gift, matching: find.text('No price in force')), findsOneWidget);
  });

  testWidgets('a refused request is shown, and nothing can be asked with an empty sheet', (tester) async {
    final api = await _pump(tester, const ShelfLabelsTab(initialVariantIds: ['v1']), (a) {
      a.labelsStatus = 400;
      a.labels = '{"error":{"code":"PRICING_LABELS_INVALID","message":"variantIds lists 1 to 200 variants"}}';
    });
    await tester.tap(find.byKey(const Key('label-make')));
    await tester.pumpAndSettle();
    expect(find.text('variantIds lists 1 to 200 variants'), findsOneWidget);

    tester.widget<InputChip>(find.byKey(const Key('label-chip-v1'))).onDeleted!();
    await tester.pumpAndSettle();
    final make = tester.widget<ButtonStyleButton>(find.byKey(const Key('label-make')));
    expect(make.onPressed, isNull);
    expect(api.requests.where((r) => r.method == 'POST'), hasLength(1));
  });

  testWidgets('the gaps name each priced item without a measure, and say the law requires one', (tester) async {
    await _pump(tester, const UnitPricingGapsTab(), (a) => a.gaps =
        '{"data":{"required":true,"gaps":[{"variantId":"v2","catalogued":true},{"variantId":"v9","catalogued":false}]}}');
    expect(find.textContaining('A unit price is law for this business'), findsOneWidget);
    expect(find.text('Loose Carrots'), findsOneWidget);
    expect(find.textContaining('CAR-KG · add its net content and unit on the variant'), findsOneWidget);
    expect(find.textContaining('not yet in the catalogue feed'), findsOneWidget);
  });

  testWidgets('no gaps says so; where the law does not require it, the page says that too', (tester) async {
    await _pump(tester, const UnitPricingGapsTab(), (a) => a.gaps = '{"data":{"required":false,"gaps":[]}}');
    expect(find.byKey(const Key('gaps-empty')), findsOneWidget);
    expect(find.textContaining('not law for this business today'), findsOneWidget);
  });

  testWidgets('a failed read of the gaps offers to try again', (tester) async {
    await _pump(tester, const UnitPricingGapsTab(), (a) {
      a.gapsStatus = 403;
      a.gaps = '{"error":{"code":"FORBIDDEN","message":"Insufficient role for this operation"}}';
    });
    expect(find.byType(ListTile), findsNothing);
    expect(find.textContaining('Retry', findRichText: true), findsWidgets);
  });
}
