package com.shelfj.web;

/**
 * Internal header names the gateway injects downstream after validating the JWT, and the request-id header.
 *
 * <p>Business services trust these because traffic only reaches them through the gateway (golden rule #2).
 * The gateway is responsible for stripping any client-supplied copies of these headers on the way in.</p>
 */
public final class HttpHeaders {

    private HttpHeaders() {}

    /** Correlation id propagated from the gateway through every hop and into events. */
    public static final String REQUEST_ID = "X-Request-Id";

    /** Tenant id extracted from the verified JWT by the gateway. */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** User id extracted from the verified JWT by the gateway. */
    public static final String USER_ID = "X-User-Id";

    /** Comma-separated roles extracted from the verified JWT by the gateway. */
    public static final String ROLES = "X-Roles";

    /** Idempotency key for retryable writes (checkout, payment capture, stock receipt). */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
}
