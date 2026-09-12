// A labelling scale prints a barcode that carries a price or a weight, in the
// GS1 restricted-circulation range (prefix 2). The conventions differ by
// country and by vendor — which prefixes are the shop's own, how many digits
// name the item, whether the number that follows is a price or a weight, and
// its decimal places — so the scheme is configured on the labelling scale in
// tenant-svc's register and read here on every scan.
//
// Nothing here estimates: a label the scheme cannot read exactly, or whose
// check digit does not match, is not a reading at all.

/// How one labelling scale encodes its labels. Mirrors the JSON tenant-svc
/// validated when the scheme was set.
class LabelScheme {
  final List<String> prefixes;
  final int itemDigits;

  /// PRICE or WEIGHT.
  final String valueKind;
  final int valueDecimals;

  /// Some schemes spend one digit on a check digit for the price alone
  /// (the UK "price check" convention); it is skipped, not verified.
  final bool priceCheckDigit;

  const LabelScheme({
    required this.prefixes,
    required this.itemDigits,
    required this.valueKind,
    required this.valueDecimals,
    this.priceCheckDigit = false,
  });

  static LabelScheme? fromJson(Map<String, dynamic>? j) {
    if (j == null) return null;
    final prefixes = (j['prefixes'] as List?)?.map((e) => '$e').toList();
    final itemDigits = (j['itemDigits'] as num?)?.toInt();
    final kind = j['valueKind'] as String?;
    final decimals = (j['valueDecimals'] as num?)?.toInt();
    if (prefixes == null || prefixes.isEmpty || itemDigits == null || kind == null || decimals == null) {
      return null;
    }
    return LabelScheme(
      prefixes: prefixes,
      itemDigits: itemDigits,
      valueKind: kind,
      valueDecimals: decimals,
      priceCheckDigit: j['priceCheckDigit'] == true,
    );
  }
}

/// What a label said: which item, and either how much it costs or how much it
/// weighs. Exactly one of [price] and [weight] is set.
class LabelReading {
  final String itemCode;
  final double? price;
  final double? weight;

  const LabelReading({required this.itemCode, this.price, this.weight});
}

/// True for a 13-digit code whose GS1 check digit is right.
bool ean13ChecksumOk(String code) {
  if (code.length != 13 || !RegExp(r'^\d{13}$').hasMatch(code)) return false;
  var sum = 0;
  for (var i = 0; i < 12; i++) {
    final d = code.codeUnitAt(i) - 48;
    sum += (i.isEven) ? d : d * 3;
  }
  final check = (10 - (sum % 10)) % 10;
  return check == code.codeUnitAt(12) - 48;
}

/// Reads a scanned code against a scheme. Returns null when the code is not
/// one of this scale's labels — the wrong length, the wrong prefix, a bad check
/// digit — so the caller falls back to an ordinary barcode lookup.
LabelReading? readVariableMeasureBarcode(String code, LabelScheme scheme) {
  final c = code.trim();
  if (!ean13ChecksumOk(c)) return null;
  if (!scheme.prefixes.contains(c.substring(0, 2))) return null;
  // prefix(2) + item + [price check digit] + value + check(1) == 13
  final valueDigits = 13 - 2 - scheme.itemDigits - (scheme.priceCheckDigit ? 1 : 0) - 1;
  if (valueDigits < 3) return null;
  final itemCode = c.substring(2, 2 + scheme.itemDigits);
  final valueStart = 2 + scheme.itemDigits + (scheme.priceCheckDigit ? 1 : 0);
  final raw = int.parse(c.substring(valueStart, valueStart + valueDigits));
  final value = raw / _pow10(scheme.valueDecimals);
  if (scheme.valueKind == 'WEIGHT') {
    if (value <= 0) return null;
    return LabelReading(itemCode: itemCode, weight: value);
  }
  if (value <= 0) return null;
  return LabelReading(itemCode: itemCode, price: value);
}

int _pow10(int n) {
  var p = 1;
  for (var i = 0; i < n; i++) {
    p *= 10;
  }
  return p;
}

/// The quantity a price-embedded label implies at the item's unit price, or
/// null when the unit price is not positive. Rounded to the gram, because a
/// price in pence cannot resolve finer than the scale did.
double? quantityFromPrice(double price, double unitPrice) {
  if (unitPrice <= 0) return null;
  return (price / unitPrice * 1000).round() / 1000;
}
