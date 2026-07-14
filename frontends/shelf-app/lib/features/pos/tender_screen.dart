import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/theme.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_providers.dart';
import 'pos_receipt.dart';
import 'pos_session_providers.dart';

/// Multi-tender payment screen: a sale can be split across cash, card, gift card
/// and store credit. The cashier stages tenders until the balance is cleared,
/// then completes — placing one order and recording each tender against it.
class TenderScreen extends ConsumerStatefulWidget {
  const TenderScreen({super.key});

  @override
  ConsumerState<TenderScreen> createState() => _TenderScreenState();
}

class _TenderScreenState extends ConsumerState<TenderScreen> {
  final List<PosTender> _tenders = [];
  bool _processing = false;

  double get _due {
    final subtotal = ref.read(posCartProvider.notifier).total;
    final discount = ref.read(posDiscountProvider).clamp(0, subtotal).toDouble();
    return subtotal - discount;
  }

  double get _paid => _tenders.fold(0.0, (s, t) => s + t.amount);
  double get _remaining => (_due - _paid).clamp(0.0, double.infinity);
  double get _change => _tenders.fold(0.0, (s, t) => s + t.change);

  void _snack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    ));
  }

  Future<void> _addCashOrCard(String method) async {
    final result = await showDialog<({double amount, double given})>(
      context: context,
      builder: (_) => _AmountDialog(
        title: switch (method) {
          'CASH' => 'Cash',
          'UPI' => 'UPI',
          'WALLET' => 'Wallet',
          _ => 'Card',
        },
        currency: _currency,
        remaining: _remaining,
        allowOverpay: method == 'CASH',
      ),
    );
    if (result == null) return;
    final applied = method == 'CASH'
        ? result.amount.clamp(0, _remaining).toDouble()
        : result.amount;
    if (applied <= 0) return;
    setState(() => _tenders.add(PosTender(
          method: method,
          amount: applied,
          cashGiven: method == 'CASH' ? result.given : 0,
        )));
  }

  Future<void> _addGiftCard() async {
    final tender = await showDialog<PosTender>(
      context: context,
      builder: (_) => _GiftCardTenderDialog(currency: _currency, remaining: _remaining),
    );
    if (tender != null) setState(() => _tenders.add(tender));
  }

  Future<void> _addStoreCredit() async {
    final customer = ref.read(posCustomerProvider);
    if (customer == null) {
      _snack('Attach a customer on the Sale screen to use store credit.',
          error: true);
      return;
    }
    final tender = await showDialog<PosTender>(
      context: context,
      builder: (_) => _StoreCreditTenderDialog(
          customer: customer, currency: _currency, remaining: _remaining),
    );
    if (tender != null) setState(() => _tenders.add(tender));
  }

  String get _currency {
    final cart = ref.read(posCartProvider);
    return cart.isNotEmpty ? cart.first.currency : '';
  }

  Future<void> _complete() async {
    if (_processing) return;
    final cart = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer = ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    final discount = ref.read(posDiscountProvider).clamp(0, double.infinity).toDouble();
    if (cart.isEmpty) return;
    if (storeId == null) {
      _snack('Select a store before tendering.', error: true);
      return;
    }
    if (customer == null && walkInPhone.isEmpty) {
      _snack('Enter a contact phone number for this sale.', error: true);
      return;
    }
    if (_remaining > 0.001) {
      _snack('Balance not fully tendered.', error: true);
      return;
    }
    setState(() => _processing = true);
    final dio = ref.read(apiClientProvider).dio;
    final currency = _currency;
    final idemBase = 'pos-${DateTime.now().millisecondsSinceEpoch}';
    try {
      // 1. Place the POS order (server is authoritative for the total).
      final orderResp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: {
          'storeId': storeId,
          'channel': 'POS',
          'fulfilmentType': 'PICKUP',
          'currency': currency,
          if (discount > 0) 'discountAmount': discount,
          if (customer != null) 'customerId': customer.id,
          'contactPhone': customer != null ? '' : walkInPhone,
          'items': [
            for (final l in cart)
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
        },
        options: Options(headers: {'Idempotency-Key': '$idemBase-order'}),
      );
      final order = orderResp.data['data'] as Map<String, dynamic>;
      final orderId = order['id'] as String? ?? '';

      // 2. Record each tender against the order. STORE_CREDIT redemption is done server-side by
      // payment-svc (it redeems the customer's balance as part of capturing the tender), so the
      // client no longer redeems directly — it just supplies the customer + currency.
      for (var i = 0; i < _tenders.length; i++) {
        final t = _tenders[i];
        await dio.post(
          '/${ApiConstants.payment}/payments',
          data: {
            'orderId': orderId,
            'amount': t.amount,
            'method': t.paymentMethod,
            'storeId': storeId,
            if (t.method == 'GIFT_CARD') 'reference': t.giftCardCode,
            if (t.method == 'STORE_CREDIT') 'reference': 'STORE_CREDIT',
            if (t.method == 'STORE_CREDIT') 'customerId': t.customerId,
            if (t.method == 'STORE_CREDIT') 'currency': currency,
          },
          options: Options(headers: {'Idempotency-Key': '$idemBase-pay$i'}),
        );
        if (t.method == 'GIFT_CARD' && t.giftCardCode != null) {
          await dio.post(
            '/${ApiConstants.order}/gift-cards/${t.giftCardCode}/redeem',
            data: {'amount': t.amount, 'orderId': orderId},
          );
        }
      }

      // A completed sale is the strongest activity signal — keep the session alive.
      ref.read(posSessionProvider.notifier).touch();

      // Capture everything needed for the receipt before clearing state.
      final receiptData = _buildReceiptData(
        orderId: orderId,
        cartSnapshot: [...cart],
        tenderSnapshot: [..._tenders],
        discount: discount,
        total: (order['total'] as num?)?.toDouble() ?? _due,
        currency: currency,
        customerName: customer?.fullName.isNotEmpty == true ? customer!.fullName : customer?.email,
      );

      final change = _change;
      final email = customer?.email;
      ref.read(posCartProvider.notifier).clear();
      ref.read(posCustomerProvider.notifier).state = null;
      ref.read(posDiscountProvider.notifier).state = 0;
      ref.read(posWalkInPhoneProvider.notifier).state = '';
      _tenders.clear();
      if (!mounted) return;
      setState(() => _processing = false);

      // Open print dialog automatically — cashier can dismiss or save as PDF.
      openReceiptPrint(receiptData);

      await _showReceiptDialog(orderId, currency, change, email, receiptData: receiptData);
    } catch (e) {
      if (!mounted) return;
      setState(() => _processing = false);
      _snack(friendlyError(e, fallback: 'Sale failed.'), error: true);
    }
  }

  PosReceiptData _buildReceiptData({
    required String orderId,
    required List<PosLine> cartSnapshot,
    required List<PosTender> tenderSnapshot,
    required double discount,
    required double total,
    required String currency,
    String? customerName,
  }) {
    final subtotal = cartSnapshot.fold<double>(0, (s, l) => s + l.lineTotal);
    final storeId = ref.read(posStoreProvider);
    final stores = ref.read(posStoresProvider).valueOrNull ?? [];
    final store = stores.firstWhere((s) => s.id == storeId,
        orElse: () => stores.isNotEmpty ? stores.first : _emptyStore());
    final addressParts = [
      if (store.line1 != null && store.line1!.isNotEmpty) store.line1!,
      if (store.city != null && store.city!.isNotEmpty) store.city!,
      if (store.pincode != null && store.pincode!.isNotEmpty) store.pincode!,
      if (store.country != null && store.country!.isNotEmpty) store.country!,
    ];
    final authState = ref.read(authNotifierProvider).value;
    final cashierEmail = authState is AuthAuthenticated ? authState.email : null;
    return PosReceiptData(
      orderId: orderId,
      storeName: store.name,
      storeAddress: addressParts.isNotEmpty ? addressParts.join(', ') : null,
      dateTime: DateTime.now(),
      cashierEmail: cashierEmail,
      items: cartSnapshot,
      subtotal: subtotal,
      discount: discount,
      total: total,
      currency: currency,
      tenders: tenderSnapshot,
      change: tenderSnapshot.fold<double>(0, (s, t) => s + t.change),
      customerName: customerName,
    );
  }

  /// Record a printed or emailed receipt (best-effort — never blocks completion).
  Future<void> _recordReceipt(String orderId, String type, String? email) async {
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/admin/orders/$orderId/receipts',
        data: {
          'receiptType': type,
          if (type == 'EMAIL' && email != null) 'emailedTo': email,
          'printCount': 1,
        },
      );
      if (!mounted) return;
      _snack(type == 'EMAIL' ? 'Receipt emailed.' : 'Receipt printed.');
    } catch (e) {
      if (!mounted) return;
      _snack(friendlyError(e, fallback: 'Could not record receipt.'),
          error: true);
    }
  }

  Future<void> _showReceiptDialog(
      String orderId, String currency, double change, String? customerEmail,
      {required PosReceiptData receiptData}) {
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
            Text('Order #${orderId.length >= 8 ? orderId.substring(0, 8).toUpperCase() : orderId}'),
            if (change > 0) ...[
              const SizedBox(height: 8),
              Text('Change due: $currency ${change.toStringAsFixed(2)}',
                  style: TextStyle(
                      color: Theme.of(ctx).colorScheme.primary,
                      fontWeight: FontWeight.bold,
                      fontSize: 18)),
            ],
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: () {
                    openReceiptPrint(receiptData);
                    _recordReceipt(orderId, 'SALE', null);
                  },
                  icon: const Icon(Icons.print_outlined, size: 18),
                  label: const Text('Reprint'),
                ),
                if (customerEmail != null && customerEmail.isNotEmpty)
                  OutlinedButton.icon(
                    onPressed: () => _recordReceipt(orderId, 'EMAIL', customerEmail),
                    icon: const Icon(Icons.email_outlined, size: 18),
                    label: const Text('Email'),
                  ),
              ],
            ),
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              context.go('/pos/cart');
            },
            child: const Text('New sale'),
          ),
        ],
      ),
    );
  }

  // ── Catalog mode: order-only checkout (no prices, no payment) ──────────────

  Widget _orderOnlyView(List<PosLine> cart) {
    final qty = cart.fold<int>(0, (s, l) => s + l.qty);
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Place order', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 4),
          Text('$qty item${qty == 1 ? '' : 's'}',
              style: TextStyle(color: Theme.of(context).colorScheme.outline)),
          const SizedBox(height: 16),
          Expanded(
            child: ListView.separated(
              itemCount: cart.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final l = cart[i];
                return ListTile(
                  dense: true,
                  leading: const Icon(Icons.inventory_2_outlined),
                  title: Text(l.name),
                  subtitle: Text(l.sku),
                  trailing: Text('× ${l.qty}',
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                );
              },
            ),
          ),
          FilledButton.icon(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.posAccent,
              padding: const EdgeInsets.symmetric(vertical: 16),
            ),
            onPressed: _processing ? null : _placeOrderOnly,
            icon: _processing
                ? const SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Colors.white))
                : const Icon(Icons.receipt_long),
            label: Text(_processing ? 'Placing…' : 'Place order',
                style: const TextStyle(fontSize: 17)),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: _processing ? null : () => context.go('/pos/cart'),
            child: const Text('Back to Sale'),
          ),
        ],
      ),
    );
  }

  Future<void> _placeOrderOnly() async {
    final cart = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer = ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    if (cart.isEmpty) return;
    if (storeId == null) {
      _snack('Select a store before placing the order.', error: true);
      return;
    }
    if (customer == null && walkInPhone.isEmpty) {
      _snack('Enter a contact phone number for this sale.', error: true);
      return;
    }
    setState(() => _processing = true);
    final dio = ref.read(apiClientProvider).dio;
    final currency = _currency;
    final idem = 'pos-${DateTime.now().millisecondsSinceEpoch}-order';
    try {
      final resp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: {
          'storeId': storeId,
          'channel': 'POS',
          'fulfilmentType': 'PICKUP',
          'currency': currency,
          if (customer != null) 'customerId': customer.id,
          'contactPhone': customer != null ? '' : walkInPhone,
          'items': [
            for (final l in cart)
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
        },
        options: Options(headers: {'Idempotency-Key': idem}),
      );
      final order = resp.data['data'] as Map<String, dynamic>;
      final orderId = order['id'] as String? ?? '';
      ref.read(posSessionProvider.notifier).touch();
      final receiptData = _buildReceiptData(
        orderId: orderId,
        cartSnapshot: [...cart],
        tenderSnapshot: const [],
        discount: 0,
        total: 0,
        currency: currency,
        customerName: customer?.fullName.isNotEmpty == true ? customer!.fullName : customer?.email,
      );
      final email = customer?.email;
      ref.read(posCartProvider.notifier).clear();
      ref.read(posCustomerProvider.notifier).state = null;
      ref.read(posDiscountProvider.notifier).state = 0;
      ref.read(posWalkInPhoneProvider.notifier).state = '';
      if (!mounted) return;
      setState(() => _processing = false);
      openReceiptPrint(receiptData);
      await _showOrderPlacedDialog(orderId, email, receiptData: receiptData);
    } catch (e) {
      if (!mounted) return;
      setState(() => _processing = false);
      _snack(friendlyError(e, fallback: 'Could not place order.'), error: true);
    }
  }

  Future<void> _showOrderPlacedDialog(String orderId, String? customerEmail,
      {required PosReceiptData receiptData}) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        icon: Icon(Icons.check_circle_outline,
            color: Theme.of(ctx).colorScheme.primary, size: 40),
        title: const Text('Order placed'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Order #${orderId.length >= 8 ? orderId.substring(0, 8).toUpperCase() : orderId}'),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: () {
                    openReceiptPrint(receiptData);
                    _recordReceipt(orderId, 'SALE', null);
                  },
                  icon: const Icon(Icons.print_outlined, size: 18),
                  label: const Text('Reprint'),
                ),
                if (customerEmail != null && customerEmail.isNotEmpty)
                  OutlinedButton.icon(
                    onPressed: () => _recordReceipt(orderId, 'EMAIL', customerEmail),
                    icon: const Icon(Icons.email_outlined, size: 18),
                    label: const Text('Email'),
                  ),
              ],
            ),
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              context.go('/pos/cart');
            },
            child: const Text('New sale'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final cart = ref.watch(posCartProvider);
    final showPrices = ref.watch(posShowPricesProvider);
    final currency = _currency;
    // Recompute reactively (watch so discount/cart edits refresh the figures).
    ref.watch(posDiscountProvider);
    final due = _due;
    final remaining = _remaining;
    final settled = remaining <= 0.001;

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

    // Catalog mode (store shows no prices): order-only checkout — no tender,
    // no amounts, just confirm the items and place the order.
    if (!showPrices) {
      return _orderOnlyView(cart);
    }

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Tender', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 12),
          _SummaryRow(label: 'Total due', value: '$currency ${due.toStringAsFixed(2)}', bold: true),
          _SummaryRow(label: 'Paid', value: '$currency ${_paid.toStringAsFixed(2)}'),
          _SummaryRow(
            label: settled ? 'Change' : 'Remaining',
            value:
                '$currency ${(settled ? _change : remaining).toStringAsFixed(2)}',
            bold: true,
            color: settled ? cs.primary : cs.error,
          ),
          const SizedBox(height: 16),
          Text('Add payment', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Builder(builder: (context) {
            // Only the tenders the owner enabled for this store (gift card and
            // store credit are store-issued instruments — always available).
            final enabled = ref.watch(posEnabledPaymentMethodsProvider);
            return Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (enabled.contains('CASH'))
                  _TenderButton(
                      icon: Icons.payments_outlined,
                      label: 'Cash',
                      onTap: _processing || settled
                          ? null
                          : () => _addCashOrCard('CASH')),
                if (enabled.contains('CARD'))
                  _TenderButton(
                      icon: Icons.credit_card,
                      label: 'Card',
                      onTap: _processing || settled
                          ? null
                          : () => _addCashOrCard('CARD')),
                if (enabled.contains('UPI'))
                  _TenderButton(
                      icon: Icons.qr_code_2,
                      label: 'UPI',
                      onTap: _processing || settled
                          ? null
                          : () => _addCashOrCard('UPI')),
                if (enabled.contains('WALLET'))
                  _TenderButton(
                      icon: Icons.wallet_outlined,
                      label: 'Wallet',
                      onTap: _processing || settled
                          ? null
                          : () => _addCashOrCard('WALLET')),
                _TenderButton(
                    icon: Icons.card_giftcard,
                    label: 'Gift card',
                    onTap: _processing || settled ? null : _addGiftCard),
                _TenderButton(
                    icon: Icons.account_balance_wallet_outlined,
                    label: 'Store credit',
                    onTap: _processing || settled ? null : _addStoreCredit),
              ],
            );
          }),
          const SizedBox(height: 16),
          Expanded(
            child: _tenders.isEmpty
                ? Center(
                    child: Text('No payments added yet',
                        style: TextStyle(color: cs.outline)))
                : ListView.separated(
                    itemCount: _tenders.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final t = _tenders[i];
                      return ListTile(
                        dense: true,
                        leading: const Icon(Icons.check_circle, size: 20),
                        title: Text(t.label),
                        subtitle: t.method == 'CASH' && t.change > 0
                            ? Text(
                                'Given $currency ${t.cashGiven.toStringAsFixed(2)} · change $currency ${t.change.toStringAsFixed(2)}')
                            : (t.method == 'GIFT_CARD'
                                ? Text('Code ${t.giftCardCode}')
                                : null),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text('$currency ${t.amount.toStringAsFixed(2)}',
                                style:
                                    const TextStyle(fontWeight: FontWeight.bold)),
                            IconButton(
                              icon: const Icon(Icons.delete_outline, size: 20),
                              onPressed: _processing
                                  ? null
                                  : () => setState(() => _tenders.removeAt(i)),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
          FilledButton.icon(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.posAccent,
              padding: const EdgeInsets.symmetric(vertical: 16),
            ),
            onPressed: (_processing || !settled) ? null : _complete,
            icon: _processing
                ? const SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Colors.white))
                : const Icon(Icons.check_circle_outline),
            label: Text(_processing ? 'Processing…' : 'Complete Sale',
                style: const TextStyle(fontSize: 17)),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: _processing ? null : () => context.go('/pos/cart'),
            child: const Text('Back to Sale'),
          ),
        ],
      ),
    );
  }
}

class _SummaryRow extends StatelessWidget {
  final String label;
  final String value;
  final bool bold;
  final Color? color;
  const _SummaryRow(
      {required this.label, required this.value, this.bold = false, this.color});

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.titleMedium?.copyWith(
        fontWeight: bold ? FontWeight.bold : FontWeight.normal, color: color);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Text(label, style: style),
          const Spacer(),
          Text(value, style: style),
        ],
      ),
    );
  }
}

class _TenderButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  const _TenderButton({required this.icon, required this.label, this.onTap});

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onTap,
      icon: Icon(icon, size: 18),
      label: Text(label),
      style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12)),
    );
  }
}

/// Prompts for an amount; for cash the default is the remaining balance but the
/// cashier may hand over more (to compute change).
class _AmountDialog extends StatefulWidget {
  final String title;
  final String currency;
  final double remaining;
  final bool allowOverpay;
  const _AmountDialog({
    required this.title,
    required this.currency,
    required this.remaining,
    required this.allowOverpay,
  });

  @override
  State<_AmountDialog> createState() => _AmountDialogState();
}

class _AmountDialogState extends State<_AmountDialog> {
  late final TextEditingController _ctrl =
      TextEditingController(text: widget.remaining.toStringAsFixed(2));

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  List<double> _quick() {
    final notes = [5, 10, 20, 50, 100];
    return [
      for (final n in notes)
        if (n >= widget.remaining) n.toDouble()
    ].take(4).toList();
  }

  @override
  Widget build(BuildContext context) {
    final entered = double.tryParse(_ctrl.text) ?? 0;
    final change = widget.allowOverpay && entered > widget.remaining
        ? entered - widget.remaining
        : 0.0;
    return AlertDialog(
      title: Text('${widget.title} payment'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _ctrl,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
                labelText: 'Amount', prefixText: '${widget.currency} '),
          ),
          if (widget.allowOverpay) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              children: [
                ActionChip(
                  label: const Text('Exact'),
                  onPressed: () => setState(() =>
                      _ctrl.text = widget.remaining.toStringAsFixed(2)),
                ),
                for (final amt in _quick())
                  ActionChip(
                    label: Text('${widget.currency} ${amt.toStringAsFixed(0)}'),
                    onPressed: () =>
                        setState(() => _ctrl.text = amt.toStringAsFixed(2)),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Text('Change: ${widget.currency} ${change.toStringAsFixed(2)}',
                style: TextStyle(
                    color: Theme.of(context).colorScheme.primary,
                    fontWeight: FontWeight.bold)),
          ],
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: entered <= 0
              ? null
              : () => Navigator.pop(
                  context, (amount: entered, given: entered)),
          child: const Text('Add'),
        ),
      ],
    );
  }
}

/// Validates a gift-card code, shows its balance, and stages a gift-card tender
/// (capped at the lower of the card balance and the remaining balance).
class _GiftCardTenderDialog extends ConsumerStatefulWidget {
  final String currency;
  final double remaining;
  const _GiftCardTenderDialog(
      {required this.currency, required this.remaining});

  @override
  ConsumerState<_GiftCardTenderDialog> createState() =>
      _GiftCardTenderDialogState();
}

class _GiftCardTenderDialogState extends ConsumerState<_GiftCardTenderDialog> {
  final _codeCtrl = TextEditingController();
  bool _checking = false;
  String? _error;
  double? _balance;
  String? _validCode;

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _check() async {
    final code = _codeCtrl.text.trim();
    if (code.isEmpty) return;
    setState(() {
      _checking = true;
      _error = null;
      _balance = null;
    });
    try {
      final r = await giftCardLookup(ref, code);
      if (r.status.toUpperCase() != 'ACTIVE') {
        setState(() => _error = 'Card is ${r.status.toLowerCase()}.');
      } else if (r.balance <= 0) {
        setState(() => _error = 'Card has no balance.');
      } else {
        setState(() {
          _balance = r.balance;
          _validCode = code;
        });
      }
    } catch (e) {
      setState(() => _error =
          (e is DioException && e.response?.statusCode == 404)
              ? 'No gift card with that code.'
              : friendlyError(e, fallback: 'Lookup failed.'));
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final applied =
        _balance == null ? 0.0 : _balance!.clamp(0, widget.remaining).toDouble();
    return AlertDialog(
      title: const Text('Gift card'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _codeCtrl,
            autofocus: true,
            textCapitalization: TextCapitalization.characters,
            decoration: InputDecoration(
              labelText: 'Card code',
              suffixIcon: _checking
                  ? const Padding(
                      padding: EdgeInsets.all(12),
                      child: SizedBox(
                          height: 16,
                          width: 16,
                          child: CircularProgressIndicator(strokeWidth: 2)))
                  : IconButton(
                      icon: const Icon(Icons.search), onPressed: _check),
            ),
            onSubmitted: (_) => _check(),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          if (_balance != null) ...[
            const SizedBox(height: 12),
            Text('Balance: ${widget.currency} ${_balance!.toStringAsFixed(2)}'),
            Text('Applies: ${widget.currency} ${applied.toStringAsFixed(2)}',
                style: const TextStyle(fontWeight: FontWeight.bold)),
          ],
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: (_balance == null || applied <= 0)
              ? null
              : () => Navigator.pop(
                  context,
                  PosTender(
                      method: 'GIFT_CARD',
                      amount: applied,
                      giftCardCode: _validCode)),
          child: const Text('Add'),
        ),
      ],
    );
  }
}

/// Shows the customer's store-credit balance and stages a store-credit tender
/// (capped at the lower of the balance and the remaining balance).
class _StoreCreditTenderDialog extends ConsumerWidget {
  final Customer customer;
  final String currency;
  final double remaining;
  const _StoreCreditTenderDialog(
      {required this.customer, required this.currency, required this.remaining});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(customerStoreCreditProvider(customer.id));
    return AlertDialog(
      title: const Text('Store credit'),
      content: SizedBox(
        width: 320,
        child: async.when(
          loading: () => const SizedBox(
              height: 80, child: Center(child: CircularProgressIndicator())),
          error: (e, _) =>
              Text(friendlyError(e, fallback: 'Could not load store credit.')),
          data: (acct) {
            final applied = acct.balance.clamp(0, remaining).toDouble();
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(customer.fullName.isEmpty ? customer.email : customer.fullName),
                const SizedBox(height: 8),
                Text('Balance: $currency ${acct.balance.toStringAsFixed(2)}'),
                Text('Applies: $currency ${applied.toStringAsFixed(2)}',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
                if (applied <= 0) ...[
                  const SizedBox(height: 8),
                  Text('No store credit available.',
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        Consumer(builder: (context, ref, _) {
          final async = ref.watch(customerStoreCreditProvider(customer.id));
          final applied = async.maybeWhen(
              data: (a) => a.balance.clamp(0, remaining).toDouble(),
              orElse: () => 0.0);
          return FilledButton(
            onPressed: applied <= 0
                ? null
                : () => Navigator.pop(
                    context,
                    PosTender(
                        method: 'STORE_CREDIT',
                        amount: applied,
                        customerId: customer.id)),
            child: const Text('Add'),
          );
        }),
      ],
    );
  }
}

// Fallback used when no matching store is found in posStoresProvider.
StoreInfo _emptyStore() =>
    const StoreInfo(id: '', name: 'Store', code: '', type: 'STORE', status: 'ACTIVE');
