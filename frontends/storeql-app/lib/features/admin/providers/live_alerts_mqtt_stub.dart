/// Non-web implementation of [connectLiveAlerts] — see `live_alerts_mqtt.dart`.
///
/// Live push is a browser-only feature today: the shortage-alert dashboard is a back-office
/// screen used from a browser, and mqtt_client's native transport (`MqttServerClient`) has
/// never been wired up. Returning null says "no live connection" rather than failing, so the
/// dashboard still works everywhere — [shortageAlertsProvider] over REST remains the
/// authoritative feed and live push only saves it a refresh.
library;

/// Always null: there is no live connection off the web.
///
/// @param url ignored
/// @param clientId ignored
/// @param tenantId ignored
/// @param jwt ignored
/// @param topics ignored
/// @param onPayload never called
Future<void Function()?> connectLiveAlerts({
  required String url,
  required String clientId,
  required String tenantId,
  required String jwt,
  required List<String> topics,
  required void Function(String payload) onPayload,
}) async =>
    null;
