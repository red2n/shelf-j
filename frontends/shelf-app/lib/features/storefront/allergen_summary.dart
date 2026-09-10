import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import 'storefront_providers.dart';

// ---------------------------------------------------------------------------
// What a shopper is told about a product's allergens.
//
// Built around the one mistake that matters: telling someone a product is free
// from something when nobody has checked. product-svc keeps apart a product
// declared to contain none of the fourteen (DECLARED, empty list) and one nobody
// has declared (UNDECLARED, also an empty list), and so does this screen.
// ---------------------------------------------------------------------------

/// Shown whenever the truth is unknown: undeclared, unreadable, or failed to
/// load. Never replaced by silence.
const askInStore =
    'Allergen information is not available for this product. Please ask in store before buying.';

class AllergenDeclaration {
  final String status;
  final List<({String code, String presence})> allergens;

  const AllergenDeclaration({required this.status, required this.allergens});

  /// A reply with no status is treated as not declared — never as free-from.
  factory AllergenDeclaration.fromJson(Map<String, dynamic> j) => AllergenDeclaration(
        status: j['status'] as String? ?? 'UNDECLARED',
        allergens: [
          for (final a in (j['allergens'] as List? ?? const []))
            (
              code: (a as Map)['code'] as String? ?? '',
              presence: a['presence'] as String? ?? '',
            ),
        ],
      );
}

/// The regulated list's names by code, for the sentence a shopper reads.
final storefrontAllergenNamesProvider = FutureProvider<Map<String, String>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.product}/catalog/allergens');
  final data = (resp.data['data'] as List?) ?? const [];
  return {
    for (final e in data)
      (e as Map)['code'] as String: e['name'] as String? ?? e['code'] as String,
  };
});

final storefrontAllergensProvider =
    FutureProvider.family<AllergenDeclaration, String>((ref, variantId) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp =
      await dio.get('/${ApiConstants.product}/catalog/variants/$variantId/allergens');
  return AllergenDeclaration.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The sentence a shopper reads, or null when there is nothing to declare
/// because the product is not food.
String? allergenSentence(AllergenDeclaration d, Map<String, String> names) {
  if (d.status == 'NOT_APPLICABLE') return null;
  if (d.status != 'DECLARED') return askInStore;
  if (d.allergens.isEmpty) return 'Contains none of the 14 regulated allergens.';
  String named(String presence) => d.allergens
      .where((a) => a.presence == presence)
      .map((a) => names[a.code] ?? a.code)
      .join(', ');
  final contains = named('CONTAINS');
  final mayContain = named('MAY_CONTAIN');
  return [
    if (contains.isNotEmpty) 'Contains: $contains.',
    if (mayContain.isNotEmpty) 'May contain: $mayContain.',
  ].join(' ');
}

class AllergenSummary extends ConsumerWidget {
  final String variantId;

  const AllergenSummary({super.key, required this.variantId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final declaration = ref.watch(storefrontAllergensProvider(variantId));
    final names = ref.watch(storefrontAllergenNamesProvider).value ?? const {};
    // The error is checked before loading and before the value. Riverpod
    // retries a failed load, and while it retries the state reads as loading:
    // rendering the loading branch then would be silence, and to a shopper no
    // allergen line reads as nothing to declare. A stale value is not shown
    // over a failure either — the declaration may have changed since.
    if (declaration.hasError) {
      return _line(context, askInStore, warn: true);
    }
    final d = declaration.value;
    if (d == null) return const SizedBox.shrink();
    final sentence = allergenSentence(d, names);
    if (sentence == null) return const SizedBox.shrink();
    return _line(context, sentence, warn: d.status != 'DECLARED');
  }

  Widget _line(BuildContext context, String text, {required bool warn}) {
    final cs = Theme.of(context).colorScheme;
    final color = warn ? cs.error : cs.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(warn ? Icons.info_outline : Icons.no_food_outlined, size: 14, color: color),
          const SizedBox(width: 4),
          Expanded(child: Text(text, style: TextStyle(fontSize: 12, color: color))),
        ],
      ),
    );
  }
}
