import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/paged.dart';

// Temperature monitoring and HACCP checks. The server judges every check and
// keeps the limits it judged against; the app shows a live preview so a
// member of staff knows before saving, but never sends a verdict of its own
// for a temperature — a warm chiller cannot be declared a pass from here.

final _staffBase = '/${ApiConstants.inventory}/admin/inventory/food-safety';
final _setupBase = '/${ApiConstants.inventory}/admin/food-safety';

// ── Models ───────────────────────────────────────────────────────────────────

class FsCheckType {
  final String id;
  final String code;
  final String name;

  /// TEMPERATURE takes a reading in °C; PASS_FAIL takes a yes or no.
  final String kind;
  final double? minValue;
  final double? maxValue;
  final String? basis;

  /// True only where the limit is law rather than guidance.
  final bool statutory;
  final bool platform;

  const FsCheckType({
    required this.id,
    required this.code,
    required this.name,
    required this.kind,
    this.minValue,
    this.maxValue,
    this.basis,
    this.statutory = false,
    this.platform = false,
  });

  bool get isTemperature => kind == 'TEMPERATURE';

  factory FsCheckType.fromJson(Map<String, dynamic> j) => FsCheckType(
        id: j['id'] as String? ?? '',
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        kind: j['kind'] as String? ?? 'PASS_FAIL',
        minValue: (j['minValue'] as num?)?.toDouble(),
        maxValue: (j['maxValue'] as num?)?.toDouble(),
        basis: j['basis'] as String?,
        statutory: j['statutory'] as bool? ?? false,
        platform: j['platform'] as bool? ?? false,
      );
}

class FsPoint {
  final String id;
  final String storeId;
  final String name;
  final FsCheckType checkType;
  final double? minValue;
  final double? maxValue;
  final int frequencyHours;
  final bool active;
  final DateTime? lastRecordedAt;
  final String? lastResult;
  final double? lastValue;
  final DateTime? nextDueAt;

  /// OK, DUE or OVERDUE, as the server computed it.
  final String dueStatus;
  final int openFailures;

  const FsPoint({
    required this.id,
    required this.storeId,
    required this.name,
    required this.checkType,
    this.minValue,
    this.maxValue,
    required this.frequencyHours,
    required this.active,
    this.lastRecordedAt,
    this.lastResult,
    this.lastValue,
    this.nextDueAt,
    required this.dueStatus,
    required this.openFailures,
  });

  /// What the server will judge a reading against. Null for a pass/fail check.
  String? get limitLabel => formatLimits(minValue, maxValue);

  /// PASS or FAIL for a reading, mirroring the server's inclusive bounds. A
  /// preview only: the saved result is always the server's.
  String? preview(double reading) {
    if (minValue != null && reading < minValue!) return 'FAIL';
    if (maxValue != null && reading > maxValue!) return 'FAIL';
    return 'PASS';
  }

  factory FsPoint.fromJson(Map<String, dynamic> j) => FsPoint(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        checkType: FsCheckType.fromJson(
            (j['checkType'] as Map?)?.cast<String, dynamic>() ?? const {}),
        minValue: (j['minValue'] as num?)?.toDouble(),
        maxValue: (j['maxValue'] as num?)?.toDouble(),
        frequencyHours: (j['frequencyHours'] as num?)?.toInt() ?? 24,
        active: j['active'] as bool? ?? true,
        lastRecordedAt: _time(j['lastRecordedAt']),
        lastResult: j['lastResult'] as String?,
        lastValue: (j['lastValue'] as num?)?.toDouble(),
        nextDueAt: _time(j['nextDueAt']),
        dueStatus: j['dueStatus'] as String? ?? 'OK',
        openFailures: (j['openFailures'] as num?)?.toInt() ?? 0,
      );
}

class FsCorrectiveAction {
  final String action;
  final String foodDisposition;
  final DateTime? recordedAt;

  const FsCorrectiveAction({
    required this.action,
    required this.foodDisposition,
    this.recordedAt,
  });

  factory FsCorrectiveAction.fromJson(Map<String, dynamic> j) =>
      FsCorrectiveAction(
        action: j['action'] as String? ?? '',
        foodDisposition: j['foodDisposition'] as String? ?? 'NONE',
        recordedAt: _time(j['recordedAt']),
      );
}

class FsRecord {
  final String id;
  final String pointId;
  final String pointName;
  final String checkTypeName;
  final String kind;
  final double? value;
  final double? minValue;
  final double? maxValue;
  final String result;
  final String? notes;
  final DateTime? recordedAt;
  final bool openFailure;
  final int correctiveActionCount;

  const FsRecord({
    required this.id,
    required this.pointId,
    required this.pointName,
    required this.checkTypeName,
    required this.kind,
    this.value,
    this.minValue,
    this.maxValue,
    required this.result,
    this.notes,
    this.recordedAt,
    required this.openFailure,
    required this.correctiveActionCount,
  });

  bool get failed => result == 'FAIL';

  /// "9.50 °C" for a reading, "Passed"/"Failed" for a checklist.
  String get reading => value != null
      ? '${value!.toStringAsFixed(2)} °C'
      : (failed ? 'Failed' : 'Passed');

  factory FsRecord.fromJson(Map<String, dynamic> j) => FsRecord(
        id: j['id'] as String? ?? '',
        pointId: j['pointId'] as String? ?? '',
        pointName: j['pointName'] as String? ?? '-',
        checkTypeName: j['checkTypeName'] as String? ?? '',
        kind: j['kind'] as String? ?? 'PASS_FAIL',
        value: (j['value'] as num?)?.toDouble(),
        minValue: (j['minValue'] as num?)?.toDouble(),
        maxValue: (j['maxValue'] as num?)?.toDouble(),
        result: j['result'] as String? ?? 'PASS',
        notes: j['notes'] as String?,
        recordedAt: _time(j['recordedAt']),
        openFailure: j['openFailure'] as bool? ?? false,
        correctiveActionCount:
            (j['correctiveActionCount'] as num?)?.toInt() ?? 0,
      );
}

class FsReview {
  final String id;
  final DateTime? periodFrom;
  final DateTime? periodTo;
  final int recordsCount;
  final int failuresCount;
  final int openFailuresCount;
  final String? notes;
  final DateTime? reviewedAt;

  const FsReview({
    required this.id,
    this.periodFrom,
    this.periodTo,
    required this.recordsCount,
    required this.failuresCount,
    required this.openFailuresCount,
    this.notes,
    this.reviewedAt,
  });

  factory FsReview.fromJson(Map<String, dynamic> j) => FsReview(
        id: j['id'] as String? ?? '',
        periodFrom: _time(j['periodFrom']),
        periodTo: _time(j['periodTo']),
        recordsCount: (j['recordsCount'] as num?)?.toInt() ?? 0,
        failuresCount: (j['failuresCount'] as num?)?.toInt() ?? 0,
        openFailuresCount: (j['openFailuresCount'] as num?)?.toInt() ?? 0,
        notes: j['notes'] as String?,
        reviewedAt: _time(j['reviewedAt']),
      );
}

/// The foods' fate after a failure, in the order a member of staff reaches for them.
const foodDispositions = <String, String>{
  'NONE': 'No food affected',
  'MOVED': 'Moved to working storage',
  'DISCARDED': 'Discarded',
  'REHEATED': 'Reheated',
  'RECOOKED': 'Recooked',
  'OTHER': 'Other',
};

/// "≤ 8.00 °C", "≥ 63.00 °C" or "0.00 – 5.00 °C"; null when there are none.
String? formatLimits(double? min, double? max) {
  if (min == null && max == null) return null;
  if (min != null && max != null) {
    return '${min.toStringAsFixed(2)} – ${max.toStringAsFixed(2)} °C';
  }
  return min != null
      ? '≥ ${min.toStringAsFixed(2)} °C'
      : '≤ ${max!.toStringAsFixed(2)} °C';
}

DateTime? _time(Object? v) =>
    v is String ? DateTime.tryParse(v)?.toLocal() : null;

/// One key per attempt at a check, reused if that attempt is retried, so a
/// tablet on poor Wi-Fi records the reading once however often it resends.
String newFoodSafetyKey() {
  final r = Random.secure();
  final bytes = List<int>.generate(16, (_) => r.nextInt(256));
  return 'fs-${bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join()}';
}

// ── Selection ────────────────────────────────────────────────────────────────

/// The store whose checks are on screen.
final foodSafetyStoreProvider = StateProvider<String?>((ref) => null);

// ── Readers ──────────────────────────────────────────────────────────────────

class FsPointsQuery {
  final String storeId;
  final bool includeInactive;
  const FsPointsQuery(this.storeId, {this.includeInactive = false});

  @override
  bool operator ==(Object other) =>
      other is FsPointsQuery &&
      other.storeId == storeId &&
      other.includeInactive == includeInactive;

  @override
  int get hashCode => Object.hash(storeId, includeInactive);
}

final foodSafetyPointsProvider = FutureProvider.autoDispose
    .family<List<FsPoint>, FsPointsQuery>((ref, q) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '$_staffBase/points',
    queryParameters: {
      'storeId': q.storeId,
      if (q.includeInactive) 'includeInactive': true,
    },
  );
  final data = (resp.data['data'] as List?) ?? const [];
  return data
      .map((e) => FsPoint.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

final foodSafetyCheckTypesProvider =
    FutureProvider.autoDispose<List<FsCheckType>>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('$_staffBase/check-types');
  final data = (resp.data['data'] as List?) ?? const [];
  return data
      .map((e) => FsCheckType.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

class FsDiaryQuery {
  final String storeId;
  final bool openOnly;

  /// Local calendar days, turned into a half-open UTC window at the edge:
  /// from the first day's midnight to the midnight after the last (SJ-D30).
  final DateTime fromDay;
  final DateTime toDay;

  const FsDiaryQuery({
    required this.storeId,
    required this.openOnly,
    required this.fromDay,
    required this.toDay,
  });

  Map<String, dynamic> get params => {
        'storeId': storeId,
        if (openOnly) 'openOnly': true,
        'from': DateTime(fromDay.year, fromDay.month, fromDay.day)
            .toUtc()
            .toIso8601String(),
        'to': DateTime(toDay.year, toDay.month, toDay.day + 1)
            .toUtc()
            .toIso8601String(),
      };

  @override
  bool operator ==(Object other) =>
      other is FsDiaryQuery &&
      other.storeId == storeId &&
      other.openOnly == openOnly &&
      other.fromDay == fromDay &&
      other.toDay == toDay;

  @override
  int get hashCode => Object.hash(storeId, openOnly, fromDay, toDay);
}

/// Every record in the window, newest first — a store's diary for a month is
/// a few hundred rows, and an inspector asks for all of it.
final foodSafetyDiaryProvider = FutureProvider.autoDispose
    .family<List<FsRecord>, FsDiaryQuery>((ref, q) async {
  final data = await fetchAllPages(
    ref.read(apiClientProvider).dio,
    '$_staffBase/records',
    query: q.params,
  );
  return data
      .map((e) => FsRecord.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

final foodSafetyReviewsProvider = FutureProvider.autoDispose
    .family<List<FsReview>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '$_setupBase/reviews',
    queryParameters: {'storeId': storeId, 'limit': 50},
  );
  final data = (resp.data['data'] as List?) ?? const [];
  return data
      .map((e) => FsReview.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

// ── Writes ───────────────────────────────────────────────────────────────────

/// Records a check and returns the server's record of it — including its
/// verdict, which is the only one that counts.
Future<FsRecord> recordFoodSafetyCheck(
  Dio dio, {
  required String pointId,
  double? value,
  bool? passed,
  String? notes,
  required String idempotencyKey,
}) async {
  final resp = await dio.post(
    '$_staffBase/records',
    data: {
      'pointId': pointId,
      'value': ?value,
      'passed': ?passed,
      if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
    },
    options: Options(headers: {'Idempotency-Key': idempotencyKey}),
  );
  return FsRecord.fromJson((resp.data['data'] as Map).cast<String, dynamic>());
}

Future<void> addFoodSafetyCorrectiveAction(
  Dio dio, {
  required String recordId,
  required String action,
  required String foodDisposition,
}) =>
    dio.post(
      '$_staffBase/records/$recordId/corrective-actions',
      data: {'action': action.trim(), 'foodDisposition': foodDisposition},
    );

Future<void> saveFoodSafetyPoint(
  Dio dio, {
  String? pointId,
  required String storeId,
  required String name,
  String? checkTypeId,
  double? minValue,
  double? maxValue,
  required int frequencyHours,
}) {
  final body = {
    'name': name.trim(),
    'minValue': ?minValue,
    'maxValue': ?maxValue,
    'frequencyHours': frequencyHours,
  };
  return pointId == null
      ? dio.post('$_setupBase/points',
          data: {...body, 'storeId': storeId, 'checkTypeId': checkTypeId})
      : dio.put('$_setupBase/points/$pointId', data: body);
}

Future<void> switchFoodSafetyPoint(
  Dio dio, {
  required String pointId,
  required bool active,
  required String reason,
}) =>
    dio.post(
      '$_setupBase/points/$pointId/${active ? 'activate' : 'deactivate'}',
      data: {'reason': reason.trim()},
    );

Future<void> signOffFoodSafetyReview(
  Dio dio, {
  required String storeId,
  required DateTime fromDay,
  required DateTime toDay,
  String? notes,
}) =>
    dio.post('$_setupBase/reviews', data: {
      'storeId': storeId,
      'from': DateTime(fromDay.year, fromDay.month, fromDay.day)
          .toUtc()
          .toIso8601String(),
      'to': DateTime(toDay.year, toDay.month, toDay.day + 1)
          .toUtc()
          .toIso8601String(),
      if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
    });
