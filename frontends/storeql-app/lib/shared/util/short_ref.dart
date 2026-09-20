/// A short handle for people — an order number on screen or on a receipt, a
/// placeholder label for a store or variant whose name hasn't loaded — cut from
/// the end of an id.
///
/// Never cut one from the front. Ids are UUIDv7, which start with a timestamp:
/// the first eight characters are the same for every id created in the same 65
/// seconds, so a whole minute of orders would all read "#01A0905D". The last
/// characters are random in v7 ids and in the older v4 ids alike.
///
/// A handle is not a key — two ids can share one — so never look anything up by
/// it. A value no longer than [length] comes back unchanged.
String shortRef(String id, {int length = 8}) {
  if (length < 1) {
    throw ArgumentError.value(length, 'length', 'must be at least 1');
  }
  return id.length <= length ? id : id.substring(id.length - length);
}
