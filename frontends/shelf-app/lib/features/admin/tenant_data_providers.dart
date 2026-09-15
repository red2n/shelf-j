import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// Per-tenant export and leaving the platform (21.14, EU Data Act ch.VI).
//
// Every service serves what it holds for the business at /admin/tenant-data:
// a manifest of its tables with their rows and checksums, what it leaves out
// with the reason (the register art.26 asks for), and the rows page by page. A
// bundle is those rows as JSON Lines, one per row, after a header line naming
// every service's manifest. Importing one loads each table in the order the
// manifests give. tenant-svc keeps the owner's notice to leave and the evidence
// each service sends back once it has erased its share.

/// The services that hold a business's data, in the order an import loads them.
const tenantDataServices = <String>[
  ApiConstants.tenant,
  ApiConstants.iam,
  ApiConstants.product,
  ApiConstants.pricing,
  ApiConstants.inventory,
  ApiConstants.purchase,
  ApiConstants.order,
  ApiConstants.payment,
  ApiConstants.customer,
  ApiConstants.notification,
  ApiConstants.reporting,
  ApiConstants.cart,
];

/// What a bundle's first line says it is.
const tenantBundleFormat = 'shelfj-tenant-bundle/1';

/// Tables every service leaves out: how events and migrations moved, not data.
const tenantDataMachinery = {
  'outbox',
  'processed_events',
  'flyway_schema_history',
};

final _tableName = RegExp(r'^[a-z_][a-z0-9_]{0,62}$');

Map<String, String> _reasons(Object? j) => {
  for (final e in ((j as Map?) ?? const {}).entries)
    e.key.toString(): e.value.toString(),
};

/// One table in a service's manifest.
class TenantDataTable {
  final String name;
  final int rows;
  final String? checksum;
  final bool derived;
  final String? importSkippedReason;

  const TenantDataTable({
    required this.name,
    required this.rows,
    this.checksum,
    this.derived = false,
    this.importSkippedReason,
  });

  factory TenantDataTable.fromJson(Map<String, dynamic> j) => TenantDataTable(
    name: j['name'] as String? ?? '',
    rows: (j['rows'] as num?)?.toInt() ?? 0,
    checksum: j['checksum'] as String?,
    derived: j['derived'] == true,
    importSkippedReason: j['importSkippedReason'] as String?,
  );
}

/// What one service holds for the business, and what it leaves out.
class TenantDataManifest {
  final String service;
  final String format;
  final List<TenantDataTable> tables;
  final Map<String, String> excludedTables;
  final Map<String, String> excludedColumns;
  final Map<String, String> keptAtErasure;

  const TenantDataManifest({
    required this.service,
    required this.format,
    required this.tables,
    this.excludedTables = const {},
    this.excludedColumns = const {},
    this.keptAtErasure = const {},
  });

  int get rows => tables.fold(0, (n, t) => n + t.rows);

  factory TenantDataManifest.fromJson(String service, Map<String, dynamic> j) =>
      TenantDataManifest(
        service: service,
        format: j['format'] as String? ?? '',
        tables: ((j['tables'] as List?) ?? const [])
            .map(
              (e) =>
                  TenantDataTable.fromJson((e as Map).cast<String, dynamic>()),
            )
            .toList(),
        excludedTables: _reasons(j['excludedTables']),
        excludedColumns: _reasons(j['excludedColumns']),
        keptAtErasure: _reasons(j['keptAtErasure']),
      );
}

/// Every service's manifest; one that cannot be read fails the lot, so a
/// partial picture is never shown as the whole.
final tenantDataManifestsProvider =
    FutureProvider.autoDispose<List<TenantDataManifest>>((ref) async {
      final dio = ref.read(apiClientProvider).dio;
      final out = <TenantDataManifest>[];
      for (final service in tenantDataServices) {
        final resp = await dio.get('/$service/admin/tenant-data');
        out.add(
          TenantDataManifest.fromJson(
            service,
            (resp.data['data'] as Map).cast<String, dynamic>(),
          ),
        );
      }
      return out;
    });

/// Reads every row of every service into one JSON Lines bundle: a header, then
/// a line per row naming its service and table, in the order an import loads
/// them. [onProgress] hears how many rows have been read.
Future<String> buildTenantDataBundle(
  Dio dio,
  List<TenantDataManifest> manifests, {
  void Function(int rows)? onProgress,
}) async {
  final lines = <String>[
    jsonEncode({
      'format': tenantBundleFormat,
      'exportedAt': DateTime.now().toUtc().toIso8601String(),
      'services': [
        for (final m in manifests)
          {
            'service': m.service,
            'format': m.format,
            'tables': [
              for (final t in m.tables)
                {'name': t.name, 'rows': t.rows, 'checksum': t.checksum},
            ],
          },
      ],
    }),
  ];
  var read = 0;
  for (final m in manifests) {
    for (final t in m.tables) {
      String? after;
      do {
        final resp = await dio.get(
          '/${m.service}/admin/tenant-data/tables/${t.name}',
          queryParameters: {'limit': 1000, 'after': ?after},
        );
        final page = (resp.data['data'] as Map).cast<String, dynamic>();
        final rows = (page['rows'] as List?) ?? const [];
        for (final row in rows) {
          lines.add(
            jsonEncode({'service': m.service, 'table': t.name, 'row': row}),
          );
        }
        read += rows.length;
        onProgress?.call(read);
        after = page['nextCursor'] as String?;
      } while (after != null);
    }
  }
  return lines.join('\n');
}

/// What an import loaded, and where it stopped if it was refused.
class TenantDataImport {
  final int rows;
  final Map<String, int> tables;
  final String? refusedAt;
  final String? refusal;

  const TenantDataImport({
    required this.rows,
    required this.tables,
    this.refusedAt,
    this.refusal,
  });

  bool get complete => refusal == null;
}

/// Loads a bundle into the signed-in business, a table's rows [pageSize] at a
/// time in the bundle's order, leaving out the tables in [skip] (as
/// `service/table`). The whole file is checked before anything is sent:
/// a file that is not a bundle, or names a service or table this platform does
/// not export, is refused with a [FormatException]. The first refusal from a
/// service stops the import and names the table; what loaded before it stays.
Future<TenantDataImport> importTenantDataBundle(
  Dio dio,
  String bundle, {
  Set<String> skip = const {},
  int pageSize = 500,
  void Function(int rows)? onProgress,
}) async {
  final lines = const LineSplitter()
      .convert(bundle)
      .where((l) => l.trim().isNotEmpty)
      .toList();
  if (lines.isEmpty) throw const FormatException('The file is empty.');
  final header = _decode(lines.first, 'Not a Shelf-J data bundle.');
  if (header is! Map || header['format'] != tenantBundleFormat) {
    throw const FormatException('Not a Shelf-J data bundle.');
  }
  final rows = <(String, Object?)>[];
  for (var i = 1; i < lines.length; i++) {
    final line = _decode(lines[i], 'Line ${i + 1} is not JSON.');
    if (line is! Map || line['row'] is! Map) {
      throw FormatException('Line ${i + 1} is not a row.');
    }
    final service = line['service'];
    final table = line['table'];
    if (service is! String ||
        !tenantDataServices.contains(service) ||
        table is! String ||
        !_tableName.hasMatch(table)) {
      throw FormatException(
        'Line ${i + 1} names no table this platform exports.',
      );
    }
    final key = '$service/$table';
    if (!skip.contains(key)) rows.add((key, line['row']));
  }

  final loaded = <String, int>{};
  var total = 0;
  var start = 0;
  while (start < rows.length) {
    final key = rows[start].$1;
    var end = start;
    while (end < rows.length && rows[end].$1 == key && end - start < pageSize) {
      end++;
    }
    final slash = key.indexOf('/');
    try {
      await dio.post(
        '/${key.substring(0, slash)}/admin/tenant-data/tables/${key.substring(slash + 1)}',
        data: {
          'rows': [for (var i = start; i < end; i++) rows[i].$2],
        },
      );
    } on DioException catch (e) {
      final body = e.response?.data;
      final error = body is Map ? body['error'] : null;
      return TenantDataImport(
        rows: total,
        tables: loaded,
        refusedAt: key,
        refusal: error is Map
            ? (error['message'] ?? error['code'] ?? 'refused').toString()
            : (e.message ?? 'refused'),
      );
    }
    loaded[key] = (loaded[key] ?? 0) + end - start;
    total += end - start;
    onProgress?.call(total);
    start = end;
  }
  return TenantDataImport(rows: total, tables: loaded);
}

Object? _decode(String line, String problem) {
  try {
    return jsonDecode(line);
  } on FormatException {
    throw FormatException(problem);
  }
}

// ── leaving ──────────────────────────────────────────────────────────────────

/// The owner's notice to leave, as tenant-svc recorded it.
class SwitchingNotice {
  final String id;
  final String intent;
  final String? noticeEndsOn;
  final String? transitionEndsOn;
  final String? retrievalEndsOn;
  final String? erasureDueOn;
  final String? extendedAt;
  final String? cancelReason;

  const SwitchingNotice({
    required this.id,
    required this.intent,
    this.noticeEndsOn,
    this.transitionEndsOn,
    this.retrievalEndsOn,
    this.erasureDueOn,
    this.extendedAt,
    this.cancelReason,
  });

  factory SwitchingNotice.fromJson(Map<String, dynamic> j) => SwitchingNotice(
    id: j['id'] as String? ?? '',
    intent: j['intent'] as String? ?? '',
    noticeEndsOn: j['noticeEndsOn'] as String?,
    transitionEndsOn: j['transitionEndsOn'] as String?,
    retrievalEndsOn: j['retrievalEndsOn'] as String?,
    erasureDueOn: j['erasureDueOn'] as String?,
    extendedAt: j['extendedAt'] as String?,
    cancelReason: j['cancelReason'] as String?,
  );
}

/// What one service erased.
class ErasureEvidence {
  final String service;
  final int rowsErased;

  const ErasureEvidence({required this.service, required this.rowsErased});

  factory ErasureEvidence.fromJson(Map<String, dynamic> j) => ErasureEvidence(
    service: j['service'] as String? ?? '',
    rowsErased: (j['rowsErased'] as num?)?.toInt() ?? 0,
  );
}

/// Where the business's leaving stands.
class SwitchingStatus {
  final SwitchingNotice notice;
  final String stage;
  final List<ErasureEvidence> evidence;
  final List<String> awaiting;

  const SwitchingStatus({
    required this.notice,
    required this.stage,
    this.evidence = const [],
    this.awaiting = const [],
  });

  factory SwitchingStatus.fromJson(Map<String, dynamic> j) => SwitchingStatus(
    notice: SwitchingNotice.fromJson(
      ((j['notice'] as Map?) ?? const {}).cast<String, dynamic>(),
    ),
    stage: j['stage'] as String? ?? '',
    evidence: ((j['evidence'] as List?) ?? const [])
        .map(
          (e) => ErasureEvidence.fromJson((e as Map).cast<String, dynamic>()),
        )
        .toList(),
    awaiting: ((j['awaiting'] as List?) ?? const [])
        .map((e) => e.toString())
        .toList(),
  );
}

/// The business's notice, or null when it has given none.
final switchingStatusProvider = FutureProvider.autoDispose<SwitchingStatus?>((
  ref,
) async {
  try {
    final resp = await ref
        .read(apiClientProvider)
        .dio
        .get('/${ApiConstants.tenant}/admin/tenant/switching');
    return SwitchingStatus.fromJson(
      (resp.data['data'] as Map).cast<String, dynamic>(),
    );
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return null;
    rethrow;
  }
});
