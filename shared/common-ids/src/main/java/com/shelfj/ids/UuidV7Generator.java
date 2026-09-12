package com.shelfj.ids;

import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Generates RFC 9562 version-7 UUIDs: 48 bits of Unix-epoch milliseconds, the version, a 12-bit
 * counter, the variant, then 62 random bits.
 *
 * <p>Each thread keeps its own millisecond and counter, so no call waits on another thread. A
 * thread's ids are strictly increasing in byte order — the order PostgreSQL sorts {@code uuid} in —
 * even when many are made in the same millisecond or the clock steps back. The counter (RFC 9562
 * §6.2, method 1) starts at a random value below 2048 each new millisecond, so at least 2048 ids
 * fit in one; past that the thread borrows the next millisecond rather than break order.
 *
 * <p>Ids from different threads interleave by millisecond, which is all index locality needs. Two
 * threads can land on the same millisecond and counter, so uniqueness between them rests on the 62
 * random bits — which is why those must come from a secure source that never repeats a draw.
 *
 * <p>The random bits keep ids unguessable, but the timestamp is readable by anyone holding the id:
 * never use one as a secret, and never read business time out of it.
 */
final class UuidV7Generator {

  private static final int COUNTER_MAX = 0xFFF;
  private static final long COUNTER_SEED_MASK = 0x7FF;
  private static final long VERSION_7 = 0x7000L;
  private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;
  private static final long RANDOM_62_BITS = 0x3FFF_FFFF_FFFF_FFFFL;

  private final LongSupplier clock;
  private final LongSupplier random;
  private final ThreadLocal<Sequence> sequences = ThreadLocal.withInitial(Sequence::new);

  /**
   * @param clock current time in Unix-epoch milliseconds
   * @param random secure random longs; must be thread-safe
   */
  UuidV7Generator(LongSupplier clock, LongSupplier random) {
    this.clock = clock;
    this.random = random;
  }

  /**
   * @return a new version-7 UUID, greater than every id this generator has returned on this thread
   */
  UUID next() {
    Sequence sequence = sequences.get();
    long now = clock.getAsLong();
    if (now > sequence.lastMillis) {
      sequence.lastMillis = now;
      sequence.counter = (int) (random.getAsLong() & COUNTER_SEED_MASK);
    } else if (sequence.counter < COUNTER_MAX) {
      sequence.counter++;
    } else {
      sequence.lastMillis++;
      sequence.counter = (int) (random.getAsLong() & COUNTER_SEED_MASK);
    }
    return layout(sequence.lastMillis, sequence.counter, random.getAsLong());
  }

  /**
   * The version-7 bit layout, shared by generated and derived ids.
   *
   * @param millis Unix-epoch milliseconds; the low 48 bits are used
   * @param twelveBits the counter or other value for the 12 bits after the version; low 12 used
   * @param sixtyTwoBits the value for the 62 bits after the variant; low 62 used
   */
  static UUID layout(long millis, long twelveBits, long sixtyTwoBits) {
    return new UUID(
        ((millis & 0xFFFF_FFFF_FFFFL) << 16) | VERSION_7 | (twelveBits & COUNTER_MAX),
        (sixtyTwoBits & RANDOM_62_BITS) | VARIANT_RFC);
  }

  /** One thread's place: the millisecond it last used and its counter within it. */
  private static final class Sequence {
    private long lastMillis = -1;
    private int counter;
  }
}
