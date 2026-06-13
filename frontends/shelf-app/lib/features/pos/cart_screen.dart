import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme.dart';

class PosCartScreen extends StatefulWidget {
  const PosCartScreen({super.key});

  @override
  State<PosCartScreen> createState() => _PosCartScreenState();
}

class _PosCartScreenState extends State<PosCartScreen> {
  final _barcodeCtrl = TextEditingController();
  final List<_LineItem> _items = [];

  @override
  void dispose() {
    _barcodeCtrl.dispose();
    super.dispose();
  }

  void _addItem(String barcode) {
    final sku = barcode.trim();
    if (sku.isEmpty) return;
    setState(() {
      final existing = _items.where((i) => i.sku == sku).firstOrNull;
      if (existing != null) {
        existing.qty++;
      } else {
        _items.add(_LineItem(sku: sku, name: 'Product ($sku)', qty: 1, unitPrice: 0.00));
      }
    });
    _barcodeCtrl.clear();
  }

  double get _total => _items.fold(0.0, (s, i) => s + i.qty * i.unitPrice);

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: TextField(
            controller: _barcodeCtrl,
            autofocus: true,
            decoration: InputDecoration(
              hintText: 'Scan barcode or type SKU…',
              prefixIcon: const Icon(Icons.qr_code_scanner),
              suffixIcon: IconButton(
                icon: const Icon(Icons.add_circle_outline),
                color: AppTheme.posAccent,
                onPressed: () => _addItem(_barcodeCtrl.text),
              ),
            ),
            onSubmitted: _addItem,
          ),
        ),
        Expanded(
          child: _items.isEmpty
              ? const Center(child: Text('Scan a product to start the sale'))
              : ListView.separated(
                  itemCount: _items.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, idx) {
                    final item = _items[idx];
                    return ListTile(
                      leading: const Icon(Icons.inventory_2_outlined),
                      title: Text(item.name),
                      subtitle: Text('SKU: ${item.sku}'),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: const Icon(Icons.remove),
                            onPressed: () => setState(() {
                              if (item.qty > 1) {
                                item.qty--;
                              } else {
                                _items.removeAt(idx);
                              }
                            }),
                          ),
                          Text('${item.qty}',
                              style: const TextStyle(fontWeight: FontWeight.bold)),
                          IconButton(
                            icon: const Icon(Icons.add),
                            onPressed: () => setState(() => item.qty++),
                          ),
                        ],
                      ),
                    );
                  },
                ),
        ),
        const Divider(height: 1),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Text(
                'Total: \$${_total.toStringAsFixed(2)}',
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
              const Spacer(),
              OutlinedButton(
                onPressed: _items.isEmpty
                    ? null
                    : () => setState(() => _items.clear()),
                child: const Text('Clear'),
              ),
              const SizedBox(width: 12),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: AppTheme.posAccent),
                onPressed: _items.isEmpty ? null : () => context.go('/pos/tender'),
                child: const Text('Tender  →'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _LineItem {
  final String sku;
  final String name;
  int qty;
  final double unitPrice;

  _LineItem({
    required this.sku,
    required this.name,
    required this.qty,
    required this.unitPrice,
  });
}
