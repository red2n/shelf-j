import 'package:web/web.dart' as web;

import 'sso.dart';

/// The page the app runs in.
class _WebSsoBrowser implements SsoBrowser {
  const _WebSsoBrowser();

  // Per tab, and gone with it: a sign-in started in one tab is finished in that tab.
  static const _key = 'storeql.sso.verifier';

  @override
  bool get supported => true;

  @override
  String get origin => web.window.location.origin;

  @override
  Future<void> go(String url) async => web.window.location.assign(url);

  @override
  void keepVerifier(String verifier) => web.window.sessionStorage.setItem(_key, verifier);

  @override
  String? takeVerifier() {
    final v = web.window.sessionStorage.getItem(_key);
    web.window.sessionStorage.removeItem(_key);
    return v;
  }

  @override
  SsoReturn? takeReturn() {
    final hash = web.window.location.hash;
    SsoReturn? back;
    if (hash.startsWith('#sso_ticket=')) {
      back = SsoTicket(Uri.decodeComponent(hash.substring('#sso_ticket='.length)));
    } else if (hash.startsWith('#sso_error=')) {
      back = SsoFailed(Uri.decodeComponent(hash.substring('#sso_error='.length)));
    }
    if (back != null) {
      // Out of the address bar and the history entry: the ticket is spent on use
      // anyway, but a URL with one in it is not something to leave lying about.
      web.window.history.replaceState(null, '', '${web.window.location.pathname}#/login');
    }
    return back;
  }
}

SsoBrowser platformSsoBrowser() => const _WebSsoBrowser();
