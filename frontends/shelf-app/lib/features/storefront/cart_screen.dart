import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import 'storefront_providers.dart';

class StorefrontCartScreen extends ConsumerStatefulWidget {
  const StorefrontCartScreen({super.key});

  @override
  ConsumerState<StorefrontCartScreen> createState() =>
      _StorefrontCartScreenState();
}

class _StorefrontCartScreenState extends ConsumerState<StorefrontCartScreen> {
  bool _placing = false;

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final notifier = ref.read(cartProvider.notifier);
    final cs = Theme.of(context).colorScheme;
    final currency = cart.isNotEmpty ? cart.first.currency : 'GBP';
    final total = cart.fold<double>(0, (s, l) => s + l.lineTotal);

    if (cart.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.shopping_bag_outlined, size: 64, color: cs.outlineVariant),
            const SizedBox(height: 16),
            const Text('Your cart is empty'),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: () => context.go('/store/products'),
              icon: const Icon(Icons.storefront),
              label: const Text('Browse products'),
            ),
          ],
        ),
      );
    }

    return Column(
      children: [
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: cart.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (_, i) {
              final l = cart[i];
              return ListTile(
                title: Text(l.productName),
                subtitle: Text(
                    '${l.sku}  ·  ${l.currency} ${l.unitPrice.toStringAsFixed(2)}'),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      icon: const Icon(Icons.remove_circle_outline),
                      onPressed: () =>
                          notifier.setQty(l.variantId, l.qty - 1),
                    ),
                    Text('${l.qty}',
                        style: const TextStyle(fontWeight: FontWeight.bold)),
                    IconButton(
                      icon: const Icon(Icons.add_circle_outline),
                      onPressed: () =>
                          notifier.setQty(l.variantId, l.qty + 1),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 72,
                      child: Text(
                        '${l.currency} ${l.lineTotal.toStringAsFixed(2)}',
                        textAlign: TextAlign.right,
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
        SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                Row(
                  children: [
                    Text('Total (incl. VAT)',
                        style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    Text('$currency ${total.toStringAsFixed(2)}',
                        style: Theme.of(context)
                            .textTheme
                            .titleLarge
                            ?.copyWith(fontWeight: FontWeight.bold)),
                  ],
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: _placing ? null : _checkout,
                    icon: _placing
                        ? const SizedBox(
                            height: 18,
                            width: 18,
                            child: CircularProgressIndicator(
                                strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.lock_outline),
                    label: Text(_placing
                        ? 'Processing payment…'
                        : 'Pay $currency ${total.toStringAsFixed(2)}'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _checkout() async {
    final cart = ref.read(cartProvider);
    if (cart.isEmpty) return;
    setState(() => _placing = true);
    final dio = ref.read(storefrontDioProvider);
    final storeId = ref.read(storefrontStoreProvider);
    final currency = cart.first.currency;
    final cartTotal = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final idemBase = 'sf-${DateTime.now().millisecondsSinceEpoch}';
    try {
      // 1. Place the order (created PENDING).
      final resp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: {
          'storeId': storeId,
          'channel': 'ONLINE',
          'currency': currency,
          'items': [
            for (final l in cart)
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
        },
        options: Options(headers: {'Idempotency-Key': '$idemBase-order'}),
      );
      final data = resp.data['data'] as Map<String, dynamic>;
      final orderId = data['id'] as String? ?? '';
      final total = (data['total'] as num?)?.toDouble() ?? cartTotal;

      // 2. Pay online (cashless). Capture emits PaymentCaptured → order auto-confirms.
      await dio.post(
        '/${ApiConstants.payment}/payments/online',
        data: {
          'orderId': orderId,
          'amount': total,
          'method': 'CARD',
          'storeId': storeId,
        },
        options: Options(headers: {'Idempotency-Key': '$idemBase-pay'}),
      );

      // Remember this order on-device so it shows in "My orders".
      await ref.read(storefrontOrdersProvider.notifier).add(
            StorefrontOrderRecord(
              orderId: orderId,
              total: total,
              currency: currency,
              itemCount: cart.fold<int>(0, (s, l) => s + l.qty),
              placedAt: DateTime.now(),
            ),
          );

      ref.read(cartProvider.notifier).clear();
      if (!mounted) return;
      setState(() => _placing = false);
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(Icons.check_circle_outline,
              color: Theme.of(ctx).colorScheme.primary, size: 40),
          title: const Text('Payment successful'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Order #${orderId.length >= 8 ? orderId.substring(0, 8) : orderId}'),
              const SizedBox(height: 6),
              Text('$currency ${total.toStringAsFixed(2)} paid',
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              const SizedBox(height: 6),
              Text('Your order is confirmed.',
                  style: TextStyle(color: Theme.of(ctx).colorScheme.outline)),
            ],
          ),
          actions: [
            FilledButton(
              onPressed: () {
                Navigator.pop(ctx);
                context.go('/store/products');
              },
              child: const Text('Continue shopping'),
            ),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _placing = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Checkout failed: $e'),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }
}
