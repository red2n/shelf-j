package com.shelfj.ids;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
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

  /** Hex digits in a {@link #shortRef}: 32 random bits, as the v4 prefixes they replace had. */
  public static final int SHORT_REF_LENGTH = 8;

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

  /**
   * A short handle for people — an order number on a receipt, the tail of a batch number — cut from
   * the end of the id.
   *
   * <p>Never cut one from the front: a v7 id starts with its timestamp, so its first eight
   * characters are the same for every id minted in the same 65 seconds. The last eight are random
   * in v7 ids and in the older v4 ids alike. A handle is not a key: two ids can share one, so never
   * look anything up by it.
   *
   * @param id the id to shorten
   * @return the id's last {@value #SHORT_REF_LENGTH} hex digits, lowercase
   */
  public static String shortRef(UUID id) {
    String text = Objects.requireNonNull(id, "id").toString();
    return text.substring(text.length() - SHORT_REF_LENGTH);
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
