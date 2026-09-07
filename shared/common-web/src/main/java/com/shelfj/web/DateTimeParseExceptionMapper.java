package com.shelfj.web;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.time.format.DateTimeParseException;
import org.glassfish.jersey.spi.ExtendedExceptionMapper;

/**
 * Turns a malformed date/timestamp {@link DateTimeParseException} into a clean 400 {@code
 * INVALID_DATE} envelope instead of a 500.
 *
 * <p>The exact counterpart of {@link UuidParseExceptionMapper}, for the same reason and found the
 * same way: services call {@code Instant.parse(...)} / {@code LocalDate.parse(...)} directly on
 * client-supplied strings, and on a malformed value that fell through to {@link
 * GenericExceptionMapper} as {@code 500 INTERNAL_ERROR}. Wrong for client input (golden rule #15 —
 * reject bad input with 400), and it makes a caller's typo look like a server fault, both to the
 * caller and to alerting.
 *
 * <p>The preferred fix remains {@link Parsing#instant(String, String)} / {@link
 * Parsing#date(String, String)} at the boundary, because those name the offending field. This
 * mapper is the defense-in-depth backstop that guarantees no date parse escapes as a 5xx from a
 * site not yet converted.
 *
 * <p><b>Scope.</b> {@link #isMappable} declines any exception whose stack shows it came from a
 * repository or a Kafka message handler. A date that fails to parse there is not client input — it
 * is a corrupt cached value or a malformed event payload, i.e. a genuine server-side fault that
 * must stay a 500 rather than being reported to the caller as their mistake. (Kafka consumers do
 * not run on HTTP request threads so they rarely reach a JAX-RS mapper at all; the check costs
 * nothing and documents the intent.)
 */
@Provider
public class DateTimeParseExceptionMapper
    implements ExtendedExceptionMapper<DateTimeParseException> {

  /** A bounded scan is enough: the boundary → service → parse chain is shallow. */
  private static final int MAX_FRAMES = 20;

  /**
   * @param ex the exception Jersey is deciding whether to route to this mapper
   * @return {@code false} when the parse happened inside a repository or messaging handler (a
   *     server-side data fault, which must stay a 500); {@code true} otherwise
   */
  @Override
  public boolean isMappable(DateTimeParseException ex) {
    StackTraceElement[] frames = ex.getStackTrace();
    int limit = Math.min(frames.length, MAX_FRAMES);
    for (int i = 0; i < limit; i++) {
      String cls = frames[i].getClassName();
      if (cls.startsWith("com.shelfj.")
          && (cls.contains(".repo.") || cls.contains(".messaging."))) {
        return false;
      }
    }
    return true;
  }

  /**
   * @param ex the malformed-date exception (never echoed to the client, to avoid leaking internals)
   * @return a {@code 400} envelope with {@link ErrorCodes#INVALID_DATE}
   */
  @Override
  public Response toResponse(DateTimeParseException ex) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(
                ErrorBody.of(
                    ErrorCodes.INVALID_DATE,
                    "A date or timestamp field is not in the expected ISO-8601 format")))
        .build();
  }
}
