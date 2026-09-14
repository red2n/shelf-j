import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import 'storefront_providers.dart';

// ---------------------------------------------------------------------------
// Product safety information on the online offer (01.12).
//
// Regulation (EU) 2023/988 art.19: an online offer shows the manufacturer, the
// EU responsible person where the manufacturer is outside the EU, and the
// warnings. Shown to a shopper before anyone signs in, like the allergens.
// ---------------------------------------------------------------------------

class StoreSafety {
  final String? manufacturerName;
  final String? manufacturerAddress;
  final String? manufacturerContact;
  final String? responsiblePersonName;
  final String? responsiblePersonAddress;
  final String? responsiblePersonContact;
  final String? warnings;
  final bool noWarnings;

  const StoreSafety({
    this.manufacturerName,
    this.manufacturerAddress,
    this.manufacturerContact,
    this.responsiblePersonName,
    this.responsiblePersonAddress,
    this.responsiblePersonContact,
    this.warnings,
    required this.noWarnings,
  });

  factory StoreSafety.fromJson(Map<String, dynamic> j) => StoreSafety(
        manufacturerName: j['manufacturerName'] as String?,
        manufacturerAddress: j['manufacturerAddress'] as String?,
        manufacturerContact: j['manufacturerContact'] as String?,
        responsiblePersonName: j['responsiblePersonName'] as String?,
        responsiblePersonAddress: j['responsiblePersonAddress'] as String?,
        responsiblePersonContact: j['responsiblePersonContact'] as String?,
        warnings: j['warnings'] as String?,
        noWarnings: j['noWarnings'] as bool? ?? false,
      );
}

/// The product's statement, or null when the business has made none.
final storefrontSafetyProvider =
    FutureProvider.autoDispose.family<StoreSafety?, String>((ref, productId) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio
      .get('/${ApiConstants.product}/catalog/products/$productId/safety-information');
  final data = resp.data['data'] as Map<String, dynamic>;
  return data['recorded'] == true ? StoreSafety.fromJson(data) : null;
});

class ProductSafetySection extends ConsumerWidget {
  final String productId;

  const ProductSafetySection({super.key, required this.productId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final safety = ref.watch(storefrontSafetyProvider(productId));
    // The error is checked before loading and before the value, as the allergen line does: Riverpod
    // retries a failed load, and while it retries the state reads as loading — rendering nothing
    // then would hide that the offer's safety information could not be read.
    if (safety.hasError) {
      return Padding(
        padding: const EdgeInsets.only(top: 16),
        child: Row(
          children: [
            Expanded(
              child: Text('Safety information could not be loaded.',
                  key: const Key('safety-unavailable'),
                  style: TextStyle(color: theme.colorScheme.error)),
            ),
            TextButton(
              onPressed: () => ref.invalidate(storefrontSafetyProvider(productId)),
              child: const Text('Try again'),
            ),
          ],
        ),
      );
    }
    return safety.maybeWhen(
      orElse: () => const SizedBox.shrink(),
      data: (s) {
        if (s == null) return const SizedBox.shrink();
        final party = theme.textTheme.bodyMedium;
        String lines(String? name, String? address, String? contact) =>
            [name, address, contact].whereType<String>().join('\n');
        return Padding(
          padding: const EdgeInsets.only(top: 16),
          child: Card(
            key: const Key('product-safety'),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Product safety', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  if (s.manufacturerName != null) ...[
                    Text('Manufacturer', style: theme.textTheme.labelLarge),
                    Text(lines(s.manufacturerName, s.manufacturerAddress, s.manufacturerContact),
                        style: party),
                    const SizedBox(height: 8),
                  ],
                  if (s.responsiblePersonName != null) ...[
                    Text('Responsible person in the EU', style: theme.textTheme.labelLarge),
                    Text(
                        lines(s.responsiblePersonName, s.responsiblePersonAddress,
                            s.responsiblePersonContact),
                        style: party),
                    const SizedBox(height: 8),
                  ],
                  Text('Warnings', style: theme.textTheme.labelLarge),
                  Text(
                    s.warnings ?? (s.noWarnings ? 'No warnings apply.' : 'None given.'),
                    key: const Key('product-safety-warnings'),
                    style: party,
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
