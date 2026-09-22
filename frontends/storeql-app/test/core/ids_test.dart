import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/ids.dart';

// ---------------------------------------------------------------------------
// The ids the app makes are RFC 9562 UUIDv7 — the only kind every service
// accepts as an Idempotency-Key — and a derived key is the server's
// Ids.derived bit for bit.
// ---------------------------------------------------------------------------

void main() {
  test('a new id is a canonical UUIDv7, and no two are the same', () {
    final seen = <String>{};
    for (var i = 0; i < 5000; i++) {
      final id = newId();
      expect(isV7(id), isTrue, reason: id);
      expect(id, id.toLowerCase());
      seen.add(id);
    }
    expect(seen.length, 5000);
  });

  test('its first 48 bits are the clock', () {
    final before = DateTime.now().millisecondsSinceEpoch;
    final id = newId();
    final after = DateTime.now().millisecondsSinceEpoch;
    final ms = int.parse(id.replaceAll('-', '').substring(0, 12), radix: 16);
    expect(ms >= before && ms <= after, isTrue);
  });

  test('a derived id matches the server\'s Ids.derived (the shared vector)', () {
    expect(
      derivedId('01a0905d-7082-7518-9ec6-aee90d72a43e', 'pay:1'),
      '01a0905d-7082-7bcd-a20b-17cf9c3cdc20',
    );
  });

  test('a derived id is stable, distinct per name, and always a v7', () {
    final base = newId();
    expect(derivedId(base, 'order'), derivedId(base, 'order'));
    expect(derivedId(base, 'order'), isNot(derivedId(base, 'pay:0')));
    expect(derivedId(base, 'pay:0'), isNot(derivedId(base, 'pay:1')));
    expect(isV7(derivedId(base, 'order')), isTrue);
    expect(derivedId(base, 'order').substring(0, 13), base.substring(0, 13),
        reason: 'it sorts beside what it derives from');
    // From a source that is not a v7 id at all — an older queued sale's local id.
    expect(isV7(derivedId('pos-1757590000000', 'order')), isTrue);
  });

  test('only a canonical v7 counts', () {
    expect(isV7('919108f7-52d1-4320-9bac-f847db4148a8'), isFalse); // v4
    expect(isV7('01a0905d-7082-7518-cec6-aee90d72a43e'), isFalse); // wrong variant
    expect(isV7('00000000-0000-0000-0000-000000000000'), isFalse);
    expect(isV7('pos-1757590000000-order'), isFalse);
    expect(isV7('01A0905D-7082-7518-9EC6-AEE90D72A43E'), isTrue);
  });
}
