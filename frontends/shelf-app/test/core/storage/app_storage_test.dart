import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/constants.dart';
import 'package:shelf_app/core/storage/app_storage.dart';

// ---------------------------------------------------------------------------
// Sign-out wipes device storage. That is right for tokens and wrong for the POS
// offline queue: those are sales the customer has already paid for that the
// server has not been told about, and a cashier signing out at the end of a
// shift must not destroy them.
// ---------------------------------------------------------------------------

/// In-memory stand-in for the flutter_secure_storage platform channel.
Map<String, String> _installFakeSecureStorage() {
  final data = <String, String>{};
  const channel = MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  TestWidgetsFlutterBinding.ensureInitialized();
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(channel, (call) async {
    final args = (call.arguments as Map?) ?? const {};
    final key = args['key'] as String? ?? '';
    switch (call.method) {
      case 'read':
        return data[key];
      case 'write':
        data[key] = args['value'] as String;
        return null;
      case 'delete':
        data.remove(key);
        return null;
      case 'readAll':
        return Map<String, String>.from(data);
      case 'deleteAll':
        data.clear();
        return null;
      default:
        return null;
    }
  });
  return data;
}

void main() {
  const storage = AppStorage();

  test('deleteAll clears everything by default', () async {
    final data = _installFakeSecureStorage();
    await storage.write(key: StorageKeys.accessToken, value: 'A');
    await storage.write(key: StorageKeys.posOfflineSales, value: '[{"id":"x"}]');

    await storage.deleteAll();

    expect(data, isEmpty);
  });

  test('sign-out keeps the offline sales queue and drops the tokens', () async {
    final data = _installFakeSecureStorage();
    await storage.write(key: StorageKeys.accessToken, value: 'A');
    await storage.write(key: StorageKeys.refreshToken, value: 'R');
    await storage.write(key: StorageKeys.posOfflineSales, value: '[{"id":"x"}]');

    await storage.deleteAll(keep: const {StorageKeys.posOfflineSales});

    expect(await storage.read(key: StorageKeys.accessToken), isNull);
    expect(await storage.read(key: StorageKeys.refreshToken), isNull);
    expect(await storage.read(key: StorageKeys.posOfflineSales), '[{"id":"x"}]');
    expect(data.keys, [StorageKeys.posOfflineSales]);
  });

  test('keeping a key that was never written does not resurrect it', () async {
    _installFakeSecureStorage();
    await storage.write(key: StorageKeys.accessToken, value: 'A');

    await storage.deleteAll(keep: const {StorageKeys.posOfflineSales});

    expect(await storage.read(key: StorageKeys.posOfflineSales), isNull);
  });
}
