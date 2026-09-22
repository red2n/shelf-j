import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

// ---------------------------------------------------------------------------
// Ids the app makes: RFC 9562 UUIDv7, the only kind StoreQL accepts.
//
// The app mints one thing that the server stores or trusts: the
// Idempotency-Key of a write. Every service refuses a key that is not a
// UUIDv7 (400 IDEMPOTENCY_KEY_INVALID), because a key built from a clock
// repeats — two tills in one business finishing a sale in the same
// millisecond once sent the same key, and the second was handed the first's
// order and payment (SJ-D70).
//
//   * newId()               a fresh key per attempt at a write, or the base of
//                           one that has steps (a sale: order, payments, terminal)
//   * derivedId(base, name) a step of that attempt: the same every time for the
//                           same base and name, so a retried or replayed step
//                           is recognised, and different for every other name
//
// derivedId is the server's Ids.derived, bit for bit (a shared test vector
// holds the two together). Everything here works on bytes, never on 64-bit
// integers: on the web, Dart's int is a JavaScript number and loses bits past 53.
// ---------------------------------------------------------------------------

final Random _secure = Random.secure();

/// A new RFC 9562 UUIDv7: 48 bits of Unix-epoch milliseconds, then random bits
/// with the version and variant set.
String newId() {
  final bytes = Uint8List(16);
  var ms = DateTime.now().millisecondsSinceEpoch;
  for (var i = 5; i >= 0; i--) {
    bytes[i] = ms % 256;
    ms ~/= 256;
  }
  for (var i = 6; i < 16; i++) {
    bytes[i] = _secure.nextInt(256);
  }
  return _format(_versioned(bytes));
}

/// A UUIDv7 derived from [source] and [name]: the source's timestamp when it is
/// a v7 (zero otherwise), then 74 bits of SHA-256(source + ':' + name) — the
/// server's `Ids.derived`. Anyone who knows the source and name can compute it:
/// a key, never a secret.
String derivedId(String source, String name) {
  final normalised = isV7(source) ? source.toLowerCase() : source;
  final hash = sha256.convert(utf8.encode('$normalised:$name')).bytes;
  final bytes = Uint8List(16);
  if (isV7(source)) {
    final hex = normalised.replaceAll('-', '');
    for (var i = 0; i < 6; i++) {
      bytes[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
    }
  }
  // The 12 bits after the version: the low 12 bits of the hash's first four bytes.
  bytes[6] = hash[2] & 0x0F;
  bytes[7] = hash[3];
  // The 62 bits after the variant: the low 62 bits of the hash's next eight bytes.
  for (var i = 0; i < 8; i++) {
    bytes[8 + i] = hash[4 + i];
  }
  return _format(_versioned(bytes));
}

final RegExp _canonicalV7 = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
);

/// Whether [text] is a canonical RFC 9562 UUIDv7: version 7, variant 10.
bool isV7(String text) => _canonicalV7.hasMatch(text);

Uint8List _versioned(Uint8List bytes) {
  bytes[6] = (bytes[6] & 0x0F) | 0x70; // version 7
  bytes[8] = (bytes[8] & 0x3F) | 0x80; // variant 10
  return bytes;
}

String _format(Uint8List bytes) {
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}
