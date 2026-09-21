import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/shelf_space_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Shelf space and range (07.17, 07.18): the gap a shop acts on in the morning,
// the shelving a layout is drawn for, and range changes that are due but not in
// force.
//
// The assertions worth having here are the ones a shop would notice if they went
// wrong: a bay below its presentation minimum is marked, a refused range change
// is reported by its reason rather than silently counted as done, and the store
// shown is the one that was picked.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> sweep;

  _Server({required this.sweep});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.contains('/admin/stores')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': 's1', 'name': 'High Street', 'code': 'HS', 'type': 'STORE', 'status': 'ACTIVE'},
          {'id': 's2', 'name': 'Retail Park', 'code': 'RP', 'type': 'STORE', 'status': 'ACTIVE'},
        ],
      }));
    }
    if (o.method == 'GET' && o.path.contains('shelf-gaps')) {
      final store = o.queryParameters['storeId'];
      return jsonResponse(jsonEncode({
        'data': store == 's1'
            ? [
                {
                  'storeId': 's1',
                  'variantId': 'v-empty',
                  'capacity': 60,
                  'minPresentation': 12,
                  'available': '4.000',
                  'gap': '56.000',
                  'belowMinimum': true,
                },
                {
                  'storeId': 's1',
                  'variantId': 'v-full',
                  'capacity': 12,
                  'minPresentation': 3,
                  'available': '20.000',
                  'gap': '0.000',
                  'belowMinimum': false,
                },
              ]
            : [],
      }));
    }
    if (o.method == 'GET' && o.path.contains('/merchandising/fixtures')) {
      return jsonResponse(jsonEncode({
        'data': [
          {
            'id': 'f1',
            'code': 'GOND-1',
            'name': 'Aisle 4 gondola',
            'kind': 'GONDOLA',
            'shelfCount': 4,
            'shelfWidthMm': 1200,
            'totalWidthMm': 4800,
            'status': 'ACTIVE',
          },
        ],
      }));
    }
    if (o.method == 'GET' && o.path.contains('/changes/due')) {
      return jsonResponse(jsonEncode({
        'data': [
          {
            'id': 'c1',
            'productId': 'p1',
            'action': 'DELIST',
            'effectiveFrom': '2026-09-19',
            'reason': 'Bottom of the category on margin',
          },
        ],
      }));
    }
    if (o.method == 'POST' && o.path.contains('/changes/apply')) {
      return jsonResponse(jsonEncode({'data': sweep}));
    }
    return jsonResponse(jsonEncode({'data': []}));
  }
}

Future<_Server> _pump(WidgetTester tester, Widget child,
    {Map<String, dynamic>? sweep}) async {
  tester.view.physicalSize = const Size(1400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(sweep: sweep ?? {'applied': 1, 'notApplied': []});
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: MaterialApp(home: Scaffold(body: child)),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('a bay below its presentation minimum is marked, a full one is not',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    expect(find.text('56.000 to fill  ·  shelf holds 60'), findsOneWidget);
    expect(find.text('0.000 to fill  ·  shelf holds 12'), findsOneWidget);
    // The warning is on the picked-over bay only: 20 units against a shelf of 12
    // is not a gap, however low the reorder level would call it.
    expect(find.text('Below minimum'), findsOneWidget);
    expect(find.textContaining('looks picked over below 12'), findsOneWidget);
  });

  testWidgets('the shelving says what a layout is checked against', (tester) async {
    await _pump(tester, const ShelfSpaceScreen());
    await tester.tap(find.text('Shelving'));
    await tester.pumpAndSettle();

    expect(find.text('Aisle 4 gondola  ·  GOND-1'), findsOneWidget);
    expect(find.text('gondola  ·  4 shelves of 1200mm  ·  4800mm in all'),
        findsOneWidget);
  });

  testWidgets('picking another store reads that store\'s shelves', (tester) async {
    final server = await _pump(tester, const ShelfSpaceScreen());

    await tester.tap(find.byKey(const Key('shelf-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Retail Park').last);
    await tester.pumpAndSettle();

    expect(server.requests.last.queryParameters['storeId'], 's2');
    expect(find.byKey(const Key('gaps-empty')), findsOneWidget);
  });

  testWidgets('a due change is shown with its reason and applied on request',
      (tester) async {
    final server = await _pump(tester, const ShelfSpaceScreen());
    await tester.tap(find.text('Range'));
    await tester.pumpAndSettle();

    expect(find.text('De-list  ·  due 2026-09-19'), findsOneWidget);
    expect(find.text('Bottom of the category on margin'), findsOneWidget);

    await tester.tap(find.byKey(const Key('range-apply')));
    await tester.pumpAndSettle();
    expect(
        server.requests.any((r) =>
            r.method == 'POST' && r.path.contains('/changes/apply')),
        isTrue);
    expect(find.text('1 change in force.'), findsOneWidget);
  });

  testWidgets('a change that could not be applied is named, not counted as done',
      (tester) async {
    // The refusal matters more than the count: the change stays due, so the shop
    // fixes the cause instead of re-entering the decision.
    await _pump(
      tester,
      const ShelfSpaceScreen(),
      sweep: {
        'applied': 0,
        'notApplied': [
          {
            'changeId': 'c1',
            'productId': 'p1',
            'code': 'ASSORTMENT_RANGED_EVERYWHERE',
            'detail': 'This line has no store range at all',
          },
        ],
      },
    );
    await tester.tap(find.text('Range'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('range-apply')));
    await tester.pumpAndSettle();

    expect(
        find.textContaining('1 could not be applied. This line has no store range'),
        findsOneWidget);
  });
}
