import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/theme.dart';
import 'pos_providers.dart';

class TenderScreen extends ConsumerStatefulWidget {
  const TenderScreen({super.key});

  @override
  ConsumerState<TenderScreen> createState() => _TenderScreenState();
}

class _TenderScreenState extends ConsumerState<TenderScreen> {
  String _method = 'CASH';
  final _amountCtrl = TextEditingController();
  bool _processing = false;

  @override
  void dispose() {
    _amountCtrl.dispose();
    super.dispose();
  }

  double get _due => ref.read(posCartProvider.notifier).total;
  double get _tendered => double.tryParse(_amountCtrl.text) ?? 0.0;
  double get _change => (_tendered - _due).clamp(0.0, double.infinity);

  Future<void> _completeSale() async {
    final cart = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    if (cart.isEmpty) return;
    if (storeId == null) {
      _snack('Select a store before tendering.', error: true);
      return;
    }
    if (_method == 'CASH' && _tendered < _due) {
      _snack('Cash tendered is less than the total due.', error: true);
      return;
    }
    setState(() => _processing = true);
    final dio = ref.read(apiClientProvider).dio;
    final currency = cart.first.currency;
    final idemBase = 'pos-${DateTime.now().millisecondsSinceEpoch}';
    try {
      // 1. Place the POS order.
      final orderResp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: {
          'storeId': storeId,
          'channel': 'POS',
          'fulfilmentType': 'PICKUP',
          'currency': currency,
          'items': [
            for (final l in cart)
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
        },
        options: Options(headers: {'Idempotency-Key': '$idemBase-order'}),
      );
      final order = orderResp.data['data'] as Map<String, dynamic>;
      final orderId = order['id'] as String? ?? '';
      // Trust the server-computed total for the tender amount.
      final total = (order['total'] as num?)?.toDouble() ?? _due;

      // 2. Record the tender against the order.
      await dio.post(
        '/${ApiConstants.payment}/payments',
        data: {
          'orderId': orderId,
          'amount': total,
          'method': _method,
          'storeId': storeId,
        },
        options: Options(headers: {'Idempotency-Key': '$idemBase-pay'}),
      );

      final change = _method == 'CASH'
          ? (_tendered - total).clamp(0.0, double.infinity)
          : 0.0;

      ref.read(posCartProvider.notifier).clear();
      if (!mounted) return;
      setState(() => _processing = false);
      await _showReceiptDialog(orderId, currency, total, change);
    } catch (e) {
      if (!mounted) return;
      setState(() => _processing = false);
      _snack('Sale failed: $e', error: true);
    }
  }

  Future<void> _showReceiptDialog(
      String orderId, String currency, double total, double change) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        icon: Icon(Icons.check_circle_outline,
            color: Theme.of(ctx).colorScheme.primary, size: 40),
        title: const Text('Sale complete'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Order #${orderId.length >= 8 ? orderId.substring(0, 8) : orderId}'),
            const SizedBox(height: 6),
            Text('$currency ${total.toStringAsFixed(2)} paid via $_method',
                style: const TextStyle(fontWeight: FontWeight.bold)),
            if (change > 0) ...[
              const SizedBox(height: 6),
              Text('Change due: $currency ${change.toStringAsFixed(2)}',
                  style: TextStyle(
                      color: Theme.of(ctx).colorScheme.primary,
                      fontWeight: FontWeight.bold)),
            ],
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              _amountCtrl.clear();
              context.go('/pos/cart');
            },
            child: const Text('New sale'),
          ),
        ],
      ),
    );
  }

  void _snack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final cart = ref.watch(posCartProvider);
    final currency = cart.isNotEmpty ? cart.first.currency : '';
    final due = ref.watch(posCartProvider.notifier).total;

    if (cart.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.point_of_sale, size: 64, color: cs.outlineVariant),
            const SizedBox(height: 16),
            const Text('No sale in progress'),
            const SizedBox(height: 16),
            OutlinedButton(
              onPressed: () => context.go('/pos/cart'),
              child: const Text('Back to Sale'),
            ),
          ],
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Tender', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 4),
          Text(
            'Total due: $currency ${due.toStringAsFixed(2)}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 28),
          Text('Payment method', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: ['CASH', 'CARD', 'QR_CODE'].map((m) {
              return ChoiceChip(
                label: Text(m.replaceAll('_', ' ')),
                selected: _method == m,
                onSelected: _processing
                    ? null
                    : (_) => setState(() => _method = m),
              );
            }).toList(),
          ),
          if (_method == 'CASH') ...[
            const SizedBox(height: 20),
            TextField(
              controller: _amountCtrl,
              enabled: !_processing,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                labelText: 'Cash tendered',
                prefixText: '$currency ',
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Change: $currency ${_change.toStringAsFixed(2)}',
              style: TextStyle(color: cs.primary, fontWeight: FontWeight.bold),
            ),
          ],
          const Spacer(),
          FilledButton.icon(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.posAccent,
              padding: const EdgeInsets.symmetric(vertical: 16),
            ),
            onPressed: _processing ? null : _completeSale,
            icon: _processing
                ? const SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Icon(Icons.check_circle_outline),
            label: Text(_processing ? 'Processing…' : 'Complete Sale',
                style: const TextStyle(fontSize: 17)),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: _processing ? null : () => context.go('/pos/cart'),
            child: const Text('Back to Cart'),
          ),
        ],
      ),
    );
  }
}
