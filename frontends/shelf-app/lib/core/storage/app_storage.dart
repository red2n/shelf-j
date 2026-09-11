import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Local key/value storage: OS Keychain/Keystore on mobile/desktop, plain
/// browser storage on web.
///
/// flutter_secure_storage's web backend encrypts values with the browser's
/// WebCrypto (`window.crypto.subtle`) API, which browsers only expose in a
/// "secure context" — HTTPS, or the literal origin `http://localhost`.
/// Opening the app from another device on the LAN (`http://<ip>:8088`) is not
/// a secure context, so every read/write throws and any `await` on it never
/// completes — symptom: a bottom sheet whose Save/Skip button silently stops
/// working. flutter_secure_storage's own web storage is already just
/// plaintext localStorage under the hood (the "encryption" key itself is
/// stored unencrypted alongside the value), so SharedPreferences on web keeps
/// the same (non-)security guarantee without the WebCrypto dependency.
class AppStorage {
  const AppStorage();

  static const FlutterSecureStorage _secure = FlutterSecureStorage();

  Future<String?> read({required String key}) async {
    if (kIsWeb) {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(key);
    }
    return _secure.read(key: key);
  }

  Future<void> write({required String key, required String? value}) async {
    if (kIsWeb) {
      final prefs = await SharedPreferences.getInstance();
      if (value == null) {
        await prefs.remove(key);
      } else {
        await prefs.setString(key, value);
      }
      return;
    }
    await _secure.write(key: key, value: value);
  }

  Future<void> delete({required String key}) async {
    if (kIsWeb) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(key);
      return;
    }
    await _secure.delete(key: key);
  }

  /// Wipe everything this app stored on the device.
  ///
  /// [keep] names keys that must survive — sign-out uses it to preserve state
  /// that does not belong to the session. The POS offline queue is the reason it
  /// exists: those are sales the customer has already paid for and the server has
  /// not been told about, so a cashier signing out at the end of a shift must not
  /// destroy them.
  Future<void> deleteAll({Set<String> keep = const {}}) async {
    final preserved = <String, String>{};
    for (final key in keep) {
      final value = await read(key: key);
      if (value != null) preserved[key] = value;
    }
    if (kIsWeb) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.clear();
    } else {
      await _secure.deleteAll();
    }
    for (final entry in preserved.entries) {
      await write(key: entry.key, value: entry.value);
    }
  }
}
