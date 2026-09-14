import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/obligations_screen.dart';

// ---------------------------------------------------------------------------
// The laws this business trades under: what is in force, what is coming, and
// what the screen says when it has nothing or cannot find out.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Tenant implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"country":"GB","on":"2026-09-14","obligations":[]}}';
  RequestOptions? last;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    last = o;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Tenant> _pump(WidgetTester tester, void Function(_Tenant) setUp) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final tenant = _Tenant();
  setUp(tenant);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = tenant;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: ObligationsScreen())),
  ));
  await tester.pumpAndSettle();
  return tenant;
}

const _gb = '{"data":{"country":"GB","on":"2026-09-14","obligations":['
    '{"code":"UNIT_PRICING","scope":"GB","effectiveFrom":"2026-04-06",'
    '"citation":"Price Marking Order 2004, as amended","summary":"Unit prices are shown legibly.","status":"IN_FORCE"},'
    '{"code":"TOBACCO_BIRTH_COHORT","scope":"GB","effectiveFrom":"2027-01-01",'
    '"citation":"Tobacco and Vapes Act 2026","summary":"No tobacco is sold to anyone born on or after 1 January 2009.","status":"UPCOMING"}]}}';

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('what is in force and what is coming, each with its day and instrument',
      (tester) async {
    final tenant = await _pump(tester, (t) => t.body = _gb);

    expect(tenant.last!.path, '/tenant-svc/admin/tenant/obligations');
    expect(find.text('In force in GB'), findsOneWidget);
    expect(find.text('Coming'), findsOneWidget);
    expect(find.text('Unit prices are shown legibly.'), findsOneWidget);
    expect(find.text('Since 6 Apr 2026'), findsOneWidget);
    expect(find.text('From 1 Jan 2027'), findsOneWidget);
    expect(find.textContaining('Tobacco and Vapes Act 2026 · National law'), findsOneWidget);
  });

  testWidgets('EU law is labelled as such, with the day it stopped when it did',
      (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"country":"GB","on":"2019-06-01","obligations":['
            '{"code":"GDPR","scope":"EU","effectiveFrom":"2018-05-25","effectiveTo":"2020-01-31",'
            '"citation":"Regulation (EU) 2016/679","summary":"Personal data needs a lawful basis.","status":"IN_FORCE"}]}}');
    expect(find.textContaining('EU law · until 31 Jan 2020'), findsOneWidget);
    expect(find.text('Coming'), findsNothing);
  });

  testWidgets('an empty list says the platform tracks none, not that none apply',
      (tester) async {
    await _pump(tester, (t) => t.body = '{"data":{"country":"US","on":"2026-09-14","obligations":[]}}');
    expect(find.textContaining('No obligations are recorded for US'), findsOneWidget);
    expect(find.textContaining('not that none apply'), findsOneWidget);
  });

  testWidgets('a failed read says so, with a way to try again', (tester) async {
    await _pump(tester, (t) {
      t.status = 503;
      t.body = '{"error":{"code":"SERVICE_UNAVAILABLE","message":"down"}}';
    });
    expect(find.byType(ListTile), findsNothing);
    expect(find.textContaining('Retry', findRichText: true), findsWidgets);
  });
}
