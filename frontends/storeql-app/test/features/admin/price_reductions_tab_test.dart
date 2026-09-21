import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/price_reductions_tab.dart';

// ---------------------------------------------------------------------------
// The reductions on offer (03.12): what a manager is told about each promotional
// price — may it be announced, and when not, why.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Api implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  int status = 200;
  final bodies = <String, String>{};

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final (code, body) = o.path.endsWith('/admin/prices/reductions')
        ? (
            status,
            bodies[o.queryParameters['channel']] ??
                '{"data":{"channel":"${o.queryParameters['channel']}","pending":0,"rows":[]}}'
          )
        : o.path.endsWith('/variants/resolve')
            ? (
                200,
                '{"data":[{"variantId":"v1","productName":"Rioja 75cl","sku":"RIO-75"},'
                    '{"variantId":"v2","productName":"Loose Carrots","sku":"CAR-KG"},'
                    '{"variantId":"v3","productName":"Gift Box","sku":"GIFT"}]}'
              )
            : (200, '{"data":[],"meta":{}}');
    return ResponseBody.fromString(body, code, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Api> _pump(WidgetTester tester, void Function(_Api) setUp) async {
  tester.view.physicalSize = const Size(1400, 1800);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final api = _Api();
  setUp(api);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = api;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: PriceReductionsTab())),
  ));
  await tester.pumpAndSettle();
  return api;
}

String _eur(double v) => AppFormat.money(v, currencyCode: 'EUR');

Finder _in(String key, Finder f) => find.descendant(of: find.byKey(Key(key)), matching: f);

void main() {
  testWidgets('each reduction on the shop says whether it may be announced, and why not',
      (tester) async {
    final api = await _pump(tester, (a) {
      a.bodies['ONLINE'] = '{"data":{"channel":"ONLINE","pending":0,"rows":['
          '{"variantId":"v1","channel":"ONLINE","price":9.6,"regularPrice":12,"promotionName":"Summer",'
          '"currency":"EUR","priorPrice":12,"priorPriceStatus":"ANNOUNCEABLE","priorPriceRequired":true,'
          '"reductionAnnounceable":true},'
          '{"variantId":"v2","channel":"ONLINE","price":2,"regularPrice":2.4,"promotionName":"Too soon",'
          '"currency":"EUR","priorPrice":2.4,"priorPriceStatus":"SHORT_HISTORY","priorPriceRequired":true,'
          '"reductionAnnounceable":false},'
          '{"variantId":"v3","channel":"ONLINE","storeId":"s1","price":8,"regularPrice":10,'
          '"currency":"EUR","priorPrice":10,"priorPriceStatus":"UNCERTAIN","priorPriceRequired":true,'
          '"reductionAnnounceable":false}]}}';
    });
    final get = api.requests.firstWhere((r) => r.path.endsWith('/admin/prices/reductions'));
    expect(get.path, '/pricing-svc/admin/prices/reductions');
    expect(get.queryParameters, {'channel': 'ONLINE'});

    expect(_in('reduction-v1-all', find.text('Rioja 75cl')), findsOneWidget);
    expect(_in('reduction-v1-all', find.textContaining('may be announced · was ${_eur(12)}')),
        findsOneWidget);
    expect(
        _in('reduction-v2-all',
            find.textContaining('not to be shown as a reduction: fewer than 30 days of prices')),
        findsOneWidget);
    expect(_in('reduction-v2-all', find.textContaining('was')), findsNothing);
    expect(_in('reduction-v3-s1', find.textContaining('at one store only')), findsOneWidget);
    expect(_in('reduction-v3-s1', find.textContaining('could not be established')), findsOneWidget);
  });

  testWidgets('the till has its own reductions; changes still being recorded are said',
      (tester) async {
    final api = await _pump(tester, (a) {
      a.bodies['POS'] = '{"data":{"channel":"POS","pending":2,"rows":['
          '{"variantId":"v1","channel":"POS","price":9.6,"regularPrice":12,"promotionName":"Summer",'
          '"currency":"EUR","priorPriceStatus":"PENDING","priorPriceRequired":true,'
          '"reductionAnnounceable":false}]}}';
    });
    expect(find.byKey(const Key('reductions-empty')), findsOneWidget);

    await tester.tap(find.text('In store'));
    await tester.pumpAndSettle();
    expect(
        api.requests.where((r) => r.path.endsWith('/admin/prices/reductions')).last.queryParameters,
        {'channel': 'POS'});
    expect(find.textContaining('2 price changes are still being recorded'), findsOneWidget);
    expect(_in('reduction-v1-all', find.textContaining('a change to it is still being recorded')),
        findsOneWidget);
  });

  testWidgets('where the rule does not bind it is said, and nothing unreadable is called announceable',
      (tester) async {
    await _pump(tester, (a) {
      a.bodies['ONLINE'] = '{"data":{"channel":"ONLINE","pending":0,"rows":['
          '{"variantId":"v1","price":9.6,"regularPrice":12,"currency":"GBP",'
          '"priorPriceRequired":false,"reductionAnnounceable":true},'
          '{"variantId":"v2","price":2,"regularPrice":2.4,"currency":"EUR","priorPrice":2.4,'
          '"reductionAnnounceable":"yes"}]}}';
    });
    expect(_in('reduction-v1-all', find.textContaining('the prior-price rule does not bind here')),
        findsOneWidget);
    expect(
        _in('reduction-v2-all', find.textContaining('not to be shown as a reduction: its prior price')),
        findsOneWidget);
  });

  testWidgets('a refused read offers to try again', (tester) async {
    await _pump(tester, (a) {
      a.status = 403;
      a.bodies['ONLINE'] = '{"error":{"code":"FORBIDDEN","message":"Insufficient role for this operation"}}';
    });
    expect(find.byType(ListTile), findsNothing);
    expect(find.textContaining('Retry', findRichText: true), findsWidgets);
  });

  test('every status has a reason a manager can act on', () {
    for (final s in ['NO_HISTORY', 'SHORT_HISTORY', 'NOT_LOWER', 'PENDING', 'UNCERTAIN']) {
      expect(reductionReason(s), isNot(reductionReason(null)), reason: s);
    }
    expect(reductionReason('ANYTHING_ELSE'), reductionReason(null));
  });
}
