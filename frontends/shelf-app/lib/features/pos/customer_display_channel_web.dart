import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';

// ignore: avoid_web_libraries_in_flutter
import 'package:web/web.dart' as web;

import 'customer_display_channel.dart';

const _channelName = 'shelfj-customer-display';

/// Web: a BroadcastChannel between the till's window and the display's.
class _WebCustomerDisplay implements CustomerDisplayChannel {
  final web.BroadcastChannel _channel = web.BroadcastChannel(_channelName);
  final _controller = StreamController<Map<String, dynamic>>.broadcast();

  _WebCustomerDisplay() {
    _channel.onmessage = ((web.MessageEvent e) {
      final data = e.data;
      if (data.isA<JSString>()) {
        final decoded = jsonDecode((data as JSString).toDart);
        if (decoded is Map) _controller.add(Map<String, dynamic>.from(decoded));
      }
    }).toJS;
  }

  @override
  bool get supported => true;

  @override
  void post(Map<String, dynamic> message) =>
      _channel.postMessage(jsonEncode(message).toJS);

  @override
  Stream<Map<String, dynamic>> get messages => _controller.stream;

  @override
  void openWindow() {
    final loc = web.window.location;
    web.window.open('${loc.origin}${loc.pathname}#/pos/display', _channelName);
  }
}

CustomerDisplayChannel createCustomerDisplayChannel() => _WebCustomerDisplay();
