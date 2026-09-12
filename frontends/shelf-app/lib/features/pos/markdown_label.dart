// A reduce-to-clear sticker (05.4): the till's side of the code pricing-svc
// prints. EAN-13 in the shop's own range — prefix 21, five digits of item
// number, five of price in pence, and the check digit — so a plain barcode
// scanner reads it like any other pack. The code names the markdown; the
// price in it is what the sticker says, and pricing-svc is asked what the
// markdown behind it is before anything is charged.

/// Whether a scanned code is shaped like one of the shop's reduced-price
/// stickers: thirteen digits, prefix 21, check digit right. A code that is
/// not is looked up as an ordinary barcode.
bool isMarkdownLabelCode(String code) {
  if (code.length != 13 || !code.startsWith('21')) return false;
  if (!RegExp(r'^\d{13}$').hasMatch(code)) return false;
  return ean13CheckDigit(code.substring(0, 12)) == code[12];
}

/// The EAN-13 check digit for twelve digits.
String ean13CheckDigit(String twelve) {
  var sum = 0;
  for (var i = 0; i < 12; i++) {
    final d = twelve.codeUnitAt(i) - 48;
    sum += i.isEven ? d : d * 3;
  }
  return ((10 - sum % 10) % 10).toString();
}

/// The price the sticker carries, in the shop's currency.
double markdownLabelPrice(String code) =>
    int.parse(code.substring(7, 12)) / 100;
