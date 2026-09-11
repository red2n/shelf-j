import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/shared/util/short_ref.dart';

const _v7 = '01a0905d-7082-7518-9ec6-aee90d72a43e';

void main() {
  group('shortRef', () {
    test('is the last eight characters of an id', () {
      expect(shortRef(_v7), '0d72a43e');
    });

    test('works the same for the older v4 ids already stored', () {
      expect(shortRef('f47ac10b-58cc-4372-a567-0e02b2c3d479'), 'b2c3d479');
    });

    // The worst case it exists for: a burst of ids from one millisecond, as a
    // busy till or a bulk import makes. They share the first eight characters
    // (the timestamp); their handles must all differ.
    test('ids minted in the same millisecond get different handles', () {
      final burst = [
        for (var i = 0; i < 1000; i++)
          '01a0905d-7082-7518-9ec6-${i.toRadixString(16).padLeft(12, '0')}',
      ];
      expect(burst.map((id) => id.substring(0, 8)).toSet(), {'01a0905d'});
      expect(burst.map(shortRef).toSet(), hasLength(burst.length));
    });

    test('a value no longer than the handle comes back unchanged', () {
      expect(shortRef(''), '');
      expect(shortRef('o-1'), 'o-1');
      expect(shortRef('12345678'), '12345678');
      expect(shortRef('123456789'), '23456789');
    });

    test('keeps the case it is given; upper-casing is the caller\'s choice', () {
      expect(shortRef(_v7.toUpperCase()), '0D72A43E');
    });

    test('a longer handle reaches back past the last separator', () {
      expect(shortRef(_v7, length: 16), 'ec6-aee90d72a43e');
      expect(shortRef(_v7, length: 20), '18-9ec6-aee90d72a43e');
      expect(shortRef(_v7, length: 36), _v7);
      expect(shortRef(_v7, length: 99), _v7);
    });

    test('rejects a handle length below one', () {
      expect(() => shortRef(_v7, length: 0), throwsArgumentError);
      expect(() => shortRef(_v7, length: -8), throwsArgumentError);
    });

    // Negative by design: a handle is for people, never a lookup key.
    test('different ids can share a handle', () {
      const other = '01a0ffff-0000-7000-8000-00000d72a43e';
      expect(other, isNot(_v7));
      expect(shortRef(other), shortRef(_v7));
    });
  });

  // A v7 id starts with its timestamp, so a handle cut from the front repeats
  // for every id made in the same minute. This scans the app so a new screen
  // can't quietly bring back one order number per minute.
  group('no code in lib/ cuts a handle from the front of an id', () {
    final headCut = RegExp(
        r'''[iI][dD]['"]?\]?(?:\s+as\s+String\??\))?[!?]?\.substring\(\s*0\s*,''');

    test('the check recognises the shapes the app used to have', () {
      for (final cut in [
        'order.id.substring(0, 8)',
        'orderId.substring(0, 8).toUpperCase()',
        'm.storeId.substring(0,8)',
        "(it['variantId'] as String).substring(0, 8)",
        'sessionId!.substring(0, 8)',
      ]) {
        expect(headCut.hasMatch(cut), isTrue, reason: cut);
      }
      for (final fine in [
        'o.createdAt.substring(0, 10)',
        'p.description!.substring(0, 40)',
        'shortRef(order.id)',
        'orderId.substring(orderId.length - 8)',
      ]) {
        expect(headCut.hasMatch(fine), isFalse, reason: fine);
      }
    });

    test('finds none', () {
      final offenders = <String>[];
      final files = Directory('lib')
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart'));
      for (final file in files) {
        final lines = file.readAsLinesSync();
        for (var i = 0; i < lines.length; i++) {
          if (headCut.hasMatch(lines[i])) {
            offenders.add('${file.path}:${i + 1}: ${lines[i].trim()}');
          }
        }
      }
      expect(files, isNotEmpty, reason: 'run from the package root');
      expect(offenders, isEmpty,
          reason: 'use shortRef(id) from lib/shared/util/short_ref.dart');
    });
  });
}
