import 'passkeys.dart';

/// No passkeys outside the browser yet: the app offers an authenticator app and
/// recovery codes, and hides every passkey control.
class _NoPasskeys implements Passkeys {
  const _NoPasskeys();

  @override
  bool get supported => false;

  @override
  Future<Map<String, String>> create(Map<String, dynamic> options) =>
      throw UnsupportedError('passkeys are not available on this platform');

  @override
  Future<Map<String, String>> get(Map<String, dynamic> options) =>
      throw UnsupportedError('passkeys are not available on this platform');
}

Passkeys platformPasskeys() => const _NoPasskeys();
