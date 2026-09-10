/// The contents of one POS receipt, and its HTML rendering.
///
/// Deliberately free of any platform imports: an offline sale builds one of these
/// with no server and no browser involved, and the widget tests construct them on
/// the Dart VM. How a receipt is actually *presented* is platform-specific and
/// lives behind the conditional export in `pos_receipt.dart`.
library;

import 'pos_providers.dart';

class PosReceiptData {
  final String orderId;
  final String storeName;
  final String? storeAddress; // e.g. "12 High St, London, EC1A 1BB"
  final DateTime dateTime;
  final String? cashierEmail;
  final List<PosLine> items;
  final double subtotal;
  final double discount;
  final double total;
  final String currency;
  final List<PosTender> tenders;
  final double change;
  final String? customerName;

  /// The legal receipt number, e.g. `2026-000042`. Null until order-svc has
  /// issued it — and always null for a sale held offline.
  final String? fiscalNumber;

  /// Printed where the number would be when there is none, so a receipt never
  /// passes an order id off as a receipt number.
  final String? fiscalNumberNote;

  const PosReceiptData({
    required this.orderId,
    required this.storeName,
    this.storeAddress,
    required this.dateTime,
    this.cashierEmail,
    required this.items,
    required this.subtotal,
    required this.discount,
    required this.total,
    required this.currency,
    required this.tenders,
    required this.change,
    this.customerName,
    this.fiscalNumber,
    this.fiscalNumberNote,
  });

  /// The same receipt, now carrying the number that was not issued in time.
  PosReceiptData withFiscalNumber(String number) => PosReceiptData(
        orderId: orderId,
        storeName: storeName,
        storeAddress: storeAddress,
        dateTime: dateTime,
        cashierEmail: cashierEmail,
        items: items,
        subtotal: subtotal,
        discount: discount,
        total: total,
        currency: currency,
        tenders: tenders,
        change: change,
        customerName: customerName,
        fiscalNumber: number,
      );

  String get shortId =>
      orderId.length >= 8 ? orderId.substring(0, 8).toUpperCase() : orderId.toUpperCase();

  String _fmt(double v) => '$currency ${v.toStringAsFixed(2)}';

  String _fmtDate() {
    final d = dateTime.toLocal();
    String p(int n) => n.toString().padLeft(2, '0');
    return '${d.year}-${p(d.month)}-${p(d.day)}  ${p(d.hour)}:${p(d.minute)}';
  }

  String toHtml() {
    final itemRows = StringBuffer();
    for (final l in items) {
      final lineTotal = _fmt(l.lineTotal);
      // A measured line prints its reading and the price per unit:
      // "0.375 kg × GBP 12.00/kg" is what a weights inspector reads.
      final qtyPrice = l.measured
          ? '${l.qtyLabel} × ${_fmt(l.unitPrice)}/${l.unit ?? ''}'
          : '${l.qtyLabel} × ${_fmt(l.unitPrice)}';
      itemRows.write('''
        <tr>
          <td class="item-name">${_esc(l.name)}</td>
          <td class="item-qty">${_esc(qtyPrice)}</td>
          <td class="item-total">${_esc(lineTotal)}</td>
        </tr>
      ''');
    }

    final tenderRows = StringBuffer();
    for (final t in tenders) {
      tenderRows.write('''
        <tr>
          <td>${_esc(t.label)}</td>
          <td></td>
          <td class="item-total">${_esc(_fmt(t.amount))}</td>
        </tr>
      ''');
    }

    final discountRow = discount > 0
        ? '<tr><td>Discount</td><td></td><td class="item-total">- ${_esc(_fmt(discount))}</td></tr>'
        : '';

    final changeRow = change > 0.005
        ? '<tr class="change-row"><td colspan="2"><b>Change</b></td><td class="item-total"><b>${_esc(_fmt(change))}</b></td></tr>'
        : '';

    final customerRow = customerName != null && customerName!.isNotEmpty
        ? '<div class="info-row"><span>Customer:</span><span>${_esc(customerName!)}</span></div>'
        : '';

    final addressLine =
        storeAddress != null && storeAddress!.isNotEmpty ? '<div>${_esc(storeAddress!)}</div>' : '';

    // The receipt number is the legal one or nothing. This row used to read
    // "Receipt #" over the first eight characters of the order's UUID, which is
    // an order reference wearing a receipt number's label.
    final numberRows = fiscalNumber != null
        ? '<div class="info-row receipt-no"><span>Receipt no.:</span><span>${_esc(fiscalNumber!)}</span></div>\n'
            '  <div class="info-row"><span>Order ref:</span><span>$shortId</span></div>'
        : '<div class="info-row"><span>Order ref:</span><span>$shortId</span></div>'
            '${fiscalNumberNote != null ? '\n  <div class="info-row">${_esc(fiscalNumberNote!)}</div>' : ''}';

    return '''<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>${fiscalNumber != null ? 'Receipt ${_esc(fiscalNumber!)}' : 'Order $shortId'}</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Courier New', Courier, monospace;
      font-size: 13px;
      color: #000;
      background: #fff;
      width: 340px;
      margin: 0 auto;
      padding: 20px 12px;
    }
    .center { text-align: center; }
    .store-name { font-size: 20px; font-weight: bold; letter-spacing: 1px; margin-bottom: 4px; }
    .store-sub { font-size: 11px; color: #444; line-height: 1.4; }
    .divider { border: none; border-top: 1px dashed #000; margin: 8px 0; }
    .divider-solid { border: none; border-top: 2px solid #000; margin: 8px 0; }
    .info-row { display: flex; justify-content: space-between; margin: 2px 0; font-size: 12px; }
    table { width: 100%; border-collapse: collapse; margin: 4px 0; }
    th { font-size: 11px; text-align: left; border-bottom: 1px solid #000; padding-bottom: 3px; }
    td { font-size: 12px; padding: 2px 0; vertical-align: top; }
    .item-name { width: 55%; }
    .item-qty { width: 28%; font-size: 11px; color: #333; }
    .item-total { width: 17%; text-align: right; }
    .totals-table td { padding: 1px 0; }
    .total-label { width: 70%; }
    .total-value { width: 30%; text-align: right; }
    .grand-total td { font-size: 15px; font-weight: bold; border-top: 1px solid #000; border-bottom: 2px solid #000; padding: 4px 0; }
    .change-row td { font-size: 14px; color: #006600; padding-top: 4px; }
    .footer { text-align: center; font-size: 11px; color: #444; margin-top: 12px; line-height: 1.6; }
    .receipt-no { font-size: 12px; letter-spacing: 1px; }
    @media print {
      body { width: 100%; padding: 0; margin: 0; }
      button, .no-print { display: none !important; }
    }
  </style>
</head>
<body>
  <div class="center">
    <div class="store-name">${_esc(storeName)}</div>
    $addressLine
  </div>

  <hr class="divider-solid">

  <div class="info-row"><span>Date:</span><span>${_fmtDate()}</span></div>
  $numberRows
  ${cashierEmail != null ? '<div class="info-row"><span>Cashier:</span><span>${_esc(cashierEmail!)}</span></div>' : ''}
  $customerRow

  <hr class="divider">

  <table>
    <thead>
      <tr>
        <th class="item-name">Item</th>
        <th class="item-qty">Qty × Price</th>
        <th class="item-total">Amt</th>
      </tr>
    </thead>
    <tbody>
      $itemRows
    </tbody>
  </table>

  <hr class="divider">

  <table class="totals-table">
    <tr>
      <td class="total-label">Subtotal</td>
      <td class="total-value">${_esc(_fmt(subtotal))}</td>
    </tr>
    $discountRow
    <tr class="grand-total">
      <td class="total-label">TOTAL</td>
      <td class="total-value">${_esc(_fmt(total))}</td>
    </tr>
    $tenderRows
    $changeRow
  </table>

  <hr class="divider-solid">

  <div class="footer">
    Thank you for your purchase!<br>
    Please retain this receipt.
  </div>

  <script>window.onload = function () { window.print(); };</script>
</body>
</html>''';
  }

  static String _esc(String s) => s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
}
