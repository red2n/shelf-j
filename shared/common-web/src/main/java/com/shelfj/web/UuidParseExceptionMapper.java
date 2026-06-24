package com.shelfj.web;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.spi.ExtendedExceptionMapper;

/**
 * Turns a malformed-UUID {@link IllegalArgumentException} into a clean 400 {@code INVALID_UUID}
 * envelope instead of a 500.
 *
 * <p>Many resources/services parse path, query, and body string fields with {@code
 * UUID.fromString(...)} directly. On a malformed value that throws {@link
 * IllegalArgumentException}, which previously fell through to {@link GenericExceptionMapper} and
 * surfaced as {@code 500 INTERNAL_ERROR} — wrong for client-supplied input (golden rule #15: reject
 * bad input with 400) and noisy for alerting. The preferred fix is {@link Parsing#uuid(String,
 * String)} at the boundary (it names the offending field); this mapper is the defense-in-depth
 * backstop that guarantees no malformed-UUID parse ever escapes as a 5xx, including from sites not
 * yet converted.
 *
 * <p>Scope is deliberately narrow: {@link #isMappable} claims the exception ONLY when {@code
 * java.util.UUID.fromString} appears in its stack, so a genuine internal {@link
 * IllegalArgumentException} (a real bug) still becomes a 500 via {@link GenericExceptionMapper}.
 * JAX-RS exception mappers only run on HTTP request threads, so Kafka-consumer parse failures never
 * reach here — those keep their existing poison-pill/DLT handling.
 */
@Provider
public class UuidParseExceptionMapper implements ExtendedExceptionMapper<IllegalArgumentException> {

  /** A bounded scan is enough: the boundary → service → parse chain is shallow. */
  private static final int MAX_FRAMES = 20;

  @Override
  public boolean isMappable(IllegalArgumentException ex) {
    StackTraceElement[] frames = ex.getStackTrace();
    int limit = Math.min(frames.length, MAX_FRAMES);
    for (int i = 0; i < limit; i++) {
      StackTraceElement f = frames[i];
      if ("java.util.UUID".equals(f.getClassName()) && "fromString".equals(f.getMethodName())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Response toResponse(IllegalArgumentException ex) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(
                ErrorBody.of(ErrorCodes.INVALID_UUID, "A request identifier is not a valid UUID")))
        .build();
  }
}
