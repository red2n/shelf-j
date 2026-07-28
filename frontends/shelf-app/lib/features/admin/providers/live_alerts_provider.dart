import 'dart:convert';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mqtt_client/mqtt_browser_client.dart';
import 'package:mqtt_client/mqtt_client.dart';

import '../../../core/auth/auth_notifier.dart';
import '../../../core/auth/auth_state.dart';
import '../../../core/constants.dart';

/// One shortage-alert push received live over MQTT (notification-svc's `MqttChannel`, topic
/// `shelfj/notifications/{tenantId}/{storeId}`). This is a "wake up and refetch" signal plus
/// enough to show an immediate toast — [shortageAlertsProvider] (admin_providers.dart) via the
/// REST feed stays the authoritative record, this just tells the UI not to wait for the next
/// pull-to-refresh.
class LiveAlert {
  final String subject;
  final String body;
  final DateTime receivedAt;

  LiveAlert({required this.subject, required this.body, required this.receivedAt});
}

/// Web-only for now: [MqttBrowserClient] needs a browser WebSocket, unavailable on mobile/desktop
/// builds. Native (iOS/Android/desktop) support would use mqtt_client's `MqttServerClient` behind
/// a conditional import — not implemented, since the shortage-alert dashboard is a back-office
/// screen predominantly used from a browser.
class LiveAlertsNotifier extends StateNotifier<LiveAlert?> {
  LiveAlertsNotifier(this._tenantId, this._jwt) : super(null) {
    if (kIsWeb && _tenantId != null && _jwt.isNotEmpty) {
      _connect(_tenantId, _jwt);
    }
  }

  final String? _tenantId;
  final String _jwt;
  MqttBrowserClient? _client;

  Future<void> _connect(String tenantId, String jwt) async {
    final client = MqttBrowserClient(
      ApiConstants.mqttWsUrl,
      'admin-$tenantId-${DateTime.now().millisecondsSinceEpoch}',
    );
    client.keepAlivePeriod = 30;
    client.autoReconnect = true;
    client.logging(on: false);

    try {
      await client.connect(tenantId, jwt);
    } catch (_) {
      client.disconnect();
      return;
    }
    if (client.connectionStatus?.state != MqttConnectionState.connected) {
      return;
    }

    _client = client;
    client.subscribe('shelfj/notifications/$tenantId/#', MqttQos.atLeastOnce);
    client.updates?.listen((events) {
      for (final event in events) {
        final publish = event.payload as MqttPublishMessage;
        final raw = MqttPublishPayload.bytesToStringAsString(publish.payload.message);
        try {
          final json = jsonDecode(raw) as Map<String, dynamic>;
          state = LiveAlert(
            subject: json['subject'] as String? ?? '',
            body: json['body'] as String? ?? '',
            receivedAt: DateTime.now(),
          );
        } catch (_) {
          // Malformed payload — ignore; the polled feed remains the source of truth.
        }
      }
    });
  }

  @override
  void dispose() {
    _client?.disconnect();
    super.dispose();
  }
}

/// Emits the most recently received live alert (null until the first arrives). Consume with
/// `ref.listen` to refetch the REST feed and show a toast — this is a signal, not a list.
final liveAlertsProvider = StateNotifierProvider.autoDispose<LiveAlertsNotifier, LiveAlert?>((
  ref,
) {
  final auth = ref.watch(authNotifierProvider).value;
  final tenantId = auth is AuthAuthenticated ? auth.tenantId : null;
  final jwt = auth is AuthAuthenticated ? auth.accessToken : '';
  return LiveAlertsNotifier(tenantId, jwt);
});
