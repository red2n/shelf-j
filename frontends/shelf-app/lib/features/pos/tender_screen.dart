import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme.dart';

class TenderScreen extends StatefulWidget {
  const TenderScreen({super.key});

  @override
  State<TenderScreen> createState() => _TenderScreenState();
}

class _TenderScreenState extends State<TenderScreen> {
  String _method = 'CASH';
  final _amountCtrl = TextEditingController();

  @override
  void dispose() {
    _amountCtrl.dispose();
    super.dispose();
  }

  double get _due => 0.00; // TODO: receive from cart state
  double get _tendered => double.tryParse(_amountCtrl.text) ?? 0.0;
  double get _change => (_tendered - _due).clamp(0.0, double.infinity);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Tender', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 4),
          Text(
            'Total due: \$${_due.toStringAsFixed(2)}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 28),
          Text('Payment method', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: ['CASH', 'CARD', 'QR_CODE', 'SPLIT'].map((m) {
              return ChoiceChip(
                label: Text(m.replaceAll('_', ' ')),
                selected: _method == m,
                onSelected: (_) => setState(() => _method = m),
              );
            }).toList(),
          ),
          if (_method == 'CASH') ...[
            const SizedBox(height: 20),
            TextField(
              controller: _amountCtrl,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                labelText: 'Cash tendered',
                prefixText: '\$ ',
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Change: \$${_change.toStringAsFixed(2)}',
              style: TextStyle(color: cs.primary, fontWeight: FontWeight.bold),
            ),
          ],
          const Spacer(),
          FilledButton.icon(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.posAccent,
              padding: const EdgeInsets.symmetric(vertical: 16),
            ),
            onPressed: () {
              // TODO: POST to order-svc to finalise sale, then payment-svc
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Sale complete — receipt printing…')),
              );
              context.go('/pos/cart');
            },
            icon: const Icon(Icons.check_circle_outline),
            label: const Text('Complete Sale', style: TextStyle(fontSize: 17)),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: () => context.go('/pos/cart'),
            child: const Text('Back to Cart'),
          ),
        ],
      ),
    );
  }
}
