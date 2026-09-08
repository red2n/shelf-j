import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/offline/offline_queue.dart';
import 'package:shelf_app/core/offline/offline_sale.dart';
import 'package:shelf_app/core/storage/app_storage.dart';
import 'package:shelf_app/features/pos/offline_queue_screen.dart';

// ---------------------------------------------------------------------------
// An invisible queue is worse than none: the cashier has taken real money and
// has to be able to see it is still owed to the server. These pin what the
// screen tells them, and that discarding a sale is never one tap away.
// ---------------------------------------------------------------------------

class _MemStorage implements AppStorage {
  final Map<String, String> data = {};

  @override
  Future<String?> read({required String key}) async => data[key];

  @override
  Future<void> write({required String key, required String? value}) async {
    if (value == null) {
      data.remove(key);
    } else {
      data[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => data.remove(key);

  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      data.removeWhere((k, _) => !keep.contains(k));
}

OfflineSale _sale({
  String id = 'pos-1700000123456',
  OfflineSaleStatus status = OfflineSaleStatus.pending,
  String? lastError,
}) =>
    OfflineSale(
      id: id,
      capturedAt: DateTime.utc(2026, 9, 8, 11, 30),
      storeId: 'store-1',
      currency: 'GBP',
      orderRequest: const {'storeId': 'store-1'},
      tenders: const [OfflineTender(body: {'method': 'CASH'}, amount: 12.5)],
      total: 12.5,
      itemCount: 3,
      status: status,
      lastError: lastError,
    );

Future<OfflineQueueNotifier> _pump(
    WidgetTester tester, List<OfflineSale> sales) async {
  late OfflineQueueNotifier notifier;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      offlineQueueProvider.overrideWith((ref) {
        notifier = OfflineQueueNotifier(ref,
            storage: _MemStorage(), autoSync: false);
        return notifier;
      }),
    ],
    child: const MaterialApp(home: Scaffold(body: OfflineQueueScreen())),
  ));
  for (final s in sales) {
    await notifier.enqueue(s);
  }
  await tester.pump();
  return notifier;
}

void main() {
  testWidgets('an empty queue says so rather than showing a blank pane',
      (tester) async {
    await _pump(tester, const []);
    expect(find.text('Everything is synced'), findsOneWidget);
  });

  testWidgets('a waiting sale shows its reference, total and item count',
      (tester) async {
    await _pump(tester, [_sale()]);

    expect(find.text('Sale #123456  ·  GBP 12.50'), findsOneWidget);
    expect(find.textContaining('3 items'), findsOneWidget);
    expect(find.text('1 sale not yet on the server'), findsOneWidget);
    // Still in line: a spinner, and no way to throw it away.
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byTooltip('Discard'), findsNothing);
  });

  testWidgets('a rejected sale shows why, and offers retry and discard',
      (tester) async {
    await _pump(tester, [
      _sale(status: OfflineSaleStatus.failed, lastError: 'Store is closed.')
    ]);

    expect(find.text('Store is closed.'), findsOneWidget);
    expect(find.text('1 sale not yet on the server · 1 need attention'),
        findsOneWidget);
    expect(find.byTooltip('Try again'), findsOneWidget);
    expect(find.byTooltip('Discard'), findsOneWidget);
  });

  testWidgets('discarding is confirmed first — the customer has already paid',
      (tester) async {
    final notifier = await _pump(tester, [
      _sale(status: OfflineSaleStatus.failed, lastError: 'Rejected.')
    ]);

    await tester.tap(find.byTooltip('Discard'));
    await tester.pumpAndSettle();
    expect(find.text('Discard sale #123456?'), findsOneWidget);

    // Backing out leaves the sale exactly where it was.
    await tester.tap(find.text('Keep'));
    await tester.pumpAndSettle();
    expect(notifier.state, hasLength(1));

    await tester.tap(find.byTooltip('Discard'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Discard'));
    await tester.pumpAndSettle();
    expect(notifier.state, isEmpty);
    expect(find.text('Everything is synced'), findsOneWidget);
  });
}
