import 'customer_display_channel.dart';

/// No second window on this platform: nothing is sent, nothing arrives.
class _NoCustomerDisplay implements CustomerDisplayChannel {
  @override
  bool get supported => false;

  @override
  void post(Map<String, dynamic> message) {}

  @override
  Stream<Map<String, dynamic>> get messages => const Stream.empty();

  @override
  void openWindow() {}
}

CustomerDisplayChannel createCustomerDisplayChannel() => _NoCustomerDisplay();
