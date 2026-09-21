import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';

/// A business's identity provider, as iam-svc shows it: never the secret.
class SsoConnection {
  final String slug;
  final String issuer;
  final String clientId;
  final bool clientSecretSet;
  final bool enabled;
  final List<String> requiredTiers;
  final bool requireVerifiedEmail;

  /// What the business registers with its provider as the redirect URI.
  final String? callbackUrl;

  const SsoConnection({
    required this.slug,
    required this.issuer,
    required this.clientId,
    required this.clientSecretSet,
    required this.enabled,
    required this.requiredTiers,
    required this.requireVerifiedEmail,
    this.callbackUrl,
  });

  factory SsoConnection.fromJson(Map<String, dynamic> j) => SsoConnection(
        slug: j['slug'] as String,
        issuer: j['issuer'] as String,
        clientId: j['clientId'] as String,
        clientSecretSet: j['clientSecretSet'] as bool? ?? false,
        enabled: j['enabled'] as bool? ?? false,
        requiredTiers: [for (final t in j['requiredTiers'] as List<dynamic>? ?? const []) t.toString()],
        requireVerifiedEmail: j['requireVerifiedEmail'] as bool? ?? true,
        callbackUrl: j['callbackUrl'] as String?,
      );
}

/// One thing that must hold before staff can sign in through the provider.
class SsoCheck {
  final String code;
  final bool satisfied;
  final String detail;

  const SsoCheck(this.code, this.satisfied, this.detail);
}

/// A business's own single sign-on settings (20.x): an owner changes them, an
/// owner or a manager reads them.
class SsoApi {
  final Dio _dio;
  SsoApi(this._dio);

  static const _base = '/${ApiConstants.iam}/auth/admin/sso';

  /// The connection, or null when the business has none.
  Future<SsoConnection?> connection() async {
    try {
      final resp = await _dio.get(_base);
      return SsoConnection.fromJson(resp.data['data'] as Map<String, dynamic>);
    } catch (e) {
      if (apiErrorCode(e) == 'SSO_NOT_CONFIGURED') return null;
      rethrow;
    }
  }

  /// Saves the connection; a [clientSecret] left empty keeps the one already held.
  Future<SsoConnection> save({
    required String slug,
    required String issuer,
    required String clientId,
    String? clientSecret,
    required bool enabled,
    required List<String> requiredTiers,
    required bool requireVerifiedEmail,
  }) async {
    final resp = await _dio.put(_base, data: {
      'slug': slug.trim().toLowerCase(),
      'issuer': issuer.trim(),
      'clientId': clientId.trim(),
      if (clientSecret != null && clientSecret.trim().isNotEmpty) 'clientSecret': clientSecret.trim(),
      'enabled': enabled,
      'requiredTiers': requiredTiers,
      'requireVerifiedEmail': requireVerifiedEmail,
    });
    return SsoConnection.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<void> disconnect() => _dio.delete(_base);

  /// Each check, reading the provider now.
  Future<List<SsoCheck>> readiness() async {
    final resp = await _dio.get('$_base/readiness');
    return [
      for (final c in resp.data['data']['checks'] as List<dynamic>)
        SsoCheck(c['code'] as String, c['satisfied'] as bool, c['detail'] as String? ?? ''),
    ];
  }
}

final ssoApiProvider = Provider<SsoApi>((ref) => SsoApi(ref.watch(apiClientProvider).dio));

final ssoConnectionProvider =
    FutureProvider.autoDispose<SsoConnection?>((ref) => ref.watch(ssoApiProvider).connection());
