import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/unit_price.dart';

// ---------------------------------------------------------------------------
// Unit prices beside selling prices (03.13): shown as pricing-svc computed
// them, in the price's currency, and never invented when there is no measure.
// ---------------------------------------------------------------------------

class _Pricing implements HttpClientAdapter {
  String body = '{"data":{}}';
  RequestOptions? last;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    last = o;
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

ResolvedPrice _price(String body) =>
    ResolvedPrice.fromJson(jsonDecode(body) as Map<String, dynamic>);

Future<void> _pump(WidgetTester tester, Widget child, {List<Override> overrides = const []}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: overrides,
    child: MaterialApp(home: Scaffold(body: child)),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a 750 ml bottle at £1.80 shows £2.40 per litre', (tester) async {
    final p = _price('{"unitPrice":1.5,"totalWithVat":1.8,"currency":"GBP",'
        '"unitPricing":{"amount":2.4,"unit":"L","quantity":0.75,"label":"per litre"}}');
    await _pump(tester, UnitPriceText(price: p));

    expect(p.unitPricing!.unit, 'L');
    expect(find.text('${AppFormat.money(2.4, currencyCode: 'GBP')} per litre'), findsOneWidget);
  });

  testWidgets('yen are shown in whole yen, as pricing-svc rounded them', (tester) async {
    final p = _price('{"unitPrice":498,"totalWithVat":597.6,"currency":"JPY",'
        '"unitPricing":{"amount":1195,"unit":"KG","quantity":0.5,"label":"per kg"}}');
    await _pump(tester, UnitPriceText(price: p));
    expect(find.text('${AppFormat.money(1195, currencyCode: 'JPY')} per kg'), findsOneWidget);
  });

  testWidgets('no measure, no unit price: nothing is invented', (tester) async {
    for (final body in [
      '{"unitPrice":2,"totalWithVat":2.4,"currency":"GBP"}',
      '{"unitPrice":2,"totalWithVat":2.4,"currency":"GBP","unitPricing":null}',
      '{"unitPrice":2,"totalWithVat":2.4,"currency":"GBP","unitPricing":{"unit":"KG"}}',
      '{"unitPrice":2,"totalWithVat":2.4,"currency":"GBP","unitPricing":"per kg"}',
    ]) {
      final p = _price(body);
      expect(p.unitPricing, isNull, reason: body);
      await _pump(tester, UnitPriceText(price: p));
      expect(find.byKey(const Key('unit-price')), findsNothing, reason: body);
    }
  });

  testWidgets('a cart line shows the unit price of the price it was added at', (tester) async {
    final pricing = _Pricing()
      ..body = '{"data":{"unitPrice":0.5,"totalWithVat":0.6,"currency":"GBP",'
          '"unitPricing":{"amount":0.1,"unit":"EA","quantity":6,"label":"each"}}}';
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = pricing;
    await _pump(tester, const CartLineUnitPrice(variantId: 'eggs'),
        overrides: [storefrontDioProvider.overrideWithValue(dio)]);

    expect(pricing.last!.path, '/pricing-svc/prices/resolve');
    expect(pricing.last!.data, {'variantId': 'eggs', 'channel': 'ONLINE', 'qty': 1});
    expect(find.text('${AppFormat.money(0.1, currencyCode: 'GBP')} each'), findsOneWidget);
  });

  testWidgets('an announceable reduction shows its promotion and the 30-day prior price, struck through',
      (tester) async {
    final p = _price('{"unitPrice":8,"totalWithVat":9.6,"currency":"EUR","promotionApplied":"Summer",'
        '"priorPrice":12.0,"priorPriceStatus":"ANNOUNCEABLE","reductionAnnounceable":true}');
    await _pump(tester, WasPriceText(price: p));
    final text = tester.widget<Text>(find.byKey(const Key('was-price')));
    expect(text.textSpan!.toPlainText(), 'Summer · was ${AppFormat.money(12.0, currencyCode: 'EUR')}');
  });

  testWidgets('a reduction the law does not let be announced shows no was price', (tester) async {
    for (final body in [
      '{"unitPrice":8,"totalWithVat":9.6,"currency":"EUR","promotionApplied":"Fake sale","priorPrice":9.0,"priorPriceStatus":"NOT_LOWER","reductionAnnounceable":false}',
      '{"unitPrice":8,"totalWithVat":9.6,"currency":"EUR","promotionApplied":"New","priorPriceStatus":"NO_HISTORY","reductionAnnounceable":false}',
      '{"unitPrice":8,"totalWithVat":9.6,"currency":"EUR","promotionApplied":"New","priorPrice":12.0,"priorPriceStatus":"SHORT_HISTORY","reductionAnnounceable":false}',
      '{"unitPrice":8,"totalWithVat":9.6,"currency":"EUR"}',
      '{"unitPrice":8,"totalWithVat":9.6,"currency":"EUR","priorPrice":9.0,"reductionAnnounceable":true}',
    ]) {
      await _pump(tester, WasPriceText(price: _price(body)));
      expect(find.byKey(const Key('was-price')), findsNothing, reason: body);
    }
  });
}
