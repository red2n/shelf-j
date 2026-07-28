import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/constants.dart';
import 'package:shelf_app/features/admin/providers/live_alerts_provider.dart';

void main() {
  group('LiveAlert', () {
    test('holds the subject/body/receivedAt it was built with', () {
      final now = DateTime.now();
      final alert = LiveAlert(
        subject: 'Stock below threshold',
        body: 'available 2',
        receivedAt: now,
      );

      expect(alert.subject, 'Stock below threshold');
      expect(alert.body, 'available 2');
      expect(alert.receivedAt, now);
    });
  });

  group('ApiConstants.mqttWsUrl', () {
    test('defaults to a ws:// URL (matches the local docker-compose EMQX listener)', () {
      expect(ApiConstants.mqttWsUrl, startsWith('ws://'));
    });
  });
}
