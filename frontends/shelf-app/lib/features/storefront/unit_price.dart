import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import 'storefront_providers.dart';

// ---------------------------------------------------------------------------
// Unit prices beside selling prices (03.13).
//
// The Price Marking Order 2004 as amended and Directive 98/6/EC art.3 require
// the price per kilogram, litre, metre, square metre or item to be shown with
// the selling price, promotional prices included. pricing-svc computes it on
// every quote from the variant's declared measure; the app only shows it, and
// shows nothing rather than inventing one when there is no measure.
// ---------------------------------------------------------------------------

class UnitPriceInfo {
  final double amount;
  final String unit;
  final String label;

  const UnitPriceInfo({required this.amount, required this.unit, required this.label});

  static UnitPriceInfo? fromJson(Object? j) {
    if (j is! Map<String, dynamic>) return null;
    final amount = (j['amount'] as num?)?.toDouble();
    final unit = j['unit'] as String?;
    if (amount == null || unit == null) return null;
    return UnitPriceInfo(amount: amount, unit: unit, label: j['label'] as String? ?? '');
  }
}

/// "£2.40 per litre", in the price's own currency and its rounding.
String unitPriceLabel(UnitPriceInfo u, String currency) =>
    '${AppFormat.money(u.amount, currencyCode: currency)} ${u.label}';

/// The unit price under a selling price, or nothing when the item has no
/// declared measure.
class UnitPriceText extends StatelessWidget {
  final ResolvedPrice price;

  const UnitPriceText({super.key, required this.price});

  @override
  Widget build(BuildContext context) {
    final u = price.unitPricing;
    if (u == null) return const SizedBox.shrink();
    return Text(
      unitPriceLabel(u, price.currency),
      key: const Key('unit-price'),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant),
    );
  }
}

/// A cart line's unit price, from the same price the line was added at.
class CartLineUnitPrice extends ConsumerWidget {
  final String variantId;

  const CartLineUnitPrice({super.key, required this.variantId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final price = ref.watch(variantPriceProvider(variantId)).value;
    return price == null ? const SizedBox.shrink() : UnitPriceText(price: price);
  }
}
