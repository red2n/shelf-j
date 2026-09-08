/// Transport seam for the live shortage-alert push.
///
/// Import this; the right implementation is picked per platform, the same way
/// `shared/util/image_compress.dart` does it:
/// - web opens an MQTT-over-WebSocket connection (`MqttBrowserClient`),
/// - everything else no-ops.
///
/// The seam exists because `package:mqtt_client/mqtt_browser_client.dart` reaches
/// `dart:js_interop`, which does not exist on the Dart VM or on mobile. A plain top-level
/// import of it therefore breaks `flutter test` and any Android/iOS build outright — the
/// runtime `kIsWeb` guard that used to sit in `live_alerts_provider.dart` could not help,
/// because the failure is at compile time, not at run time.
library;

export 'live_alerts_mqtt_stub.dart'
    if (dart.library.js_interop) 'live_alerts_mqtt_web.dart';
