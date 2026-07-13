package com.shelfj.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Helper for cursor-based pagination (README §7.3). Lists use {@code ?after=<cursor>&limit=<n>} —
 * no page numbers. Default limit 20, max 100. A cursor is an opaque base64 token (here, wrapping
 * the last-seen sort key).
 */
public final class Cursor {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  private Cursor() {}

  /** Clamp a requested limit to [1, MAX_LIMIT], defaulting when null/invalid. */
  public static int clampLimit(Integer requested) {
    if (requested == null || requested < 1) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  /** Encode a raw sort key (e.g. an ISO timestamp + id) into an opaque cursor token. */
  public static String encode(String rawKey) {
    if (rawKey == null) {
      return null;
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(rawKey.getBytes(StandardCharsets.UTF_8));
  }

  /** Decode an opaque cursor token back to its raw sort key, or null if absent/invalid. */
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
   */
  public record CreatedAtId(Instant createdAt, UUID id) {}

  /**
   * Decode a cursor wrapping {@code "<ISO created_at>|<uuid>"} — the keyset of the last row the
   * previous page served — or null when no cursor was supplied (first page).
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

  /** One page of rows plus the opaque cursor for the next page (null when exhausted). */
  public record Page<T>(List<T> items, String nextCursor) {}

  /**
   * Build a page from a query that fetched {@code limit + 1} rows — the extra row only proves a
   * further page exists and is dropped. {@code rawKeyOf} yields a row's raw sort key (e.g. {@code
   * row.createdAt() + "|" + row.id()}); it is encoded into the opaque {@code nextCursor}.
   */
  public static <T> Page<T> page(List<T> rowsPlusOne, int limit, Function<T, String> rawKeyOf) {
    if (rowsPlusOne.size() <= limit) {
      return new Page<>(rowsPlusOne, null);
    }
    List<T> page = rowsPlusOne.subList(0, limit);
    return new Page<>(page, encode(rawKeyOf.apply(page.get(page.size() - 1))));
  }
}
