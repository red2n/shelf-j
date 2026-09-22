import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/platform/plans_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The platform's price list (21.8): a plan says where it stands and what it
// includes, one with no price says it cannot be sold, a plan is not written
// without a code and a name, and only the keys the platform enforces can be
// promised.
// ---------------------------------------------------------------------------

Map<String, dynamic> _plan(
  String code,
  String status, {
  bool isDefault = false,
  List<Map<String, dynamic>> prices = const [],
  List<Map<String, dynamic>> includes = const [],
  List<Map<String, dynamic>> meters = const [],
  List<Map<String, dynamic>> meterPrices = const [],
}) => {
      'id': 'id-$code',
      'code': code,
      'name': '$code plan',
      'description': 'for a shop',
      'status': status,
      'billingInterval': 'MONTH',
      'trialDays': 14,
      'isDefault': isDefault,
      'isPublic': true,
      'sortOrder': 1,
      'prices': prices,
      'includes': includes,
      'meters': meters,
      'meterPrices': meterPrices,
    };

final _meterKeys = [
  {'key': 'ORDERS', 'label': 'Orders taken', 'unit': 'order', 'refusable': false, 'countedBy': 'order-svc'},
  {'key': 'SMS', 'label': 'Text messages', 'unit': 'text part', 'refusable': true, 'countedBy': 'notification-svc'},
];

final _keys = [
  {'key': 'stores.max', 'label': 'Stores and warehouses', 'limit': true, 'enforcedBy': 'tenant-svc'},
  {'key': 'staff.max', 'label': 'Staff logins', 'limit': true, 'enforcedBy': 'iam-svc'},
  {'key': 'feature.storefront', 'label': 'The online shop', 'limit': false, 'enforcedBy': 'tenant-svc'},
];

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  List<Map<String, dynamic>> plans;
  int? refuseWith;
  String refusal = 'PLAN_HAS_NO_PRICE';

  _Server(this.plans);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/meter-keys')) {
      return jsonResponse(jsonEncode({
        'data': {'meters': _meterKeys},
      }));
    }
    if (o.path.endsWith('/entitlement-keys')) {
      return jsonResponse(jsonEncode({
        'data': {'entitlements': _keys},
      }));
    }
    if (o.method != 'GET' && refuseWith != null) {
      return jsonResponse(jsonEncode({'code': refusal, 'detail': 'no', 'status': refuseWith}), refuseWith!);
    }
    if (o.method != 'GET') return jsonResponse(jsonEncode({'data': plans.first}));
    return jsonResponse(jsonEncode({'data': plans}));
  }
}

Future<_Server> _pump(WidgetTester tester, List<Map<String, dynamic>> plans, Widget child) async {
  tester.view.physicalSize = const Size(1400, 2600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(plans);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
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
  testWidgets('the price list says where each plan stands, and what it includes', (tester) async {
    await _pump(
      tester,
      [
        _plan('STARTER', 'ACTIVE', isDefault: true, prices: [
          {'currency': 'GBP', 'amount': 29, 'effectiveFrom': '2026-01-01'},
        ], includes: [
          {'key': 'stores.max', 'label': 'Stores and warehouses', 'limitValue': 2},
          {'key': 'feature.storefront', 'label': 'The online shop', 'enabled': true},
        ]),
        _plan('DRAFTY', 'DRAFT'),
      ],
      const PlansScreen(),
    );

    expect(find.text('STARTER plan · STARTER'), findsOneWidget);
    expect(find.text('On sale'), findsOneWidget);
    expect(find.text('Draft'), findsOneWidget);
    expect(find.text('New businesses start here'), findsOneWidget, reason: 'the default is marked');
    expect(find.text('29 GBP from 2026-01-01'), findsOneWidget);
    expect(find.text('Stores and warehouses: 2'), findsOneWidget);
    expect(find.text('The online shop: included'), findsOneWidget);
    expect(find.text('No price yet — it cannot go on sale without one.'), findsOneWidget);
    expect(find.byKey(const Key('plan-sell-DRAFTY')), findsOneWidget);
    expect(find.byKey(const Key('plan-sell-STARTER')), findsNothing, reason: 'already on sale');
    expect(find.byKey(const Key('plan-retire-STARTER')), findsOneWidget);
  });

  testWidgets('a plan with no price cannot be sold, and says why in words', (tester) async {
    final server = await _pump(tester, [_plan('DRAFTY', 'DRAFT')], const PlansScreen());
    server.refuseWith = 409;

    await tester.tap(find.byKey(const Key('plan-sell-DRAFTY')));
    await tester.pumpAndSettle();

    expect(find.text('Give it a price before selling it.'), findsOneWidget);
    expect(server.requests.last.path, endsWith('/id-DRAFTY/activate'));
  });

  testWidgets('a plan is not written without a code and a name', (tester) async {
    final server = await _pump(tester, [_plan('X', 'DRAFT')], const WritePlanDialog());

    await tester.tap(find.byKey(const Key('plan-write-save')));
    await tester.pumpAndSettle();
    expect(find.text('A plan needs a code and a name.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);

    await tester.enterText(find.byKey(const Key('plan-code')), 'growth');
    await tester.enterText(find.byKey(const Key('plan-name')), 'Growth');
    await tester.enterText(find.byKey(const Key('plan-trial')), '30');
    await tester.tap(find.byKey(const Key('plan-write-save')));
    await tester.pumpAndSettle();

    final sent = server.requests.lastWhere((r) => r.method == 'POST');
    expect(sent.data, {
      'code': 'growth',
      'name': 'Growth',
      'billingInterval': 'MONTH',
      'trialDays': 30,
      'isPublic': true,
    }, reason: 'an empty description is not sent');
  });

  testWidgets('a taken code is said in words, not as a status', (tester) async {
    final server = await _pump(tester, [_plan('X', 'DRAFT')], const WritePlanDialog());
    server
      ..refuseWith = 409
      ..refusal = 'PLAN_CODE_TAKEN';
    await tester.enterText(find.byKey(const Key('plan-code')), 'STARTER');
    await tester.enterText(find.byKey(const Key('plan-name')), 'Starter');
    await tester.tap(find.byKey(const Key('plan-write-save')));
    await tester.pumpAndSettle();
    expect(find.text('A plan already goes by that code.'), findsOneWidget);
  });

  testWidgets('only what the platform enforces can be promised, and a dash means unlimited', (tester) async {
    final plan = _plan('STARTER', 'ACTIVE', includes: [
      {'key': 'stores.max', 'label': 'Stores and warehouses', 'limitValue': 2},
    ]);
    final server = await _pump(tester, [plan], SetIncludesDialog(plan: Plan.fromJson(plan)));

    expect(find.byKey(const Key('include-stores.max')), findsOneWidget);
    expect(find.byKey(const Key('include-staff.max')), findsOneWidget);
    expect(find.byKey(const Key('include-feature.storefront')), findsOneWidget);
    expect(find.text('enforced by tenant-svc'), findsNWidgets(2));
    expect(find.text('2'), findsOneWidget, reason: 'what the plan already says is shown');

    await tester.enterText(find.byKey(const Key('include-stores.max')), '-');
    await tester.enterText(find.byKey(const Key('include-staff.max')), '5');
    await tester.tap(find.byKey(const Key('include-feature.storefront')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('includes-save')));
    await tester.pumpAndSettle();

    final sent = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(sent.data, {
      'grants': [
        {'key': 'stores.max'},
        {'key': 'staff.max', 'limitValue': 5},
        {'key': 'feature.storefront', 'enabled': true},
      ],
    });
  });

  testWidgets('a limit that is not a number is refused before anything is sent', (tester) async {
    final plan = _plan('STARTER', 'ACTIVE');
    final server = await _pump(tester, [plan], SetIncludesDialog(plan: Plan.fromJson(plan)));

    await tester.enterText(find.byKey(const Key('include-stores.max')), 'lots');
    await tester.tap(find.byKey(const Key('includes-save')));
    await tester.pumpAndSettle();

    expect(find.text('Stores and warehouses is a whole number, or “-” for unlimited.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  // ── metered use (21.10) ─────────────────────────────────────────────────────

  testWidgets('a plan says what it includes of each meter, and what happens beyond it', (tester) async {
    final plan = _plan('GROWTH', 'ACTIVE', prices: [
      {'currency': 'GBP', 'amount': 49, 'effectiveFrom': '2026-01-01'},
    ], meters: [
      {'meter': 'ORDERS', 'label': 'Orders taken', 'unit': 'order', 'included': 1000, 'hard': false},
      {'meter': 'SMS', 'label': 'Text messages', 'unit': 'text part', 'included': 50, 'hard': true},
    ], meterPrices: [
      {'meter': 'ORDERS', 'currency': 'GBP', 'unitAmount': 0.05, 'effectiveFrom': '2026-01-01'},
    ]);
    await _pump(tester, [plan], const PlansScreen());

    expect(find.text('Orders taken: 1000 a period, then 0.05 GBP each'), findsOneWidget);
    expect(find.text('Text messages: 50 a period, then marketing stops'), findsOneWidget);
  });

  testWidgets('only a meter that may be refused can stop, and a dash means unlimited', (tester) async {
    final plan = _plan('GROWTH', 'ACTIVE', meters: [
      {'meter': 'ORDERS', 'label': 'Orders taken', 'unit': 'order', 'included': 1000, 'hard': false},
    ]);
    final server = await _pump(tester, [plan], SetMetersDialog(plan: Plan.fromJson(plan)));

    expect(find.text('1000'), findsOneWidget, reason: 'what the plan already says is shown');
    expect(find.byKey(const Key('meter-hard-ORDERS')), findsNothing, reason: 'an order is never refused');
    expect(find.byKey(const Key('meter-hard-SMS')), findsOneWidget);

    await tester.enterText(find.byKey(const Key('meter-SMS')), '50');
    await tester.tap(find.byKey(const Key('meter-hard-SMS')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('meters-save')));
    await tester.pumpAndSettle();

    expect(server.requests.lastWhere((r) => r.method == 'PUT').data, {
      'meters': [
        {'meter': 'ORDERS', 'included': 1000, 'hard': false},
        {'meter': 'SMS', 'included': 50, 'hard': true},
      ],
    });

    await tester.pumpWidget(const SizedBox());
    final again = await _pump(tester, [plan], SetMetersDialog(plan: Plan.fromJson(plan)));
    await tester.enterText(find.byKey(const Key('meter-ORDERS')), '-');
    await tester.enterText(find.byKey(const Key('meter-SMS')), 'lots');
    await tester.tap(find.byKey(const Key('meters-save')));
    await tester.pumpAndSettle();
    expect(find.text('Text messages is a whole number, or “-” for unlimited.'), findsOneWidget);
    expect(again.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  testWidgets('an overage price is set per meter, to four places', (tester) async {
    final plan = _plan('GROWTH', 'ACTIVE');
    final server = await _pump(tester, [plan], const SetMeterPriceDialog(planId: 'id-GROWTH'));

    await tester.tap(find.byKey(const Key('meter-price-save')));
    await tester.pumpAndSettle();
    expect(find.text('Choose what is being priced.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('meter-price-meter')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Text messages, per text part').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('meter-price-amount')), '0.035');
    await tester.tap(find.byKey(const Key('meter-price-save')));
    await tester.pumpAndSettle();

    final sent = server.requests.lastWhere((r) => r.method == 'POST');
    expect(sent.path, '/tenant-svc/platform/plans/id-GROWTH/meter-prices');
    expect(sent.data, {'meter': 'SMS', 'currency': 'GBP', 'unitAmount': 0.035});
  });
}
