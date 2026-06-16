import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';

// State for the multi-step wizard
class OnboardingState {
  final int step; // 0 = tenant, 1 = store, 2 = done
  final bool loading;
  final String? error;
  final String? tenantId;

  const OnboardingState({
    this.step = 0,
    this.loading = false,
    this.error,
    this.tenantId,
  });

  OnboardingState copyWith({
    int? step,
    bool? loading,
    String? error,
    String? tenantId,
  }) =>
      OnboardingState(
        step: step ?? this.step,
        loading: loading ?? this.loading,
        error: error,
        tenantId: tenantId ?? this.tenantId,
      );
}

final onboardingNotifierProvider =
    StateNotifierProvider.autoDispose<OnboardingNotifier, OnboardingState>(
  (ref) => OnboardingNotifier(ref),
);

class OnboardingNotifier extends StateNotifier<OnboardingState> {
  final Ref _ref;

  OnboardingNotifier(this._ref) : super(const OnboardingState());

  // Step 1: POST /tenant-svc/onboarding/tenants
  Future<void> createTenant({
    required String businessName,
    String? legalName,
    required String country,
    required String currency,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      final dio = _ref.read(apiClientProvider).dio;
      final resp = await dio.post(
        '/${ApiConstants.tenant}/onboarding/tenants',
        data: {
          'businessName': businessName,
          if (legalName != null && legalName.isNotEmpty) 'legalName': legalName,
          'country': country,
          'currency': currency,
        },
      );
      final tenantId = resp.data['data']['id'] as String;
      // iam-svc binds the OWNER role via a Kafka TenantCreated event — poll
      // until the new JWT contains tenantId (up to 5 attempts, 600ms apart).
      await _pollUntilTenantId(maxAttempts: 5, delay: const Duration(milliseconds: 600));
      state = state.copyWith(step: 1, loading: false, tenantId: tenantId);
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  // Step 2: POST /tenant-svc/onboarding/stores
  Future<void> createStore({
    required String name,
    required String code,
    String type = 'STORE',
    String? line1,
    String? city,
    String? country,
    String? pincode,
    String? timezone,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      final dio = _ref.read(apiClientProvider).dio;
      await dio.post(
        '/${ApiConstants.tenant}/onboarding/stores',
        data: {
          'name': name,
          'code': code,
          'type': type,
          if (line1 != null) 'line1': line1,
          if (city != null) 'city': city,
          if (country != null) 'country': country,
          if (pincode != null) 'pincode': pincode,
          if (timezone != null) 'timezone': timezone,
        },
      );
      // Refresh once more so JWT reflects the completed tenant
      await _ref.read(authNotifierProvider.notifier).refresh();
      state = state.copyWith(step: 2, loading: false);
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// Refresh JWT in a loop until `tenantId` is present in the claims.
  /// Stops early on success; falls through after [maxAttempts] regardless.
  Future<void> _pollUntilTenantId({
    required int maxAttempts,
    required Duration delay,
  }) async {
    for (var i = 0; i < maxAttempts; i++) {
      await _ref.read(authNotifierProvider.notifier).refresh();
      final auth = _ref.read(authNotifierProvider).valueOrNull;
      if (auth is AuthAuthenticated && auth.tenantId != null) return;
      if (i < maxAttempts - 1) await Future.delayed(delay);
    }
  }

  String _friendly(Object e) {
    final s = e.toString();
    if (s.contains('409')) return 'A tenant already exists for this account.';
    if (s.contains('SocketException') || s.contains('Failed host')) {
      return 'Cannot reach the server. Is the backend running?';
    }
    return 'Something went wrong. Please try again.';
  }
}
