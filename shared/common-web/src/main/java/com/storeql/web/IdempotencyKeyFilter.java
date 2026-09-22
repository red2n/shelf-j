package com.storeql.web;

import com.storeql.ids.Ids;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Refuses an {@code Idempotency-Key} that is not a UUIDv7, in every service, before any write.
 *
 * <p>A key is the identity of one attempt at a write, and every write it guards is unique on {@code
 * (tenant_id, idempotency_key)}. A key made from a clock reading repeats: two tills in one business
 * finishing a sale in the same millisecond sent the same {@code pos-<ms>-order}, and the second was
 * handed the first's order and payment (SJ-D70). A v7 key is a fresh random id per attempt, or one
 * derived from the attempt's own id ({@code Ids.derived}) for a step of it.
 *
 * <p>A key that passes is put back in its canonical lowercase form, so {@code 01A0…} and {@code
 * 01a0…} are one key to every resource and table behind this filter, as they are one UUID. The
 * databases hold that form: each {@code idempotency_key} column refuses anything else.
 *
 * <p>Runs after authentication and authorisation, so a caller who may not write is told that first.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 100)
public class IdempotencyKeyFilter implements ContainerRequestFilter {

  @Override
  public void filter(ContainerRequestContext req) {
    String key = req.getHeaderString(HttpHeaders.IDEMPOTENCY_KEY);
    if (key == null) {
      return;
    }
    try {
      req.getHeaders().putSingle(HttpHeaders.IDEMPOTENCY_KEY, Ids.parse(key).toString());
    } catch (Ids.InvalidIdException e) {
      req.abortWith(
          Response.status(Response.Status.BAD_REQUEST)
              .type(MediaType.APPLICATION_JSON)
              .entity(
                  ApiResponse.error(
                      ErrorBody.of(ErrorCodes.IDEMPOTENCY_KEY_INVALID, IdempotencyKeys.REFUSAL)))
              .build());
    }
  }
}
