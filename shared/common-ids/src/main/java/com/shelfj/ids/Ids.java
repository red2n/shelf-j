package com.shelfj.ids;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one way Shelf-J mints ids: primary keys, event ids, outbox rows and request ids.
 *
 * <p>Ids are UUIDv7, so new rows land at the right-hand edge of a B-tree index instead of on a
 * random page — denser indexes, fewer page splits and less WAL than {@code UUID.randomUUID()}. They
 * are still plain {@code uuid} values, so they sit alongside the version-4 ids already stored. PMD
 * rule {@code UseTimeOrderedIds} rejects {@code UUID.randomUUID()} in main code.
 */
public final class Ids {

  /** 256 longs = one 2 KB DRBG call per refill. */
  private static final int BATCH_SIZE = 256;

  private static final UuidV7Generator GENERATOR =
      new UuidV7Generator(
          System::currentTimeMillis,
          new BufferedSecureRandom(Ids::newDrbg, stripes(), BATCH_SIZE)::nextLong);

  private Ids() {}

  /**
   * @return a new time-ordered (version-7) UUID
   */
  public static UUID newId() {
    return GENERATOR.next();
  }

  /** Four batches per core, rounded up to a power of two, so threads rarely share one. */
  private static int stripes() {
    int wanted = 4 * Runtime.getRuntime().availableProcessors();
    return Integer.highestOneBit(wanted - 1) << 1;
  }

  /** A NIST SP 800-90A DRBG with its own state and lock, seeded from the OS entropy source. */
  private static Consumer<byte[]> newDrbg() {
    try {
      return SecureRandom.getInstance("DRBG")::nextBytes;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK since 9 provides DRBG", e);
    }
  }
}
