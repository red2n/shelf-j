import 'dart:convert';

import 'package:flutter_riverpod/legacy.dart';

import '../../../core/auth/auth_notifier.dart';
import '../../../core/auth/auth_state.dart';
import '../../../core/constants.dart';
import 'live_alerts_mqtt.dart';

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

/// Web-only for now: the live push needs a browser WebSocket. On every other platform the
/// transport seam ([connectLiveAlerts]) no-ops and this notifier simply never emits — the REST
/// feed in `admin_providers.dart` stays the authoritative record either way. Native support would
/// mean giving `live_alerts_mqtt_stub.dart` a real `MqttServerClient` implementation; nothing
/// outside that file would change.
class LiveAlertsNotifier extends StateNotifier<LiveAlert?> {
  LiveAlertsNotifier(this._tenantId, this._userId, this._jwt, this._storeIds) : super(null) {
    if (_tenantId != null && _jwt.isNotEmpty) {
      _connect(_tenantId, _jwt);
    }
  }

  final String? _tenantId;
  final String _userId;
  final String _jwt;
  final List<String> _storeIds;
  void Function()? _disconnect;

  Future<void> _connect(String tenantId, String jwt) async {
    final disconnect = await connectLiveAlerts(
      url: ApiConstants.mqttWsUrl,
      // Deterministic clientId (not a per-connection random suffix): iam-svc's
      // MqttSessionRevoker computes this exact same string on logout to force-disconnect this
      // session — see services/iam-svc/.../client/MqttSessionRevoker.java. A second simultaneous
      // connection with the same clientId disconnects the first (standard MQTT behavior), so only
      // one live push connection per user is supported at a time — an accepted trade-off.
      clientId: 'mqtt-$tenantId-$_userId',
      tenantId: tenantId,
      jwt: jwt,
      // Store-restricted staff (e.g. a CASHIER/STOREKEEPER assigned to specific stores) only ever
      // subscribe to their own stores' topics, not the whole tenant — the ACL is tenant-scoped only
      // (a device *could* still ask for the tenant wildcard), but a well-behaved client should never
      // ask for more than the signed-in user is allowed to see. Unrestricted staff (empty storeIds —
      // OWNER/MANAGER/PLATFORM_ADMIN) keep the tenant-wide wildcard.
      topics: _storeIds.isEmpty
          ? ['shelfj/notifications/$tenantId/#']
          : [for (final storeId in _storeIds) 'shelfj/notifications/$tenantId/$storeId'],
      onPayload: _emit,
    );
    if (disconnect == null) return;
    // Connecting is asynchronous, so the screen may already be gone by the time it lands. Hang up
    // rather than leaking a socket that nothing will ever read.
    if (!mounted) {
      disconnect();
      return;
    }
    _disconnect = disconnect;
  }

  /// Decodes one broker payload into state. Ignores anything malformed: the polled feed remains
  /// the source of truth, so a bad push is not worth surfacing as an error.
  void _emit(String raw) {
    if (!mounted) return;
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

  @override
  void dispose() {
    _disconnect?.call();
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
  final userId = auth is AuthAuthenticated ? auth.userId : '';
  final jwt = auth is AuthAuthenticated ? auth.accessToken : '';
  final storeIds = auth is AuthAuthenticated ? auth.storeIds : const <String>[];
  return LiveAlertsNotifier(tenantId, userId, jwt, storeIds);
});
