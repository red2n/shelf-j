/// Web implementation of [connectLiveAlerts] — see `live_alerts_mqtt.dart`.
///
/// Opens one MQTT-over-WebSocket connection to notification-svc's broker and forwards every
/// payload on the subscribed topics. All the mqtt_client types stay inside this file, which is
/// the point of the seam: nothing outside it may reference a browser-only library.
library;

import 'package:mqtt_client/mqtt_browser_client.dart';
import 'package:mqtt_client/mqtt_client.dart';

/// Connects, subscribes to [topics], and calls [onPayload] with each message body.
///
/// @param url broker WebSocket URL
/// @param clientId MQTT client id; see the caller for why it must be deterministic
/// @param tenantId sent as the MQTT username
/// @param jwt sent as the MQTT password
/// @param topics topics to subscribe to, already scoped to what the caller may see
/// @param onPayload called with the raw message body for each publish received
/// @return a disconnect callback, or null if the connection could not be established
Future<void Function()?> connectLiveAlerts({
  required String url,
  required String clientId,
  required String tenantId,
  required String jwt,
  required List<String> topics,
  required void Function(String payload) onPayload,
}) async {
  final client = MqttBrowserClient(url, clientId);
  client.keepAlivePeriod = 30;
  client.autoReconnect = true;
  client.logging(on: false);

  try {
    await client.connect(tenantId, jwt);
  } catch (_) {
    client.disconnect();
    return null;
  }
  if (client.connectionStatus?.state != MqttConnectionState.connected) {
    client.disconnect();
    return null;
  }

  for (final topic in topics) {
    client.subscribe(topic, MqttQos.atLeastOnce);
  }
  client.updates?.listen((events) {
    for (final event in events) {
      final publish = event.payload as MqttPublishMessage;
      onPayload(MqttPublishPayload.bytesToStringAsString(publish.payload.message));
    }
  });

  return client.disconnect;
}
