import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/pos/pos_age_check.dart';
import 'package:shelf_app/features/pos/pos_providers.dart';

// ---------------------------------------------------------------------------
// The till's age check. The cases that matter most are the ones where the
// answer is "couldn't tell": each of them must keep the item out of the sale,
// because a till that treats not knowing as not restricted sells alcohol to a
// child while every screen says it asked.
// ---------------------------------------------------------------------------

class _Stub implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"restricted":false}}';
  RequestOptions? last;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<List<int>>? requestStream, Future<void>? cancelFuture) async {
    last = options;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Dio _dio(_Stub stub) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = stub;

void main() {
  group('checkAgeRestriction', () {
    test('an unrestricted item passes, and the store country is what is asked',
        () async {
      final stub = _Stub();
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'gb');

      expect(r, isA<AgeCheckNotRestricted>());
      expect(stub.last!.path, '/product-svc/catalog/variants/v-1/age-check');
      expect(stub.last!.queryParameters['country'], 'GB');
    });

    test('a restricted item comes back with its category, age and country',
        () async {
      final stub = _Stub()
        ..body = '{"data":{"restricted":true,"category":"ALCOHOL",'
            '"minimumAge":20,"country":"JP","tenantOverride":false}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'JP');

      expect(r, isA<AgeCheckRestricted>());
      final check = r as AgeCheckRestricted;
      // The same bottle is 18 in the UK; the age belongs to the country.
      expect(check.minimumAge, 20);
      expect(check.category, 'ALCOHOL');
      expect(check.country, 'JP');
      expect(check.storePolicy, isFalse);
    });

    test('a store policy stricter than the law is labelled as policy', () async {
      final stub = _Stub()
        ..body = '{"data":{"restricted":true,"category":"ALCOHOL",'
            '"minimumAge":25,"country":"GB","tenantOverride":true}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'GB')
          as AgeCheckRestricted;
      expect(r.storePolicy, isTrue);
    });

    test('an answer with no restricted flag is not taken as unrestricted',
        () async {
      // product-svc leaves null fields out of the JSON. A reply missing the
      // flag looks exactly like an unrestricted item that lost a field.
      final stub = _Stub()..body = '{"data":{"country":"GB","tenantOverride":false}}';
      expect(await checkAgeRestriction(_dio(stub), 'v-1', 'GB'),
          isA<AgeCheckBlocked>());
    });

    test('a restricted item with no rule for the country is blocked', () async {
      final stub = _Stub()
        ..status = 400
        ..body = '{"error":{"code":"PRODUCT_NO_AGE_RULE",'
            '"message":"No age rule for KNIVES in IN","details":[]}}';
      final r = await checkAgeRestriction(_dio(stub), 'v-1', 'IN');

      expect(r, isA<AgeCheckBlocked>());
      expect((r as AgeCheckBlocked).message, contains('no minimum age is set for IN'));
    });

    test('a store with no country is blocked, and nothing is guessed', () async {
      final stub = _Stub();
      expect(await checkAgeRestriction(_dio(stub), 'v-1', null),
          isA<AgeCheckBlocked>());
      expect(await checkAgeRestriction(_dio(stub), 'v-1', '-'),
          isA<AgeCheckBlocked>());
      // No request at all: there is no country it would be right to ask about.
      expect(stub.last, isNull);
    });

    test('a failed call is blocked, not waved through', () async {
      final stub = _Stub()
        ..status = 503
        ..body = '{"error":{"code":"UPSTREAM_UNAVAILABLE","details":[]}}';
      expect(await checkAgeRestriction(_dio(stub), 'v-1', 'GB'),
          isA<AgeCheckBlocked>());
    });
  });

  group('AgeVerificationDialog', () {
    const check = AgeCheckRestricted(
        category: 'ALCOHOL', minimumAge: 18, country: 'GB', storePolicy: false);

    // Shared with the tests: a refusal completes after the helper returns,
    // once a reason is chosen, and the test reads what the dialog decided.
    AgeDecision? result;

    Future<AgeDecision?> openAndChoose(WidgetTester tester, String button,
        {AgeCheckRestricted c = check}) async {
      result = null;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await showDialog<AgeDecision>(
                context: context,
                barrierDismissible: false,
                builder: (_) =>
                    AgeVerificationDialog(itemName: 'Rioja 75cl', check: c),
              );
            },
            child: const Text('open'),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.tap(find.text(button));
      await tester.pumpAndSettle();
      return result;
    }

    testWidgets('says what to check, and against which law', (tester) async {
      await tester.pumpWidget(const MaterialApp(
          home: Scaffold(
              body: AgeVerificationDialog(itemName: 'Rioja 75cl', check: check))));
      expect(find.text('Rioja 75cl'), findsOneWidget);
      expect(find.text('Alcohol'), findsOneWidget);
      expect(find.text('The customer must be 18 or over.'), findsOneWidget);
      expect(find.text('Legal minimum in GB.'), findsOneWidget);
    });

    testWidgets('a stricter store policy is shown as policy, not as law',
        (tester) async {
      await tester.pumpWidget(const MaterialApp(
          home: Scaffold(
              body: AgeVerificationDialog(
                  itemName: 'Rioja 75cl',
                  check: AgeCheckRestricted(
                      category: 'ALCOHOL',
                      minimumAge: 25,
                      country: 'GB',
                      storePolicy: true)))));
      expect(find.textContaining('Store policy in GB'), findsOneWidget);
      expect(find.text('Checked — 25+'), findsOneWidget);
    });

    testWidgets('refusing asks why, and the answer is the record', (tester) async {
      // 'Refuse sale' alone decides nothing: the dialog stays open for a reason.
      expect(await openAndChoose(tester, 'Refuse sale'), isNull);
      expect(find.text('Why is the sale refused?'), findsOneWidget);
      await tester.tap(find.text('Under age'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Record refusal'));
      await tester.pumpAndSettle();
      expect(result, isA<AgeRefused>());
      expect((result as AgeRefused).reason, 'UNDER_AGE');
    });

    testWidgets('confirming the check lets it in', (tester) async {
      expect(await openAndChoose(tester, 'Checked — 18+'), isA<AgePassed>());
    });
  });

  group('one check per sale', () {
    test('clearing the cart clears the check — the next sale is the next customer',
        () {
      final cart = PosCartNotifier()..ageVerifiedUpTo = 18;
      cart.clear();
      expect(cart.ageVerifiedUpTo, 0);
    });

    test('resuming a parked sale asks again', () {
      final cart = PosCartNotifier()..ageVerifiedUpTo = 18;
      cart.loadLines(const []);
      expect(cart.ageVerifiedUpTo, 0);
    });
  });
}
