import 'sso.dart';

/// Not a browser: nowhere to send one, and nothing to come back to.
class _NoBrowser implements SsoBrowser {
  const _NoBrowser();

  @override
  bool get supported => false;

  @override
  String get origin => '';

  @override
  Future<void> go(String url) async => throw UnsupportedError('single sign-on needs a browser');

  @override
  void keepVerifier(String verifier) {}

  @override
  String? takeVerifier() => null;

  @override
  SsoReturn? takeReturn() => null;
}

SsoBrowser platformSsoBrowser() => const _NoBrowser();
