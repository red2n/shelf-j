import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/plan_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The plan a business is on (21.8): what it allows against what is in use, a
// count the owning service could not give shown as unknown rather than guessed
// at, and a business on no plan told plainly that nothing is limited.
// ---------------------------------------------------------------------------

Map<String, dynamic> _onAPlan() => {
      'plan': {
        'id': 'p1',
        'code': 'STARTER',
        'name': 'Starter',
        'status': 'ACTIVE',
        'billingInterval': 'MONTH',
        'trialDays': 14,
        'isDefault': true,
        'isPublic': true,
        'sortOrder': 1,
        'prices': [
          {'currency': 'GBP', 'amount': 29, 'effectiveFrom': '2026-01-01'},
        ],
        'includes': [
          {'key': 'stores.max', 'label': 'Stores and warehouses', 'limitValue': 2},
          {'key': 'feature.storefront', 'label': 'The online shop', 'enabled': true},
        ],
      },
      'usage': [
        {'key': 'stores.max', 'label': 'Stores and warehouses', 'limitValue': 2, 'used': 2, 'over': false},
        {'key': 'staff.max', 'label': 'Staff logins', 'limitValue': 10, 'used': 3, 'over': false},
        // product-svc owns the count, so tenant-svc leaves it out entirely.
        {'key': 'products.max', 'label': 'Products', 'limitValue': 500, 'over': false},
      ],
    };

Map<String, dynamic> _onNoPlan() => {
      'usage': <Map<String, dynamic>>[],
      'note': 'This business is on no plan',
    };

class _Server implements HttpClientAdapter {
  final Map<String, dynamic> body;
  _Server(this.body);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      jsonResponse(jsonEncode({'data': body}));
}

Future<void> _pump(WidgetTester tester, Map<String, dynamic> body) async {
  tester.view.physicalSize = const Size(1200, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server(body);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: const MaterialApp(home: Scaffold(body: PlanScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the plan says what it allows against what is in use', (tester) async {
    await _pump(tester, _onAPlan());

    expect(find.text('Starter · STARTER'), findsOneWidget);
    expect(find.text('billed every month'), findsOneWidget);
    expect(find.text('Stores and warehouses: 2'), findsOneWidget);
    expect(find.text('The online shop: included'), findsOneWidget);

    expect(find.text('2 of 2'), findsOneWidget, reason: 'the store allowance is full');
    expect(find.text('3 of 10'), findsOneWidget);
    expect(find.byKey(const Key('allowance-stores.max')), findsOneWidget);
    expect(find.byKey(const Key('allowance-staff.max')), findsOneWidget);
  });

  testWidgets('a count the owning service holds is left unknown, not guessed at', (tester) async {
    await _pump(tester, _onAPlan());

    expect(find.text('up to 500'), findsOneWidget);
    expect(find.text('counted by the service that holds them'), findsOneWidget);
    // Nothing to draw when there is no count, so no bar for that row.
    expect(find.byType(LinearProgressIndicator), findsNWidgets(2));
  });

  testWidgets('a business on no plan is told nothing is limited', (tester) async {
    await _pump(tester, _onNoPlan());

    expect(find.byKey(const Key('plan-none')), findsOneWidget);
    expect(find.text('This business is on no plan'), findsOneWidget);
    expect(find.byKey(const Key('plan-name')), findsNothing);
    expect(find.text('What it allows'), findsNothing, reason: 'nothing to show');
  });
}
