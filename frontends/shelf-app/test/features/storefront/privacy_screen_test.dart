import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/storefront/privacy_screen.dart';
import 'package:shelf_app/features/storefront/storefront_providers.dart';

// The shopper's half of the privacy work: the preference centre (PECR reg.22 —
// nothing is on until they turn it on) and the data download (UK GDPR art.20 —
// all of it or none of it, never a quiet half).

class _FakeAuth extends StorefrontAuthNotifier {
  _FakeAuth({bool signedIn = true}) {
    if (signedIn) {
      state = const StorefrontAuthState(
          accessToken: 'tok', refreshToken: 'ref', email: 'shopper@example.com');
    }
  }
}

/// Records what the screen asked the server, and answers with [responses]
/// keyed by "METHOD path".
class _Recorder {
  final List<RequestOptions> calls = [];
  final Map<String, dynamic> responses;
  final Map<String, int> statuses;

  _Recorder({this.responses = const {}, this.statuses = const {}});

  Dio dio() {
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.interceptors.add(InterceptorsWrapper(onRequest: (opts, handler) {
      calls.add(opts);
      final key = '${opts.method} ${opts.path}';
      final status = statuses[key] ?? 200;
      if (status >= 400) {
        handler.reject(DioException(
          requestOptions: opts,
          response: Response(
            requestOptions: opts,
            statusCode: status,
            data: {
              'error': {
                'code': 'EXPORT_ORDERS_UNAVAILABLE',
                'message':
                    'order-svc could not be reached, so the export would be incomplete',
              }
            },
          ),
          type: DioExceptionType.badResponse,
        ));
        return;
      }
      handler.resolve(Response(
        requestOptions: opts,
        statusCode: status,
        data: {'data': responses[key], 'error': null, 'meta': {}},
      ));
    }));
    return dio;
  }
}

Future<void> _pump(WidgetTester tester, _Recorder recorder,
    {bool signedIn = true}) async {
  // The screen copies the export to the clipboard. Without a handler the
  // platform channel never replies, the button's spinner never stops, and
  // pumpAndSettle waits forever.
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  addTearDown(() => TestDefaultBinaryMessengerBinding
      .instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform, null));

  tester.view.physicalSize = const Size(900, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => _FakeAuth(signedIn: signedIn)),
      storefrontDioProvider.overrideWith((ref) => recorder.dio()),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontPrivacyScreen())),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('every channel starts off — silence is not consent',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);

    final switches = tester.widgetList<SwitchListTile>(find.byType(SwitchListTile));
    expect(switches.length, 4);
    expect(switches.every((s) => s.value == false), isTrue);
  });

  testWidgets('a recorded consent shows as on', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'EMAIL', 'granted': true, 'basis': 'CONSENT'},
      ],
    });
    await _pump(tester, recorder);

    final email = tester.widget<SwitchListTile>(find
        .ancestor(
            of: find.text('Email'), matching: find.byType(SwitchListTile))
        .first);
    expect(email.value, isTrue);
  });

  testWidgets('turning a channel on sends the wording it was agreed against',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);

    await tester.tap(find
        .ancestor(of: find.text('Email'), matching: find.byType(SwitchListTile))
        .first);
    await tester.pumpAndSettle();

    final put = recorder.calls
        .firstWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    final body = put.data as Map<String, dynamic>;
    expect((body['channels'] as List).first['channel'], 'EMAIL');
    expect((body['channels'] as List).first['granted'], isTrue);
    expect(body['notice'], isNotNull,
        reason: 'art.7(1): the shop must be able to show what was agreed to');
  });

  testWidgets('turning one off records no wording — an opt-out agrees to nothing',
      (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': [
        {'channel': 'SMS', 'granted': true, 'basis': 'CONSENT'},
      ],
      'PUT /customer-svc/customers/me/marketing': <dynamic>[],
    });
    await _pump(tester, recorder);

    await tester.tap(find
        .ancestor(
            of: find.text('Text message'), matching: find.byType(SwitchListTile))
        .first);
    await tester.pumpAndSettle();

    final put = recorder.calls
        .firstWhere((c) => c.method == 'PUT' && c.path.endsWith('/marketing'));
    final body = put.data as Map<String, dynamic>;
    expect((body['channels'] as List).first['granted'], isFalse);
    expect(body['notice'], isNull);
  });

  testWidgets('the export is shown when it is complete', (tester) async {
    final recorder = _Recorder(responses: {
      'GET /customer-svc/customers/me/marketing': <dynamic>[],
      'GET /customer-svc/customers/me/export': {
        'exportedAt': '2026-09-12T09:00:00Z',
        'subject': {'loginId': 'abc', 'email': 'shopper@example.com'},
        'orders': [],
      },
    });
    await _pump(tester, recorder);

    await tester.tap(find.text('Download my data'));
    await tester.pumpAndSettle();

    expect(find.text('Your data'), findsWidgets);
    expect(find.textContaining('shopper@example.com'), findsOneWidget);
  });

  testWidgets('a failed export hands over nothing and says so', (tester) async {
    final recorder = _Recorder(
      responses: {'GET /customer-svc/customers/me/marketing': <dynamic>[]},
      statuses: {'GET /customer-svc/customers/me/export': 503},
    );
    await _pump(tester, recorder);

    await tester.tap(find.text('Download my data'));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsNothing);
    expect(find.textContaining('incomplete'), findsOneWidget);
  });

  testWidgets('a signed-out visitor is asked to sign in rather than shown switches',
      (tester) async {
    await _pump(tester, _Recorder(), signedIn: false);
    expect(find.byType(SwitchListTile), findsNothing);
    expect(find.text('Sign in'), findsOneWidget);
  });
}
