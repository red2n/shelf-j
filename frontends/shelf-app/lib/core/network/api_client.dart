import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../constants.dart';

final apiClientProvider = Provider<ApiClient>((ref) => ApiClient());

class ApiClient {
  late final Dio dio;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  ApiClient() {
    dio = Dio(BaseOptions(
      baseUrl: ApiConstants.baseUrl,
      connectTimeout: const Duration(seconds: 5),
      receiveTimeout: const Duration(seconds: 10),
      headers: const {'Content-Type': 'application/json'},
    ));
    dio.interceptors.add(AuthInterceptor(dio, _storage));
  }
}

/// Attaches the bearer token and transparently refreshes it on a 401. Public so
/// the refresh-coordination behaviour can be exercised in tests.
class AuthInterceptor extends Interceptor {
  final Dio _dio;
  final FlutterSecureStorage _storage;

  /// Single-flight token refresh. When an access token expires, several in-flight
  /// requests 401 at nearly the same instant; without a shared future the first
  /// one refreshes and the rest fail spuriously. Every concurrent 401 awaits this
  /// same future instead, then replays with the new token. Reset to null once the
  /// refresh settles so the next expiry can start a fresh one.
  Future<String?>? _refreshing;

  AuthInterceptor(this._dio, this._storage);

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    final token = await _storage.read(key: StorageKeys.accessToken);
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    // Only recover from an expired access token — and never from the refresh call
    // itself, otherwise a bad refresh token would recurse indefinitely.
    final isRefreshCall =
        err.requestOptions.path.contains('/${ApiConstants.iam}/auth/refresh');
    if (err.response?.statusCode != 401 || isRefreshCall) {
      handler.next(err);
      return;
    }

    final newAccess = await _refreshOnce();
    if (newAccess == null) {
      // Refresh failed / no refresh token — tokens are already cleared.
      handler.next(err);
      return;
    }

    try {
      // Replay the original request with the new token. fetch() re-runs the
      // interceptor chain, so a second 401 (new token also rejected) resolves
      // through this same path once and then gives up rather than looping.
      err.requestOptions.headers['Authorization'] = 'Bearer $newAccess';
      final retried = await _dio.fetch(err.requestOptions);
      handler.resolve(retried);
    } on DioException catch (retryErr) {
      handler.next(retryErr);
    }
  }

  /// Runs at most one refresh at a time; concurrent callers share the result.
  /// Returns the new access token, or null if refresh was not possible.
  Future<String?> _refreshOnce() {
    return _refreshing ??=
        _doRefresh().whenComplete(() => _refreshing = null);
  }

  Future<String?> _doRefresh() async {
    // Read the refresh token at call time (not captured earlier): each sequential
    // refresh uses the latest rotated token, so we never present a token that an
    // earlier refresh already consumed (which iam-svc treats as reuse and would
    // revoke the whole session family).
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (refresh == null) {
      await _clearTokens();
      return null;
    }
    try {
      final resp = await _dio.post(
        '/${ApiConstants.iam}/auth/refresh',
        data: {'refreshToken': refresh},
        options: Options(headers: {'Authorization': null}),
      );
      final data = resp.data['data'] as Map<String, dynamic>;
      final newAccess = data['accessToken'] as String;
      final newRefresh = data['refreshToken'] as String;
      await _storage.write(key: StorageKeys.accessToken, value: newAccess);
      await _storage.write(key: StorageKeys.refreshToken, value: newRefresh);
      return newAccess;
    } catch (_) {
      await _clearTokens();
      return null;
    }
  }

  Future<void> _clearTokens() async {
    await _storage.delete(key: StorageKeys.accessToken);
    await _storage.delete(key: StorageKeys.refreshToken);
  }
}
