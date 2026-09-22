package com.storeql.web;

/**
 * Registry of the generic, cross-cutting {@code ApiException} codes shared by every service — each
 * one was independently retyped as a string literal in 6+ places before this existed, risking
 * silent collisions (a new service picking the same code for a different meaning).
 *
 * <p>Domain-specific codes (e.g. {@code ORDER_DUPLICATE_KEY}, {@code STORE_NOT_FOUND}) belong to
 * their own service and are NOT centralized here — only codes that mean the identical thing
 * everywhere they're thrown.
 */
public final class ErrorCodes {

  private ErrorCodes() {}

  /** A path/query/body field that should be a UUID failed {@code UUID.fromString}. */
  public static final String INVALID_UUID = "INVALID_UUID";

  /**
   * An {@code Idempotency-Key} that is not a UUIDv7. A key is the identity of one attempt at a
   * write: a clock reading or a hand-made string repeats, and a repeated key hands one caller
   * another's result (SJ-D70).
   */
  public static final String IDEMPOTENCY_KEY_INVALID = "IDEMPOTENCY_KEY_INVALID";

  /** A date/timestamp field failed to parse against its expected format. */
  public static final String INVALID_DATE = "INVALID_DATE";

  /** Bean Validation failed on a request DTO — see {@link Validations#validate}. */
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  /** A request body was required but missing. */
  public static final String BODY_REQUIRED = "BODY_REQUIRED";

  /**
   * A request body was not the JSON the request takes — malformed, or a field of the wrong type
   * (see {@link RequestBodyExceptionMapper}).
   */
  public static final String REQUEST_BODY_INVALID = "REQUEST_BODY_INVALID";
}
