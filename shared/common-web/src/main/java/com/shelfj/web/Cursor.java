package com.shelfj.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

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
}
