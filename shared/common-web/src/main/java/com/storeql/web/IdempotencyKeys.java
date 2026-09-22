package com.storeql.web;

import com.storeql.ids.Ids;
import java.util.List;

/**
 * Which Idempotency-Key a write runs under, held to the one rule wherever it came from: a UUIDv7.
 *
 * <p>The header is authoritative, and {@link IdempotencyKeyFilter} refuses a bad one before any
 * resource runs. A few requests also take the key in the body — a legacy fallback for callers that
 * cannot set a header — and that door is held here, so a key cannot come in by the side that the
 * filter does not see.
 */
public final class IdempotencyKeys {

  static final String REFUSAL =
      "Idempotency-Key must be a UUIDv7: a new one for each attempt at a write, and the same one"
          + " when that attempt is retried";

  private IdempotencyKeys() {}

  /**
   * @param header the {@code Idempotency-Key} header, or null
   * @param body the request body's key field, or null
   * @return the key the write runs under — the header's when there is one — in canonical lowercase
   *     form, or null when neither carries one
   * @throws ApiException 400 {@link ErrorCodes#IDEMPOTENCY_KEY_INVALID} if the key it chose is not
   *     a UUIDv7
   */
  public static String effective(String header, String body) {
    String key =
        header != null && !header.isBlank()
            ? header
            : body != null && !body.isBlank() ? body : null;
    return key == null ? null : require(key);
  }

  /**
   * @return the key in canonical lowercase form, when it is a UUIDv7
   * @throws ApiException 400 {@link ErrorCodes#IDEMPOTENCY_KEY_INVALID} otherwise
   */
  public static String require(String key) {
    try {
      return Ids.parse(key).toString();
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, ErrorCodes.IDEMPOTENCY_KEY_INVALID, REFUSAL, List.of(), e);
    }
  }
}
