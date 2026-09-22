package com.storeql.ids;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one way StoreQL mints ids: primary keys, event ids, outbox rows and request ids.
 *
 * <p>Ids are RFC 9562 UUIDv7 — minted here, and the only kind accepted from outside ({@link
 * #parse}) or stored. Being time-ordered, new rows land at the right-hand edge of a B-tree index
 * instead of on a random page — denser indexes, fewer page splits and less WAL than {@code
 * UUID.randomUUID()}. PMD ({@code UseTimeOrderedIds}, {@code NoDatabaseMintedIds}, {@code
 * ParseIdsAsV7}) holds main code to this, ArchUnit ({@code IDS_ARE_V7}) holds tests, common-web
 * refuses another version at every HTTP entry, every uuid column carries a database CHECK, and
 * every integration test ends by failing on a uuid of another version anywhere.
 */
public final class Ids {

  /** 256 longs = one 2 KB DRBG call per refill. */
  private static final int BATCH_SIZE = 256;

  /** {@code 01a0905d-7082-7518-9ec6-aee90d72a43e}: 32 hex digits and four hyphens. */
  private static final int CANONICAL_LENGTH = 36;

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
   * Whether an id is an RFC 9562 version-7 UUID: version nibble 7 and the RFC variant ({@code 10}).
   * The only kind StoreQL mints, stores or accepts.
   */
  public static boolean isV7(UUID id) {
    return id != null && id.version() == 7 && id.variant() == 2;
  }

  /**
   * An id as StoreQL accepts it from anywhere outside the JVM — a request, a header, an event, a
   * row: the canonical 36-character form (hex in either case), version 7, the RFC variant.
   *
   * <p>Stricter than {@code UUID.fromString}, which takes {@code "1-1-1-1-1"} and every version. An
   * id that is not v7 cannot name anything StoreQL made, so it is refused where it arrives rather
   * than looked up, stored or passed on.
   *
   * @param text the id's text
   * @return the id
   * @throws InvalidIdException if the text is not a canonical UUIDv7
   */
  public static UUID parse(String text) {
    if (text == null || text.length() != CANONICAL_LENGTH) {
      throw new InvalidIdException(text, "is not a 36-character UUID");
    }
    long msb = 0;
    long lsb = 0;
    int digits = 0;
    for (int i = 0; i < CANONICAL_LENGTH; i++) {
      char ch = text.charAt(i);
      if (i == 8 || i == 13 || i == 18 || i == 23) {
        if (ch != '-') throw new InvalidIdException(text, "is not a UUID in canonical form");
        continue;
      }
      int nibble = Character.digit(ch, 16);
      if (nibble < 0) throw new InvalidIdException(text, "is not a UUID in canonical form");
      if (digits < 16) {
        msb = (msb << 4) | nibble;
      } else {
        lsb = (lsb << 4) | nibble;
      }
      digits++;
    }
    UUID id = new UUID(msb, lsb);
    if (!isV7(id)) {
      throw new InvalidIdException(text, "is a version-" + id.version() + " UUID, not a UUIDv7");
    }
    return id;
  }

  /**
   * An id handed over as a {@link UUID}, refused unless it is v7.
   *
   * @param what what the id is, as a person would say it, for the refusal
   * @throws InvalidIdException if it is null or not a v7 UUID
   */
  public static UUID requireV7(UUID id, String what) {
    if (!isV7(id)) {
      throw new InvalidIdException(
          String.valueOf(id), "is not a UUIDv7" + (what == null ? "" : " (" + what + ")"));
    }
    return id;
  }

  /**
   * An id that is not a canonical RFC 9562 version-7 UUID. An {@link IllegalArgumentException}, so
   * every place that already treats a malformed id as bad input treats this one the same way.
   */
  public static final class InvalidIdException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    InvalidIdException(String text, String why) {
      super("'" + abbreviate(text) + "' " + why);
    }

    private static String abbreviate(String text) {
      if (text == null) return "null";
      return text.length() <= 64 ? text : text.substring(0, 64) + "…";
    }
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
