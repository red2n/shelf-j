/// A bounded least-recently-used cache of fetched image bytes.
///
/// Product images are fetched through Dio rather than `Image.network` (the storefront
/// tenant header cannot ride on a plain browser `<img>` request on web), so nothing in
/// the platform caches them for us and the app has to hold them itself. Holding them
/// forever is what this replaces: an unbounded per-product cache grows with every
/// category and search a shopper touches and is never released.
///
/// Bounded by total bytes first — that is the resource actually being spent — with an
/// entry count as a secondary guard so a catalog of very small images cannot accumulate
/// unbounded map overhead.
library;

import 'dart:collection';
import 'dart:typed_data';

/// Total encoded bytes held across all cached images.
///
/// A catalog page is 50 products, so this comfortably holds a page and a couple of
/// screens of scroll-back at typical compressed sizes (~100 KB), which is what keeps
/// scrolling from refetching and tripping the gateway's per-IP rate limit.
const int kImageCacheMaxBytes = 16 * 1024 * 1024;

/// Hard ceiling on entries, independent of their size.
const int kImageCacheMaxEntries = 200;

/// The outcome of a cache lookup.
///
/// Distinguishes "not cached" (null result) from "cached, and this product genuinely has
/// no image" (a hit whose [bytes] is null) — without it, negative results would be
/// refetched on every rebuild.
typedef ImageCacheHit = ({Uint8List? bytes});

class ImageByteCache {
  ImageByteCache({
    this.maxBytes = kImageCacheMaxBytes,
    this.maxEntries = kImageCacheMaxEntries,
  });

  final int maxBytes;
  final int maxEntries;

  /// Insertion-ordered, so the first key is always the least recently used —
  /// [lookup] re-inserts on a hit to move it to the back.
  final LinkedHashMap<String, Uint8List?> _entries = LinkedHashMap();
  int _byteCount = 0;

  /// Total bytes currently held. Never exceeds [maxBytes].
  int get byteCount => _byteCount;

  /// Number of cached entries, including negative ones.
  int get length => _entries.length;

  /// Looks [key] up, marking it most recently used on a hit.
  ///
  /// Returns null when [key] is absent. A non-null result whose `bytes` is null means
  /// the product is known to have no image.
  ImageCacheHit? lookup(String key) {
    if (!_entries.containsKey(key)) return null;
    final bytes = _entries.remove(key);
    _entries[key] = bytes;
    return (bytes: bytes);
  }

  /// Caches [bytes] (or a negative result, when null) against [key], evicting the least
  /// recently used entries until the cache is back within budget.
  void store(String key, Uint8List? bytes) {
    if (_entries.containsKey(key)) {
      _byteCount -= _entries.remove(key)?.lengthInBytes ?? 0;
    }
    _entries[key] = bytes;
    _byteCount += bytes?.lengthInBytes ?? 0;

    // An image larger than the whole budget evicts itself on the way in; the caller
    // still gets the bytes it fetched, they are simply not worth caching.
    while (_entries.isNotEmpty &&
        (_byteCount > maxBytes || _entries.length > maxEntries)) {
      _byteCount -= _entries.remove(_entries.keys.first)?.lengthInBytes ?? 0;
    }
  }

  void clear() {
    _entries.clear();
    _byteCount = 0;
  }
}
