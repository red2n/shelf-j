package com.shelfj.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Helper for cursor-based pagination (docs/ARCHITECTURE.md §14). Lists use {@code
 * ?after=<cursor>&limit=<n>} — no page numbers. Default limit 20, max 100. A cursor is an opaque
 * base64 token (here, wrapping the last-seen sort key).
 */
public final class Cursor {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  private Cursor() {}

  /**
   * Clamp a requested limit to {@code [1, MAX_LIMIT]}, defaulting when null/invalid.
   *
   * @param requested the caller-supplied {@code ?limit=} value; may be {@code null}
   * @return {@link #DEFAULT_LIMIT} if {@code requested} is {@code null} or less than 1, otherwise
   *     {@code requested} clamped to at most {@link #MAX_LIMIT}
   */
  public static int clampLimit(Integer requested) {
    if (requested == null || requested < 1) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  /**
   * Encode a raw sort key (e.g. an ISO timestamp + id) into an opaque cursor token.
   *
   * @param rawKey the plaintext sort key; may be {@code null}
   * @return a URL-safe, unpadded base64 token, or {@code null} if {@code rawKey} is {@code null}
   */
  public static String encode(String rawKey) {
    if (rawKey == null) {
      return null;
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(rawKey.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decode an opaque cursor token back to its raw sort key.
   *
   * @param token the {@code ?after=} value from the request; may be {@code null}/blank
   * @return the decoded raw sort key, or {@code null} if {@code token} is {@code null}/blank
   *     (meaning "first page")
   * @throws ApiException 400 {@code INVALID_CURSOR} if {@code token} is not valid base64
   */
  public static String decode(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
    }
  }

  /**
   * A decoded {@code (created_at, id)} keyset — the common sort key of created-at-ordered lists.
   *
   * @param createdAt the {@code created_at} of the last row the previous page served
   * @param id the {@code id} of that same row, used as a tiebreaker for equal timestamps
   */
  public record CreatedAtId(Instant createdAt, UUID id) {}

  /**
   * Decode a cursor wrapping {@code "<ISO created_at>|<uuid>"} — the keyset of the last row the
   * previous page served.
   *
   * @param token the {@code ?after=} value from the request; may be {@code null}/blank
   * @return the decoded keyset, or {@code null} when no cursor was supplied (first page)
   * @throws ApiException 400 {@code INVALID_CURSOR} if {@code token} decodes but does not contain a
   *     valid {@code "<instant>|<uuid>"} pair
   */
  public static CreatedAtId decodeCreatedAtId(String token) {
    String raw = decode(token);
    if (raw == null) {
      return null;
    }
    int sep = raw.indexOf('|');
    try {
      if (sep < 0) {
        throw new IllegalArgumentException("missing separator");
      }
      return new CreatedAtId(
          Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
    } catch (RuntimeException e) {
      throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
    }
  }

  /**
   * One page of rows plus the opaque cursor for the next page.
   *
   * @param items the rows for this page (defensively copied to an immutable list)
   * @param nextCursor opaque cursor to request the next page, or {@code null} when this is the last
   *     page
   */
  public record Page<T>(List<T> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * Build a page from a query that fetched {@code limit + 1} rows — the extra row only proves a
   * further page exists and is dropped. {@code rawKeyOf} yields a row's raw sort key (e.g. {@code
   * row.createdAt() + "|" + row.id()}); it is encoded into the opaque {@code nextCursor}.
   *
   * @param rowsPlusOne the rows fetched, up to {@code limit + 1} of them, in sort order
   * @param limit the page size requested (typically from {@link #clampLimit(Integer)})
   * @param rawKeyOf yields the raw (unencoded) sort key for a given row
   * @return a page of at most {@code limit} items; {@code nextCursor} is {@code null} iff {@code
   *     rowsPlusOne} had {@code limit} or fewer rows (no further page)
   */
  public static <T> Page<T> page(List<T> rowsPlusOne, int limit, Function<T, String> rawKeyOf) {
    if (rowsPlusOne.size() <= limit) {
      return new Page<>(rowsPlusOne, null);
    }
    List<T> page = rowsPlusOne.subList(0, limit);
    return new Page<>(page, encode(rawKeyOf.apply(page.get(page.size() - 1))));
  }
}
