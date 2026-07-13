import 'package:dio/dio.dart';

/// Walks a cursor-paginated list endpoint (`?after=&limit=`, `meta.nextCursor`)
/// to completion and returns every page's `data` rows in order.
///
/// For low-cardinality master-data lists (stores, zones, staff, price lists)
/// whose consumers need the complete set (dropdowns, pickers). High-cardinality
/// lists (orders, products, inventory levels) must NOT use this — they get a
/// load-more pagination notifier instead (see inventory_levels_pagination.dart).
Future<List<dynamic>> fetchAllPages(
  Dio dio,
  String path, {
  Map<String, dynamic>? query,
  int pageSize = 100,
}) async {
  final all = <dynamic>[];
  String? after;
  do {
    final resp = await dio.get(path, queryParameters: {
      ...?query,
      'limit': pageSize,
      if (after != null) 'after': after,
    });
    final body = resp.data as Map<String, dynamic>;
    all.addAll((body['data'] as List?) ?? const []);
    after = (body['meta'] as Map<String, dynamic>?)?['nextCursor'] as String?;
  } while (after != null);
  return all;
}
