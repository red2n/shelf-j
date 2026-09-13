import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/onboarding/onboarding_wizard.dart';

// ---------------------------------------------------------------------------
// Setting up a business starts with nothing chosen (SJ-D53). The wizard used to
// open on the United Kingdom and pounds, so a business that did not look twice
// was created in the wrong country and currency. Now neither is preselected,
// submitting without them is refused before any request, and choosing a
// country suggests the currency it trades in.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    return ResponseBody.fromString(
      '{"data":{"id":"t-1","name":"Kyoto Market"}}',
      201,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1400, 5000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: const MaterialApp(home: OnboardingWizard()),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('no country or currency is chosen for the business', (
    tester,
  ) async {
    final server = await _pump(tester);
    expect(find.text('United Kingdom (GB)'), findsNothing);
    expect(find.textContaining('British Pound'), findsNothing);

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Business name *'),
      'Kyoto Market',
    );
    await tester.tap(find.text('Continue  →'));
    await tester.pumpAndSettle();
    expect(find.text('Choose a country'), findsOneWidget);
    expect(find.text('Choose a currency'), findsOneWidget);
    expect(server.requests, isEmpty);
  });

  testWidgets('choosing Japan suggests yen, and the business is created in both', (
    tester,
  ) async {
    final server = await _pump(tester);
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Business name *'),
      'Kyoto Market',
    );
    await tester.tap(
      find.widgetWithText(DropdownButtonFormField<String>, 'Country *'),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Japan (JP)').last);
    await tester.pumpAndSettle();
    expect(find.text('JPY — Japanese Yen'), findsOneWidget);

    await tester.tap(find.text('Continue  →'));
    // A progress indicator spins while the request is out, so settle by time.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    final sent = server.requests.single;
    expect(sent.path, endsWith('/onboarding/tenants'));
    expect((sent.data as Map)['country'], 'JP');
    expect((sent.data as Map)['currency'], 'JPY');
  });
}
