import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/storefront/allergen_summary.dart';

// ---------------------------------------------------------------------------
// What a shopper reads about allergens. Every test that matters here is about
// not saying "free from" when nobody has checked.
// ---------------------------------------------------------------------------

const _names = {'MILK': 'Milk', 'NUTS': 'Tree nuts', 'EGGS': 'Eggs'};

AllergenDeclaration _decl(String status, [List<(String, String)> entries = const []]) =>
    AllergenDeclaration(
      status: status,
      allergens: [for (final e in entries) (code: e.$1, presence: e.$2)],
    );

Future<void> _pump(WidgetTester tester, Future<AllergenDeclaration> Function() load) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAllergensProvider('v-1').overrideWith((ref) => load()),
      storefrontAllergenNamesProvider.overrideWith((ref) async => _names),
    ],
    child: const MaterialApp(home: Scaffold(body: AllergenSummary(variantId: 'v-1'))),
  ));
  await tester.pumpAndSettle();
}

void main() {
  group('allergenSentence', () {
    test('a product declared to contain none of the fourteen says so', () {
      expect(allergenSentence(_decl('DECLARED'), _names),
          'Contains none of the 14 regulated allergens.');
    });

    test('an undeclared product is not free-from — the shopper is told to ask', () {
      // Same empty list as the line above. Only the status tells them apart.
      expect(allergenSentence(_decl('UNDECLARED'), _names), askInStore);
    });

    test('contains and may-contain are named separately', () {
      expect(
          allergenSentence(
              _decl('DECLARED', [('MILK', 'CONTAINS'), ('NUTS', 'MAY_CONTAIN')]), _names),
          'Contains: Milk. May contain: Tree nuts.');
    });

    test('a product that is not food says nothing', () {
      expect(allergenSentence(_decl('NOT_APPLICABLE'), _names), isNull);
    });

    test('a status it does not recognise is treated as undeclared', () {
      expect(allergenSentence(_decl('SOMETHING_NEW'), _names), askInStore);
    });

    test('a reply with no status at all is not read as declared', () {
      final d = AllergenDeclaration.fromJson({'allergens': []});
      expect(d.status, 'UNDECLARED');
      expect(allergenSentence(d, _names), askInStore);
    });
  });

  group('AllergenSummary', () {
    testWidgets('shows the declaration', (tester) async {
      await _pump(tester, () async => _decl('DECLARED', [('EGGS', 'CONTAINS')]));
      expect(find.text('Contains: Eggs.'), findsOneWidget);
    });

    testWidgets('a failure to load is never silent', (tester) async {
      // Silence here would read, to a shopper, exactly like "nothing to declare".
      await _pump(tester, () async => throw Exception('product-svc unreachable'));
      expect(find.text(askInStore), findsOneWidget);
    });

    testWidgets('a non-food product shows no allergen line', (tester) async {
      await _pump(tester, () async => _decl('NOT_APPLICABLE'));
      expect(find.byType(Text), findsNothing);
    });
  });
}
