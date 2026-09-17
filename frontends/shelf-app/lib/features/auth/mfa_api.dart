import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/passkeys.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

/// Where a login stands with second factors (20.12).
class MfaStatus {
  final bool totp;
  final List<PasskeyView> passkeys;
  final int recoveryCodesLeft;

  /// Whether the login's business, or the platform, requires a second factor of it.
  final bool required;

  const MfaStatus({
    required this.totp,
    required this.passkeys,
    required this.recoveryCodesLeft,
    required this.required,
  });

  bool get enrolled => totp || passkeys.isNotEmpty;

  factory MfaStatus.fromJson(Map<String, dynamic> j) => MfaStatus(
        totp: j['totp'] == true,
        passkeys: [
          for (final p in j['passkeys'] as List<dynamic>? ?? const [])
            PasskeyView.fromJson(Map<String, dynamic>.from(p as Map)),
        ],
        recoveryCodesLeft: (j['recoveryCodesLeft'] as num?)?.toInt() ?? 0,
        required: j['required'] == true,
      );
}

class PasskeyView {
  final String id;
  final String name;
  final String createdAt;
  final String? lastUsedAt;

  const PasskeyView({required this.id, required this.name, required this.createdAt, this.lastUsedAt});

  factory PasskeyView.fromJson(Map<String, dynamic> j) => PasskeyView(
        id: j['id'] as String,
        name: j['name'] as String,
        createdAt: j['createdAt'] as String,
        lastUsedAt: j['lastUsedAt'] as String?,
      );
}

/// A new authenticator secret, waiting for a code to confirm it.
class TotpEnrolment {
  final String secret;
  final String otpauthUri;

  const TotpEnrolment({required this.secret, required this.otpauthUri});
}

/// What setting a factor up answers: the recovery codes, shown once, when they
/// were made — and the real session when the caller held an enrolment token.
class FactorEnrolled {
  final List<String> recoveryCodes;
  final Map<String, dynamic>? tokens;

  const FactorEnrolled({required this.recoveryCodes, this.tokens});

  factory FactorEnrolled.fromJson(Map<String, dynamic> j) => FactorEnrolled(
        recoveryCodes: [for (final c in j['recoveryCodes'] as List<dynamic>? ?? const []) c.toString()],
        tokens: j['tokens'] == null ? null : Map<String, dynamic>.from(j['tokens'] as Map),
      );
}

/// The second-factor routes. [token] is the enrolment token of a login that owes a
/// factor and has no session yet; without it the stored session is used.
class MfaApi {
  final Dio _dio;
  final String? token;

  const MfaApi(this._dio, {this.token});

  static const _base = '/${ApiConstants.iam}/auth';

  Options? get _auth => token == null ? null : Options(headers: {'Authorization': 'Bearer $token'});

  Future<Map<String, dynamic>> _post(String path, [Object? body]) async {
    final resp = await _dio.post('$_base$path', data: body ?? const <String, dynamic>{}, options: _auth);
    final data = resp.data['data'];
    return data is Map ? Map<String, dynamic>.from(data) : const {};
  }

  Future<MfaStatus> status() async {
    final resp = await _dio.get('$_base/mfa', options: _auth);
    return MfaStatus.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
  }

  Future<TotpEnrolment> beginTotp() async {
    final d = await _post('/mfa/totp');
    return TotpEnrolment(secret: d['secret'] as String, otpauthUri: d['otpauthUri'] as String);
  }

  Future<FactorEnrolled> confirmTotp(String code) async =>
      FactorEnrolled.fromJson(await _post('/mfa/totp/confirm', {'code': code.trim()}));

  Future<void> removeTotp(String password) => _post('/mfa/totp/remove', {'password': password});

  Future<List<String>> newRecoveryCodes(String password) async =>
      FactorEnrolled.fromJson(await _post('/mfa/recovery-codes', {'password': password})).recoveryCodes;

  /// Makes a passkey on this device and registers it under [name].
  Future<FactorEnrolled> addPasskey(String name) async {
    final options = await _post('/mfa/passkeys/options');
    final made = await passkeys.create(options);
    return FactorEnrolled.fromJson(await _post('/mfa/passkeys', {
      'registrationToken': options['registrationToken'],
      'name': name.trim(),
      ...made,
    }));
  }

  Future<void> removePasskey(String id, String password) =>
      _post('/mfa/passkeys/$id/remove', {'password': password});

  /// The tiers of this business's staff who must have a second factor.
  Future<List<String>> policy() async {
    final resp = await _dio.get('$_base/admin/mfa-policy');
    return [for (final t in resp.data['data']['requiredTiers'] as List<dynamic>) t.toString()];
  }

  Future<List<String>> setPolicy(List<String> tiers) async {
    final resp = await _dio.put('$_base/admin/mfa-policy', data: {'requiredTiers': tiers});
    return [for (final t in resp.data['data']['requiredTiers'] as List<dynamic>) t.toString()];
  }

  /// The lost phone: clears a member of staff's second factors and ends their sessions.
  Future<void> resetStaff(String userId) => _dio.delete('$_base/admin/staff-users/$userId/mfa');
}

final mfaApiProvider = Provider<MfaApi>((ref) => MfaApi(ref.watch(apiClientProvider).dio));

final mfaStatusProvider = FutureProvider.autoDispose<MfaStatus>((ref) => ref.watch(mfaApiProvider).status());

final mfaPolicyProvider = FutureProvider.autoDispose<List<String>>((ref) => ref.watch(mfaApiProvider).policy());
