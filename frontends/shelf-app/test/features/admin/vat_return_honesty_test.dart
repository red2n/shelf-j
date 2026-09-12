import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/pricing_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D39: the VAT return screen presents nine boxes in the shape of an HMRC
// return and only five are computed. It must say so on its face — a "0.00" in
// box 4 reads as a figure, and box 5 built on it is overstated by exactly the
// VAT the business may reclaim.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final bool honest;
  _Server({required this.honest});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    String body = '{"data":[]}';
    if (o.path.endsWith('/vat-return')) {
      body = honest
          ? '{"data":{"box1":40.00,"box2":0,"box3":40.00,"box4":0,"box5":40.00,"box6":200.00,"box7":0,"box8":0,"box9":0,'
              '"computedBoxes":[1,3,5,6],"notComputedBoxes":[2,4,7,8,9],"fitToFile":false,'
              '"caveat":"Boxes 2, 4, 7, 8 and 9 are not computed. Not fit to file."}}'
          : '{"data":{"box1":40.00,"box2":0,"box3":40.00,"box4":0,"box5":40.00,"box6":200.00,"box7":0,"box8":0,"box9":0}}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<void> _pump(WidgetTester tester, {required bool honest}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server(honest: honest);
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: PricingScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('VAT Return'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('says on its face which boxes are not computed, and that it is not fit to file',
      (tester) async {
    await _pump(tester, honest: true);
    expect(find.byKey(const Key('vat-return-caveat')), findsOneWidget);
    expect(find.textContaining('Not fit to file'), findsOneWidget);
    // The real boxes carry figures; the others carry no figure at all.
    expect(find.text('40.00'), findsNWidgets(3)); // boxes 1, 3 and 5
    expect(find.byKey(const Key('vat-box-4-not-computed')), findsOneWidget);
    expect(find.byKey(const Key('vat-box-7-not-computed')), findsOneWidget);
    expect(find.textContaining('Not computed'), findsNWidgets(5));
    expect(find.text('0.00'), findsNothing, reason: 'a zero would read as a figure someone might file');
  });

  testWidgets('an older server that does not say defaults to not fit to file', (tester) async {
    await _pump(tester, honest: false);
    expect(find.byKey(const Key('vat-return-caveat')), findsOneWidget);
  });
}
