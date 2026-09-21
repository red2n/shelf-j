import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// Data retention (21.16). tenant-svc keeps the schedule: what the law of the
// countries the business trades in requires for each class of data, what the
// business set, the holds that stop a purge, and the register of every purge
// the services ran. Each purging service runs its own sweep; this screen asks
// each in turn.

final _base = '/${ApiConstants.tenant}/admin/tenant/retention';

class RetentionClass {
  final String code;
  final String name;

  /// DELETE, ANONYMISE or KEEP.
  final String purgeKind;
  final String? purgedBy;
  final String description;
  final int? floorDays;
  final String? floorScope;
  final String? floorCitation;
  final String? floorSummary;
  final int? periodDays;
  final DateTime? setAt;

  const RetentionClass({
    required this.code,
    required this.name,
    required this.purgeKind,
    this.purgedBy,
    required this.description,
    this.floorDays,
    this.floorScope,
    this.floorCitation,
    this.floorSummary,
    this.periodDays,
    this.setAt,
  });

  bool get isSet => periodDays != null;
  bool get isKept => purgeKind == 'KEEP';

  factory RetentionClass.fromJson(Map<String, dynamic> j) => RetentionClass(
    code: j['code'] as String? ?? '',
    name: j['name'] as String? ?? '',
    purgeKind: j['purgeKind'] as String? ?? 'KEEP',
    purgedBy: j['purgedBy'] as String?,
    description: j['description'] as String? ?? '',
    floorDays: (j['floorDays'] as num?)?.toInt(),
    floorScope: j['floorScope'] as String?,
    floorCitation: j['floorCitation'] as String?,
    floorSummary: j['floorSummary'] as String?,
    periodDays: (j['periodDays'] as num?)?.toInt(),
    setAt: _time(j['setAt']),
  );
}

class RetentionHold {
  final String id;
  final String? dataClass;
  final String subjectKind;
  final String? subjectId;
  final String reason;
  final DateTime? placedAt;
  final bool active;
  final String? releaseReason;

  const RetentionHold({
    required this.id,
    this.dataClass,
    required this.subjectKind,
    this.subjectId,
    required this.reason,
    this.placedAt,
    this.active = true,
    this.releaseReason,
  });

  factory RetentionHold.fromJson(Map<String, dynamic> j) => RetentionHold(
    id: j['id'] as String? ?? '',
    dataClass: j['dataClass'] as String?,
    subjectKind: j['subjectKind'] as String? ?? 'ALL',
    subjectId: j['subjectId'] as String?,
    reason: j['reason'] as String? ?? '',
    placedAt: _time(j['placedAt']),
    active: j['active'] as bool? ?? true,
    releaseReason: j['releaseReason'] as String?,
  );
}

class RetentionSheet {
  final String country;
  final List<String> countries;
  final List<RetentionClass> classes;
  final List<RetentionHold> holds;

  const RetentionSheet({
    required this.country,
    this.countries = const [],
    this.classes = const [],
    this.holds = const [],
  });

  factory RetentionSheet.fromJson(Map<String, dynamic> j) => RetentionSheet(
    country: j['country'] as String? ?? '',
    countries: [for (final c in (j['countries'] as List?) ?? const []) '$c'],
    classes: _list(j['classes'], RetentionClass.fromJson),
    holds: _list(j['holds'], RetentionHold.fromJson),
  );
}

class RetentionRun {
  final String id;
  final String service;
  final String dataClass;
  final DateTime? cutoff;
  final int rowsAffected;
  final int heldSkipped;
  final DateTime? finishedAt;

  const RetentionRun({
    required this.id,
    required this.service,
    required this.dataClass,
    this.cutoff,
    required this.rowsAffected,
    required this.heldSkipped,
    this.finishedAt,
  });

  factory RetentionRun.fromJson(Map<String, dynamic> j) => RetentionRun(
    id: j['id'] as String? ?? '',
    service: j['service'] as String? ?? '',
    dataClass: j['dataClass'] as String? ?? '',
    cutoff: _time(j['cutoff']),
    rowsAffected: (j['rowsAffected'] as num?)?.toInt() ?? 0,
    heldSkipped: (j['heldSkipped'] as num?)?.toInt() ?? 0,
    finishedAt: _time(j['finishedAt']),
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

/// "6 years", "90 days", "at once".
String describeDays(int days) {
  if (days == 0) return 'at once';
  if (days % 365 == 0) {
    final y = days ~/ 365;
    return y == 1 ? '1 year' : '$y years';
  }
  return days == 1 ? '1 day' : '$days days';
}

/// The services that purge, with the route each runs its sweep on.
const retentionSweeps = {
  'order-svc': '/${ApiConstants.order}/admin/orders/retention/sweep',
  'customer-svc': '/${ApiConstants.customer}/admin/customers/retention/sweep',
  'notification-svc':
      '/${ApiConstants.notification}/admin/notifications/retention/sweep',
};

// ── Readers ──────────────────────────────────────────────────────────────────

final retentionSheetProvider = FutureProvider.autoDispose<RetentionSheet>((
  ref,
) async {
  final resp = await ref.read(apiClientProvider).dio.get(_base);
  return RetentionSheet.fromJson(
    (resp.data['data'] as Map).cast<String, dynamic>(),
  );
});

final retentionRunsProvider = FutureProvider.autoDispose<List<RetentionRun>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('$_base/runs', queryParameters: {'limit': 50});
  return _list(resp.data['data'], RetentionRun.fromJson);
});

// ── Writes ───────────────────────────────────────────────────────────────────

Future<RetentionClass> setRetentionPeriod(
  Dio dio, {
  required String dataClass,
  required int periodDays,
}) async {
  final resp = await dio.put(
    '$_base/$dataClass',
    data: {'periodDays': periodDays},
  );
  return RetentionClass.fromJson(
    (resp.data['data'] as Map).cast<String, dynamic>(),
  );
}

Future<RetentionHold> placeRetentionHold(
  Dio dio, {
  String? dataClass,
  required String subjectKind,
  String? subjectId,
  required String reason,
}) async {
  final resp = await dio.post(
    '$_base/holds',
    data: {
      'dataClass': ?dataClass,
      'subjectKind': subjectKind,
      'subjectId': ?subjectId,
      'reason': reason.trim(),
    },
  );
  return RetentionHold.fromJson(
    (resp.data['data'] as Map).cast<String, dynamic>(),
  );
}

Future<void> releaseRetentionHold(
  Dio dio, {
  required String holdId,
  required String reason,
}) => dio.post('$_base/holds/$holdId/release', data: {'reason': reason.trim()});

/// Runs one service's purge now and returns what it did.
Future<RetentionRun> runRetentionSweep(Dio dio, String service) async {
  final resp = await dio.post(retentionSweeps[service]!, data: const {});
  final j = (resp.data['data'] as Map).cast<String, dynamic>();
  return RetentionRun.fromJson({...j, 'id': '', 'service': service});
}
