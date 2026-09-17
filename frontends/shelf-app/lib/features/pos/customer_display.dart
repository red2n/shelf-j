import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'customer_display_channel.dart';
import 'pos_providers.dart';

// ---------------------------------------------------------------------------
// The customer-facing display: a second window on the customer's side of the
// counter that shows the sale as it is rung up — each line as it is scanned,
// the subtotal, any discount, the refundable container deposit, the total —
// then what was paid and the change, and a thank-you; idle, the store's name.
// Large type, read from a metre away.
// ---------------------------------------------------------------------------

/// What the display shows for a sale in progress; idle when the cart is empty.
Map<String, dynamic> customerDisplaySale({
  required String storeName,
  required String currency,
  required List<PosLine> lines,
  required double discount,
}) {
  final subtotal = lines.fold<double>(0, (s, l) => s + l.lineTotal);
  final deposit = lines.fold<double>(0, (s, l) => s + l.depositTotal);
  final off = discount.clamp(0, subtotal).toDouble();
  return {
    'type': lines.isEmpty ? 'idle' : 'sale',
    'storeName': storeName,
    'currency': currency,
    'lines': [
      for (final l in lines)
        {
          'name': l.name,
          'qty': l.qtyLabel,
          'lineTotal': l.lineTotal,
          if (l.depositTotal > 0) 'deposit': l.depositTotal,
        }
    ],
    'subtotal': subtotal,
    'discount': off,
    'deposit': deposit,
    'total': subtotal - off + deposit,
  };
}

/// What the display shows once the sale is paid.
Map<String, dynamic> customerDisplayPaid({
  required String storeName,
  required String currency,
  required double total,
  required double paid,
  required double change,
}) =>
    {
      'type': 'paid',
      'storeName': storeName,
      'currency': currency,
      'total': total,
      'paid': paid,
      'change': change,
    };

/// How long a paid screen stays up before the display goes idle, so the
/// customer sees the change due even though the till has already cleared.
const customerDisplayPaidHold = Duration(seconds: 8);

class CustomerDisplayScreen extends ConsumerStatefulWidget {
  const CustomerDisplayScreen({super.key});

  @override
  ConsumerState<CustomerDisplayScreen> createState() =>
      _CustomerDisplayScreenState();
}

class _CustomerDisplayScreenState extends ConsumerState<CustomerDisplayScreen> {
  Map<String, dynamic> _shown = const {'type': 'idle'};
  DateTime? _holdUntil;

  void _take(Map<String, dynamic> m) {
    final type = m['type'] as String? ?? 'idle';
    final holding = _holdUntil != null && DateTime.now().isBefore(_holdUntil!);
    final empty = (m['lines'] as List?)?.isEmpty ?? true;
    // A paid screen is kept up over the till clearing its cart; a new sale with
    // lines is the next customer and takes over at once.
    if (holding && (type == 'idle' || (type == 'sale' && empty))) return;
    setState(() {
      _shown = m;
      _holdUntil =
          type == 'paid' ? DateTime.now().add(customerDisplayPaidHold) : null;
    });
    if (type == 'paid') {
      Future.delayed(customerDisplayPaidHold, () {
        if (!mounted || _shown['type'] != 'paid') return;
        setState(() {
          _shown = {'type': 'idle', 'storeName': m['storeName']};
          _holdUntil = null;
        });
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<AsyncValue<Map<String, dynamic>>>(
        customerDisplayMessagesProvider, (_, next) {
      final m = next.value;
      if (m != null) _take(m);
    });
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final m = _shown;
    final type = m['type'] as String? ?? 'idle';
    final storeName = m['storeName'] as String? ?? '';
    final currency = m['currency'] as String? ?? '';
    String money(Object? v) =>
        '$currency ${((v as num?) ?? 0).toStringAsFixed(2)}'.trim();
    final big =
        theme.textTheme.displayMedium?.copyWith(fontWeight: FontWeight.w600);
    final mid = theme.textTheme.headlineSmall;

    final Widget body;
    switch (type) {
      case 'sale':
        final lines = (m['lines'] as List?) ?? const [];
        body = Column(
          key: const Key('display-sale'),
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: ListView(
                reverse: true,
                children: [
                  for (final l in lines.reversed)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 6),
                      child: Row(children: [
                        Expanded(
                            child: Text('${(l as Map)['qty']} × ${l['name']}',
                                style: mid,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis)),
                        Text(money(l['lineTotal']), style: mid),
                      ]),
                    ),
                ],
              ),
            ),
            const Divider(),
            _row('Subtotal', money(m['subtotal']), mid),
            if (((m['discount'] as num?) ?? 0) > 0)
              _row('Discount', '− ${money(m['discount'])}', mid,
                  key: const Key('display-discount')),
            if (((m['deposit'] as num?) ?? 0) > 0)
              _row('Container deposit (refundable)', money(m['deposit']), mid,
                  key: const Key('display-deposit')),
            _row('Total', money(m['total']), big,
                key: const Key('display-total')),
          ],
        );
      case 'paid':
        body = Column(
          key: const Key('display-paid'),
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Thank you', style: big, textAlign: TextAlign.center),
            const SizedBox(height: 24),
            _row('Total', money(m['total']), mid),
            _row('Paid', money(m['paid']), mid),
            if (((m['change'] as num?) ?? 0) > 0)
              _row('Change', money(m['change']), big,
                  key: const Key('display-change')),
          ],
        );
      default:
        body = Center(
          key: const Key('display-idle'),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.storefront, size: 96, color: cs.primary),
              const SizedBox(height: 16),
              Text(storeName.isEmpty ? 'Welcome' : storeName,
                  style: big, textAlign: TextAlign.center),
              if (storeName.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text('Welcome', style: mid),
              ],
            ],
          ),
        );
    }
    return Scaffold(
      backgroundColor: cs.surface,
      body: SafeArea(
        child: Padding(padding: const EdgeInsets.all(32), child: body),
      ),
    );
  }

  static Widget _row(String label, String value, TextStyle? style,
          {Key? key}) =>
      Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style),
        ]),
      );
}
