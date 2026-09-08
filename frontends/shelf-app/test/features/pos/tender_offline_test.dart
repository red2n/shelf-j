import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/auth/auth_notifier.dart';
import 'package:shelf_app/core/auth/auth_state.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/core/offline/offline_queue.dart';
import 'package:shelf_app/core/storage/app_storage.dart';
import 'package:shelf_app/features/pos/pos_providers.dart';
import 'package:shelf_app/features/pos/pos_session_providers.dart';
import 'package:shelf_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// The entry point to the offline queue: what the till does when Complete Sale
// cannot reach the server. A dropped network must finish the sale locally — the
// customer has handed over cash — while a server that answers "no" must not be
// queued, because replaying it would fail in exactly the same way.
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

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Either drops the connection or answers with a rejection, per [rejectStatus].
class _FailingAdapter implements HttpClientAdapter {
  int? rejectStatus;
  int calls = 0;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    calls++;
    if (rejectStatus != null) {
      return ResponseBody.fromString(
        '{"data":null,"error":{"code":"ORDER_STORE_CLOSED","message":"Store is closed."}}',
        rejectStatus!,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType]
        },
      );
    }
    throw DioException(
        requestOptions: options, type: DioExceptionType.connectionError);
  }
}

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _StubAuthNotifier extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

const _line = PosLine(
  variantId: 'v-1',
  sku: 'SKU-1',
  name: 'Product 1',
  qty: 2,
  unitPrice: 6.0,
  currency: 'GBP',
);

class _LoadedCart extends PosCartNotifier {
  _LoadedCart() {
    loadLines(const [_line]);
  }
}

Future<_FailingAdapter> _pumpTender(WidgetTester tester,
    {int? rejectStatus}) async {
  final adapter = _FailingAdapter()..rejectStatus = rejectStatus;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = adapter;

  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posStoresProvider.overrideWith((ref) async => const []),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuthNotifier.new),
      posCartProvider.overrideWith((ref) => _LoadedCart()),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posWalkInPhoneProvider.overrideWith((ref) => '07700900000'),
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  await tester.pumpAndSettle();
  return adapter;
}

/// Stage a cash tender for the full balance, then complete the sale.
Future<void> _tenderAndComplete(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();

  await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.pumpAndSettle();
}

ProviderContainer _container(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(TenderScreen).first));

void main() {
  testWidgets('an unreachable server completes the sale offline and queues it',
      (tester) async {
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    expect(find.text('Saved offline'), findsOneWidget);
    expect(
        find.textContaining("The server couldn't be reached"), findsOneWidget);

    final queued = _container(tester).read(offlineQueueProvider);
    expect(queued, hasLength(1), reason: 'the sale is held, not lost');
    expect(queued.single.total, 12.0);
    expect(queued.single.itemCount, 2);
    expect(queued.single.storeId, 'store-1');
    expect(queued.single.orderId, isNull, reason: 'the order never landed');
    expect(queued.single.tenders.single.body['method'], 'CASH');
    expect(queued.single.tenders.single.tenderDone, isFalse);
  });

  testWidgets('the offline receipt carries the reference shown to the cashier',
      (tester) async {
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    final reference = _container(tester).read(offlineQueueProvider).single.reference;
    expect(find.text('Sale #$reference'), findsOneWidget);
  });

  testWidgets('the till is cleared so the next customer can be served',
      (tester) async {
    await _pumpTender(tester);
    await _tenderAndComplete(tester);

    final container = _container(tester);
    expect(container.read(posCartProvider), isEmpty);
    expect(container.read(posWalkInPhoneProvider), '');
    expect(container.read(posDiscountProvider), 0);
  });

  testWidgets('a sale the server rejects is not queued', (tester) async {
    // Queueing it would tell the cashier the sale went through, and every replay
    // would be refused for the same reason.
    await _pumpTender(tester, rejectStatus: 422);
    await _tenderAndComplete(tester);

    expect(find.text('Saved offline'), findsNothing);
    expect(find.text('Store is closed.'), findsOneWidget);

    final container = _container(tester);
    expect(container.read(offlineQueueProvider), isEmpty);
    expect(container.read(posCartProvider), isNotEmpty,
        reason: 'the sale is still on the till for the cashier to deal with');
  });
}
