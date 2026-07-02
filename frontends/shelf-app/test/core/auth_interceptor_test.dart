import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/constants.dart';
import 'package:shelf_app/core/network/api_client.dart';

/// In-memory stand-in for the flutter_secure_storage platform channel so the
/// interceptor's token reads/writes work in a pure Dart unit test.
class _FakeSecureStorage {
  final Map<String, String> _data = {};

  void install() {
    const channel =
        MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
    TestWidgetsFlutterBinding.ensureInitialized();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      final args = (call.arguments as Map?) ?? const {};
      final key = args['key'] as String? ?? '';
      switch (call.method) {
        case 'read':
          return _data[key];
        case 'write':
          _data[key] = args['value'] as String;
          return null;
        case 'delete':
          _data.remove(key);
          return null;
        case 'containsKey':
          return _data.containsKey(key);
        case 'readAll':
          return Map<String, String>.from(_data);
        case 'deleteAll':
          _data.clear();
          return null;
        default:
          return null;
      }
    });
  }
}

/// Fake HTTP layer: the protected endpoint 401s while the token is still the
/// expired one and 200s once it has been rotated; the refresh endpoint rotates
/// the token and counts how many times it was called.
class _FakeAdapter implements HttpClientAdapter {
  int refreshCalls = 0;
  int protectedOkResponses = 0;
  String currentValidAccess = 'NEW';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.contains('/auth/refresh')) {
      refreshCalls++;
      // Simulate rotation latency so concurrent callers pile up on one refresh.
      await Future<void>.delayed(const Duration(milliseconds: 20));
      return ResponseBody.fromString(
        '{"data":{"accessToken":"$currentValidAccess","refreshToken":"R-NEW"}}',
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    // Protected resource: authorized only with the rotated access token.
    final auth = options.headers['Authorization'];
    if (auth == 'Bearer $currentValidAccess') {
      protectedOkResponses++;
      return ResponseBody.fromString('{"data":"ok"}', 200, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      });
    }
    return ResponseBody.fromString('{"error":{"code":"UNAUTHORIZED"}}', 401,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        });
  }
}

void main() {
  late FlutterSecureStorage storage;

  setUp(() async {
    _FakeSecureStorage().install();
    storage = const FlutterSecureStorage();
    // Seed an expired access token + a valid refresh token.
    await storage.write(key: StorageKeys.accessToken, value: 'EXPIRED');
    await storage.write(key: StorageKeys.refreshToken, value: 'R-OLD');
  });

  Dio buildDio(_FakeAdapter adapter) {
    final dio = Dio(BaseOptions(baseUrl: ApiConstants.baseUrl));
    dio.httpClientAdapter = adapter;
    dio.interceptors.add(AuthInterceptor(dio, storage));
    return dio;
  }

  test('concurrent 401s trigger exactly one refresh and all recover', () async {
    final adapter = _FakeAdapter();
    final dio = buildDio(adapter);

    // Fire several protected requests in parallel, all carrying the expired token.
    final results = await Future.wait([
      for (var i = 0; i < 6; i++) dio.get('/${ApiConstants.order}/orders/mine'),
    ]);

    // Every request succeeded after the transparent refresh...
    expect(results.every((r) => r.statusCode == 200), isTrue);
    expect(adapter.protectedOkResponses, 6);
    // ...and the refresh happened once, not once-per-request.
    expect(adapter.refreshCalls, 1);

    // New tokens were persisted.
    expect(await storage.read(key: StorageKeys.accessToken), 'NEW');
    expect(await storage.read(key: StorageKeys.refreshToken), 'R-NEW');
  });

  test('failed refresh clears tokens and surfaces the original 401', () async {
    final adapter = _FakeAdapter();
    final dio = buildDio(adapter);
    // No refresh token → refresh is impossible.
    await storage.delete(key: StorageKeys.refreshToken);

    await expectLater(
      dio.get('/${ApiConstants.order}/orders/mine'),
      throwsA(isA<DioException>()),
    );
    // Access token cleared so the app falls back to signed-out.
    expect(await storage.read(key: StorageKeys.accessToken), isNull);
    expect(adapter.refreshCalls, 0);
  });
}
