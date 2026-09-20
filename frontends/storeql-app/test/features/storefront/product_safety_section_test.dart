import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/product_safety_section.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// The safety information an online offer shows a shopper (01.12): the
// manufacturer, the EU responsible person, the warnings — and nothing
// invented when the business stated none.
// ---------------------------------------------------------------------------

class _Catalog implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"recorded":false}}';
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

Future<_Catalog> _pump(WidgetTester tester, void Function(_Catalog) setUp) async {
  final api = _Catalog();
  setUp(api);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = api;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[storefrontDioProvider.overrideWithValue(dio)],
    child: const MaterialApp(
      home: Scaffold(body: SingleChildScrollView(child: ProductSafetySection(productId: 'p1'))),
    ),
  ));
  await tester.pumpAndSettle();
  return api;
}

void main() {
  testWidgets('the manufacturer, the EU responsible person and the warnings, as stated',
      (tester) async {
    final api = await _pump(tester, (a) => a.body =
        '{"data":{"recorded":true,"manufacturerName":"Shenzhen Toys Ltd","manufacturerAddress":"1 Nanshan Road",'
        '"manufacturerContact":"https://toys.example.cn/safety","responsiblePersonName":"EU Rep BV",'
        '"responsiblePersonAddress":"Keizersgracht 1, Amsterdam","responsiblePersonContact":"rep@eurep.nl",'
        '"warnings":"Not suitable for children under 3.","noWarnings":false}}');

    expect(api.last!.path, '/product-svc/catalog/products/p1/safety-information');
    expect(find.text('Product safety'), findsOneWidget);
    expect(find.text('Shenzhen Toys Ltd\n1 Nanshan Road\nhttps://toys.example.cn/safety'), findsOneWidget);
    expect(find.text('Responsible person in the EU'), findsOneWidget);
    expect(find.text('EU Rep BV\nKeizersgracht 1, Amsterdam\nrep@eurep.nl'), findsOneWidget);
    expect(find.text('Not suitable for children under 3.'), findsOneWidget);
  });

  testWidgets('an EU manufacturer shows no responsible person, and no warnings says so',
      (tester) async {
    await _pump(tester, (a) => a.body =
        '{"data":{"recorded":true,"manufacturerName":"Atelier Lumière SAS","manufacturerAddress":"Paris",'
        '"manufacturerContact":"securite@lumiere.fr","noWarnings":true}}');
    expect(find.text('Responsible person in the EU'), findsNothing);
    expect(find.text('No warnings apply.'), findsOneWidget);
  });

  testWidgets('nothing stated shows nothing, rather than an empty box', (tester) async {
    await _pump(tester, (_) {});
    expect(find.byKey(const Key('product-safety')), findsNothing);
    expect(find.text('Product safety'), findsNothing);
  });

  testWidgets('a failed read says so, with a way to try again', (tester) async {
    final api = await _pump(tester, (a) {
      a.status = 503;
      a.body = '{"error":{"code":"SERVICE_UNAVAILABLE","message":"down"}}';
    });
    expect(find.byKey(const Key('safety-unavailable')), findsOneWidget);
    api.status = 200;
    api.body = '{"data":{"recorded":true,"manufacturerName":"Acme","noWarnings":true}}';
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(find.text('No warnings apply.'), findsOneWidget);
  });
}
