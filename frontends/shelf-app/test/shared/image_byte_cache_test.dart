import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/shared/util/image_byte_cache.dart';

Uint8List bytes(int length) => Uint8List(length);

void main() {
  group('lookup', () {
    test('misses on an unknown key', () {
      expect(ImageByteCache().lookup('nope'), isNull);
    });

    test('returns stored bytes', () {
      final cache = ImageByteCache()..store('a', bytes(10));
      expect(cache.lookup('a')?.bytes, isNotNull);
      expect(cache.lookup('a')!.bytes!.lengthInBytes, 10);
    });

    test('distinguishes a cached negative from a miss', () {
      final cache = ImageByteCache()..store('no-image', null);
      final hit = cache.lookup('no-image');
      expect(hit, isNotNull, reason: 'the key IS cached');
      expect(hit!.bytes, isNull, reason: 'and it is cached as having no image');
      expect(cache.lookup('never-seen'), isNull);
    });
  });

  group('byte budget', () {
    test('tracks bytes held, and forgets them on eviction', () {
      final cache = ImageByteCache(maxBytes: 100, maxEntries: 100);
      cache.store('a', bytes(60));
      expect(cache.byteCount, 60);
      cache.store('b', bytes(30));
      expect(cache.byteCount, 90);
      cache.store('c', bytes(30)); // 120 > 100, so 'a' goes
      expect(cache.byteCount, 60);
      expect(cache.lookup('a'), isNull);
      expect(cache.lookup('b'), isNotNull);
      expect(cache.lookup('c'), isNotNull);
    });

    test('never exceeds the byte budget', () {
      final cache = ImageByteCache(maxBytes: 1000, maxEntries: 1000);
      for (var i = 0; i < 200; i++) {
        cache.store('k$i', bytes(120));
        expect(cache.byteCount, lessThanOrEqualTo(1000));
      }
    });

    test('replacing a key does not double-count its bytes', () {
      final cache = ImageByteCache(maxBytes: 1000, maxEntries: 10);
      cache.store('a', bytes(100));
      cache.store('a', bytes(40));
      expect(cache.byteCount, 40);
      expect(cache.length, 1);
    });

    test('an image bigger than the whole budget is simply not retained', () {
      final cache = ImageByteCache(maxBytes: 100, maxEntries: 10);
      cache.store('huge', bytes(500));
      expect(cache.lookup('huge'), isNull);
      expect(cache.byteCount, 0);
    });

    test('negative entries cost no bytes but still count as entries', () {
      final cache = ImageByteCache(maxBytes: 100, maxEntries: 10);
      cache.store('none', null);
      expect(cache.byteCount, 0);
      expect(cache.length, 1);
    });
  });

  group('entry budget', () {
    test('caps entry count even when the images are tiny', () {
      final cache = ImageByteCache(maxBytes: 1 << 30, maxEntries: 3);
      for (var i = 0; i < 10; i++) {
        cache.store('k$i', bytes(1));
      }
      expect(cache.length, 3);
      expect(cache.lookup('k9'), isNotNull);
      expect(cache.lookup('k0'), isNull);
    });
  });

  group('LRU ordering', () {
    test('evicts the least recently used, not the oldest inserted', () {
      final cache = ImageByteCache(maxBytes: 1 << 30, maxEntries: 3);
      cache.store('a', bytes(1));
      cache.store('b', bytes(1));
      cache.store('c', bytes(1));

      cache.lookup('a'); // 'a' is now the most recently used, 'b' the least

      cache.store('d', bytes(1));

      expect(cache.lookup('b'), isNull, reason: 'b was least recently used');
      expect(cache.lookup('a'), isNotNull, reason: 'a was refreshed by lookup');
      expect(cache.lookup('c'), isNotNull);
      expect(cache.lookup('d'), isNotNull);
    });

    test('a cached negative is refreshed by lookup like any other entry', () {
      final cache = ImageByteCache(maxBytes: 1 << 30, maxEntries: 2);
      cache.store('none', null);
      cache.store('a', bytes(1));
      cache.lookup('none');
      cache.store('b', bytes(1));

      expect(cache.lookup('a'), isNull);
      expect(cache.lookup('none'), isNotNull);
    });
  });

  test('clear drops everything', () {
    final cache = ImageByteCache()
      ..store('a', bytes(10))
      ..store('b', null);
    cache.clear();
    expect(cache.length, 0);
    expect(cache.byteCount, 0);
    expect(cache.lookup('a'), isNull);
  });

  test('defaults are bounded and sized for a catalog page', () {
    final cache = ImageByteCache();
    expect(cache.maxBytes, kImageCacheMaxBytes);
    expect(cache.maxEntries, kImageCacheMaxEntries);
    // A catalog page is 50 products; the cache must hold at least a page even if every
    // image sits just under the 256 KB upload cap, or scrolling would refetch.
    expect(cache.maxBytes ~/ (256 * 1024), greaterThanOrEqualTo(50));
    expect(cache.maxEntries, greaterThanOrEqualTo(50));
  });
}
