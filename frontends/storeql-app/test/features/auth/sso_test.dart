import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/auth/sso.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/sso_settings_dialog.dart';
import 'package:storeql_app/features/auth/login_screen.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Signing in through a business's own identity provider (20.x). The app starts
// the sign-in holding a verifier it keeps, leaves for the provider, and on the
// way back trades the ticket with that verifier — and nothing else: a ticket
// without the verifier this browser kept is not even sent. What comes back is
// what a password gets, so a second step owed is a second step owed. A password
// the business no longer accepts names the business, and one press continues.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final Map<String, ResponseBody Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];

  _Server(this.routes);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final route = routes['${o.method} ${o.path}'];
    return route == null ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no route"}}', 404) : route(o);
  }

  RequestOptions last(String path) => requests.lastWhere((r) => r.path == path);
  bool asked(String path) => requests.any((r) => r.path == path);
}

/// The browser, in memory: where it was sent, and what it kept.
class _FakeBrowser implements SsoBrowser {
  String? wentTo;
  String? kept;

  @override
  bool get supported => true;

  @override
  String get origin => 'http://localhost:8088';

  @override
  Future<void> go(String url) async => wentTo = url;

  @override
  void keepVerifier(String verifier) => kept = verifier;

  @override
  String? takeVerifier() {
    final v = kept;
    kept = null;
    return v;
  }

  @override
  SsoReturn? takeReturn() => null;
}

void _secureStorage() {
  final data = <String, String>{};
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
    const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
    (call) async {
      final args = (call.arguments as Map?) ?? const {};
      final key = args['key'] as String? ?? '';
      switch (call.method) {
        case 'read':
          return data[key];
        case 'write':
          data[key] = args['value'] as String;
          return null;
        case 'delete':
          data.remove(key);
          return null;
        case 'deleteAll':
          data.clear();
          return null;
        default:
          return null;
      }
    },
  );
}

String _jwt(Map<String, dynamic> claims) {
  String part(Object o) => base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256'})}.${part(claims)}.sig';
}

final _session = jsonEncode({
  'data': {
    'accessToken': _jwt({'sub': 'u-1', 'tenant': 't-1', 'roles': ['CASHIER'], 'amr': ['sso']}),
    'refreshToken': 'refresh-1',
    'tokenType': 'Bearer',
    'expiresInSeconds': 900,
  },
});

const _iam = '/iam-svc/auth';

Future<ProviderContainer> _pump(WidgetTester tester, _Server server, Widget screen) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final container = ProviderContainer(overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))]);
  addTearDown(container.dispose);
  await tester.runAsync(() async {
    try {
      await container.read(authNotifierProvider.future);
    } catch (_) {
      // A sign-in that came back refused is an error state; the screen shows it.
    }
  });
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: screen,
    ),
  ));
  await tester.pump();
  return container;
}

/// A few frames, for a screen whose spinner never stops.
Future<void> _pumps(WidgetTester tester) async {
  for (var i = 0; i < 5; i++) {
    await tester.pump(const Duration(milliseconds: 20));
  }
}

void main() {
  late _FakeBrowser browser;

  setUp(() {
    _secureStorage();
    browser = _FakeBrowser();
    ssoBrowser = browser;
    ssoReturnAtLaunch = null;
  });

  test('the challenge is RFC 7636\'s own S256 example', () {
    expect(ssoChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
    final v = newSsoVerifier();
    expect(v.length, 43);
    expect(RegExp(r'^[A-Za-z0-9_-]+$').hasMatch(v), isTrue);
    expect(newSsoVerifier(), isNot(v));
  });

  testWidgets('Signing in with the business: its name, the provider, a verifier kept', (tester) async {
    final server = _Server({
      'POST $_iam/sso/start': (_) => jsonResponse('{"data":{"authorizationUrl":"https://idp.example.com/authorize?x=1"}}'),
    });
    await _pump(tester, server, const LoginScreen());

    await tester.tap(find.byKey(const Key('sso-start')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('sso-slug')), '  Acme-Foods ');
    await tester.tap(find.byKey(const Key('sso-go')));
    // Pumped, not settled: the button spins until the page is left, and here it never is.
    await _pumps(tester);

    final sent = server.last('$_iam/sso/start').data as Map<String, dynamic>;
    expect(sent['slug'], 'acme-foods');
    expect(sent['returnTo'], 'http://localhost:8088/');
    expect(browser.kept, isNotNull);
    expect(sent['codeChallenge'], ssoChallenge(browser.kept!), reason: 'the verifier kept is the one the start was told of');
    expect(browser.wentTo, 'https://idp.example.com/authorize?x=1');
  });

  testWidgets('A name no business signs in with is said so, and the browser goes nowhere', (tester) async {
    final server = _Server({
      'POST $_iam/sso/start': (_) => jsonResponse(
          '{"error":{"code":"SSO_NOT_FOUND","message":"No business signs in with that name"}}', 404),
    });
    await _pump(tester, server, const LoginScreen());
    await tester.tap(find.byKey(const Key('sso-start')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('sso-slug')), 'nobody');
    await tester.tap(find.byKey(const Key('sso-go')));
    await tester.pumpAndSettle();

    expect(find.textContaining('No business signs in with that name'), findsOneWidget);
    expect(browser.wentTo, isNull);
    expect(browser.kept, isNull);
  });

  testWidgets('Back with a ticket: traded with the kept verifier for the session', (tester) async {
    final server = _Server({'POST $_iam/sso/token': (_) => jsonResponse(_session)});
    browser.kept = 'v' * 43;
    ssoReturnAtLaunch = const SsoTicket('the-ticket');
    final container = await _pump(tester, server, const LoginScreen());

    final sent = server.last('$_iam/sso/token').data as Map<String, dynamic>;
    expect(sent, {'ticket': 'the-ticket', 'codeVerifier': 'v' * 43});
    final auth = container.read(authNotifierProvider).value;
    expect(auth, isA<AuthAuthenticated>());
    expect((auth as AuthAuthenticated).roles, ['CASHIER']);
    expect(browser.kept, isNull, reason: 'the verifier is used once');
    expect(ssoReturnAtLaunch, isNull);
  });

  testWidgets('Back with a ticket that owes a second step: the step, as after a password', (tester) async {
    final server = _Server({
      'POST $_iam/sso/token': (_) => jsonResponse(
          '{"data":{"mfaRequired":true,"mfaToken":"wait-1","mfaMethods":["TOTP","RECOVERY_CODE"]}}'),
    });
    browser.kept = 'v' * 43;
    ssoReturnAtLaunch = const SsoTicket('the-ticket');
    final container = await _pump(tester, server, const LoginScreen());

    final auth = container.read(authNotifierProvider).value;
    expect(auth, isA<AuthSecondFactorOwed>());
    expect((auth as AuthSecondFactorOwed).mfaToken, 'wait-1');
  });

  testWidgets('A ticket this browser did not start is not even sent', (tester) async {
    final server = _Server({'POST $_iam/sso/token': (_) => jsonResponse(_session)});
    ssoReturnAtLaunch = const SsoTicket('somebody-elses');
    await _pump(tester, server, const LoginScreen());

    expect(server.asked('$_iam/sso/token'), isFalse);
    expect(find.textContaining('took too long or was already used'), findsOneWidget);
  });

  testWidgets('Back refused: the reason, in words, on the sign-in screen', (tester) async {
    final server = _Server({});
    ssoReturnAtLaunch = const SsoFailed('SSO_NO_ACCOUNT');
    await _pump(tester, server, const LoginScreen());

    expect(find.textContaining('has not added you here yet'), findsOneWidget);
    expect(server.requests, isEmpty);
  });

  testWidgets('A password the business no longer takes names the business; one press continues', (tester) async {
    final server = _Server({
      'POST $_iam/login': (_) => jsonResponse(
          '{"error":{"code":"SSO_REQUIRED","message":"This business signs its staff in through its identity provider",'
          '"details":["slug=acme-foods"]}}',
          403),
      'POST $_iam/sso/start': (_) => jsonResponse('{"data":{"authorizationUrl":"https://idp.example.com/a"}}'),
    });
    await _pump(tester, server, const LoginScreen());
    await tester.enterText(find.byType(TextFormField).at(0), 'cashier@example.com');
    await tester.enterText(find.byType(TextFormField).at(1), 'a long enough password');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();

    expect(find.textContaining('signs you in through its own sign-in page'), findsOneWidget);
    expect(find.text('Continue with acme-foods'), findsOneWidget);
    await tester.tap(find.byKey(const Key('sso-continue')));
    await _pumps(tester);
    expect((server.last('$_iam/sso/start').data as Map)['slug'], 'acme-foods');
    expect(browser.wentTo, 'https://idp.example.com/a');
  });

  // ── the owner's settings ─────────────────────────────────────────────────────

  Future<void> openSettings(WidgetTester tester, _Server server) async {
    await _pump(
      tester,
      server,
      Builder(
        builder: (context) => TextButton(
          onPressed: () => showDialog<bool>(context: context, builder: (_) => const SsoSettingsDialog()),
          child: const Text('open'),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  const connected =
      '{"data":{"slug":"acme-foods","issuer":"https://login.example.com/t/v2.0","clientId":"client-1",'
      '"clientSecretSet":true,"enabled":true,"requiredTiers":["CASHIER"],"requireVerifiedEmail":true,'
      '"callbackUrl":"https://api.storeql.com/api/iam-svc/auth/sso/callback","updatedAt":"2026-09-21T00:00:00Z"}}';

  testWidgets('An owner changes the settings; an empty secret keeps the one saved', (tester) async {
    final server = _Server({
      'GET $_iam/admin/sso': (_) => jsonResponse(connected),
      'PUT $_iam/admin/sso': (_) => jsonResponse(connected),
    });
    await openSettings(tester, server);

    expect(find.text('https://api.storeql.com/api/iam-svc/auth/sso/callback'), findsOneWidget);
    expect(find.text('Leave empty to keep the one saved.'), findsOneWidget);
    await tester.tap(find.byKey(const Key('sso-tier-MANAGER')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('sso-save')));
    await tester.pumpAndSettle();

    // The PUT, not the read the saved dialog refreshes itself with after it.
    final sent = server.requests.lastWhere((r) => r.method == 'PUT').data as Map<String, dynamic>;
    expect(sent.containsKey('clientSecret'), isFalse);
    expect(sent['requiredTiers'], ['CASHIER', 'MANAGER']);
    expect(sent['slug'], 'acme-foods');
    expect(find.byType(AlertDialog), findsNothing);
  });

  testWidgets('A first connection asks for the secret; a manager is told only an owner saves', (tester) async {
    final server = _Server({
      'GET $_iam/admin/sso': (_) =>
          jsonResponse('{"error":{"code":"SSO_NOT_CONFIGURED","message":"none"}}', 404),
      'PUT $_iam/admin/sso': (_) => jsonResponse('{"error":{"code":"FORBIDDEN","message":"no"}}', 403),
    });
    await openSettings(tester, server);

    await tester.enterText(find.byKey(const Key('sso-form-slug')), 'acme-foods');
    await tester.enterText(find.byKey(const Key('sso-form-issuer')), 'https://login.example.com/t/v2.0');
    await tester.enterText(find.byKey(const Key('sso-form-client')), 'client-1');
    await tester.tap(find.byKey(const Key('sso-save')));
    await tester.pump();
    expect(find.text('Required the first time'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);

    await tester.enterText(find.byKey(const Key('sso-form-secret')), 'the secret');
    await tester.tap(find.byKey(const Key('sso-save')));
    await tester.pumpAndSettle();
    expect(find.text('Only an owner changes this.'), findsOneWidget);
  });

  testWidgets('Check reads the provider now and says what holds and what does not', (tester) async {
    final server = _Server({
      'GET $_iam/admin/sso': (_) => jsonResponse(connected),
      'GET $_iam/admin/sso/readiness': (_) => jsonResponse(
          '{"data":{"ready":false,"checks":[{"code":"DISCOVERY_READ","satisfied":true,"detail":"Read"},'
          '{"code":"KEYS_READ","satisfied":false,"detail":"The provider publishes no RS256 signing key"}]}}'),
    });
    await openSettings(tester, server);
    await tester.tap(find.byKey(const Key('sso-check')));
    await tester.pumpAndSettle();

    expect(find.text('Provider found'), findsOneWidget);
    expect(find.text('The provider publishes no RS256 signing key'), findsOneWidget);
  });
}
