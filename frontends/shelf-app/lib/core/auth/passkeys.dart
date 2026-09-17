// Passkeys (WebAuthn) — the phishing-resistant second factor (20.12). The browser
// does the ceremony: it binds every signature to the origin it was made for, so a
// look-alike site gets a signature that is no use to it. On the web this talks to
// navigator.credentials; elsewhere there is no such thing yet and the app offers
// the authenticator app and recovery codes only.
import 'passkeys_io.dart' if (dart.library.js_interop) 'passkeys_web.dart' as impl;

/// The person closed the browser's passkey prompt, or it timed out.
class PasskeyCancelled implements Exception {
  const PasskeyCancelled();
}

/// The platform's passkey ceremonies. Replaceable in tests.
abstract class Passkeys {
  /// Whether this platform can make and use passkeys at all.
  bool get supported;

  /// Makes a passkey from the server's creation options. Answers the fields
  /// `POST /auth/mfa/passkeys` takes — `clientDataJson` and `attestationObject`,
  /// base64url — to which the caller adds the registration token and a name.
  Future<Map<String, String>> create(Map<String, dynamic> options);

  /// Signs the server's challenge with one of the allowed passkeys. Answers the
  /// `assertion` of `POST /auth/mfa/login`.
  Future<Map<String, String>> get(Map<String, dynamic> options);
}

/// The passkey ceremonies of the platform the app is running on.
Passkeys passkeys = impl.platformPasskeys();
