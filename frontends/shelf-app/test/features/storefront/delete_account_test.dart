import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/storefront/storefront_providers.dart';
import 'package:shelf_app/features/storefront/storefront_shell.dart';

// SJ-D43: the account holder can delete their own login from the storefront.
// The password is asked for again, a wrong one says so and keeps them signed
// in, and a right one signs the device out with the account.

/// Signed in, with the server call replaced: the real notifier builds its own
/// Dio and writes secure storage, neither of which a widget test can reach.
class _FakeAuth extends StorefrontAuthNotifier {
  final List<String> attempts = [];

  _FakeAuth() {
    state = const StorefrontAuthState(
        accessToken: 'tok', refreshToken: 'ref', email: 'leaving@example.com');
  }

  @override
  Future<void> deleteAccount(String password) async {
    attempts.add(password);
    if (password != 'strongpass1') {
      final options = RequestOptions(path: '/iam-svc/auth/delete-account');
      throw DioException(
        requestOptions: options,
        response: Response(requestOptions: options, statusCode: 401),
        type: DioExceptionType.badResponse,
      );
    }
    state = const StorefrontAuthState();
  }
}

Future<_FakeAuth> _pumpShell(WidgetTester tester) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  final auth = _FakeAuth();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => auth),
      storefrontConfigProvider.overrideWith((ref) async =>
          const StorefrontConfig(showPrices: true, storeName: 'Test Store')),
      storefrontSuspendedProvider.overrideWith((ref) async => false),
      storefrontDioProvider.overrideWith((ref) {
        final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
        dio.interceptors.add(InterceptorsWrapper(
          onRequest: (opts, handler) => handler.reject(DioException(
            requestOptions: opts,
            type: DioExceptionType.connectionError,
          )),
        ));
        return dio;
      }),
    ],
    child: const MaterialApp(
      home: StorefrontShell(
        currentLocation: '/store/products',
        child: SizedBox.expand(),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return auth;
}

Future<void> _openDeleteDialog(WidgetTester tester) async {
  await tester.tap(find.byTooltip('leaving@example.com'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Delete my account'));
  await tester.pumpAndSettle();
}

Finder get _dialog => find.byType(AlertDialog);

void main() {
  testWidgets('the account menu offers to delete the account', (tester) async {
    await _pumpShell(tester);
    await _openDeleteDialog(tester);

    expect(find.descendant(of: _dialog, matching: find.text('Delete my account?')),
        findsOneWidget);
    // It says what is not deleted, so nobody assumes a shop's records went too.
    expect(
        find.descendant(
            of: _dialog, matching: find.textContaining('ask each shop separately')),
        findsOneWidget);
  });

  testWidgets('nothing is sent without a password', (tester) async {
    final auth = await _pumpShell(tester);
    await _openDeleteDialog(tester);

    await tester.tap(
        find.descendant(of: _dialog, matching: find.text('Delete account')));
    await tester.pumpAndSettle();

    expect(auth.attempts, isEmpty);
    expect(_dialog, findsOneWidget);
  });

  testWidgets('a wrong password says so and keeps the customer signed in',
      (tester) async {
    final auth = await _pumpShell(tester);
    await _openDeleteDialog(tester);

    await tester.enterText(
        find.descendant(of: _dialog, matching: find.byType(TextField)), 'guess');
    await tester.tap(
        find.descendant(of: _dialog, matching: find.text('Delete account')));
    await tester.pumpAndSettle();

    expect(auth.attempts, ['guess']);
    expect(find.descendant(of: _dialog, matching: find.text('Incorrect password.')),
        findsOneWidget);
    expect(auth.state.isSignedIn, isTrue);
  });

  testWidgets('the right password deletes the account and signs out',
      (tester) async {
    final auth = await _pumpShell(tester);
    await _openDeleteDialog(tester);

    await tester.enterText(
        find.descendant(of: _dialog, matching: find.byType(TextField)),
        'strongpass1');
    await tester.tap(
        find.descendant(of: _dialog, matching: find.text('Delete account')));
    await tester.pumpAndSettle();

    expect(auth.attempts, ['strongpass1']);
    expect(_dialog, findsNothing);
    expect(find.text('Your account has been deleted.'), findsOneWidget);
    expect(auth.state.isSignedIn, isFalse);
    // The menu is back to the signed-out one.
    expect(find.byTooltip('Account'), findsOneWidget);
  });
}
