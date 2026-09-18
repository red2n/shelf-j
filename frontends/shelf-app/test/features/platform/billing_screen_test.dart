import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/platform/billing_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The platform's own billing (21.9): what is owed, oldest first, and whether the
// platform can invoice at all — which is worth its own card, because a platform
// that has not said who it is looks exactly like a platform with no customers.
// ---------------------------------------------------------------------------

Map<String, dynamic> _profile() => {
      'legalName': 'Shelf-J Platform Ltd',
      'country': 'IE',
      'invoicePrefix': 'INV',
      'paymentTermsDays': 14,
      'taxRate': 0.23,
      'vatNumber': 'IE1234567X',
    };

List<Map<String, dynamic>> _owed() => [
      {
        'id': 'i1',
        'number': 'INV-2026-000041',
        'issueDate': '2026-08-01',
        'dueDate': '2026-08-15',
        'currency': 'EUR',
        'totalAmount': 12.30,
        'outstanding': 12.30,
        'taxTreatment': 'DOMESTIC',
      },
      {
        'id': 'i2',
        'number': 'INV-2026-000042',
        'issueDate': '2099-01-01',
        'dueDate': '2099-01-15',
        'currency': 'EUR',
        'totalAmount': 20.00,
        'outstanding': 7.70,
        'taxTreatment': 'DOMESTIC',
      },
    ];

/// The profile is asked for separately and may legitimately answer 409.
class _Server implements HttpClientAdapter {
  final Map<String, dynamic>? profile;
  final List<Map<String, dynamic>> owed;

  _Server({required this.profile, required this.owed});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.path.contains('/receivables')) {
      return jsonResponse(jsonEncode({'data': owed}));
    }
    if (profile == null) {
      return jsonResponse(
        jsonEncode({
          'code': 'BILLING_PROFILE_NOT_SET',
          'error': {'code': 'BILLING_PROFILE_NOT_SET'},
        }),
        409,
      );
    }
    return jsonResponse(jsonEncode({'data': profile}));
  }
}

Future<void> _pump(
  WidgetTester tester, {
  Map<String, dynamic>? profile,
  List<Map<String, dynamic>>? owed,
}) async {
  tester.view.physicalSize = const Size(1200, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server(profile: profile, owed: owed ?? const []);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: const MaterialApp(home: Scaffold(body: PlatformBillingScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the platform says who it invoices as, and on what terms', (tester) async {
    await _pump(tester, profile: _profile(), owed: _owed());

    expect(find.byKey(const Key('profile')), findsOneWidget);
    expect(find.byKey(const Key('profile-unset')), findsNothing);
    expect(find.text('Shelf-J Platform Ltd'), findsOneWidget);
    expect(find.textContaining('IE · INV-… · 14 days · 23.00%'), findsOneWidget);
    expect(find.text('VAT IE1234567X'), findsOneWidget);
  });

  testWidgets('a platform that has not said who it is is told that nothing can be billed', (tester) async {
    await _pump(tester, profile: null, owed: _owed());

    expect(find.byKey(const Key('profile-unset')), findsOneWidget);
    expect(find.byKey(const Key('profile')), findsNothing);
    expect(find.textContaining('nothing can be billed'), findsOneWidget);
  });

  testWidgets('what is owed is totalled, and the overdue ones are marked', (tester) async {
    await _pump(tester, profile: _profile(), owed: _owed());

    // 12.30 outstanding plus 7.70 of a part-paid invoice.
    expect(find.byKey(const Key('total-owed')), findsOneWidget);
    expect(find.text('20.00'), findsOneWidget);
    expect(find.byKey(const Key('owed-INV-2026-000041')), findsOneWidget);
    expect(find.text('Overdue since 2026-08-15'), findsOneWidget);
    // The 2099 one is not overdue yet, and says when it falls due instead.
    expect(find.text('Due 2099-01-15'), findsOneWidget);
    expect(find.byIcon(Icons.warning_amber), findsOneWidget);
  });

  testWidgets('nothing owed says so, rather than showing an empty list', (tester) async {
    await _pump(tester, profile: _profile(), owed: const []);

    expect(find.byKey(const Key('owed-none')), findsOneWidget);
    expect(find.text('Every invoice is settled.'), findsOneWidget);
    expect(find.text('nothing'), findsOneWidget);
  });
}
