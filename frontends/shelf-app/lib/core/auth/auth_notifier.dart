import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../constants.dart';
import '../network/api_client.dart';
import '../storage/app_storage.dart';
import 'auth_state.dart';

final authNotifierProvider =
    AsyncNotifierProvider<AuthNotifier, AuthState>(AuthNotifier.new);

class AuthNotifier extends AsyncNotifier<AuthState> {
  final AppStorage _storage = const AppStorage();

  @override
  Future<AuthState> build() => _restoreFromStorage();

  Future<AuthState> _restoreFromStorage() async {
    final access = await _storage.read(key: StorageKeys.accessToken);
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (access == null || refresh == null) return const AuthUnauthenticated();
    return _decode(access, refresh);
  }

  Future<void> login(String email, String password) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/login',
        data: {'email': email, 'password': password},
      );
      return _saveAndDecode(resp.data['data'] as Map<String, dynamic>);
    });
  }

  /// Platform console login — distinct endpoint from [login]: a store/tenant staff
  /// credential is never valid here, and a platform-admin credential is never valid
  /// on the store/POS login screen.
  Future<void> platformLogin(String email, String password) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/platform-login',
        data: {'email': email, 'password': password},
      );
      return _saveAndDecode(resp.data['data'] as Map<String, dynamic>);
    });
  }

  Future<void> register(String email, String password, String? phone) async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/register',
        data: {'email': email, 'password': password, if (phone != null) 'phone': phone},
      );
      return _saveAndDecode(resp.data['data'] as Map<String, dynamic>);
    });
  }

  // Called after onboarding steps so the JWT picks up the new tenantId
  Future<void> refresh() async {
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (refresh == null) return;
    state = await AsyncValue.guard(() async {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.iam}/auth/refresh',
        data: {'refreshToken': refresh},
      );
      return _saveAndDecode(resp.data['data'] as Map<String, dynamic>);
    });
  }

  Future<void> logout() async {
    final refresh = await _storage.read(key: StorageKeys.refreshToken);
    if (refresh != null) {
      try {
        await ref.read(apiClientProvider).dio.post(
          '/${ApiConstants.iam}/auth/logout',
          data: {'refreshToken': refresh},
        );
      } catch (_) {}
    }
    await _storage.deleteAll();
    state = const AsyncValue.data(AuthUnauthenticated());
  }

  Future<AuthState> _saveAndDecode(Map<String, dynamic> tokenData) async {
    final access = tokenData['accessToken'] as String;
    final refresh = tokenData['refreshToken'] as String;
    await _storage.write(key: StorageKeys.accessToken, value: access);
    await _storage.write(key: StorageKeys.refreshToken, value: refresh);
    return _decode(access, refresh);
  }

  AuthState _decode(String access, String refresh) {
    try {
      final parts = access.split('.');
      if (parts.length != 3) return const AuthUnauthenticated();
      final payload = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      final claims = jsonDecode(payload) as Map<String, dynamic>;
      return AuthAuthenticated(
        accessToken: access,
        refreshToken: refresh,
        userId: claims['sub'] as String? ?? '',
        tenantId: claims['tenant'] as String?,
        roles: (claims['roles'] as List<dynamic>?)
                ?.map((r) => r.toString())
                .toList() ??
            [],
        email: claims['email'] as String?,
      );
    } catch (_) {
      return const AuthUnauthenticated();
    }
  }
}
