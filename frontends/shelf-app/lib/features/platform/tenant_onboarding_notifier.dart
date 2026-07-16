import 'package:dio/dio.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/network/api_error.dart';

class TenantOnboardingState {
  final int step; // 0=account, 1=business+store, 2=done
  final bool loading;
  final String? error;
  final String? ownerEmail;
  final String? newUserToken;
  final String? tenantName;

  const TenantOnboardingState({
    this.step = 0,
    this.loading = false,
    this.error,
    this.ownerEmail,
    this.newUserToken,
    this.tenantName,
  });

  bool get isDone => step == 2;

  TenantOnboardingState copyWith({
    int? step,
    bool? loading,
    String? error,
    String? ownerEmail,
    String? newUserToken,
    String? tenantName,
  }) =>
      TenantOnboardingState(
        step: step ?? this.step,
        loading: loading ?? this.loading,
        error: error,
        ownerEmail: ownerEmail ?? this.ownerEmail,
        newUserToken: newUserToken ?? this.newUserToken,
        tenantName: tenantName ?? this.tenantName,
      );
}

final tenantOnboardingProvider = StateNotifierProvider.autoDispose<
    TenantOnboardingNotifier, TenantOnboardingState>(
  (ref) => TenantOnboardingNotifier(),
);

class TenantOnboardingNotifier extends StateNotifier<TenantOnboardingState> {
  TenantOnboardingNotifier() : super(const TenantOnboardingState());

  late final Dio _dio = Dio(BaseOptions(
    baseUrl: ApiConstants.baseUrl,
    connectTimeout: const Duration(seconds: 8),
    receiveTimeout: const Duration(seconds: 15),
    headers: const {'Content-Type': 'application/json'},
  ));

  /// Step 0 → 1: Register + login the new owner.
  Future<void> registerOwner({
    required String email,
    required String password,
    String? phone,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      await _dio.post(
        '/${ApiConstants.iam}/auth/register',
        data: {
          'email': email,
          'password': password,
          if (phone != null && phone.isNotEmpty) 'phone': phone,
        },
      );
      final loginResp = await _dio.post(
        '/${ApiConstants.iam}/auth/login',
        data: {'email': email, 'password': password},
      );
      final data = loginResp.data['data'] as Map<String, dynamic>;
      state = state.copyWith(
        step: 1,
        loading: false,
        ownerEmail: email,
        newUserToken: data['accessToken'] as String,
      );
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// Step 1 → 2: Single POST /onboarding that creates tenant + store atomically.
  /// Uses the new user's token — no tenantId in JWT required.
  Future<void> onboard({
    required String businessName,
    String? legalName,
    required String country,
    required String currency,
    required String storeName,
    required String storeCode,
    String storeType = 'STORE',
    String? storeLine1,
    String? storeCity,
    String? storeCountry,
    String? storePincode,
    String? storeTimezone,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      await _dio.post(
        '/${ApiConstants.tenant}/onboarding',
        data: {
          'businessName': businessName,
          if (legalName != null && legalName.isNotEmpty) 'legalName': legalName,
          'country': country,
          'currency': currency,
          'storeName': storeName,
          'storeCode': storeCode,
          'storeType': storeType,
          if (storeLine1 != null && storeLine1.isNotEmpty) 'storeLine1': storeLine1,
          if (storeCity != null && storeCity.isNotEmpty) 'storeCity': storeCity,
          if (storeCountry != null && storeCountry.isNotEmpty) 'storeCountry': storeCountry,
          if (storePincode != null && storePincode.isNotEmpty) 'storePincode': storePincode,
          'storeTimezone': storeTimezone ?? 'Asia/Kolkata',
        },
        options: Options(
          headers: {'Authorization': 'Bearer ${state.newUserToken}'},
        ),
      );
      state = state.copyWith(step: 2, loading: false, tenantName: businessName);
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  void reset() => state = const TenantOnboardingState();

  String _friendly(Object e) {
    if (e is DioException) {
      final status = e.response?.statusCode;
      if (status == 409) return 'An account with this email already exists.';
      if (status == 401) return 'Authentication failed. Try again.';
    }
    return friendlyError(e,
        fallback: 'Something went wrong. Please try again.');
  }
}
