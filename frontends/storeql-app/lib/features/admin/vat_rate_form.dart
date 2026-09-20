import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'pricing_providers.dart';

// ---------------------------------------------------------------------------
// VAT rates as a business sets them (SJ-D56).
//
// pricing-svc never assumes a rate: a variant with no VAT category is charged
// the business's standard rate, code T1, and until that is set nothing is
// quoted to a shopper or at the till. A business that charges no VAT sets it
// exempt. Rates are typed and shown as percentages; pricing-svc stores the
// fraction.
// ---------------------------------------------------------------------------

/// The code of the business's standard rate.
const standardVatCode = 'T1';

/// A rate typed as a percentage — "20", "7.5", "20 %", "7,5" — as the fraction pricing-svc stores
/// (0.2). Null for anything that is not a number from 0 to 100.
double? vatFractionFromPercent(String input) {
  final text = input.trim().replaceAll('%', '').trim().replaceAll(',', '.');
  if (!RegExp(r'^\d+(\.\d+)?$').hasMatch(text)) return null;
  final percent = double.parse(text);
  if (percent > 100) return null;
  return double.parse((percent / 100).toStringAsFixed(6));
}

/// A stored fraction as the percentage to show or edit: 0.2 → "20", 0.075 → "7.5".
String vatPercentText(double fraction) {
  final fixed = (fraction * 100).toStringAsFixed(4);
  return fixed.replaceFirst(RegExp(r'\.?0+$'), '');
}

/// Above the pricing tabs: nothing can be quoted until the standard rate is set.
class StandardVatBanner extends ConsumerWidget {
  final VoidCallback onAdd;

  const StandardVatBanner({super.key, required this.onAdd});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rates = ref.watch(vatRatesProvider);
    // Only a list that was read can show the rate is missing; a failed or pending read says nothing.
    if (!rates.hasValue || rates.hasError) return const SizedBox.shrink();
    if (rates.requireValue.any((r) => r.code.toUpperCase() == standardVatCode)) {
      return const SizedBox.shrink();
    }
    final cs = Theme.of(context).colorScheme;
    return Card(
      key: const Key('standard-vat-missing'),
      color: cs.errorContainer,
      margin: const EdgeInsets.fromLTRB(24, 12, 24, 0),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(Icons.report_outlined, color: cs.onErrorContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'No standard VAT rate is set, so no price can be quoted to a shopper or at the '
                'till. Add the rate with code $standardVatCode — marked exempt if this business '
                'charges no VAT.',
                style: TextStyle(color: cs.onErrorContainer),
              ),
            ),
            const SizedBox(width: 12),
            FilledButton(
              key: const Key('standard-vat-add'),
              onPressed: onAdd,
              child: const Text('Add standard rate'),
            ),
          ],
        ),
      ),
    );
  }
}
