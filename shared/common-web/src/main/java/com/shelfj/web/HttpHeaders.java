package com.shelfj.web;

/**
 * Internal header names the gateway injects downstream after validating the JWT, and the request-id
 * header.
 *
 * <p>Business services trust these because traffic only reaches them through the gateway (golden
 * rule #2). The gateway is responsible for stripping any client-supplied copies of these headers on
 * the way in.
 */
public final class HttpHeaders {

  private HttpHeaders() {}

  /** Correlation id propagated from the gateway through every hop and into events. */
  public static final String REQUEST_ID = "X-Request-Id";

  /** Tenant id extracted from the verified JWT by the gateway. */
  public static final String TENANT_ID = "X-Tenant-Id";

  /** User id extracted from the verified JWT by the gateway. */
  public static final String USER_ID = "X-User-Id";

  /**
   * Email address extracted from the verified JWT by the gateway — the caller's own, never a
   * subject they named. A service needs it to reach the person behind a login: customer-svc matches
   * a shopper's login to the shop's customer record with it (SJ-D44), and without it an online
   * order belongs to nobody the shop can email, credit or erase.
   */
  public static final String USER_EMAIL = "X-User-Email";

  /** Comma-separated roles extracted from the verified JWT by the gateway. */
  public static final String ROLES = "X-Roles";

  /**
   * Comma-separated store ids the caller may operate in, extracted from the verified JWT's {@code
   * storeIds} claim. Absent (no header at all) means unrestricted — a tenant-wide role like
   * OWNER/PLATFORM_ADMIN — never an empty-but-present value.
   */
  public static final String STORE_IDS = "X-Store-Ids";

  /**
   * The permissions the caller's token carries (20.10), comma-separated; {@code -} when the token
   * names none. Absent when the token carries no permission claim at all, in which case a service
   * judges the caller by the defaults of their roles. Stamped by the gateway from the verified
   * token, never trusted from a client.
   */
  public static final String PERMISSIONS = "X-Permissions";

  /** Idempotency key for retryable writes (checkout, payment capture, stock receipt). */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
}
