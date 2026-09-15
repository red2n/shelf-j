import 'package:dio/dio.dart';
import 'package:shelf_app/core/auth/auth_notifier.dart';
import 'package:shelf_app/core/auth/auth_state.dart';
import 'package:shelf_app/core/network/api_client.dart';

/// An [ApiClient] whose Dio a test wires to its own adapter.
class FakeApiClient implements ApiClient {
  @override
  Dio dio;

  FakeApiClient(this.dio);
}

/// A login holding one role in tenant `t`.
class RoleAuth extends AuthNotifier {
  final String role;

  RoleAuth(this.role);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
    accessToken: 'a',
    refreshToken: 'r',
    userId: 'u',
    tenantId: 't',
    roles: [role],
  );
}
