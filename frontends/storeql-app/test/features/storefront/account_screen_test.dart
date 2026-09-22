import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/account_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// The shopper's own account at this shop (12.10): the profile the shop holds
// and the addresses they keep here. A shopper the shop has no record for is
// offered one; the profile saves trimmed; the book adds, edits, defaults and
// removes through the shopper's own routes; a refusal is shown in words; and
// a signed-out visitor is asked to sign in.

class _FakeAuth extends StorefrontAuthNotifier {
  _FakeAuth({bool signedIn = true}) {
    if (signedIn) {
      state = const StorefrontAuthState(
          accessToken: 'tok', refreshToken: 'ref', email: 'shopper@example.com');
    }
  }
}

/// Answers "METHOD path" with a body, or refuses it with a status and error.
class _Recorder {
  final List<RequestOptions> calls = [];
  final Map<String, dynamic> responses;
  final Map<String, ({int status, String code, String message})> refusals;

  _Recorder({this.responses = const {}, this.refusals = const {}});

  Dio dio() {
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (opts, handler) {
      calls.add(opts);
      final key = '${opts.method} ${opts.path}';
      final refusal = refusals[key];
      if (refusal != null) {
        handler.reject(DioException(
          requestOptions: opts,
          response: Response(
            requestOptions: opts,
            statusCode: refusal.status,
            data: {'error': {'code': refusal.code, 'message': refusal.message}},
          ),
          type: DioExceptionType.badResponse,
        ));
        return;
      }
      handler.resolve(Response(
        requestOptions: opts,
        statusCode: opts.method == 'POST' ? 201 : 200,
        data: {'data': responses[key], 'error': null, 'meta': {}},
      ));
    }));
    return dio;
  }
}

const _me = {
  'id': 'c-1',
  'email': 'shopper@example.com',
  'phone': '07700900123',
  'firstName': 'Chris',
  'lastName': 'Carter',
};
const _home = {
  'id': 'a-1', 'type': 'HOME', 'line1': '12 High Street', 'city': 'London',
  'country': 'GB', 'pincode': 'EC1A 1BB', 'isDefault': true,
};
const _work = {
  'id': 'a-2', 'type': 'WORK', 'line1': '1 Office Park', 'city': 'London',
  'country': 'GB', 'pincode': 'E1 6AN', 'isDefault': false,
};

Future<void> _pump(WidgetTester tester, _Recorder recorder, {bool signedIn = true}) async {
  tester.view.physicalSize = const Size(900, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => _FakeAuth(signedIn: signedIn)),
      storefrontDioProvider.overrideWith((ref) => recorder.dio()),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontAccountScreen())),
  ));
  await tester.pumpAndSettle();
}

RequestOptions _last(_Recorder r, String method) =>
    r.calls.lastWhere((c) => c.method == method);

void main() {
  testWidgets('a signed-out visitor is asked to sign in, and nothing is fetched', (tester) async {
    final recorder = _Recorder();
    await _pump(tester, recorder, signedIn: false);
    expect(find.text('Sign in'), findsOneWidget);
    expect(recorder.calls, isEmpty);
  });

  testWidgets('no record yet: the shopper is told, and can set one up', (tester) async {
    final recorder = _Recorder(refusals: {
      'GET /customer-svc/customers/me': (status: 404, code: 'CUSTOMER_NOT_FOUND', message: 'Customer not found'),
      'GET /customer-svc/customers/me/addresses': (status: 404, code: 'CUSTOMER_NOT_FOUND', message: 'Customer not found'),
    }, responses: {
      'POST /customer-svc/customers/me': _me,
    });
    await _pump(tester, recorder);
    expect(find.textContaining('holds no record for you yet'), findsOneWidget);
    await tester.tap(find.byKey(const Key('account-claim')));
    await tester.pumpAndSettle();
    expect(_last(recorder, 'POST').path, endsWith('/customers/me'));
    expect(find.text('Your account at this shop is set up.'), findsOneWidget);
  });

  testWidgets('the profile is shown and saved trimmed, with the email left alone', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': _me,
      'GET /customer-svc/customers/me/addresses': <dynamic>[],
      'PUT /customer-svc/customers/me': _me,
    });
    await _pump(tester, recorder);
    expect(find.text('Chris'), findsOneWidget);
    expect(find.text('shopper@example.com'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('profile-first')), '  Christopher ');
    await tester.enterText(find.byKey(const Key('profile-phone')), '');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    final put = _last(recorder, 'PUT');
    expect(put.path, endsWith('/customers/me'));
    expect(put.data, {
      'firstName': 'Christopher',
      'lastName': 'Carter',
      'phone': null,
      'dob': null,
      'preferredLanguage': '',
    });
    expect((put.data as Map).containsKey('email'), isFalse);
    expect(find.text('Saved.'), findsOneWidget);
  });

  testWidgets('the language of their messages is chosen, and a chosen one is kept', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': {..._me, 'preferredLanguage': 'pl'},
      'GET /customer-svc/customers/me/addresses': <dynamic>[],
      'PUT /customer-svc/customers/me': _me,
    });
    await _pump(tester, recorder);
    expect(find.text('Polish'), findsOneWidget, reason: 'the language they chose is shown');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect((_last(recorder, 'PUT').data as Map)['preferredLanguage'], 'pl');

    await tester.tap(find.byKey(const Key('profile-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text("The shop's language").last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect((_last(recorder, 'PUT').data as Map)['preferredLanguage'], '', reason: 'empty clears it');
  });

  testWidgets('a blank name is stopped before it is sent', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': _me,
      'GET /customer-svc/customers/me/addresses': <dynamic>[],
    });
    await _pump(tester, recorder);
    await tester.enterText(find.byKey(const Key('profile-last')), '   ');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect(find.text('Required'), findsOneWidget);
    expect(recorder.calls.where((c) => c.method == 'PUT'), isEmpty);
  });

  testWidgets('the book lists each address with its default, and adds one through the dialog',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': _me,
      'GET /customer-svc/customers/me/addresses': [_home, _work],
      'POST /customer-svc/customers/me/addresses': _work,
    });
    await _pump(tester, recorder);
    expect(find.text('12 High Street, London, EC1A 1BB, GB'), findsOneWidget);
    expect(find.text('1 Office Park, London, E1 6AN, GB'), findsOneWidget);
    expect(find.text('Default'), findsOneWidget);

    await tester.tap(find.byKey(const Key('account-add-address')));
    await tester.pumpAndSettle();
    // Required fields are enforced before anything is sent.
    await tester.tap(find.byKey(const Key('address-save')));
    await tester.pumpAndSettle();
    expect(find.text('Required'), findsNWidgets(2));
    expect(recorder.calls.where((c) => c.method == 'POST'), isEmpty);

    await tester.enterText(find.byKey(const Key('address-line1')), ' 3 Mill Lane ');
    await tester.enterText(find.byKey(const Key('address-city')), 'Leeds');
    await tester.enterText(find.byKey(const Key('address-country')), 'GB');
    await tester.tap(find.byKey(const Key('address-default')));
    await tester.tap(find.byKey(const Key('address-save')));
    await tester.pumpAndSettle();
    final post = _last(recorder, 'POST');
    expect(post.path, endsWith('/customers/me/addresses'));
    expect(post.data, {
      'type': 'HOME', 'line1': '3 Mill Lane', 'line2': null, 'city': 'Leeds', 'state': null,
      'country': 'GB', 'pincode': null, 'isDefault': true,
    });
    expect(find.text('Address added.'), findsOneWidget);
  });

  testWidgets('a full book is refused in the server\'s words', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': _me,
      'GET /customer-svc/customers/me/addresses': [_home],
    }, refusals: {
      'POST /customer-svc/customers/me/addresses': (status: 409, code: 'CUSTOMER_ADDRESS_LIMIT', message: 'an address book holds at most 10 addresses'),
    });
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('account-add-address')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('address-line1')), '11 Too Many');
    await tester.enterText(find.byKey(const Key('address-country')), 'GB');
    await tester.tap(find.byKey(const Key('address-save')));
    await tester.pumpAndSettle();
    expect(find.text('an address book holds at most 10 addresses'), findsOneWidget);
  });

  testWidgets('make default replaces the address with the flag set; remove asks first',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': _me,
      'GET /customer-svc/customers/me/addresses': [_home, _work],
      'PUT /customer-svc/customers/me/addresses/a-2': _work,
      'DELETE /customer-svc/customers/me/addresses/a-1': null,
    });
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('address-menu-a-2')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Make default'));
    await tester.pumpAndSettle();
    final put = _last(recorder, 'PUT');
    expect(put.path, endsWith('/customers/me/addresses/a-2'));
    expect((put.data as Map)['isDefault'], isTrue);
    expect((put.data as Map)['line1'], '1 Office Park');
    // Let that snackbar go, or the next one queues behind it.
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('address-menu-a-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Remove'));
    await tester.pumpAndSettle();
    expect(find.text('Remove this address?'), findsOneWidget);
    expect(recorder.calls.where((c) => c.method == 'DELETE'), isEmpty);
    await tester.tap(find.widgetWithText(FilledButton, 'Remove'));
    await tester.pumpAndSettle();
    expect(_last(recorder, 'DELETE').path, endsWith('/customers/me/addresses/a-1'));
    expect(find.text('Address removed.'), findsOneWidget);
  });

  testWidgets('the default address has no "make default" and every request is the shopper\'s own path',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me': _me,
      'GET /customer-svc/customers/me/addresses': [_home, _work],
    });
    await _pump(tester, recorder);
    await tester.tap(find.byKey(const Key('address-menu-a-1')));
    await tester.pumpAndSettle();
    expect(find.text('Make default'), findsNothing);
    expect(recorder.calls.every((c) => c.path.contains('/customers/me')), isTrue);
  });
}
