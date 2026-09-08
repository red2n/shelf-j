import 'package:flutter/foundation.dart';

import 'pos_receipt_data.dart';

/// Non-web stub.
///
/// Receipt presentation is browser-print today: [PosReceiptData.toHtml] is opened
/// in a popup and the browser's print dialog does the rest. That has no analogue
/// on Android or iOS, which the app also declares as targets.
///
/// This file exists so those builds get *nothing happening* rather than a crash.
/// The previous code reached `package:web` unconditionally, so the first completed
/// sale on a phone would have thrown — and the offline path calls this while the
/// customer is standing at the till, which is the worst possible moment for it.
/// Wiring a real native print/share path is separate work; until then a mobile
/// cashier gets no paper receipt, which is a gap rather than a failure.
void openReceiptPrint(PosReceiptData data) {
  debugPrint('Receipt ${data.shortId}: printing is web-only on this build.');
}
