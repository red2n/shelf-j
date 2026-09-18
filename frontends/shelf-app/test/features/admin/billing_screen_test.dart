import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/billing_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// What a business pays the platform (21.9). The two things an owner needs to see
// before they cost money: that the subscription ends at the period end, and that
// an unchecked VAT number means VAT is being charged that need not be.
// ---------------------------------------------------------------------------

Map<String, dynamic> _subscription({
  String status = 'ACTIVE',
  bool cancelAtPeriodEnd = false,
  String? pendingPlanId,
  String? trialEnd,
  String? vatNumber = 'DE123456789',
  bool vatChecked = true,
}) => {
      'subscription': {
        'id': 's1',
        'planId': 'p1',
        'planCode': 'STARTER',
        'planName': 'Starter',
        'status': status,
        'priceAmount': 29,
        'currency': 'GBP',
        'billingInterval': 'MONTH',
        'periodStart': '2026-09-01',
        'periodEnd': '2026-10-01',
        'trialEnd': trialEnd,
        'pendingPlanId': pendingPlanId,
        'cancelAtPeriodEnd': cancelAtPeriodEnd,
        'buyer': {
          'name': 'Weinhaus GmbH',
          'country': 'DE',
          'vatNumber': vatNumber,
          'vatChecked': vatChecked,
          'vatCheckSource': vatChecked ? 'VIES' : null,
        },
        'billingEmail': 'owner@example.com',
      },
      'events': <Map<String, dynamic>>[],
    };

List<Map<String, dynamic>> _invoices() => [
      {
        'id': 'i1',
        'number': 'INV-2026-000041',
        'status': 'OPEN',
        'issueDate': '2026-09-01',
        'dueDate': '2026-09-15',
        'currency': 'GBP',
        'totalAmount': 34.80,
        'outstanding': 34.80,
        'taxTreatment': 'DOMESTIC',
      },
      {
        'id': 'i2',
        'number': 'INV-2026-000040',
        'status': 'PAID',
        'issueDate': '2026-08-01',
        'dueDate': '2026-08-15',
        'currency': 'GBP',
        'totalAmount': 29.00,
        'outstanding': 0,
        'taxTreatment': 'REVERSE_CHARGE',
      },
    ];

/// The screen asks for the subscription and the invoices separately, so the fake
/// answers by path rather than returning one body to everything.
class _Server implements HttpClientAdapter {
  final Map<String, dynamic>? subscription;
  final List<Map<String, dynamic>> invoices;

  _Server({required this.subscription, required this.invoices});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.path.contains('/invoices')) {
      return jsonResponse(jsonEncode({'data': invoices}));
    }
    return jsonResponse(jsonEncode({'data': subscription ?? <String, dynamic>{}}));
  }
}

Future<void> _pump(
  WidgetTester tester, {
  Map<String, dynamic>? subscription,
  List<Map<String, dynamic>>? invoices,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server(
      subscription: subscription,
      invoices: invoices ?? const [],
    );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: const MaterialApp(home: Scaffold(body: BillingScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the subscription says what it costs and for which period', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());

    expect(find.byKey(const Key('subscription')), findsOneWidget);
    expect(find.text('Starter'), findsOneWidget);
    expect(find.text('ACTIVE'), findsOneWidget);
    expect(find.text('GBP 29 per month'), findsOneWidget);
    expect(find.text('Billed 2026-09-01 to 2026-10-01'), findsOneWidget);
    expect(find.byKey(const Key('note-ending')), findsNothing);
  });

  testWidgets('an unchecked VAT number says that VAT is being charged because of it', (tester) async {
    // The consequence is money, so it is said and not implied.
    await _pump(
      tester,
      subscription: _subscription(vatChecked: false),
      invoices: _invoices(),
    );

    expect(find.text('DE123456789 · not checked yet, so VAT is charged'), findsOneWidget);
  });

  testWidgets('a checked number says so, and names what checked it', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());

    expect(find.text('DE123456789 · checked (VIES)'), findsOneWidget);
  });

  testWidgets('no VAT number at all is not the same as an unchecked one', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(vatNumber: null, vatChecked: false),
      invoices: _invoices(),
    );

    expect(find.text('No VAT number given'), findsOneWidget);
  });

  testWidgets('a subscription that ends at the period end warns before it does', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(cancelAtPeriodEnd: true),
      invoices: _invoices(),
    );

    expect(find.byKey(const Key('note-ending')), findsOneWidget);
    expect(find.text('Ends on 2026-10-01.'), findsOneWidget);
  });

  testWidgets('a trial says when the first invoice comes', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(status: 'TRIALING', trialEnd: '2026-09-20'),
      invoices: const [],
    );

    expect(find.byKey(const Key('note-trial')), findsOneWidget);
    expect(find.text('Free until 2026-09-20. The first invoice comes then.'), findsOneWidget);
    expect(find.text('None yet.'), findsOneWidget);
  });

  testWidgets('being behind is said plainly', (tester) async {
    await _pump(tester, subscription: _subscription(status: 'PAST_DUE'), invoices: _invoices());

    expect(find.byKey(const Key('note-behind')), findsOneWidget);
  });

  testWidgets('a waiting plan change says when it takes effect', (tester) async {
    await _pump(
      tester,
      subscription: _subscription(pendingPlanId: 'p2'),
      invoices: _invoices(),
    );

    expect(find.byKey(const Key('note-pending')), findsOneWidget);
    expect(find.text('A plan change takes effect on 2026-10-01.'), findsOneWidget);
  });

  testWidgets('an invoice shows what is owed, and a reverse charge says why there is no VAT', (tester) async {
    await _pump(tester, subscription: _subscription(), invoices: _invoices());

    expect(find.byKey(const Key('invoice-INV-2026-000041')), findsOneWidget);
    expect(find.text('GBP 34.8 owed'), findsOneWidget);
    expect(find.textContaining('Issued 2026-09-01 · due 2026-09-15'), findsOneWidget);
    // A settled invoice is not owed, and says its status instead.
    expect(find.text('paid'), findsOneWidget);
    expect(find.textContaining('Reverse charge — no VAT'), findsOneWidget);
  });

  testWidgets('a business that is not subscribed is told so, rather than shown an empty card', (tester) async {
    await _pump(tester, subscription: null, invoices: const []);

    expect(find.byKey(const Key('billing-none')), findsOneWidget);
    expect(find.byKey(const Key('subscription')), findsNothing);
  });
}
