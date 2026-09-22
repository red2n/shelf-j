// Single sign-on through a business's own identity provider (20.x). The app
// starts a sign-in with a PKCE verifier it keeps, the browser goes to the
// provider and comes back to the app with a ticket in the URL's fragment, and
// the app trades the ticket and the verifier for a sign-in — the same answer a
// password gets, so everything after it (a second step, the session) is the
// password path's. The fragment never reaches a server, and a ticket without
// the verifier this browser kept is worth nothing.
//
// Web only for now: a phone or desktop build would need the system browser and
// a link back into the app, which this does not set up.
import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

import 'sso_io.dart' if (dart.library.js_interop) 'sso_web.dart' as impl;

/// What the browser came back from the provider with.
sealed class SsoReturn {
  const SsoReturn();
}

/// Matched to a login: the ticket to trade.
class SsoTicket extends SsoReturn {
  final String ticket;
  const SsoTicket(this.ticket);
}

/// Not: the stable code of why.
class SsoFailed extends SsoReturn {
  final String code;
  const SsoFailed(this.code);
}

/// A sign-in through the provider that did not end in one; [code] is stable.
class SsoError implements Exception {
  final String code;
  const SsoError(this.code);

  @override
  String toString() => 'SsoError($code)';
}

/// The browser's part in single sign-on. Replaceable in tests.
abstract class SsoBrowser {
  /// Whether this platform can go to a provider and come back.
  bool get supported;

  /// Where the app is served from, as the browser reports it: the sign-in comes back here.
  String get origin;

  /// Sends the browser to the provider; the page is left.
  Future<void> go(String url);

  /// Keeps the verifier across the page being left and loaded again.
  void keepVerifier(String verifier);

  /// The verifier kept, once: a second read finds nothing.
  String? takeVerifier();

  /// What the page was loaded with, if it came back from a provider — read once,
  /// and wiped from the address bar so neither the router nor a bookmark sees it.
  SsoReturn? takeReturn();
}

/// The browser the app is running in.
SsoBrowser ssoBrowser = impl.platformSsoBrowser();

/// What the page came back with, captured in main before the router reads the URL.
SsoReturn? ssoReturnAtLaunch;

final Random _random = Random.secure();

/// 256 random bits, base64url: an RFC 7636 verifier of 43 characters.
String newSsoVerifier() {
  final bytes = List<int>.generate(32, (_) => _random.nextInt(256));
  return base64Url.encode(bytes).replaceAll('=', '');
}

/// BASE64URL(SHA256(verifier)): what the start is told, and the verifier later proves.
String ssoChallenge(String verifier) =>
    base64Url.encode(sha256.convert(ascii.encode(verifier)).bytes).replaceAll('=', '');

/// What to tell a person about a sign-in that did not work, by its stable code.
String ssoMessage(String? code) => switch (code) {
      'SSO_NOT_FOUND' => 'No business signs in with that name. Check it with your manager.',
      'SSO_REQUIRED' =>
        'Your business signs you in through its own sign-in page. Use "Sign in with your business" below.',
      'SSO_NO_ACCOUNT' =>
        'You signed in with your business, but it has not added you here yet. Ask your manager to add you as staff.',
      'SSO_EMAIL_UNVERIFIED' =>
        "Your business's sign-in page has not verified your email address, so it could not be matched to your login.",
      'SSO_EMAIL_MISSING' => "Your business's sign-in page did not share your email address.",
      'SSO_ALREADY_LINKED' =>
        'Your login is linked to someone else at your business. Ask the owner to unlink it.',
      'SSO_ACCOUNT_UNAVAILABLE' => 'This login can no longer sign in here.',
      'SSO_CANCELLED' => 'Sign-in was cancelled.',
      'SSO_STATE_INVALID' || 'SSO_TICKET_INVALID' =>
        'That sign-in took too long or was already used. Start again.',
      'SSO_REAUTH_REQUIRED' => 'Sign in with your business again.',
      'SSO_NOT_READY' => "Your business's single sign-on is not finished. Ask its owner.",
      'SSO_UNAVAILABLE' => 'Signing in with your business is not available here.',
      'SSO_PROVIDER_UNREACHABLE' => "Your business's sign-in page could not be reached. Try again shortly.",
      'TENANT_INACTIVE' => 'This business account is suspended. Contact support.',
      _ => "Your business's sign-in page could not sign you in. Ask its owner to check the settings.",
    };
