import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// Withdrawals and recalls. Opening one takes every pack in scope off sale at
// every store on the server, in one go; what the app has to get right is the
// evidence afterwards — what each store found, what became of it, and that a
// pack the recall could not rule out is checked before it goes back on sale.

final _staffBase = '/${ApiConstants.inventory}/admin/inventory/recalls';
final _setupBase = '/${ApiConstants.inventory}/admin/recalls';

// ── Models ───────────────────────────────────────────────────────────────────

class RecallSummary {
  final String id;
  final String reference;
  final String kind;
  final String hazard;
  final String status;
  final DateTime? openedAt;
  final int storesAffected;
  final int storesOutstanding;
  final double qtyHeld;

  const RecallSummary({
    required this.id,
    required this.reference,
    required this.kind,
    required this.hazard,
    required this.status,
    this.openedAt,
    this.storesAffected = 0,
    this.storesOutstanding = 0,
    this.qtyHeld = 0,
  });

  factory RecallSummary.fromJson(Map<String, dynamic> j) => RecallSummary(
    id: j['id'] as String? ?? '',
    reference: j['reference'] as String? ?? '-',
    kind: j['kind'] as String? ?? 'RECALL',
    hazard: j['hazard'] as String? ?? 'OTHER',
    status: j['status'] as String? ?? 'OPEN',
    openedAt: _time(j['openedAt']),
    storesAffected: (j['storesAffected'] as num?)?.toInt() ?? 0,
    storesOutstanding: (j['storesOutstanding'] as num?)?.toInt() ?? 0,
    qtyHeld: (j['qtyHeld'] as num?)?.toDouble() ?? 0,
  );
}

class RecallScopeLine {
  final String variantId;
  final String? batchNo;
  final String? expiryFrom;
  final String? expiryTo;

  const RecallScopeLine({
    required this.variantId,
    this.batchNo,
    this.expiryFrom,
    this.expiryTo,
  });

  bool get coversEveryPack =>
      batchNo == null && expiryFrom == null && expiryTo == null;

  factory RecallScopeLine.fromJson(Map<String, dynamic> j) => RecallScopeLine(
    variantId: j['variantId'] as String? ?? '',
    batchNo: j['batchNo'] as String?,
    expiryFrom: j['expiryFrom'] as String?,
    expiryTo: j['expiryTo'] as String?,
  );

  Map<String, dynamic> toJson() => {
    'variantId': variantId,
    'batchNo': ?batchNo,
    'expiryFrom': ?expiryFrom,
    'expiryTo': ?expiryTo,
  };
}

class RecallHeldBatch {
  final String batchId;
  final String storeId;
  final String variantId;
  final String? batchNo;
  final String? expiryDate;

  /// IN_SCOPE, or LOT_UNKNOWN / DATE_UNKNOWN for a pack that could be affected.
  final String match;
  final double qtyAtQuarantine;
  final double remainingQty;
  final String quarantinedOn;
  final bool released;
  final String? releaseReason;

  const RecallHeldBatch({
    required this.batchId,
    required this.storeId,
    required this.variantId,
    this.batchNo,
    this.expiryDate,
    required this.match,
    required this.qtyAtQuarantine,
    required this.remainingQty,
    this.quarantinedOn = 'OPEN',
    this.released = false,
    this.releaseReason,
  });

  bool get releasable => match != 'IN_SCOPE' && !released;

  factory RecallHeldBatch.fromJson(Map<String, dynamic> j) => RecallHeldBatch(
    batchId: j['batchId'] as String? ?? '',
    storeId: j['storeId'] as String? ?? '',
    variantId: j['variantId'] as String? ?? '',
    batchNo: j['batchNo'] as String?,
    expiryDate: j['expiryDate'] as String?,
    match: j['match'] as String? ?? 'IN_SCOPE',
    qtyAtQuarantine: (j['qtyAtQuarantine'] as num?)?.toDouble() ?? 0,
    remainingQty: (j['remainingQty'] as num?)?.toDouble() ?? 0,
    quarantinedOn: j['quarantinedOn'] as String? ?? 'OPEN',
    released: j['released'] as bool? ?? false,
    releaseReason: j['releaseReason'] as String?,
  );
}

class RecallStoreAction {
  final String storeId;
  final double qtyFound;
  final double systemQty;
  final String disposition;
  final bool noticeDisplayed;
  final String? notes;
  final DateTime? recordedAt;

  const RecallStoreAction({
    required this.storeId,
    required this.qtyFound,
    required this.systemQty,
    required this.disposition,
    this.noticeDisplayed = false,
    this.notes,
    this.recordedAt,
  });

  factory RecallStoreAction.fromJson(Map<String, dynamic> j) =>
      RecallStoreAction(
        storeId: j['storeId'] as String? ?? '',
        qtyFound: (j['qtyFound'] as num?)?.toDouble() ?? 0,
        systemQty: (j['systemQty'] as num?)?.toDouble() ?? 0,
        disposition: j['disposition'] as String? ?? 'HELD_FOR_COLLECTION',
        noticeDisplayed: j['noticeDisplayed'] as bool? ?? false,
        notes: j['notes'] as String?,
        recordedAt: _time(j['recordedAt']),
      );
}

class RecallStoreProgress {
  final String storeId;
  final double qtyHeld;
  final double? qtyFound;
  final bool outstanding;

  const RecallStoreProgress({
    required this.storeId,
    required this.qtyHeld,
    this.qtyFound,
    required this.outstanding,
  });

  factory RecallStoreProgress.fromJson(Map<String, dynamic> j) =>
      RecallStoreProgress(
        storeId: j['storeId'] as String? ?? '',
        qtyHeld: (j['qtyHeld'] as num?)?.toDouble() ?? 0,
        qtyFound: (j['qtyFound'] as num?)?.toDouble(),
        outstanding: j['outstanding'] as bool? ?? false,
      );
}

class RecallDetail {
  final String id;
  final String reference;
  final String kind;
  final String hazard;
  final String reason;
  final String? customerNotice;
  final String source;
  final String? sourceReference;
  final String status;
  final DateTime? openedAt;
  final String? endNotes;
  final List<RecallScopeLine> items;
  final List<RecallHeldBatch> batches;
  final List<RecallStoreAction> storeActions;
  final List<RecallStoreProgress> stores;

  const RecallDetail({
    required this.id,
    required this.reference,
    required this.kind,
    required this.hazard,
    required this.reason,
    this.customerNotice,
    required this.source,
    this.sourceReference,
    required this.status,
    this.openedAt,
    this.endNotes,
    this.items = const [],
    this.batches = const [],
    this.storeActions = const [],
    this.stores = const [],
  });

  bool get isOpen => status == 'OPEN';
  bool get isRecall => kind == 'RECALL';

  factory RecallDetail.fromJson(Map<String, dynamic> j) => RecallDetail(
    id: j['id'] as String? ?? '',
    reference: j['reference'] as String? ?? '-',
    kind: j['kind'] as String? ?? 'RECALL',
    hazard: j['hazard'] as String? ?? 'OTHER',
    reason: j['reason'] as String? ?? '',
    customerNotice: j['customerNotice'] as String?,
    source: j['source'] as String? ?? 'OTHER',
    sourceReference: j['sourceReference'] as String?,
    status: j['status'] as String? ?? 'OPEN',
    openedAt: _time(j['openedAt']),
    endNotes: j['endNotes'] as String?,
    items: _list(j['items'], RecallScopeLine.fromJson),
    batches: _list(j['batches'], RecallHeldBatch.fromJson),
    storeActions: _list(j['storeActions'], RecallStoreAction.fromJson),
    stores: _list(j['stores'], RecallStoreProgress.fromJson),
  );
}

List<T> _list<T>(Object? v, T Function(Map<String, dynamic>) from) => v is List
    ? [
        for (final e in v)
          if (e is Map) from(e.cast<String, dynamic>()),
      ]
    : <T>[];

DateTime? _time(Object? v) =>
    v is String ? DateTime.tryParse(v)?.toLocal() : null;

String hazardLabel(String hazard) => switch (hazard) {
  'MICROBIOLOGICAL' => 'Microbiological contamination',
  'ALLERGEN' => 'Undeclared allergen',
  'FOREIGN_BODY' => 'Foreign body',
  'CHEMICAL' => 'Chemical contamination',
  'LABELLING' => 'Labelling error',
  'QUALITY' => 'Quality defect',
  _ => 'Other safety issue',
};

String dispositionLabel(String disposition) => switch (disposition) {
  'RETURNED_TO_SUPPLIER' => 'Returned to supplier',
  'DESTROYED' => 'Destroyed',
  _ => 'Held for collection',
};

/// What a scope line covers, in the words printed on a pack.
String describeScope(RecallScopeLine line) {
  if (line.coversEveryPack) return 'Every pack';
  final parts = <String>[
    if (line.batchNo != null) 'Lot ${line.batchNo}',
    if (line.expiryFrom != null && line.expiryTo != null)
      'dated ${line.expiryFrom} to ${line.expiryTo}'
    else if (line.expiryFrom != null)
      'dated ${line.expiryFrom} or later'
    else if (line.expiryTo != null)
      'dated ${line.expiryTo} or earlier',
  ];
  return parts.join(', ');
}

// ── Selection ────────────────────────────────────────────────────────────────

/// OPEN, CLOSED, CANCELLED, or null for every recall.
final recallStatusFilterProvider = StateProvider<String?>((ref) => 'OPEN');

// ── Readers ──────────────────────────────────────────────────────────────────

final recallsProvider = FutureProvider.autoDispose
    .family<List<RecallSummary>, String?>((ref, status) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(_staffBase, queryParameters: {'status': ?status, 'limit': 100});
      final data = (resp.data['data'] as List?) ?? const [];
      return [
        for (final e in data)
          if (e is Map) RecallSummary.fromJson(e.cast<String, dynamic>()),
      ];
    });

final recallDetailProvider = FutureProvider.autoDispose
    .family<RecallDetail, String>((ref, id) async {
      final resp = await ref.read(apiClientProvider).dio.get('$_staffBase/$id');
      return RecallDetail.fromJson(
        (resp.data['data'] as Map).cast<String, dynamic>(),
      );
    });

// ── Writes ───────────────────────────────────────────────────────────────────

Future<RecallDetail> openRecall(
  Dio dio, {
  required String reference,
  required String kind,
  required String hazard,
  required String reason,
  String? customerNotice,
  required String source,
  String? sourceReference,
  required List<RecallScopeLine> items,
}) async {
  final resp = await dio.post(
    _setupBase,
    data: {
      'reference': reference.trim(),
      'kind': kind,
      'hazard': hazard,
      'reason': reason.trim(),
      if (customerNotice != null && customerNotice.trim().isNotEmpty)
        'customerNotice': customerNotice.trim(),
      'source': source,
      if (sourceReference != null && sourceReference.trim().isNotEmpty)
        'sourceReference': sourceReference.trim(),
      'items': [for (final i in items) i.toJson()],
    },
  );
  return RecallDetail.fromJson(
    (resp.data['data'] as Map).cast<String, dynamic>(),
  );
}

Future<void> recordRecallStoreAction(
  Dio dio, {
  required String recallId,
  required String storeId,
  required double qtyFound,
  required String disposition,
  required bool noticeDisplayed,
  String? notes,
}) => dio.post(
  '$_staffBase/$recallId/stores/$storeId/actions',
  data: {
    'qtyFound': qtyFound,
    'disposition': disposition,
    'noticeDisplayed': noticeDisplayed,
    if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
  },
);

Future<void> releaseRecalledBatch(
  Dio dio, {
  required String recallId,
  required String batchId,
  required String reason,
}) => dio.post(
  '$_staffBase/$recallId/batches/$batchId/release',
  data: {'reason': reason.trim()},
);

Future<void> closeRecall(Dio dio, {required String recallId, String? notes}) =>
    dio.post(
      '$_setupBase/$recallId/close',
      data: {
        if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
      },
    );

Future<void> cancelRecall(
  Dio dio, {
  required String recallId,
  required String reason,
}) => dio.post('$_setupBase/$recallId/cancel', data: {'reason': reason.trim()});
