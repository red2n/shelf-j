import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../constants.dart';
import '../network/api_client.dart';
import '../network/api_error.dart';
import '../storage/app_storage.dart';
import 'offline_sale.dart';

/// Store-and-forward queue for POS sales taken while the server was unreachable.
///
/// Sales are held on the device, replayed FIFO, and only dropped once every write
/// they owe has been accepted. Replay leans entirely on the idempotency keys the
/// till stamps at capture time — see [OfflineSale] for why each step is safe to
/// send twice.
///
/// What this does *not* do: let a cashier ring up an item that was never loaded.
/// Scanning still resolves the barcode and price against the server, so an
/// offline till can only complete a sale whose lines are already in the cart. A
/// cached catalog is the separate, larger piece of work.
final offlineQueueProvider =
    StateNotifierProvider<OfflineQueueNotifier, List<OfflineSale>>(
        (ref) => OfflineQueueNotifier(ref));

/// How many sales are waiting to reach the server (failed ones included — they
/// are still money the server has not been told about).
final offlineQueueCountProvider =
    Provider<int>((ref) => ref.watch(offlineQueueProvider).length);

class OfflineQueueNotifier extends StateNotifier<List<OfflineSale>> {
  final Ref _ref;
  final AppStorage _storage;

  /// When false the queue never schedules a replay of its own — callers drive it.
  /// Tests use this so a background timer cannot fire mid-assertion.
  final bool _autoSync;

  /// Serialises replay runs: the periodic timer, a manual "Sync now" and an
  /// enqueue can all fire at once, and two concurrent runs would replay the same
  /// sale twice. Idempotency makes that harmless on the server, but it would
  /// double-count attempts and confuse the pending list.
  bool _syncing = false;

  Timer? _timer;
  int _consecutiveFailures = 0;

  /// True once a replay has failed to reach the server and not yet succeeded —
  /// what the shell shows as "offline".
  bool get isOffline => _consecutiveFailures > 0;

  OfflineQueueNotifier(this._ref, {AppStorage storage = const AppStorage(), bool autoSync = true})
      : _storage = storage,
        _autoSync = autoSync,
        super(const []) {
    restore().then((_) {
      if (state.isNotEmpty) _scheduleNext();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  // ── persistence ───────────────────────────────────────────────────────────

  Future<void> restore() async {
    try {
      final raw = await _storage.read(key: StorageKeys.posOfflineSales);
      if (raw == null || raw.isEmpty) return;
      state = (jsonDecode(raw) as List)
          .map((e) => OfflineSale.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } catch (_) {
      // A corrupt queue must not brick the till. It is left on disk rather than
      // cleared: the sales in it are money, and a developer can still recover the
      // raw JSON. The till starts with an empty in-memory queue.
    }
  }

  Future<void> _persist() async {
    await _storage.write(
      key: StorageKeys.posOfflineSales,
      value: jsonEncode(state.map((s) => s.toJson()).toList()),
    );
  }

  // ── queue operations ──────────────────────────────────────────────────────

  /// Take a sale the server could not be told about. Persisted before returning,
  /// so the cashier is only told "saved" once it is genuinely on disk.
  Future<void> enqueue(OfflineSale sale) async {
    state = [...state, sale];
    await _persist();
    _scheduleNext(immediate: true);
  }

  /// Put a parked (failed) sale back in line — after the cause has been dealt
  /// with, e.g. a store that was closed has been reopened.
  Future<void> retry(String id) async {
    state = [
      for (final s in state)
        if (s.id == id)
          s.copyWith(status: OfflineSaleStatus.pending, clearError: true)
        else
          s,
    ];
    await _persist();
    await sync();
  }

  /// Drop a sale without sending it. Destructive — the server will never learn
  /// about this money — so the UI confirms first and only offers it for a sale
  /// the server has permanently rejected.
  Future<void> discard(String id) async {
    state = [
      for (final s in state)
        if (s.id != id) s,
    ];
    await _persist();
  }

  // ── replay ────────────────────────────────────────────────────────────────

  /// Replay everything waiting, oldest first. Safe to call at any time.
  Future<void> sync() async {
    if (_syncing || state.isEmpty) return;
    _syncing = true;
    try {
      // Snapshot the ids: `state` is rewritten as sales complete.
      for (final id in state.map((s) => s.id).toList()) {
        final sale = state.where((s) => s.id == id).firstOrNull;
        if (sale == null || sale.status == OfflineSaleStatus.failed) continue;
        final outcome = await _replay(sale);
        if (outcome == _Outcome.unreachable) break; // no point trying the rest
      }
    } finally {
      _syncing = false;
      _scheduleNext();
    }
  }

  Future<_Outcome> _replay(OfflineSale sale) async {
    final dio = _ref.read(apiClientProvider).dio;
    var current = sale.copyWith(attempts: sale.attempts + 1);
    await _replace(current);

    try {
      // 1. Place the order. Replays on the stored key: order-svc returns the
      //    original order for a duplicate rather than creating a second one.
      if (current.orderId == null) {
        final resp = await dio.post(
          '/${ApiConstants.order}/orders',
          data: current.orderRequest,
          options: Options(headers: {'Idempotency-Key': '${current.id}-order'}),
        );
        final order = resp.data['data'] as Map<String, dynamic>;
        current = current.copyWith(orderId: order['id'] as String? ?? '');
        await _replace(current);
      }

      // 2. Record each tender, then redeem any gift card it drew on. The redeem
      //    is second so a card is never debited for a tender that did not land.
      for (var i = 0; i < current.tenders.length; i++) {
        final t = current.tenders[i];
        if (!t.tenderDone) {
          await dio.post(
            '/${ApiConstants.payment}/payments',
            data: {...t.body, 'orderId': current.orderId},
            options: Options(headers: {'Idempotency-Key': '${current.id}-pay$i'}),
          );
          current = current.markTender(i, tenderDone: true);
          await _replace(current);
        }
        final code = current.tenders[i].giftCardCode;
        if (code != null && !current.tenders[i].redeemDone) {
          await dio.post(
            '/${ApiConstants.order}/gift-cards/$code/redeem',
            data: {'amount': current.tenders[i].amount, 'orderId': current.orderId},
          );
          current = current.markTender(i, redeemDone: true);
          await _replace(current);
        }
      }

      // Everything landed — the server now knows about this sale.
      await discard(current.id);
      _consecutiveFailures = 0;
      return _Outcome.done;
    } catch (e) {
      if (isOfflineError(e)) {
        _consecutiveFailures++;
        await _replace(current.copyWith(
            lastError: 'Waiting for the network.'));
        return _Outcome.unreachable;
      }
      if (isPermanentRejection(e)) {
        // Retrying will not change the answer. Park it for a human instead of
        // looping: the customer has already paid, so it must not be dropped.
        await _replace(current.copyWith(
          status: OfflineSaleStatus.failed,
          lastError: friendlyError(e, fallback: 'The server rejected this sale.'),
        ));
        return _Outcome.rejected;
      }
      // 5xx or anything else transient: keep it pending and back off.
      _consecutiveFailures++;
      await _replace(current.copyWith(
          lastError: friendlyError(e, fallback: 'Could not sync this sale.')));
      return _Outcome.unreachable;
    }
  }

  /// Write one sale back into the queue and persist, so progress survives the app
  /// being killed halfway through a replay.
  Future<void> _replace(OfflineSale sale) async {
    state = [
      for (final s in state)
        if (s.id == sale.id) sale else s,
    ];
    await _persist();
  }

  // ── retry cadence ─────────────────────────────────────────────────────────

  /// Poll while anything is waiting, backing off so a till left offline overnight
  /// is not opening a socket every few seconds. Stops entirely once the queue
  /// drains, and starts again on the next enqueue.
  void _scheduleNext({bool immediate = false}) {
    _timer?.cancel();
    if (!_autoSync) return;
    if (state.every((s) => s.status == OfflineSaleStatus.failed)) return;
    final delay = immediate
        ? Duration.zero
        : Duration(seconds: switch (_consecutiveFailures) {
            0 => 10,
            1 => 15,
            2 => 30,
            _ => 60,
          });
    _timer = Timer(delay, sync);
  }
}

enum _Outcome { done, rejected, unreachable }
