import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'customer_display_channel_io.dart'
    if (dart.library.js_interop) 'customer_display_channel_web.dart';

// ---------------------------------------------------------------------------
// The customer-facing display's channel.
//
// A second window of the same browser — on the customer's side of the counter
// — shows the sale as it is rung up. On the web the till and the display talk
// over a BroadcastChannel (same origin, same browser, any number of windows);
// on other platforms there is no second window to reach, and the channel says
// so rather than pretending.
// ---------------------------------------------------------------------------

abstract class CustomerDisplayChannel {
  /// Whether a display window can be opened and reached on this platform.
  bool get supported;

  /// Sends a message to every display window.
  void post(Map<String, dynamic> message);

  /// The messages a display window receives.
  Stream<Map<String, dynamic>> get messages;

  /// Opens (or focuses) the display window.
  void openWindow();
}

final customerDisplayChannelProvider =
    Provider<CustomerDisplayChannel>((ref) => createCustomerDisplayChannel());

final customerDisplayMessagesProvider =
    StreamProvider<Map<String, dynamic>>(
        (ref) => ref.watch(customerDisplayChannelProvider).messages);
