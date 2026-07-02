import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;
import 'survey_widgets.dart';

class StorefrontCartScreen extends ConsumerStatefulWidget {
  const StorefrontCartScreen({super.key});

  @override
  ConsumerState<StorefrontCartScreen> createState() =>
      _StorefrontCartScreenState();
}

class _StorefrontCartScreenState extends ConsumerState<StorefrontCartScreen> {
  bool _placing = false;
  // Re-entrancy guard distinct from [_placing]: set synchronously before the first await so a
  // double-tap can't fire two concurrent checkouts while still on the pending-order lookup (which
  // happens before [_placing] flips the button's loading spinner on).
  bool _checkoutInFlight = false;
  String _fulfilment = 'PICKUP'; // PICKUP | DELIVERY
  bool _payNow = true; // only consulted when showPrices — catalog mode has no price to charge.
  final _addressFormKey = GlobalKey<FormState>();
  final _line1Ctrl = TextEditingController();
  final _line2Ctrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _postalCtrl = TextEditingController();
  final _recipientNameCtrl = TextEditingController();
  final _recipientPhoneCtrl = TextEditingController();
  final _contactPhoneCtrl = TextEditingController();

  @override
  void dispose() {
    _line1Ctrl.dispose();
    _line2Ctrl.dispose();
    _cityCtrl.dispose();
    _postalCtrl.dispose();
    _recipientNameCtrl.dispose();
    _recipientPhoneCtrl.dispose();
    _contactPhoneCtrl.dispose();
    super.dispose();
  }

  static String? _requiredField(String? v) =>
      v == null || v.trim().isEmpty ? 'Required' : null;

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final notifier = ref.read(cartProvider.notifier);
    final cs = Theme.of(context).colorScheme;
    final showPrices = ref.watch(storefrontShowPricesProvider);
    final configAsync = ref.watch(storefrontConfigProvider);
    final storeName = configAsync.value?.storeName ?? '-';
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
                subtitle: Text(showPrices
                    ? '${l.sku}  ·  ${l.currency} ${l.unitPrice.toStringAsFixed(2)}'
                    : l.sku),
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
                    if (showPrices) ...[
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
                  ],
                ),
              );
            },
          ),
        ),
        SafeArea(
          child: ConstrainedBox(
            constraints: BoxConstraints(
                maxHeight: MediaQuery.of(context).size.height * 0.7),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  if (showPrices)
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
                  if (showPrices) const SizedBox(height: 12),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(
                          value: 'PICKUP',
                          label: Text('Collect from store'),
                          icon: Icon(Icons.storefront_outlined)),
                      ButtonSegment(
                          value: 'DELIVERY',
                          label: Text('Deliver to home'),
                          icon: Icon(Icons.local_shipping_outlined)),
                    ],
                    selected: {_fulfilment},
                    onSelectionChanged: (s) =>
                        setState(() => _fulfilment = s.first),
                  ),
                  if (_fulfilment == 'DELIVERY') ...[
                    const SizedBox(height: 12),
                    Form(
                      key: _addressFormKey,
                      child: Column(
                        children: [
                          TextFormField(
                            controller: _line1Ctrl,
                            decoration: const InputDecoration(
                                labelText: 'Address line 1',
                                isDense: true),
                            validator: _requiredField,
                          ),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _line2Ctrl,
                            decoration: const InputDecoration(
                                labelText: 'Address line 2 (optional)',
                                isDense: true),
                          ),
                          const SizedBox(height: 8),
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(
                                child: TextFormField(
                                  controller: _cityCtrl,
                                  decoration: const InputDecoration(
                                      labelText: 'City', isDense: true),
                                  validator: _requiredField,
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: TextFormField(
                                  controller: _postalCtrl,
                                  decoration: const InputDecoration(
                                      labelText: 'Postal code', isDense: true),
                                  validator: _requiredField,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _recipientNameCtrl,
                            decoration: const InputDecoration(
                                labelText: 'Recipient name', isDense: true),
                            validator: _requiredField,
                          ),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _recipientPhoneCtrl,
                            decoration: const InputDecoration(
                                labelText: 'Recipient phone', isDense: true),
                            keyboardType: TextInputType.phone,
                            validator: _requiredField,
                          ),
                        ],
                      ),
                    ),
                  ],
                  if (_fulfilment == 'PICKUP') ...[
                    const SizedBox(height: 12),
                    TextField(
                      controller: _contactPhoneCtrl,
                      keyboardType: TextInputType.phone,
                      decoration: const InputDecoration(
                        labelText: 'Contact phone *',
                        hintText: 'We\'ll notify you when your order is ready',
                        isDense: true,
                        prefixIcon: Icon(Icons.phone_outlined),
                      ),
                    ),
                  ],
                  if (showPrices) ...[
                    const SizedBox(height: 12),
                    SegmentedButton<bool>(
                      segments: const [
                        ButtonSegment(
                            value: true,
                            label: Text('Pay now'),
                            icon: Icon(Icons.lock_outline)),
                        ButtonSegment(
                            value: false,
                            label: Text('Pay later'),
                            icon: Icon(Icons.schedule_outlined)),
                      ],
                      selected: {_payNow},
                      onSelectionChanged: (s) =>
                          setState(() => _payNow = s.first),
                    ),
                  ],
                  const SizedBox(height: 12),
                  Container(
                    width: double.infinity,
                    padding:
                        const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    margin: const EdgeInsets.only(bottom: 12),
                    decoration: BoxDecoration(
                      color: cs.secondaryContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      children: [
                        Icon(
                            _fulfilment == 'DELIVERY'
                                ? Icons.local_shipping_outlined
                                : Icons.storefront_outlined,
                            size: 18,
                            color: cs.onSecondaryContainer),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _fulfilmentBannerText(showPrices, storeName, currency, total),
                            style: TextStyle(
                                color: cs.onSecondaryContainer, fontSize: 12),
                          ),
                        ),
                      ],
                    ),
                  ),
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
                          : Icon((showPrices && _payNow)
                              ? Icons.lock_outline
                              : Icons.receipt_long),
                      label: Text(_placing
                          ? ((showPrices && _payNow)
                              ? 'Processing payment…'
                              : 'Placing order…')
                          : ((showPrices && _payNow)
                              ? 'Pay $currency ${total.toStringAsFixed(2)}'
                              : 'Place order')),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  /// The bottom panel's fulfilment/payment summary line. Three independent axes: fulfilment
  /// (pickup/delivery), whether a price is known (showPrices), and whether payment happens now
  /// or later — catalog-mode stores have no known price, so "pay now" is never offered there.
  String _fulfilmentBannerText(
      bool showPrices, String storeName, String currency, double total) {
    final payNow = showPrices && _payNow;
    final where =
        _fulfilment == 'DELIVERY' ? 'Deliver to your address' : 'Collect from $storeName';
    if (payNow) return where;
    if (!showPrices) {
      return _fulfilment == 'DELIVERY'
          ? '$where · price & payment confirmed on delivery'
          : '$where · price & payment confirmed in store';
    }
    final amount = '$currency ${total.toStringAsFixed(2)}';
    return _fulfilment == 'DELIVERY'
        ? '$where · pay $amount on delivery'
        : '$where · pay $amount at pickup';
  }

  Future<void> _checkout() async {
    final cart = ref.read(cartProvider);
    if (cart.isEmpty) return;

    // Order placement requires a signed-in customer so the store has at least a
    // phone number on file (mandatory for pay-later follow-up / delivery contact).
    if (!ref.read(storefrontAuthProvider).isSignedIn) {
      await showDialog<void>(
        context: context,
        builder: (_) => const StorefrontAuthDialog(),
      );
      if (!mounted || !ref.read(storefrontAuthProvider).isSignedIn) return;
    }

    final delivery = _fulfilment == 'DELIVERY';
    if (delivery && !(_addressFormKey.currentState?.validate() ?? false)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Please fill in all delivery address fields.'),
      ));
      return;
    }
    if (!delivery && _contactPhoneCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Please enter a contact phone number for collection.'),
      ));
      return;
    }
    // Re-entrancy guard, set synchronously before the first await: a double-tap landing while
    // this call is still on the pending-order lookup below must not fire a second checkout. This
    // is deliberately separate from [_placing] (which only flips once we commit to placing the
    // order) so the button doesn't show a loading spinner for the whole pending-order-dialog
    // detour — it just silently ignores the extra tap.
    if (_checkoutInFlight) return;
    _checkoutInFlight = true;
    try {
      await _doCheckout(cart, delivery);
    } finally {
      _checkoutInFlight = false;
    }
  }

  Future<void> _doCheckout(List<CartLine> cart, bool delivery) async {
    // Guard: if the customer already has a pending order, ask before firing another.
    final pendingOrder = await _findPendingOrder();
    if (!mounted) return;
    if (pendingOrder != null) {
      final action = await _showPendingOrderDialog(pendingOrder);
      if (!mounted) return;
      if (action == 'update') {
        // Navigate to the orders screen so the customer can review/contact the store.
        // When order-svc exposes a PATCH /orders/{id}/items endpoint this becomes
        // a direct edit flow instead.
        context.go('/store/orders');
        return;
      } else if (action != 'new') {
        // null = dialog dismissed / cancelled — do nothing
        return;
      }
      // action == 'new' → fall through and place a second order
    }

    final showPrices = ref.read(storefrontShowPricesProvider);
    final storeName = ref.read(storefrontConfigProvider).value?.storeName ?? '-';
    // Catalog mode (store hides prices) has no known price to charge online, so payment is
    // always deferred there regardless of the on-screen toggle; priced shops let the customer
    // choose to pay now or defer to pickup/delivery.
    final payNow = showPrices && _payNow;
    final dio = ref.read(storefrontDioProvider);
    final storeId = ref.read(storefrontStoreProvider);
    // In catalog mode, CartLine.currency is '' (no price was ever fetched). Fall
    // back to 'GBP' so the order-svc currency field is never an empty string,
    // which would trigger a 400 validation error on the server.
    final rawCurrency = cart.first.currency;
    final currency = rawCurrency.isNotEmpty ? rawCurrency : 'GBP';
    final cartTotal = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final idemBase = 'sf-${DateTime.now().millisecondsSinceEpoch}';
    setState(() => _placing = true);
    try {
      // 1. Place the order (created PENDING). In catalog mode we send no client price — the
      // server resolves it (when pricing enforcement is on).
      final resp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: {
          'storeId': storeId,
          'channel': 'ONLINE',
          'fulfilmentType': _fulfilment,
          'currency': currency,
          'items': [
            for (final l in cart)
              // In catalog mode unitPrice is 0 (no price was ever fetched); the
              // server prices the order when pricing enforcement is on, otherwise
              // it's recorded as a 0-value request to be priced/fulfilled later.
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
          'contactPhone': delivery
              ? _recipientPhoneCtrl.text.trim()
              : _contactPhoneCtrl.text.trim(),
          if (delivery) ...{
            'deliveryLine1': _line1Ctrl.text.trim(),
            if (_line2Ctrl.text.trim().isNotEmpty)
              'deliveryLine2': _line2Ctrl.text.trim(),
            'deliveryCity': _cityCtrl.text.trim(),
            'deliveryPostalCode': _postalCtrl.text.trim(),
            'deliveryRecipientName': _recipientNameCtrl.text.trim(),
            'deliveryRecipientPhone': _recipientPhoneCtrl.text.trim(),
          },
        },
        options: Options(headers: {'Idempotency-Key': '$idemBase-order'}),
      );
      final data = resp.data['data'] as Map<String, dynamic>;
      final orderId = data['id'] as String? ?? '';
      final total = (data['total'] as num?)?.toDouble() ?? cartTotal;

      // 2. "Pay now" captures payment online immediately (capture → PaymentCaptured → order
      // confirms). "Pay later" — catalog mode, or a priced shop's customer choosing to defer —
      // skips payment-svc entirely; the order is a request, priced/paid at pickup or delivery.
      if (payNow) {
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
      }

      // Remember this order on-device so it shows in "My orders" (guest fallback).
      await ref.read(storefrontOrdersProvider.notifier).add(
            StorefrontOrderRecord(
              orderId: orderId,
              total: showPrices ? total : 0,
              currency: showPrices ? currency : '',
              itemCount: cart.fold<int>(0, (s, l) => s + l.qty),
              placedAt: DateTime.now(),
              storeName: storeName,
              fulfilmentType: _fulfilment,
            ),
          );
      // Signed-in customers get a server-backed list — refresh it so the new
      // order shows on the next visit to "My orders".
      ref.invalidate(serverOrdersProvider);

      ref.read(cartProvider.notifier).clear();
      if (!mounted) return;
      setState(() => _placing = false);
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(Icons.check_circle_outline,
              color: Theme.of(ctx).colorScheme.primary, size: 40),
          title: Text(payNow ? 'Payment successful' : 'Order placed'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Order #${orderId.length >= 8 ? orderId.substring(0, 8) : orderId}'),
              if (payNow) ...[
                const SizedBox(height: 6),
                Text('$currency ${total.toStringAsFixed(2)} paid',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
              ],
              const SizedBox(height: 6),
              Text(
                  payNow
                      ? 'Your order is confirmed.'
                      : 'Your order request has been received.',
                  style: TextStyle(color: Theme.of(ctx).colorScheme.outline)),
              const SizedBox(height: 6),
              Text(
                  delivery ? 'Deliver to your address' : 'Collect from $storeName',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                  textAlign: TextAlign.center),
              if (!payNow)
                Text(
                    showPrices
                        ? (delivery
                            ? 'Pay $currency ${total.toStringAsFixed(2)} on delivery.'
                            : 'Pay $currency ${total.toStringAsFixed(2)} at pickup.')
                        : (delivery
                            ? 'Price & payment will be confirmed on delivery.'
                            : 'Price & payment will be confirmed in store.'),
                    style: TextStyle(
                        color: Theme.of(ctx).colorScheme.outline, fontSize: 12)),
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
      // Show post-order survey at most once per day — after the dialog so the
      // customer has a natural pause before the next prompt.
      final capturedOrderId = orderId;
      final shownToday =
          await ref.read(customerPrefsProvider.notifier).wasSurveyShownToday();
      if (mounted && !shownToday) {
        showPostOrderSurveySheet(context, capturedOrderId);
      }
      _line1Ctrl.clear();
      _line2Ctrl.clear();
      _cityCtrl.clear();
      _postalCtrl.clear();
      _recipientNameCtrl.clear();
      _recipientPhoneCtrl.clear();
      _contactPhoneCtrl.clear();
      setState(() {
        _fulfilment = 'PICKUP';
        _payNow = true;
      });
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

  // ── Pending-order guard ──────────────────────────────────────────────────

  /// Returns the most recent pending order for this customer, or null if none.
  ///
  /// For signed-in customers: queries the server order list and looks for any
  /// order whose status indicates it has not yet been fulfilled.
  /// For guests: checks the device-local history and treats orders placed
  /// within the last 4 hours as potentially still pending (no status available
  /// for anonymous orders without a server call).
  Future<_PendingOrder?> _findPendingOrder() async {
    final auth = ref.read(storefrontAuthProvider);
    if (auth.isSignedIn) {
      try {
        final orders = await ref.read(serverOrdersProvider.future);
        if (orders == null || orders.isEmpty) return null;
        const pendingStatuses = {
          'PENDING', 'RECEIVED', 'CONFIRMED', 'PROCESSING'
        };
        final pending = orders
            .where((o) => pendingStatuses.contains(o.status.toUpperCase()))
            .toList()
          ..sort((a, b) => b.placedAt.compareTo(a.placedAt));
        if (pending.isEmpty) return null;
        final o = pending.first;
        return _PendingOrder(
            orderId: o.id, placedAt: o.placedAt, status: o.status);
      } catch (_) {
        // Fail open — never block checkout if the status check errors.
        return null;
      }
    } else {
      final local = ref.read(storefrontOrdersProvider);
      if (local.isEmpty) return null;
      final recent = local.first; // list is newest-first
      if (DateTime.now().difference(recent.placedAt).inHours < 4) {
        return _PendingOrder(
            orderId: recent.orderId,
            placedAt: recent.placedAt,
            status: 'pending');
      }
      return null;
    }
  }

  Future<String?> _showPendingOrderDialog(_PendingOrder order) {
    final shortId = order.orderId.length >= 8
        ? order.orderId.substring(0, 8)
        : order.orderId;
    final placedStr = AppFormat.dateTime(order.placedAt.toIso8601String());
    final cs = Theme.of(context).colorScheme;
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: Icon(Icons.pending_actions_outlined,
            size: 40, color: cs.primary),
        title: const Text('You have a pending order'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Order #$shortId placed at $placedStr is still being '
                'processed by the store.'),
            const SizedBox(height: 12),
            const Text('Would you like to update that order, or go ahead '
                'and place a new one?'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          OutlinedButton(
            onPressed: () => Navigator.pop(ctx, 'new'),
            child: const Text('Place new order'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, 'update'),
            child: const Text('View pending order'),
          ),
        ],
      ),
    );
  }
}


class _PendingOrder {
  final String orderId;
  final DateTime placedAt;
  final String status;
  const _PendingOrder(
      {required this.orderId,
      required this.placedAt,
      required this.status});
}
