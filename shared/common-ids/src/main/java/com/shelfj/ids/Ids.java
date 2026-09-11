package com.shelfj.ids;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one way Shelf-J mints ids: primary keys, event ids, outbox rows and request ids.
 *
 * <p>Ids are UUIDv7, so new rows land at the right-hand edge of a B-tree index instead of on a
 * random page — denser indexes, fewer page splits and less WAL than {@code UUID.randomUUID()}. v7
 * is the only version Shelf-J stores: PMD rules {@code UseTimeOrderedIds} and {@code
 * NoDatabaseMintedIds} reject other generators in main code, and every integration test ends by
 * failing on a column that fills in its own uuid or a stored id of another version.
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
   * A v7 id that comes out the same every time for the same source and name — for an idempotency
   * key a redelivered event must reproduce, such as one per line of the event.
   *
   * <p>The timestamp is the source id's, so a derived id sorts next to what it derives from; the
   * other 74 bits are a SHA-256 of the source and the name. A source that is not v7 carries no
   * timestamp, so ids derived from it start at the epoch. Anyone who knows the source and name can
   * compute the id: use it as a key, never as a secret. Changing this algorithm changes every key
   * it has produced, so a redelivered event would no longer be recognised as processed.
   *
   * @param source the id this one is derived from, typically an event id
   * @param name what distinguishes this id among those derived from the same source
   * @return the derived id
   */
  public static UUID derived(UUID source, String name) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(name, "name");
    ByteBuffer hash = ByteBuffer.wrap(sha256(source + ":" + name));
    long millis = source.version() == 7 ? source.getMostSignificantBits() >>> 16 : 0L;
    return UuidV7Generator.layout(millis, hash.getInt(), hash.getLong());
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JVM provides SHA-256", e);
    }
  }

  /**
   * A short handle for people — an order number on a receipt, the tail of a batch number — cut from
   * the end of the id.
   *
   * <p>Never cut one from the front: a v7 id starts with its timestamp, so its first eight
   * characters are the same for every id minted in the same 65 seconds. The last eight are random.
   * A handle is not a key: two ids can share one, so never look anything up by it.
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
